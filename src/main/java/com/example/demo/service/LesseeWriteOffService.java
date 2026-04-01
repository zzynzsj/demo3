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
     * 执行单个承租人的核销逻辑（先扣利息，再扣本金）
     *
     * @param lesseeName 承租人名称
     * @param receipts   该承租人名下的有效收款单列表
     * @param plans      该承租人名下的有效应收计划单列表
     * @return 返回本次核销的统计数据（笔数、总本金、总利息）
     */
    @Transactional(rollbackFor = Exception.class) // 开启数据库事务，遇到任何异常会自动回滚，保证数据一致性
    public WriteOffStat processLesseeWriteOff(String lesseeName, List<BankReceipt> receipts, List<RentPlans> plans) {
        List<WriteOffDetail> detailList = new ArrayList<>();

        // BigDecimal用于财务计算的类，避免浮点数精度丢失。
        BigDecimal currentLesseePrincipalSum = BigDecimal.ZERO;
        BigDecimal currentLesseeInterestSum = BigDecimal.ZERO;

        // 对集合进行排序：按照日期先后顺序排序，确保早期的钱抵扣早期的账单
        // Comparator.comparing 是一种函数式写法，用于指定按照对象的哪个属性进行比较排序
        receipts.sort(Comparator.comparing(BankReceipt::getReceiptDate));
        plans.sort(Comparator.comparing(RentPlans::getDueDate));

        for (BankReceipt receipt : receipts) {
            // 计算当前收款单的可用余额：付款总金额 subtract(减去) 已使用金额
            BigDecimal availableAmount = receipt.getReceiptAmount().subtract(receipt.getUsedAmount());

            for (RentPlans plan : plans) {
                // compareTo 是 BigDecimal 的大小比较方法。如果 <= 0，说明当前收款单余额已经花光了，跳出内层循环，换下一张收款单
                if (availableAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }
                // ObjectUtil.equal 用于安全地比较两个对象是否相等，避免空指针异常。状态 2 表示已核销，跳过。
                if (ObjectUtil.equal(plan.getVerificationStatus(), 2)) {
                    continue;
                }

                // 计算当前这笔计划单还欠多少利息和本金
                BigDecimal needInterest = plan.getInterestAmount().subtract(plan.getReceivedInterest());
                BigDecimal needPrincipal = plan.getPrincipalAmount().subtract(plan.getReceivedPrincipal());

                BigDecimal writeOffInterest = BigDecimal.ZERO;
                BigDecimal writeOffPrincipal = BigDecimal.ZERO;

                // 优先抵扣利息 (当欠的利息 > 0 时)
                if (needInterest.compareTo(BigDecimal.ZERO) > 0) {
                    // min() 方法取两者中较小的一个：如果余额比欠款多，就扣欠款额；如果余额比欠款少，就把余额全扣光
                    writeOffInterest = availableAmount.min(needInterest);
                    // add() 是加法，累加到实收利息中
                    plan.setReceivedInterest(plan.getReceivedInterest().add(writeOffInterest));
                    // 扣减收款单的可用余额
                    availableAmount = availableAmount.subtract(writeOffInterest);
                }

                // 余额充足时，继续抵扣本金
                if (availableAmount.compareTo(BigDecimal.ZERO) > 0 && needPrincipal.compareTo(BigDecimal.ZERO) > 0) {
                    writeOffPrincipal = availableAmount.min(needPrincipal);
                    plan.setReceivedPrincipal(plan.getReceivedPrincipal().add(writeOffPrincipal));
                    availableAmount = availableAmount.subtract(writeOffPrincipal);
                }

                // 如果本金或利息发生了实际抵扣（两者之和大于 0）
                if (writeOffInterest.add(writeOffPrincipal).compareTo(BigDecimal.ZERO) > 0) {
                    WriteOffDetail detail = new WriteOffDetail();
                    // IdUtil.fastSimpleUUID() 是 Hutool 工具类提供的方法，用于生成不带横线的随机 UUID 字符串作为主键
                    detail.setId(IdUtil.fastSimpleUUID());
                    detail.setReceiptId(receipt.getId());
                    detail.setPlanId(plan.getId());
                    detail.setLesseeName(lesseeName);
                    detail.setWriteOffInterest(writeOffInterest);
                    detail.setWriteOffPrincipal(writeOffPrincipal);
                    detail.setCreateTime(new Date());
                    detailList.add(detail); // 将流水记录放入集合，准备稍后批量插入数据库

                    // 累加当前客户的总计数据
                    currentLesseeInterestSum = currentLesseeInterestSum.add(writeOffInterest);
                    currentLesseePrincipalSum = currentLesseePrincipalSum.add(writeOffPrincipal);

                    // 判断该计划单是否已经全部还清
                    BigDecimal totalReceived = plan.getReceivedInterest().add(plan.getReceivedPrincipal());
                    if (totalReceived.compareTo(plan.getTotalAmount()) >= 0) {
                        plan.setVerificationStatus(2); // 状态2：已完全核销
                    } else {
                        plan.setVerificationStatus(1); // 状态1：部分核销
                    }
                }
            }

            // 更新当前收款单的最终使用状态
            BigDecimal finalUsed = receipt.getReceiptAmount().subtract(availableAmount);
            receipt.setUsedAmount(finalUsed);
            if (finalUsed.compareTo(receipt.getReceiptAmount()) >= 0) {
                receipt.setUseStatus(2); // 钱花光了
            } else if (finalUsed.compareTo(BigDecimal.ZERO) > 0) {
                receipt.setUseStatus(1); // 钱没花光，部分使用
            }
        }

        // CollUtil.isNotEmpty() 检查集合是否不为 null 且内部有元素。避免给数据库发送空的批量执行 SQL
        if (CollUtil.isNotEmpty(detailList)) {
            // saveBatch 和 updateBatchById 是 MyBatis-Plus 提供的批量操作方法，比 for 循环单条更新效率高很多
            writeOffDetailService.saveBatch(detailList);
            if (CollUtil.isNotEmpty(receipts)) {
                bankReceiptService.updateBatchById(receipts);
            }
            if (CollUtil.isNotEmpty(plans)) {
                rentPlansService.updateBatchById(plans);
            }
        }

        // 返回本次操作的汇总对象
        return new WriteOffStat(detailList.size(), currentLesseePrincipalSum, currentLesseeInterestSum);
    }
}