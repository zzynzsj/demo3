package com.example.demo.listener;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class HelloReceiver {

    /**
     * 测试mq用
     */
    @RabbitListener(queues = "hello_queue")
    public void receive(String message) {
        System.out.println("收到消息：" + message);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.out.println("处理完毕");
    }
}