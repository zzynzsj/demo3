package com.example.demo.listener;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.demo.config.MqConfig;
import com.example.demo.domain.dto.WriteOffMsgDto;
import com.example.demo.domain.dto.WriteOffStat;
import com.example.demo.domain.entity.BankReceipt;
import com.example.demo.domain.entity.RentPlans;
import com.example.demo.mapper.BankReceiptMapper;
import com.example.demo.mapper.RentPlansMapper;
import com.example.demo.service.TenantWriteOffService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class WriteOffConsumer {

    @Autowired
    private TenantWriteOffService tenantWriteOffService;

    @Autowired
    private BankReceiptMapper bankReceiptMapper;

    @Autowired
    private RentPlansMapper rentPlansMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = MqConfig.QUEUE_NAME)
    public void receiveWriteOffTask(WriteOffMsgDto msgDto, Message message, Channel channel) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String lesseeName = msgDto.getLesseeName();
        String taskId = msgDto.getTaskId();
        String redisKey = "writeoff:status:" + taskId;

        // 🌟 核心状态位：记录业务到底有没有落库成功
        boolean isBusinessSuccess = false;

        // ================== 1. 核心业务处理区 (只管查库和算账) ==================
        try {
            LambdaQueryWrapper<BankReceipt> receiptWrapper = new LambdaQueryWrapper<>();
            receiptWrapper.lt(BankReceipt::getUseStatus, 2).eq(BankReceipt::getPayerAccountName, lesseeName);
            List<BankReceipt> receipts = bankReceiptMapper.selectList(receiptWrapper);

            LambdaQueryWrapper<RentPlans> planWrapper = new LambdaQueryWrapper<>();
            planWrapper.lt(RentPlans::getVerificationStatus, 2).eq(RentPlans::getLesseeName, lesseeName);
            if (msgDto.getDueDateEnd() != null) {
                planWrapper.le(RentPlans::getDueDate, msgDto.getDueDateEnd());
            }
            List<RentPlans> plans = rentPlansMapper.selectList(planWrapper);

            if (!receipts.isEmpty() && !plans.isEmpty()) {
                WriteOffStat stat = tenantWriteOffService.processTenantWriteOff(lesseeName, receipts, plans);

                stringRedisTemplate.opsForHash().increment(redisKey, "totalCount", stat.getCount());
                stringRedisTemplate.opsForHash()
                        .increment(redisKey, "totalPrincipal", stat.getPrincipal().doubleValue());
                stringRedisTemplate.opsForHash().increment(redisKey, "totalInterest", stat.getInterest().doubleValue());
                log.info("【后台任务】承租人 {} 核销成功，已更新 Redis 统计", lesseeName);
            }

            // 走到这里，说明 MySQL 事务绝对已经完美提交了！
            isBusinessSuccess = true;

        } catch (Exception e) {
            log.error("【后台任务】致命错误：业务处理异常，承租人: {}", lesseeName, e);
            // 只有真的算错账了，才记录任务失败
            stringRedisTemplate.opsForHash().put(redisKey, "hasError", "true");
        }

        // ================== 2. MQ 消息确认区 (与业务异常隔离) ==================
        try {
            if (isBusinessSuccess) {
                channel.basicAck(deliveryTag, false);
            } else {
                channel.basicNack(deliveryTag, false, false);
            }
        } catch (Exception mqEx) {
            // 🌟 重点来了：如果只是网络断了导致 ACK 报错，我们只记个日志，绝对不去干扰 Redis 的成功状态！
            log.warn("【MQ网络异常】消息确认失败，但不影响刚才的落库业务。承租人: {}, 原因: {}", lesseeName,
                    mqEx.getMessage());
        }

        // ================== 3. 终点线裁判区 (保持不变) ==================
        try {
            Long finished = stringRedisTemplate.opsForHash().increment(redisKey, "finishedTaskCount", 1);
            Object totalObj = stringRedisTemplate.opsForHash().get(redisKey, "totalTaskCount");

            if (totalObj != null && finished >= Long.parseLong(totalObj.toString())) {
                stringRedisTemplate.opsForHash().put(redisKey, "endTime", String.valueOf(System.currentTimeMillis()));
                Object hasError = stringRedisTemplate.opsForHash().get(redisKey, "hasError");
                if (hasError != null) {
                    stringRedisTemplate.opsForHash().put(redisKey, "state", "FAILED");
                } else {
                    stringRedisTemplate.opsForHash().put(redisKey, "state", "SUCCESS");
                }
                log.info("========== 任务 {} 的所有子任务已全部执行完毕！ ==========", taskId);
            }
        } catch (Exception redisEx) {
            log.error("【Redis通信异常】更新进度条失败", redisEx);
        }
    }
}