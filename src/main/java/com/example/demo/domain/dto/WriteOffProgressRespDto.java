package com.example.demo.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 异步核销任务进度与耗时响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "异步核销任务进度响应数据")
public class WriteOffProgressRespDto {

    @Schema(description = "任务状态 (RUNNING-执行中, SUCCESS-成功, FAILED-失败, PARTIAL_SUCCESS-部分成功)",
            example = "RUNNING")
    private String state;

    @Schema(description = "子任务总数（待处理的承租人数量）", example = "100")
    private Long totalTaskCount;

    @Schema(description = "已完成的子任务数（已处理的承租人数量）", example = "45")
    private Long finishedTaskCount;

    @Schema(description = "已核销成功的总流水笔数", example = "120")
    private Long totalCount;

    @Schema(description = "已抵扣的总本金", example = "50000.00")
    private BigDecimal totalPrincipal;

    @Schema(description = "已抵扣的总利息", example = "2500.50")
    private BigDecimal totalInterest;

    @Schema(description = "任务当前耗时 (精确到毫秒的秒数)", example = "12.345")
    private Double costTimeSeconds;
}