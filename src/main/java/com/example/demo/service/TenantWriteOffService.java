package com.example.demo.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import com.example.demo.domain.dto.WriteOffStat;
import com.example.demo.domain.entity.BankReceipt;
import com.example.demo.domain.entity.RentPlans;
import com.example.demo.domain.entity.WriteOffDetail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

@Service
@Slf4j
public class TenantWriteOffService {

    @Autowired
    private WriteOffDetailService writeOffDetailService;

    @Autowired
    private BankReceiptService bankReceiptService; // 记得换成Service批量更新

    @Autowired
    private RentPlansService rentPlansService;

    /**
     * 单个承租人的原子核销逻辑（先息后本），并返回该客户的核销统计数据
     *
     * @param lesseeName
     * @param receipts
     * @param plans
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public WriteOffStat processTenantWriteOff(String lesseeName, List<BankReceipt> receipts, List<RentPlans> plans) {
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
                    detail.setLesseeName(lesseeName);
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
            // 保存核销明细
            writeOffDetailService.saveBatch(detailList);
            // 更新收款单与计划单
            if (CollUtil.isNotEmpty(receipts)) {
                bankReceiptService.updateBatchById(receipts);
            }
            if (CollUtil.isNotEmpty(plans)) {
                rentPlansService.updateBatchById(plans);
            }
        }

        // 核销逻辑跑完后，该客户的结果打包返回给主线程
        return new WriteOffStat(detailList.size(), currentTenantPrincipalSum, currentTenantInterestSum);
    }
}
