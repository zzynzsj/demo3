package com.example.demo.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class WriteOffMsgDto {
    // 承租人名称
    private String lesseeName;

    // 截止日期限制
    private LocalDate dueDateEnd;

    // 任务ID
    private String taskId;
}