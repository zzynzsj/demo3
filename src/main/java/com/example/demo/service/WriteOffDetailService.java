package com.example.demo.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.demo.config.MqConfig;
import com.example.demo.domain.dto.WriteOffMsgDto;
import com.example.demo.domain.dto.WriteOffReqDto;
import com.example.demo.domain.dto.WriteOffRespDto;
import com.example.demo.domain.dto.WriteOffStat;
import com.example.demo.domain.entity.BankReceipt;
import com.example.demo.domain.entity.RentPlans;
import com.example.demo.domain.entity.WriteOffDetail;
import com.example.demo.mapper.BankReceiptMapper;
import com.example.demo.mapper.RentPlansMapper;
import com.example.demo.mapper.WriteOffDetailMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * @author zzy
 * @description 针对表【bank_receipt(银行收款表)】的数据库操作Service实现
 * @createDate 2026-03-30 23:08:50
 */
@Service
@Slf4j
public class WriteOffDetailService extends ServiceImpl<WriteOffDetailMapper, WriteOffDetail> {
    @Autowired
    private BankReceiptMapper bankReceiptMapper;

    @Autowired
    private RentPlansMapper rentPlansMapper;

    @Autowired
    @Qualifier("writeOffExecutor")
    private Executor writeOffExecutor;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private TenantWriteOffService tenantWriteOffService;

    /**
     * 批量执行核销任务入口
     */
    public WriteOffRespDto executeBatchWriteOff(WriteOffReqDto req) {
        long startTime = System.currentTimeMillis();

        log.info(">>> 开始准备核销数据...");

        // 构建收款单查询条件 (use_status: 0-未使用, 1-部分使用)
        LambdaQueryWrapper<BankReceipt> receiptWrapper = new LambdaQueryWrapper<>();
        receiptWrapper.lt(BankReceipt::getUseStatus, 2);

        // 构建计划单查询条件 (verification_status: 0-未核销, 1-部分核销)
        LambdaQueryWrapper<RentPlans> planWrapper = new LambdaQueryWrapper<>();
        planWrapper.lt(RentPlans::getVerificationStatus, 2);

        // 指定承租人
        if (ObjectUtil.isNotEmpty(req.getLesseeName())) {
            receiptWrapper.eq(BankReceipt::getPayerAccountName, req.getLesseeName());
            planWrapper.eq(RentPlans::getLesseeName, req.getLesseeName());
        }

        // 指定应收截止日期
        if (ObjectUtil.isNotNull(req.getDueDateEnd())) {
            planWrapper.le(RentPlans::getDueDate, req.getDueDateEnd());
        }

        List<BankReceipt> allReceipts = bankReceiptMapper.selectList(receiptWrapper);
        List<RentPlans> allPlans = rentPlansMapper.selectList(planWrapper);

        if (CollUtil.isEmpty(allReceipts) || CollUtil.isEmpty(allPlans)) {
            log.info("未发现满足条件的待核销匹配项，任务结束。");
            return WriteOffRespDto.builder()
                    .totalTimeSeconds(0.0)
                    .totalCount(0L)
                    .totalPrincipal(BigDecimal.ZERO)
                    .totalInterest(BigDecimal.ZERO)
                    .build();
        }

        // 5. 内存分组：收款单按付款人，计划单按承租人
        Map<String, List<BankReceipt>> receiptGroup = allReceipts.stream()
                .collect(Collectors.groupingBy(BankReceipt::getPayerAccountName));
        Map<String, List<RentPlans>> planGroup = allPlans.stream()
                .collect(Collectors.groupingBy(RentPlans::getLesseeName));
        // 使用线程安全的累加器
        LongAdder totalCount = new LongAdder();
        // BigDecimal 在多线程下累加需要特殊处理，这里我们定义两个对象
        final List<BigDecimal> principalSum = Collections.synchronizedList(new ArrayList<>());
        final List<BigDecimal> interestSum = Collections.synchronizedList(new ArrayList<>());

        // 6. 并发执行
        List<CompletableFuture<Void>> futures = receiptGroup.entrySet().stream().map(entry -> {
            String lesseeName = entry.getKey();
            List<BankReceipt> receipts = entry.getValue();
            List<RentPlans> plans = planGroup.get(lesseeName);

            return CompletableFuture.runAsync(() -> {
                if (CollUtil.isNotEmpty(plans)) {
                    // 调用内部事务方法，拿到该单客户的核销战果
                    WriteOffStat stat = tenantWriteOffService.processTenantWriteOff(lesseeName, receipts, plans);
                    // 将该客户的战果汇总累加到全局统计中
                    totalCount.add(stat.getCount());
                    principalSum.add(stat.getPrincipal());
                    interestSum.add(stat.getInterest());
                }
            }, writeOffExecutor);
        }).collect(Collectors.toList());

        // 阻塞主线程，等待线程池中所有子任务执行完毕
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        // 计算总金额
        BigDecimal totalP = principalSum.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalI = interestSum.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        double duration = (System.currentTimeMillis() - startTime) / 1000.0;

        log.info(">>> 核销完成！耗时:{}s, 笔数:{}, 本金:{}, 利息:{}", duration, totalCount.sum(), totalP, totalI);

        return WriteOffRespDto.builder()
                .totalTimeSeconds(duration)
                .totalCount(totalCount.sum())
                .totalPrincipal(totalP)
                .totalInterest(totalI)
                .build();
    }

    /**
     * 提交异步核销任务
     *
     * @return 返回任务ID
     */
    public String submitAsyncWriteOffTask(WriteOffReqDto reqDto) {
        // 生成任务ID
        String taskId = "TASK_" + System.currentTimeMillis();
        String redisKey = MqConfig.REDIS_STATUS_PREFIX + taskId;

        // 封装 MQ 消息
        WriteOffMsgDto msg = new WriteOffMsgDto(reqDto.getLesseeName(), reqDto.getDueDateEnd(), taskId);

        // 投递到 MQ
        rabbitTemplate.convertAndSend(MqConfig.EXCHANGE_NAME, MqConfig.ROUTING_KEY, msg);

        // 初始化 Redis 进度
        stringRedisTemplate.opsForHash().put(redisKey, "state", "RUNNING");
        stringRedisTemplate.expire(redisKey, 24, TimeUnit.HOURS);

        log.info("异步核销任务投递成功，taskId: {}", taskId);
        return taskId;
    }
}




