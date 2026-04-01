package com.example.demo;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
class Demo3ApplicationTests {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    // @Test
    // void testRedisHelloWorld() {
    //     System.out.println("========== Redis 测试开始 ==========");
    //
    //     //  存数据
    //     stringRedisTemplate.opsForValue().set("my_name", "张三");
    //     System.out.println("成功把名字存入 Redis！");
    //
    //     // 取数据
    //     String name = stringRedisTemplate.opsForValue().get("my_name");
    //     System.out.println("从 Redis 读出来的名字是：" + name);
    //
    //     // 存一个有保质期的数据
    //     stringRedisTemplate.opsForValue().set("login_code", "8888", 60, TimeUnit.SECONDS);
    //     System.out.println("成功存入了一个生命周期只有 60 秒的验证码！");
    //
    //     System.out.println("========== Redis 测试结束 ==========");
    // }
    //
    // @Test
    // void testMqHelloWorld() throws InterruptedException {
    //     System.out.println("========== 主线程开始发快递 ==========");
    //     // "hello_queue"
    //     String msg = "你好，这是一份来自主线程的千万级核销订单！";
    //     rabbitTemplate.convertAndSend("hello_queue", msg);
    //     System.out.println("========== 主线程发完了，去干别的事了 ==========");
    //     Thread.sleep(3000);
    //     System.out.println("========== 测试程序彻底结束 ==========");
    // }
}