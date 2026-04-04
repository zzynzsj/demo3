package com.example.demo.service;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.demo.config.MqConfig;
import com.example.demo.domain.dto.WriteOffMsgDto;
import com.example.demo.domain.dto.WriteOffProgressRespDto;
import com.example.demo.domain.dto.WriteOffReqDto;
import com.example.demo.domain.entity.BankReceipt;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
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

        // if (CharSequenceUtil.isNotBlank(reqDto.getLesseeName())) {
        //     // 指定承租人核销场景。
        //     targetLessees.add(reqDto.getLesseeName());
        // } else {
        // 全局一键核销场景：通过groupBy去重查询当前收款单表中存在未使用/部分使用余额的所有承租人名称
        LambdaQueryWrapper<BankReceipt> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(BankReceipt::getPayerAccountName);
        wrapper.lt(BankReceipt::getUseStatus, 2);
        wrapper.groupBy(BankReceipt::getPayerAccountName);
        // 查询所有待核销的收款单
        // List<BankReceipt> pendingList = bankReceiptMapper.selectList(wrapper);
        // // 如果不为空，则将所有待核销的收款单的所属租户名称添加到目标列表中
        // if (CollUtil.isNotEmpty(pendingList)) {
        //     targetLessees = pendingList.stream()
        //             .map(BankReceipt::getPayerAccountName)
        //             .collect(Collectors.toList());
        // }
        // 一查就崩
        List<Object> pendingObjs = bankReceiptMapper.selectObjs(wrapper);
        if (CollUtil.isNotEmpty(pendingObjs)) {
            targetLessees = pendingObjs.stream()
                    .map(Object::toString)
                    .collect(Collectors.toList());
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
        // 设置key的过期时间为24小时
        stringRedisTemplate.expire(redisKey, 24, TimeUnit.HOURS);
        stringRedisTemplate.opsForHash().put(redisKey, "startTime", String.valueOf(System.currentTimeMillis()));

        // 将目标承租人名单拆分，通过rabbitTemplate将每一个承租人的核销参数封装为消息投递至Exchange
        // 异步发送MQ消息
        // 创建一个 final 的局部变量，把名单传给它
        final List<String> finalTargetLessees = targetLessees;
        // CompletableFuture.runAsync(() -> {
        //     for (int i = 0; i < finalTargetLessees.size(); i++) {
        //         String lessee = finalTargetLessees.get(i);
        //         WriteOffMsgDto msg = new WriteOffMsgDto(lessee, reqDto.getDueDateEnd(), taskId);
        //         rabbitTemplate.convertAndSend(MqConfig.EXCHANGE_NAME, MqConfig.ROUTING_KEY, msg);
        //         // 限流,服务器太拉了
        //         // if (i > 0 && i % 3000 == 0) {
        //         //     try {
        //         //         TimeUnit.MILLISECONDS.sleep(30);
        //         //     } catch (InterruptedException e) {
        //         //         Thread.currentThread().interrupt();
        //         //     }
        //         // }
        //     }
        //     log.info("========= 异步任务 MQ 投递彻底完成，taskId: {}，共投递 {} 个子任务 =========", taskId,
        //             finalTargetLessees.size());
        // }, writeOffExecutor);
        // List<CompletableFuture<Void>> sendFutures = targetLessees.stream()
        //         .map(lessee -> CompletableFuture.runAsync(() -> {
        //             WriteOffMsgDto msg = new WriteOffMsgDto(lessee, reqDto.getDueDateEnd(), taskId);
        //             rabbitTemplate.convertAndSend(MqConfig.EXCHANGE_NAME, MqConfig.ROUTING_KEY, msg);
        //         }, writeOffExecutor))
        //         .collect(Collectors.toList());
        CompletableFuture.runAsync(() -> {
            // 异步线程，内部使用并行流
            finalTargetLessees.parallelStream().forEach(lessee -> {
                WriteOffMsgDto msg = new WriteOffMsgDto(lessee, taskId);
                rabbitTemplate.convertAndSend(MqConfig.EXCHANGE_NAME, MqConfig.ROUTING_KEY, msg); // 并行发送
            });
        }, writeOffExecutor);
        // 主线程不等待消息发完，直接秒回给前端！
        log.info("已触发后台投递线程，立刻向前端返回 taskId: {}", taskId);
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