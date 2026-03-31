package com.example.demo.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
@Schema(description = "核销执行结果统计")
public class WriteOffRespDto {
    @Schema(description = "核销总耗时（秒）")
    private Double totalTimeSeconds;

    @Schema(description = "核销总笔数")
    private Long totalCount;

    @Schema(description = "核销总本金")
    private BigDecimal totalPrincipal;

    @Schema(description = "核销总利息")
    private BigDecimal totalInterest;
}
