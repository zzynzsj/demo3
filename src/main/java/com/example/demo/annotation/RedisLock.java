package com.example.demo.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RedisLock {

    String prefix();

    // 锁的过期时间
    long expireSeconds() default 5;

    // 提示信息
    String msg() default "操作太频繁，请稍后再试";
}