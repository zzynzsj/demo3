package com.example.demo.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 银行收款表
 *
 * @TableName bank_receipt
 */
@TableName(value = "bank_receipt")
@Data
public class BankReceipt {
    /**
     * 主键ID
     */
    @TableId(value = "id")
    private String id;

    /**
     * 付款账户名称
     */
    @TableField(value = "payer_account_name")
    private String payerAccountName;

    /**
     * 付款银行名称
     */
    @TableField(value = "payer_bank_name")
    private String payerBankName;

    /**
     * 付款卡号
     */
    @TableField(value = "payer_card_number")
    private String payerCardNumber;

    /**
     * 付款金额
     */
    @TableField(value = "receipt_amount")
    private BigDecimal receiptAmount = BigDecimal.ZERO;

    /**
     * 付款日期
     */
    @TableField(value = "receipt_date")
    private LocalDate receiptDate;

    /**
     * 付款时间
     */
    @TableField(value = "receipt_time")
    private LocalTime receiptTime;

    /**
     * 已使用金额
     */
    @TableField(value = "used_amount")
    private BigDecimal usedAmount = BigDecimal.ZERO;

    /**
     * 使用状态（0-未使用，1-部分使用，2-已使用）
     */
    @TableField(value = "use_status")
    private Integer useStatus;

}
