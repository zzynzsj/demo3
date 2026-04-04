package com.example.demo.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class WriteOffMsgDto {
    // 承租人名称
    private String lesseeName;

    // 任务ID
    private String taskId;

}