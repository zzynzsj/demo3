package com.example.demo.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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

    /**
     * 批量执行核销任务入口
     */
    /**
     * 批量执行核销任务入口
     */
    public WriteOffRespDto executeBatchWriteOff(WriteOffReqDto req) {
        long startTime = System.currentTimeMillis();

        log.info(">>> 开始准备核销数据...");

        // 1. 构建收款单查询条件 (use_status: 0-未使用, 1-部分使用)
        LambdaQueryWrapper<BankReceipt> receiptWrapper = new LambdaQueryWrapper<>();
        receiptWrapper.lt(BankReceipt::getUseStatus, 2);

        // 2. 构建计划单查询条件 (verification_status: 0-未核销, 1-部分核销)
        LambdaQueryWrapper<RentPlans> planWrapper = new LambdaQueryWrapper<>();
        planWrapper.lt(RentPlans::getVerificationStatus, 2);

        // 3. 动态拼接：指定承租人
        if (ObjectUtil.isNotEmpty(req.getLesseeName())) {
            receiptWrapper.eq(BankReceipt::getPayerAccountName, req.getLesseeName());
            planWrapper.eq(RentPlans::getLesseeName, req.getLesseeName());
        }

        // 4. 动态拼接：指定应收截止日期
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
            String tenantName = entry.getKey();
            List<BankReceipt> receipts = entry.getValue();
            List<RentPlans> plans = planGroup.get(tenantName);

            return CompletableFuture.runAsync(() -> {
                if (CollUtil.isNotEmpty(plans)) {
                    // 调用内部事务方法，拿到该单客户的核销战果
                    WriteOffStat stat = processTenantWriteOff(tenantName, receipts, plans);

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
     * 单个承租人的原子核销逻辑（先息后本），并返回该客户的核销统计数据
     */
    @Transactional(rollbackFor = Exception.class)
    public WriteOffStat processTenantWriteOff(String tenantName, List<BankReceipt> receipts, List<RentPlans> plans) {
        List<WriteOffDetail> detailList = new ArrayList<>();

        // 用于记录当前客户本次核销累加的本金和利息
        BigDecimal currentTenantPrincipalSum = BigDecimal.ZERO;
        BigDecimal currentTenantInterestSum = BigDecimal.ZERO;

        // 按时间先后排序，确保先进先出（早期的款抵早期的账）
        receipts.sort(Comparator.comparing(BankReceipt::getReceiptDate));
        plans.sort(Comparator.comparing(RentPlans::getDueDate));

        for (BankReceipt receipt : receipts) {
            // 计算当前收款单可用余额
            BigDecimal availableAmount = receipt.getReceiptAmount().subtract(receipt.getUsedAmount());

            for (RentPlans plan : plans) {
                if (availableAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }
                if (ObjectUtil.equal(plan.getVerificationStatus(), 2)) {
                    continue;
                }

                // 计算该计划单还差多少本金和利息
                BigDecimal needInterest = plan.getInterestAmount().subtract(plan.getReceivedInterest());
                BigDecimal needPrincipal = plan.getPrincipalAmount().subtract(plan.getReceivedPrincipal());

                BigDecimal writeOffInterest = BigDecimal.ZERO;
                BigDecimal writeOffPrincipal = BigDecimal.ZERO;

                // 优先抵扣利息
                if (needInterest.compareTo(BigDecimal.ZERO) > 0) {
                    writeOffInterest = availableAmount.min(needInterest);
                    plan.setReceivedInterest(plan.getReceivedInterest().add(writeOffInterest));
                    availableAmount = availableAmount.subtract(writeOffInterest);
                }

                // 余额充足再抵扣本金
                if (availableAmount.compareTo(BigDecimal.ZERO) > 0 && needPrincipal.compareTo(BigDecimal.ZERO) > 0) {
                    writeOffPrincipal = availableAmount.min(needPrincipal);
                    plan.setReceivedPrincipal(plan.getReceivedPrincipal().add(writeOffPrincipal));
                    availableAmount = availableAmount.subtract(writeOffPrincipal);
                }

                // 发生核销动作，生成流水
                if (writeOffInterest.add(writeOffPrincipal).compareTo(BigDecimal.ZERO) > 0) {
                    WriteOffDetail detail = new WriteOffDetail();
                    detail.setId(IdUtil.fastSimpleUUID());
                    detail.setReceiptId(receipt.getId());
                    detail.setPlanId(plan.getId());
                    detail.setTenantName(tenantName);
                    detail.setWriteOffInterest(writeOffInterest);
                    detail.setWriteOffPrincipal(writeOffPrincipal);
                    detail.setCreateTime(new Date());
                    detailList.add(detail);

                    // 将单笔核销的金额累加到当前客户的总计中
                    currentTenantInterestSum = currentTenantInterestSum.add(writeOffInterest);
                    currentTenantPrincipalSum = currentTenantPrincipalSum.add(writeOffPrincipal);

                    // 更新计划单核销状态
                    BigDecimal totalReceived = plan.getReceivedInterest().add(plan.getReceivedPrincipal());
                    if (totalReceived.compareTo(plan.getTotalAmount()) >= 0) {
                        plan.setVerificationStatus(2); // 已完全核销
                    } else {
                        plan.setVerificationStatus(1); // 部分核销
                    }
                }
            }

            // 更新收款单使用状态
            BigDecimal finalUsed = receipt.getReceiptAmount().subtract(availableAmount);
            receipt.setUsedAmount(finalUsed);
            if (finalUsed.compareTo(receipt.getReceiptAmount()) >= 0) {
                receipt.setUseStatus(2); // 已完全使用
            } else if (finalUsed.compareTo(BigDecimal.ZERO) > 0) {
                receipt.setUseStatus(1); // 部分使用
            }
        }

        // 批量落库
        if (CollUtil.isNotEmpty(detailList)) {
            // 1. 保存核销明细
            this.saveBatch(detailList);
            // 2. 更新收款单与计划单 (当前为 for 循环单条更新，后续若遇到性能瓶颈可替换为 XML 批量更新)
            for (BankReceipt r : receipts) {
                bankReceiptMapper.updateById(r);
            }
            for (RentPlans p : plans) {
                rentPlansMapper.updateById(p);
            }
        }

        // 核销逻辑跑完后，该客户的结果打包返回给主线程
        return new WriteOffStat(detailList.size(), currentTenantPrincipalSum, currentTenantInterestSum);
    }

}




