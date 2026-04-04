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
public class LesseeWriteOffService {

    @Autowired
    private WriteOffDetailService writeOffDetailService;

    @Autowired
    private BankReceiptService bankReceiptService;

    @Autowired
    private RentPlansService rentPlansService;

    /**
     * 执行单个承租人的核销逻辑（核心业务规则：先扣利息，再扣本金）
     *
     * @param lesseeName 承租人名称
     * @param receipts   该承租人名下的有效收款单列表（可用于抵扣的资金）
     * @param plans      该承租人名下的有效应收计划单列表（待归还的账单）
     * @return 返回本次核销的统计数据（包含生成的流水笔数、抵扣的总本金、抵扣的总利息）
     */
    @Transactional(rollbackFor = Exception.class)
    public WriteOffStat processLesseeWriteOff(String lesseeName, List<BankReceipt> receipts, List<RentPlans> plans) {
        // 暂存本次操作产生的所有核销明细记录，循环结束后统一执行批量insert
        List<WriteOffDetail> detailList = new ArrayList<>();

        // BigDecimal 避免浮点数精度丢失问题
        // 定义这两个变量是为了在循环中持续累加当前承租人本次核销的总本金和总利息，最终返回给调用方用于数据汇总
        BigDecimal currentLesseePrincipalSum = BigDecimal.ZERO;
        BigDecimal currentLesseeInterestSum = BigDecimal.ZERO;

        // 1. 收款单按收款日期升序排序，确保优先使用最早进入账户的资金
        receipts.sort(Comparator.comparing(BankReceipt::getReceiptDate));
        // 2. 计划单按应收日期升序排序，确保优先抵扣产生时间最早的欠款
        plans.sort(Comparator.comparing(RentPlans::getDueDate));

        // 外层循环：遍历该承租人名下的每一笔有效收款单
        for (BankReceipt receipt : receipts) {
            // 计算当前收款单的剩余可用余额
            // subtract收款总额-已使用金额
            BigDecimal availableAmount = receipt.getReceiptAmount().subtract(receipt.getUsedAmount());

            // 内层循环：使用当前收款单的可用余额，按时间顺序依次尝试抵扣每一笔计划单
            for (RentPlans plan : plans) {
                // 如果<= 0，说明当前收款单的余额已被完全耗尽，无需继续遍历后续计划单，直接break退出内层循环，处理下一张收款单
                if (availableAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }

                // 2-已核销，如果在之前的循环中该计划单已经被彻底还清，则continue跳过，处理下一个计划单
                if (ObjectUtil.equal(plan.getVerificationStatus(), 2)) {
                    continue;
                }

                // 计算当前计划单实际还欠缺多少利息和本金
                BigDecimal needInterest = plan.getInterestAmount().subtract(plan.getReceivedInterest());
                BigDecimal needPrincipal = plan.getPrincipalAmount().subtract(plan.getReceivedPrincipal());

                // 记录当前收款单对当前计划单，实际发生的利息和本金抵扣额
                BigDecimal writeOffInterest = BigDecimal.ZERO;
                BigDecimal writeOffPrincipal = BigDecimal.ZERO;

                // 业务规则：先息后本。只要当前计划单还欠利息（needInterest>0），必须优先使用可用余额抵扣利息
                if (needInterest.compareTo(BigDecimal.ZERO) > 0) {
                    // min() 方法获取两者中的较小值。保证抵扣金额既不会超过计划单的欠款额，也不会透支收款单的可用余额
                    writeOffInterest = availableAmount.min(needInterest);
                    // add() 是 BigDecimal 的加法方法。更新计划单对象中的“实收利息”字段
                    plan.setReceivedInterest(plan.getReceivedInterest().add(writeOffInterest));
                    // 同步扣减当前收款单的可用余额。
                    availableAmount = availableAmount.subtract(writeOffInterest);
                }

                // 业务规则：在利息抵扣完毕后，如果收款单仍有可用余额，且计划单仍欠本金，则继续抵扣本金
                if (availableAmount.compareTo(BigDecimal.ZERO) > 0 && needPrincipal.compareTo(BigDecimal.ZERO) > 0) {
                    writeOffPrincipal = availableAmount.min(needPrincipal);
                    plan.setReceivedPrincipal(plan.getReceivedPrincipal().add(writeOffPrincipal));
                    availableAmount = availableAmount.subtract(writeOffPrincipal);
                }

                // 判断是否发生了实际的资金抵扣行为（利息抵扣额+本金抵扣额>0）。如果发生了抵扣，必须生成一条核销流水记录
                if (writeOffInterest.add(writeOffPrincipal).compareTo(BigDecimal.ZERO) > 0) {
                    WriteOffDetail detail = new WriteOffDetail();
                    // 作为数据库主键
                    detail.setId(IdUtil.fastSimpleUUID());
                    detail.setReceiptId(receipt.getId());
                    detail.setPlanId(plan.getId());
                    detail.setLesseeName(lesseeName);
                    detail.setWriteOffInterest(writeOffInterest);
                    detail.setWriteOffPrincipal(writeOffPrincipal);
                    detail.setCreateTime(new Date());
                    // 将流水对象添加至集合暂存
                    detailList.add(detail);
                    // 累加本次操作的总抵扣金额
                    currentLesseeInterestSum = currentLesseeInterestSum.add(writeOffInterest);
                    currentLesseePrincipalSum = currentLesseePrincipalSum.add(writeOffPrincipal);

                    // 重新计算并更新该计划单的最新核销状态
                    BigDecimal totalReceived = plan.getReceivedInterest().add(plan.getReceivedPrincipal());
                    if (totalReceived.compareTo(plan.getTotalAmount()) >= 0) {
                        plan.setVerificationStatus(2); // 2-已完全核销
                    } else {
                        plan.setVerificationStatus(1); // 状态1-部分核销
                    }
                }
            }
            // 当一笔收款单结束内层循环（可能是遍历完了所有欠款，也可能是余额提前耗尽），更新该收款单的最终使用状态。
            BigDecimal finalUsed = receipt.getReceiptAmount().subtract(availableAmount);
            receipt.setUsedAmount(finalUsed);
            if (finalUsed.compareTo(receipt.getReceiptAmount()) >= 0) {
                receipt.setUseStatus(2); // 状态2：已完全使用
            } else if (finalUsed.compareTo(BigDecimal.ZERO) > 0) {
                receipt.setUseStatus(1); // 状态1：部分使用
            }
        }
        if (CollUtil.isNotEmpty(detailList)) {
            // 批量保存和更新
            writeOffDetailService.saveBatch(detailList);
            if (CollUtil.isNotEmpty(receipts)) {
                bankReceiptService.updateBatchById(receipts);
            }
            if (CollUtil.isNotEmpty(plans)) {
                rentPlansService.updateBatchById(plans);
            }
        }
        return new WriteOffStat(detailList.size(), currentLesseePrincipalSum, currentLesseeInterestSum);
    }

}