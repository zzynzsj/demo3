package com.example.demo.controller;

import com.example.demo.domain.dto.WriteOffReqDto;
import com.example.demo.domain.dto.WriteOffRespDto;
import com.example.demo.service.WriteOffDetailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/test")
@Tag(name = "测试", description = "TestController")
@Slf4j
public class TestController {
    @Autowired
    private WriteOffDetailService writeOffDetailService;

    /**
     * 触发批量核销任务
     * 注意：针对百万级数据，实际生产环境中建议改为异步触发（返回“任务已提交”），
     * 但这里为了方便你直观测试，我们保持同步等待，执行完后返回结果。
     */
    @PostMapping("/execute")
    @Operation(summary = "触发批量核销", description = "基于承租人维度，利用多线程执行【先息后本】的核销逻辑")
    public WriteOffRespDto executeBatchWriteOff(@RequestBody(required = false) WriteOffReqDto reqDto) {
        if (reqDto == null) {
            reqDto = new WriteOffReqDto();
        }
        return writeOffDetailService.executeBatchWriteOff(reqDto);
    }
}
