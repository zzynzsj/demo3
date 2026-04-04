// package com.example.demo.listener;
//
// import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
// import com.example.demo.config.MqConfig;
// import com.example.demo.domain.dto.WriteOffMsgDto;
// import com.example.demo.domain.dto.WriteOffStat;
// import com.example.demo.domain.entity.BankReceipt;
// import com.example.demo.domain.entity.RentPlans;
// import com.example.demo.mapper.BankReceiptMapper;
// import com.example.demo.mapper.RentPlansMapper;
// import com.example.demo.service.LesseeWriteOffService;
// import com.rabbitmq.client.Channel;
// import lombok.extern.slf4j.Slf4j;
// import org.springframework.amqp.core.Message;
// import org.springframework.amqp.rabbit.annotation.RabbitListener;
// import org.springframework.beans.factory.annotation.Autowired;
// import org.springframework.data.redis.core.StringRedisTemplate;
// import org.springframework.stereotype.Component;
//
// import java.util.List;
//
// @Component
// @Slf4j
// public class WriteOffConsumer {
//
//     @Autowired
//     private LesseeWriteOffService lesseeWriteOffService;
//
//     @Autowired
//     private BankReceiptMapper bankReceiptMapper;
//
//     @Autowired
//     private RentPlansMapper rentPlansMapper;
//
//     @Autowired
//     private StringRedisTemplate stringRedisTemplate;
//
//     /**
//      * MQ 消费者监听入口。
//      */
//     @RabbitListener(queues = MqConfig.QUEUE_NAME)
//     public void receiveWriteOffTask(WriteOffMsgDto msgDto, Message message, Channel channel) {
//         // DeliveryTag 自增编号。在手动确认模式下，通过此编号通知 RabbitMQ 处理结果。
//         long deliveryTag = message.getMessageProperties().getDeliveryTag();
//         String lesseeName = msgDto.getLesseeName();
//         String taskId = msgDto.getTaskId();
//         String redisKey = "writeoff:status:" + taskId;
//
//         // 状态标志位，用于在catch及finally中判断数据库务是否真正完成，以隔离异常
//         boolean isBusinessSuccess = false;
//
//         //  1.核心业务处理
//         try {
//             // 幂等性控制：由于分布式系统中可能存在消息重试机制，此处必须实时读取数据库
//             // 若该承租人数据已被其他线程或重试线程核销，查询返回的list将为空，从而跳过后续逻辑
//             LambdaQueryWrapper<BankReceipt> receiptWrapper = new LambdaQueryWrapper<>();
//             receiptWrapper.lt(BankReceipt::getUseStatus, 2).eq(BankReceipt::getPayerAccountName, lesseeName);
//             List<BankReceipt> receipts = bankReceiptMapper.selectList(receiptWrapper);
//
//             LambdaQueryWrapper<RentPlans> planWrapper = new LambdaQueryWrapper<>();
//             planWrapper.lt(RentPlans::getVerificationStatus, 2).eq(RentPlans::getLesseeName, lesseeName);
//             // if (msgDto.getDueDateEnd() != null) {
//             //     planWrapper.le(RentPlans::getDueDate, msgDto.getDueDateEnd());
//             // }
//             List<RentPlans> plans = rentPlansMapper.selectList(planWrapper);
//             // 当存在可处理的账单和流水时，调用底层事务方法执行核销落库
//             if (!receipts.isEmpty() && !plans.isEmpty()) {
//                 WriteOffStat stat = lesseeWriteOffService.processLesseeWriteOff(lesseeName, receipts, plans);
//
//                 // 在多线程并发写入时，能够确保统计数据的强一致性，避免覆盖丢失。
//                 // 自增
//                 stringRedisTemplate.opsForHash().increment(redisKey, "totalCount", stat.getCount());
//                 stringRedisTemplate.opsForHash()
//                         .increment(redisKey, "totalPrincipal", stat.getPrincipal().doubleValue());
//                 stringRedisTemplate.opsForHash().increment(redisKey, "totalInterest", stat.getInterest().doubleValue());
//                 log.info("【后台任务】承租人 {} 核销成功，已更新 Redis 统计", lesseeName);
//             }
//
//             // 标记事务已提交
//             isBusinessSuccess = true;
//
//         } catch (Exception e) {
//             log.error("【后台任务】致命错误：业务处理异常，承租人: {}", lesseeName, e);
//             // 捕获到业务层抛出的异常，记录任务异常标志
//             stringRedisTemplate.opsForHash().put(redisKey, "hasError", "true");
//         }
//
//         // 2.MQ消息确认
//         try {
//             if (isBusinessSuccess) {
//                 // 发送肯定确认
//                 // false确认当前deliveryTag的消息，而非批量确认之前的消息，mq收到后会从队列中删除该消息
//                 channel.basicAck(deliveryTag, false);
//             } else {
//                 // 发送否定确认
//                 // b1-false 表示 requeue = false，即消息处理失败后不再放回队列，防止死信消息引发死循环消耗系统资源
//                 channel.basicNack(deliveryTag, false, false);
//             }
//         } catch (Exception mqEx) {
//             // 此处专门捕获通信异常。若仅在确认阶段抛出异常，说明数据库落库已经成功
//             // 此时仅记录日志，不得更改Redis业务状态，确保最终业务逻辑不受网络抖动影响
//             log.warn("【MQ网络异常】消息确认失败，但不影响刚才的落库业务。承租人: {}, 原因: {}", lesseeName,
//                     mqEx.getMessage());
//         }
//
//         // 3.全局进度更新
//         try {
//             // increment 返回的是累加后的最新数值
//             Long finished = stringRedisTemplate.opsForHash().increment(redisKey, "finishedTaskCount", 1);
//             Object totalObj = stringRedisTemplate.opsForHash().get(redisKey, "totalTaskCount");
//
//             // 判断当前消费者处理的是否为该全局任务的最后一笔子任务
//             if (totalObj != null && finished >= Long.parseLong(totalObj.toString())) {
//                 // 当所有子任务处理完毕，由最后一个完成的线程负责写入结束时间戳
//                 stringRedisTemplate.opsForHash().put(redisKey, "endTime", String.valueOf(System.currentTimeMillis()));
//
//                 // 检查历史执行序列中是否存在业务错误埋点
//                 Object hasError = stringRedisTemplate.opsForHash().get(redisKey, "hasError");
//                 if (hasError != null) {
//                     stringRedisTemplate.opsForHash().put(redisKey, "state", "FAILED");
//                 } else {
//                     // 全局数据均成功处理，更新外层任务的最终完成状态
//                     stringRedisTemplate.opsForHash().put(redisKey, "state", "SUCCESS");
//                 }
//                 log.info("========== 任务 {} 的所有子任务已全部执行完毕！ ==========", taskId);
//             }
//         } catch (Exception redisEx) {
//             log.error("【Redis通信异常】更新进度条失败", redisEx);
//         }
//     }
// }