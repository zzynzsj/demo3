package com.example.demo.aspect;

import com.example.demo.annotation.RedisLock;
import com.example.demo.common.Result;
import com.example.demo.domain.dto.WriteOffReqDto;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Aspect
@Component
@Slf4j
public class RedisLockAspect {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
 
    @Around("@annotation(redisLock)")
    public Object around(ProceedingJoinPoint joinPoint, RedisLock redisLock) throws Throwable {

        // 获取方法里的参数 承租人的名字作为锁的 Key）
        Object[] args = joinPoint.getArgs();
        String lesseeName = null;
        for (Object arg : args) {
            if (arg instanceof WriteOffReqDto) {
                lesseeName = ((WriteOffReqDto) arg).getLesseeName();
                break;
            }
        }

        if (lesseeName == null) {
            return joinPoint.proceed(); // 没传承租人，直接放行（交给正常的参数校验去管）
        }

        // 锁的名字
        String lockKey = redisLock.prefix() + lesseeName;

        // 加锁
        Boolean isLocked = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", redisLock.expireSeconds(), TimeUnit.SECONDS);

        if (Boolean.FALSE.equals(isLocked)) {
            log.warn("【AOP拦截】拦截到重复操作，Key: {}", lockKey);
            // 抢不到锁，直接打回，不执行原方法
            return Result.error(500, redisLock.msg());
        }

        try {
            // 4. 抢锁成功，执行 Controller 里的原生代码！
            return joinPoint.proceed();
        } finally {
            /* *
             * 本注解的作用是防手抖，必须让锁在 Redis 中自然存活至过期时间（expireSeconds）。
             * 如果提前删除，将导致防抖拦截失效。
             */
        }
    }
}