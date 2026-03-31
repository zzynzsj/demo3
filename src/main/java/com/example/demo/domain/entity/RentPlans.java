package com.example.demo.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 租金计划表（应收）
 *
 * @TableName rent_plans
 */
@TableName(value = "rent_plans")
@Data
public class RentPlans {

    /**
     * 主键ID
     */
    @TableId(value = "id")
    private String id;

    /**
     * 承租人名称（与收款表的付款账户关联）
     */
    @TableField(value = "lessee_name")
    private String lesseeName;

    /**
     * 应收日期
     */
    @TableField(value = "due_date")
    private Date dueDate;

    /**
     * 应收总金额
     */
    @TableField(value = "total_amount")
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /**
     * 应收本金
     */
    @TableField(value = "principal_amount")
    private BigDecimal principalAmount = BigDecimal.ZERO;

    /**
     * 应收利息
     */
    @TableField(value = "interest_amount")
    private BigDecimal interestAmount = BigDecimal.ZERO;

    /**
     * 实收本金（已核销的本金）
     */
    @TableField(value = "received_principal")
    private BigDecimal receivedPrincipal = BigDecimal.ZERO;

    /**
     * 实收利息（已核销的利息）
     */
    @TableField(value = "received_interest")
    private BigDecimal receivedInterest = BigDecimal.ZERO;

    /**
     * 核销状态（0-未核销，1-部分核销，2-已核销）
     */
    @TableField(value = "verification_status")
    private Integer verificationStatus;

}
