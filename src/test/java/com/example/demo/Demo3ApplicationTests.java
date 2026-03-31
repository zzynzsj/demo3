package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

@SpringBootTest
class Demo3ApplicationTests {

    // 🌟 这就是 Spring 帮我们准备好的 Redis 遥控器
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void testRedisHelloWorld() {
        System.out.println("========== Redis 测试开始 ==========");

        // 1. 存数据：在 Redis 里存一个 Key 叫 "my_name"，Value 叫 "张三"
        // opsForValue() 就代表我们要操作最基础的字符串（String）类型
        stringRedisTemplate.opsForValue().set("my_name", "张三");
        System.out.println("成功把名字存入 Redis！");

        // 2. 取数据：把刚才存进去的数据读出来
        String name = stringRedisTemplate.opsForValue().get("my_name");
        System.out.println("从 Redis 读出来的名字是：" + name);

        // 3. 高级玩法：存一个有“保质期”的数据（比如验证码，60秒后自动销毁）
        stringRedisTemplate.opsForValue().set("login_code", "8888", 60, TimeUnit.SECONDS);
        System.out.println("成功存入了一个生命周期只有 60 秒的验证码！");

        System.out.println("========== Redis 测试结束 ==========");
    }
}