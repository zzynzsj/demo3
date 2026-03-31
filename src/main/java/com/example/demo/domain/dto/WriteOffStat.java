package com.example.demo.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WriteOffStat {
    /**
     * 核销流水笔数
     */
    private long count;

    /**
     * 核销总本金
     */
    private BigDecimal principal;

    /**
     * 核销总利息
     */
    private BigDecimal interest;
}
