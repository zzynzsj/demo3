package com.example.demo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MqConfig {

    public static final String EXCHANGE_NAME = "writeoff.exchange";

    public static final String QUEUE_NAME = "writeoff.queue";

    public static final String ROUTING_KEY = "writeoff.routing.key";

    public static final String REDIS_STATUS_PREFIX = "writeoff:status:";

    // 声明Exchange
    @Bean
    public DirectExchange writeOffExchange() {
        return new DirectExchange(EXCHANGE_NAME);
    }

    // 声明核销队列（持久化）
    @Bean
    public Queue writeOffQueue() {
        return QueueBuilder.durable(QUEUE_NAME).build();
    }

    // 队列绑定到Exchange
    @Bean
    public Binding writeOffBinding(Queue writeOffQueue, DirectExchange writeOffExchange) {
        return BindingBuilder.bind(writeOffQueue).to(writeOffExchange).with(ROUTING_KEY);
    }

    /**
     * 引入 JSON 消息转换器
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}