package com.example.demo.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 核销明细流水表
 *
 * @TableName write_off_detail
 */
@TableName(value = "write_off_detail")
@Data
public class WriteOffDetail {
    /**
     * 主键ID
     */
    @TableId(value = "id")
    private String id;

    /**
     * 关联的银行收款单ID
     */
    @TableField(value = "receipt_id")
    private String receiptId;

    /**
     * 关联的租金计划ID
     */
    @TableField(value = "plan_id")
    private String planId;

    /**
     * 承租人名称
     */
    @TableField(value = "tenant_name")
    private String tenantName;

    /**
     * 本次核销本金
     */
    @TableField(value = "write_off_principal")
    private BigDecimal writeOffPrincipal = BigDecimal.ZERO;

    /**
     * 本次核销利息
     */
    @TableField(value = "write_off_interest")
    private BigDecimal writeOffInterest = BigDecimal.ZERO;

    /**
     * 核销创建时间
     */
    @TableField(value = "create_time")
    private Date createTime;

}
