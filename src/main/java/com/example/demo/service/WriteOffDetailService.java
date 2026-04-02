package com.example.demo.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.demo.config.MqConfig;
import com.example.demo.domain.dto.*;
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
import java.math.RoundingMode;
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
 * @description 针对表【write_off_detail(核销明细流水表)】的数据库操作Service实现
 * @createDate 2026-03-31 21:08:50
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
    private LesseeWriteOffService lesseeWriteOffService;

    /**
     * 基于线程池的同步批量核销方法（老版本）
     */
    public WriteOffRespDto executeBatchWriteOff(WriteOffReqDto req) {
        long startTime = System.currentTimeMillis();
        log.info(">>> 开始准备核销数据...");
        LambdaQueryWrapper<BankReceipt> receiptWrapper = new LambdaQueryWrapper<>();
        //  use_status < 2，仅过滤出状态为0-未使用,1-部分使用的记录。
        receiptWrapper.lt(BankReceipt::getUseStatus, 2);

        LambdaQueryWrapper<RentPlans> planWrapper = new LambdaQueryWrapper<>();
        //  verification_status < 2，仅过滤出状态为0-未核销,1-部分核销的记录。
        planWrapper.lt(RentPlans::getVerificationStatus, 2);

        // 承租人名称
        if (ObjectUtil.isNotEmpty(req.getLesseeName())) {
            receiptWrapper.eq(BankReceipt::getPayerAccountName, req.getLesseeName());
            planWrapper.eq(RentPlans::getLesseeName, req.getLesseeName());
        }

        if (ObjectUtil.isNotNull(req.getDueDateEnd())) {
            // 限制应收日期早于或等于前端传入的截止日期。
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
        // 将扁平的数据列表按承租人名称转换为 Map 分组结构，实现基于承租人维度的数据隔离，为后续的多线程并发处理提供基础。
        Map<String, List<BankReceipt>> receiptGroup = allReceipts.stream()
                .collect(Collectors.groupingBy(BankReceipt::getPayerAccountName));
        Map<String, List<RentPlans>> planGroup = allPlans.stream()
                .collect(Collectors.groupingBy(RentPlans::getLesseeName));

        // LongAdder累加器，用于统计核销总笔数。
        LongAdder totalCount = new LongAdder();

        // Collections.synchronizedList() 将非线程安全的 ArrayList 包装为线程安全的集合。
        // 因为后续的 CompletableFuture 会在多线程环境中同时向这两个集合中添加元素。
        final List<BigDecimal> principalSum = Collections.synchronizedList(new ArrayList<>());
        final List<BigDecimal> interestSum = Collections.synchronizedList(new ArrayList<>());

        // 将每个承租人的核销逻辑封装为一个异步任务。
        // 遍历 receiptGroup (按承租人分组后的 Map)，将其映射为多个独立的并发任务。
        List<CompletableFuture<Void>> futures = receiptGroup.entrySet().stream().map(entry -> {
            String lesseeName = entry.getKey();
            List<BankReceipt> receipts = entry.getValue();
            List<RentPlans> plans = planGroup.get(lesseeName);

            // CompletableFuture.runAsync() 将任务提交给自定义的 writeOffExecutor 线程池执行。
            return CompletableFuture.runAsync(() -> {
                if (CollUtil.isNotEmpty(plans)) {
                    WriteOffStat stat = lesseeWriteOffService.processLesseeWriteOff(lesseeName, receipts, plans);
                    totalCount.add(stat.getCount());
                    principalSum.add(stat.getPrincipal());
                    interestSum.add(stat.getInterest());
                }
            }, writeOffExecutor);
        }).collect(Collectors.toList());

        // CompletableFuture.allOf().join() 会阻塞当前主线程，等待上述列表中的所有子线程任务执行完毕，以确保数据完整收集。
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // reduce将集合中的所有BigDecimal累加求和。
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
     * 异步核销任务提交入口（基于 RabbitMQ 与 Redis）
     *
     * @return 返回全局唯一任务ID
     */
    public String submitAsyncWriteOffTask(WriteOffReqDto reqDto) {
        // 基于当前系统时间戳生成全局唯一任务id，并构建与之对应的 Redis 状态记录键名
        String taskId = "TASK_" + System.currentTimeMillis();
        String redisKey = MqConfig.REDIS_STATUS_PREFIX + taskId;

        List<String> targetLessees = new ArrayList<>();

        if (CharSequenceUtil.isNotBlank(reqDto.getLesseeName())) {
            // 指定承租人核销场景。
            targetLessees.add(reqDto.getLesseeName());
        } else {
            // 全局一键核销场景：通过 groupBy 去重查询当前收款单表中存在未使用/部分使用余额的所有承租人名称
            LambdaQueryWrapper<BankReceipt> wrapper = new LambdaQueryWrapper<>();
            wrapper.select(BankReceipt::getPayerAccountName);
            wrapper.lt(BankReceipt::getUseStatus, 2);
            wrapper.groupBy(BankReceipt::getPayerAccountName);
            // 查询所有待核销的收款单
            List<BankReceipt> pendingList = bankReceiptMapper.selectList(wrapper);
            // 如果不为空，则将所有待核销的收款单的所属租户名称添加到目标列表中
            if (CollUtil.isNotEmpty(pendingList)) {
                targetLessees = pendingList.stream()
                        .map(BankReceipt::getPayerAccountName)
                        .collect(Collectors.toList());
            }
        }

        if (CollUtil.isEmpty(targetLessees)) {
            // 边缘情况处理：如无可处理客户，直接初始化Redis最终成功状态，避免后续空转
            stringRedisTemplate.opsForHash().put(redisKey, "state", "SUCCESS");
            return taskId;
        }

        // 初始化RedisHash数据结构，存储该任务的元数据，供前端轮询查询
        stringRedisTemplate.opsForHash().put(redisKey, "state", "RUNNING");
        stringRedisTemplate.opsForHash().put(redisKey, "totalTaskCount", String.valueOf(targetLessees.size()));
        stringRedisTemplate.opsForHash().put(redisKey, "finishedTaskCount", "0");
        // 设置RedisKey的过期时间为 24 小时，防止历史任务堆积导致内存溢出
        stringRedisTemplate.expire(redisKey, 24, TimeUnit.HOURS);
        stringRedisTemplate.opsForHash().put(redisKey, "startTime", String.valueOf(System.currentTimeMillis()));

        // 将目标承租人名单拆分，通过rabbitTemplate将每一个承租人的核销参数封装为消息投递至Exchange
        for (String lessee : targetLessees) {
            WriteOffMsgDto msg = new WriteOffMsgDto(lessee, reqDto.getDueDateEnd(), taskId);
            // 发送
            rabbitTemplate.convertAndSend(MqConfig.EXCHANGE_NAME, MqConfig.ROUTING_KEY, msg);
        }

        log.info("异步核销任务投递成功，taskId: {}，共拆分为 {} 个子任务", taskId, targetLessees.size());
        return taskId;
    }

    /**
     * 查询任务进度与耗时
     */
    public WriteOffProgressRespDto getTaskProgress(String taskId) {
        String redisKey = MqConfig.REDIS_STATUS_PREFIX + taskId;
        Map<Object, Object> rawStats = stringRedisTemplate.opsForHash().entries(redisKey);
        if (rawStats.isEmpty()) {
            return null;
        }

        // 1. 安全地从 Redis Map 中提取并转换基础数据
        String state = getStr(rawStats, "state", "RUNNING");
        Long totalTaskCount = getLong(rawStats, "totalTaskCount", 0L);
        Long finishedTaskCount = getLong(rawStats, "finishedTaskCount", 0L);
        Long totalCount = getLong(rawStats, "totalCount", 0L);
        BigDecimal totalPrincipal = getBigDecimal(rawStats, "totalPrincipal", BigDecimal.ZERO);
        BigDecimal totalInterest = getBigDecimal(rawStats, "totalInterest", BigDecimal.ZERO);

        // 2. 动态耗时计算逻辑
        long startTime = getLong(rawStats, "startTime", System.currentTimeMillis());
        long endTime;

        if (("SUCCESS".equals(state) || "FAILED".equals(state) || "PARTIAL_SUCCESS".equals(state))
                && rawStats.containsKey("endTime")) {
            endTime = getLong(rawStats, "endTime", System.currentTimeMillis());
        } else {
            endTime = System.currentTimeMillis();
        }

        double costSeconds = (endTime - startTime) / 1000.0;
        BigDecimal bd = new BigDecimal(costSeconds).setScale(3, RoundingMode.HALF_UP);

        // 3. 构建并返回强类型响应对象
        return WriteOffProgressRespDto.builder()
                .state(state)
                .totalTaskCount(totalTaskCount)
                .finishedTaskCount(finishedTaskCount)
                .totalCount(totalCount)
                .totalPrincipal(totalPrincipal)
                .totalInterest(totalInterest)
                .costTimeSeconds(bd.doubleValue())
                .build();
    }

    // ================== 下方为私有安全转换辅助方法 ==================

    private String getStr(Map<Object, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private Long getLong(Map<Object, Object> map, String key, Long defaultValue) {
        Object value = map.get(key);
        try {
            return value != null ? Long.parseLong(value.toString()) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private BigDecimal getBigDecimal(Map<Object, Object> map, String key, BigDecimal defaultValue) {
        Object value = map.get(key);
        try {
            return value != null ? new BigDecimal(value.toString()) : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }
}