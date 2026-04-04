package com.example.demo.controller;

import com.example.demo.annotation.RedisLock;
import com.example.demo.common.Result;
import com.example.demo.domain.dto.WriteOffProgressRespDto;
import com.example.demo.domain.dto.WriteOffReqDto;
import com.example.demo.service.WriteOffDetailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/write-off")
@Tag(name = "核销", description = "WriteOffController")
@Slf4j
public class WriteOffController {
    @Autowired
    private WriteOffDetailService writeOffDetailService;

    /**
     * 异步核销
     *
     * @param reqDto 请求参数
     * @return 任务ID
     */
    @PostMapping("/async-execute")
    @Operation(summary = "异步核销（新）", description = "投递任务到MQ，利用Redis追踪进度")
    @RedisLock(prefix = "writeoff:submit_lock:", expireSeconds = 5, msg = "手速太快啦，请等待上次提交完成！")
    public Result<String> executeAsync(@RequestBody(required = false) WriteOffReqDto reqDto) {
        if (reqDto == null) {
            reqDto = new WriteOffReqDto();
        }
        String taskId = writeOffDetailService.submitAsyncWriteOffTask(reqDto);

        return Result.success(taskId);
    }

    /**
     * 获取处理进度
     *
     * @param taskId 任务ID
     * @return 处理进度
     */
    @GetMapping("/progress/{taskId}")
    public Result<WriteOffProgressRespDto> getProgress(@PathVariable String taskId) {
        // 查进度和算时间
        WriteOffProgressRespDto progressData = writeOffDetailService.getTaskProgress(taskId);
        if (progressData == null) {
            return Result.error(500, "任务不存在或已过期清理");
        }
        return Result.success(progressData);
    }
}
