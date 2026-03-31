package com.example.demo.listener;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class HelloReceiver {

    @RabbitListener(queues = "hello_queue")
    public void receive(String message) {
        System.out.println("【后台小哥】收到快递了！拆开看里面是：" + message);
        // 模拟小哥处理这个任务花了点时间（比如去核销百万级数据了）
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.out.println("【后台小哥】快递处理完毕！");
    }
}