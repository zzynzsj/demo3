package com.example.demo.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class Result<T> implements Serializable {

    private static final long serialVersionUID = -2663679485598740024L;

    private Integer code;

    private String message;

    private T data;

    private LocalDateTime timestamp;

    private Boolean success;

    // 成功响应
    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage("成功");
        result.setData(data);
        result.setTimestamp(LocalDateTime.now());
        result.setSuccess(true);
        return result;
    }

    // 失败响应
    public static <T> Result<T> error(Integer code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        result.setTimestamp(LocalDateTime.now());
        result.setSuccess(false);
        return result;
    }
}
