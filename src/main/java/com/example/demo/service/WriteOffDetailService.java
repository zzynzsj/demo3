package com.example.demo.service;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.demo.domain.dto.WriteOffProgressRespDto;
import com.example.demo.domain.dto.WriteOffReqDto;
import com.example.demo.domain.dto.WriteOffStat;
import com.example.demo.domain.entity.BankReceipt;
import com.example.demo.domain.entity.RentPlans;
import com.example.demo.domain.entity.WriteOffDetail;
import com.example.demo.mapper.BankReceiptMapper;
import com.example.demo.mapper.RentPlansMapper;
import com.example.demo.mapper.WriteOffDetailMapper;
import com.example.demo.util.AtomicBigDecimal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

import static com.example.demo.config.MqConfig.REDIS_STATUS_PREFIX;

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
     * 异步核销任务提交入口（基于 RabbitMQ 与 Redis）
     *
     * @return 返回全局唯一任务ID
     */
    public String submitAsyncWriteOffTask(WriteOffReqDto reqDto) {
        String taskId = "TASK_" + System.currentTimeMillis();
        String redisKey = REDIS_STATUS_PREFIX + taskId;

        // ============ 第一步：两条SQL加载全量数据 ============
        log.info("【任务{}】开始加载全量数据...", taskId);
        long loadStart = System.currentTimeMillis();

        LambdaQueryWrapper<BankReceipt> receiptWrapper = new LambdaQueryWrapper<>();
        receiptWrapper.lt(BankReceipt::getUseStatus, 2);
        List<BankReceipt> allReceipts = bankReceiptMapper.selectList(receiptWrapper);

        if (CollUtil.isEmpty(allReceipts)) {
            stringRedisTemplate.opsForHash().put(redisKey, "state", "SUCCESS");
            return taskId;
        }

        LambdaQueryWrapper<RentPlans> planWrapper = new LambdaQueryWrapper<>();
        planWrapper.lt(RentPlans::getVerificationStatus, 2);
        List<RentPlans> allPlans = rentPlansMapper.selectList(planWrapper);

        if (CollUtil.isEmpty(allPlans)) {
            stringRedisTemplate.opsForHash().put(redisKey, "state", "SUCCESS");
            return taskId;
        }

        // 按承租人分组 + 提前排好序
        Map<String, List<BankReceipt>> receiptMap = allReceipts.stream()
                .collect(Collectors.groupingBy(BankReceipt::getPayerAccountName));
        Map<String, List<RentPlans>> planMap = allPlans.stream()
                .collect(Collectors.groupingBy(RentPlans::getLesseeName));

        receiptMap.values().forEach(list ->
                list.sort(Comparator.comparing(BankReceipt::getReceiptDate)));
        planMap.values().forEach(list ->
                list.sort(Comparator.comparing(RentPlans::getDueDate)));

        // 取交集
        Set<String> lesseeNames = new HashSet<>(receiptMap.keySet());
        lesseeNames.retainAll(planMap.keySet());

        if (lesseeNames.isEmpty()) {
            stringRedisTemplate.opsForHash().put(redisKey, "state", "SUCCESS");
            return taskId;
        }

        log.info("【任务{}】数据加载完毕，耗时{}ms，待处理承租人{}个",
                taskId, System.currentTimeMillis() - loadStart, lesseeNames.size());

        // ============ 第二步：初始化Redis ============
        int totalLesseeCount = lesseeNames.size();
        stringRedisTemplate.opsForHash().put(redisKey, "state", "RUNNING");
        stringRedisTemplate.opsForHash().put(redisKey, "totalTaskCount", String.valueOf(totalLesseeCount));
        stringRedisTemplate.opsForHash().put(redisKey, "finishedTaskCount", "0");
        stringRedisTemplate.opsForHash().put(redisKey, "startTime", String.valueOf(System.currentTimeMillis()));
        stringRedisTemplate.expire(redisKey, 24, TimeUnit.HOURS);

        // ============ 第三步：异步执行（计算和写入分离） ============
        CompletableFuture.runAsync(() -> {
            try {
                final int BATCH_SIZE = 300;
                List<List<String>> batches = partition(new ArrayList<>(lesseeNames), BATCH_SIZE);
                LongAdder totalCount = new LongAdder();
                AtomicBigDecimal totalPrincipal = new AtomicBigDecimal();
                AtomicBigDecimal totalInterest = new AtomicBigDecimal();
                AtomicBoolean hasError = new AtomicBoolean(false);
                AtomicLong finishedCount = new AtomicLong(0);
                final Semaphore writeSemaphore = new Semaphore(4);
                log.info("【任务{}】开始执行，共{}个批次，{}个线程并行", taskId, batches.size(), 32);
                List<CompletableFuture<Void>> futures = batches.stream()
                        .map(batch -> CompletableFuture.runAsync(() -> {
                            try {
                                long batchStart = System.currentTimeMillis();
                                // --- 第1步：纯内存计算（无DB） ---
                                List<BankReceipt> batchReceipts = new ArrayList<>();
                                List<RentPlans> batchPlans = new ArrayList<>();
                                for (String lessee : batch) {
                                    List<BankReceipt> receipts = receiptMap.get(lessee);
                                    List<RentPlans> plans = planMap.get(lessee);
                                    if (receipts == null || plans == null) {
                                        continue;
                                    }
                                    WriteOffStat stat = lesseeWriteOffService
                                            .computeWriteOff(lessee, receipts, plans);
                                    totalCount.add(stat.getCount());
                                    totalPrincipal.add(stat.getPrincipal());
                                    totalInterest.add(stat.getInterest());
                                    batchReceipts.addAll(receipts);
                                    batchPlans.addAll(plans);
                                }
                                long calcCost = System.currentTimeMillis() - batchStart;
                                // --- 第2步：只写本批次的行，与其他线程零重叠，无锁竞争 ---
                                List<BankReceipt> changedReceipts = batchReceipts.stream()
                                        .filter(r -> r.getUseStatus() != null && r.getUseStatus() > 0)
                                        .collect(Collectors.toList());
                                List<RentPlans> changedPlans = batchPlans.stream()
                                        .filter(p -> p.getVerificationStatus() != null && p.getVerificationStatus() > 0)
                                        .collect(Collectors.toList());
                                if (!changedReceipts.isEmpty() || !changedPlans.isEmpty()) {
                                    long writeStart = System.currentTimeMillis();
                                    lesseeWriteOffService.batchUpdateDb(changedReceipts, changedPlans);
                                    log.info("写库耗时: {}ms, receipts:{}, plans:{}",
                                            System.currentTimeMillis() - writeStart,
                                            changedReceipts.size(), changedPlans.size());
                                }
                                long totalCost = System.currentTimeMillis() - batchStart;
                                long finished = finishedCount.addAndGet(batch.size());
                                log.info("【任务{}】批次完成 进度{}/{} 计算{}ms 总耗时{}ms",
                                        taskId, finished, totalLesseeCount, calcCost, totalCost);
                            } catch (Exception e) {
                                hasError.set(true);
                                log.error("【任务{}】批次异常", taskId, e);
                                finishedCount.addAndGet(batch.size());
                            }
                        }, writeOffExecutor))
                        .collect(Collectors.toList());
                // 等待所有批次完成
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                // 写入最终状态
                stringRedisTemplate.opsForHash().put(redisKey, "totalCount",
                        String.valueOf(totalCount.sum()));
                stringRedisTemplate.opsForHash().put(redisKey, "totalPrincipal",
                        totalPrincipal.get().toPlainString());
                stringRedisTemplate.opsForHash().put(redisKey, "totalInterest",
                        totalInterest.get().toPlainString());
                stringRedisTemplate.opsForHash().put(redisKey, "finishedTaskCount",
                        String.valueOf(totalLesseeCount));
                stringRedisTemplate.opsForHash().put(redisKey, "endTime",
                        String.valueOf(System.currentTimeMillis()));
                stringRedisTemplate.opsForHash().put(redisKey, "state",
                        hasError.get() ? "FAILED" : "SUCCESS");
                log.info("========== 任务{} 全部完成！核销{}笔 ==========",
                        taskId, totalCount.sum());
            } catch (Exception e) {
                log.error("【任务{}】顶层异常", taskId, e);
                stringRedisTemplate.opsForHash().put(redisKey, "state", "FAILED");
                stringRedisTemplate.opsForHash().put(redisKey, "endTime",
                        String.valueOf(System.currentTimeMillis()));
            }
        }, writeOffExecutor);
        log.info("已触发后台任务，立刻向前端返回 taskId: {}", taskId);
        return taskId;
    }

    /**
     * 查询任务进度与耗时
     */
    public WriteOffProgressRespDto getTaskProgress(String taskId) {
        String redisKey = REDIS_STATUS_PREFIX + taskId;
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

    /**
     * 将列表分割成指定大小的子列表
     */
    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

}