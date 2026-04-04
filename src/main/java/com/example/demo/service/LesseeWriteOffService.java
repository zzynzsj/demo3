package com.example.demo.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.example.demo.domain.dto.WriteOffStat;
import com.example.demo.domain.entity.BankReceipt;
import com.example.demo.domain.entity.RentPlans;
import com.example.demo.mapper.BankReceiptMapper;
import com.example.demo.mapper.RentPlansMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@Slf4j
public class LesseeWriteOffService {

    @Autowired
    private BankReceiptMapper bankReceiptMapper;

    @Autowired
    private RentPlansMapper rentPlansMapper;

    /**
     * 纯内存计算核销（不涉及任何数据库操作）
     * 直接修改传入的 receipts 和 plans 对象的字段值
     *
     * @param lesseeName 承租人名称
     * @param receipts   该承租人的收款单列表（会被直接修改）
     * @param plans      该承租人的计划单列表（会被直接修改）
     * @return 本次核销的统计数据
     */
    public WriteOffStat computeWriteOff(String lesseeName, List<BankReceipt> receipts, List<RentPlans> plans) {
        BigDecimal principalSum = BigDecimal.ZERO;
        BigDecimal interestSum = BigDecimal.ZERO;
        int count = 0;

        for (BankReceipt receipt : receipts) {
            BigDecimal available = receipt.getReceiptAmount().subtract(receipt.getUsedAmount());

            for (RentPlans plan : plans) {
                if (available.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }
                if (ObjectUtil.equal(plan.getVerificationStatus(), 2)) {
                    continue;
                }

                BigDecimal needInterest = plan.getInterestAmount().subtract(plan.getReceivedInterest());
                BigDecimal needPrincipal = plan.getPrincipalAmount().subtract(plan.getReceivedPrincipal());

                BigDecimal wInterest = BigDecimal.ZERO;
                BigDecimal wPrincipal = BigDecimal.ZERO;

                // 先息后本
                if (needInterest.compareTo(BigDecimal.ZERO) > 0) {
                    wInterest = available.min(needInterest);
                    plan.setReceivedInterest(plan.getReceivedInterest().add(wInterest));
                    available = available.subtract(wInterest);
                }
                if (available.compareTo(BigDecimal.ZERO) > 0 && needPrincipal.compareTo(BigDecimal.ZERO) > 0) {
                    wPrincipal = available.min(needPrincipal);
                    plan.setReceivedPrincipal(plan.getReceivedPrincipal().add(wPrincipal));
                    available = available.subtract(wPrincipal);
                }

                if (wInterest.add(wPrincipal).compareTo(BigDecimal.ZERO) > 0) {
                    count++;
                    interestSum = interestSum.add(wInterest);
                    principalSum = principalSum.add(wPrincipal);

                    BigDecimal totalReceived = plan.getReceivedInterest().add(plan.getReceivedPrincipal());
                    plan.setVerificationStatus(
                            totalReceived.compareTo(plan.getTotalAmount()) >= 0 ? 2 : 1);
                }
            }

            // 更新收款单使用状态
            BigDecimal finalUsed = receipt.getReceiptAmount().subtract(available);
            receipt.setUsedAmount(finalUsed);
            if (finalUsed.compareTo(receipt.getReceiptAmount()) >= 0) {
                receipt.setUseStatus(2);
            } else if (finalUsed.compareTo(BigDecimal.ZERO) > 0) {
                receipt.setUseStatus(1);
            }
        }

        return new WriteOffStat(count, principalSum, interestSum);
    }

    /**
     * 批量更新数据库（手写SQL，一个事务内完成）
     * 内部再分片，每片2000条，防止SQL过长
     */
    @Transactional(rollbackFor = Exception.class)
    public void batchUpdateDb(List<BankReceipt> receipts, List<RentPlans> plans) {
        long t1 = System.currentTimeMillis();
        final int SQL_BATCH = 2000;

        if (CollUtil.isNotEmpty(receipts)) {
            for (int i = 0; i < receipts.size(); i += SQL_BATCH) {
                List<BankReceipt> sub = receipts.subList(i, Math.min(i + SQL_BATCH, receipts.size()));
                bankReceiptMapper.batchUpdateUsedAmount(sub);
            }
        }
        if (CollUtil.isNotEmpty(plans)) {
            for (int i = 0; i < plans.size(); i += SQL_BATCH) {
                List<RentPlans> sub = plans.subList(i, Math.min(i + SQL_BATCH, plans.size()));
                rentPlansMapper.batchUpdateReceivedAmount(sub);
            }
        }
        long t2 = System.currentTimeMillis();
        log.info("落库耗时: {}ms, receipts:{}, plans:{}", t2 - t1, receipts.size(), plans.size());
    }
}
