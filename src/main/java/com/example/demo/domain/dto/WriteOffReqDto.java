package com.example.demo.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * @author zzy
 * @description 核销请求参数
 * @createDate 2026-03-30 23:08:50
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class WriteOffReqDto {
    @Schema(description = "指定承租人/付款账户名称", example = "张三公司")
    private String lesseeName;

    @Schema(description = "应收截止日期（仅核销该日期及之前的计划单）", type = "string", example = "2026-03-31")
    @JsonFormat(pattern = "yyyy-MM-dd")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate dueDateEnd;
}