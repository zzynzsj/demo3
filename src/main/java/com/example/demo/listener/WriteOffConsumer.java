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
import com.example.demo.service.WriteOffDetailService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
@Slf4j
public class WriteOffConsumer {
    @Autowired
    private TenantWriteOffService tenantWriteOffService;

    @Autowired
    private WriteOffDetailService writeOffDetailService;

    @Autowired
    private BankReceiptMapper bankReceiptMapper;

    @Autowired
    private RentPlansMapper rentPlansMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = MqConfig.QUEUE_NAME)
    public void receiveWriteOffTask(WriteOffMsgDto msgDto, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String tenantName = msgDto.getLesseeName();
        String taskId = msgDto.getTaskId();
        String redisKey = "writeoff:status:" + taskId;
        try {
            // 1. 查询该承租人的待处理明细
            LambdaQueryWrapper<BankReceipt> receiptWrapper = new LambdaQueryWrapper<>();
            receiptWrapper.lt(BankReceipt::getUseStatus, 2).eq(BankReceipt::getPayerAccountName, tenantName);
            List<BankReceipt> receipts = bankReceiptMapper.selectList(receiptWrapper);

            LambdaQueryWrapper<RentPlans> planWrapper = new LambdaQueryWrapper<>();
            planWrapper.lt(RentPlans::getVerificationStatus, 2).eq(RentPlans::getLesseeName, tenantName);
            if (msgDto.getDueDateEnd() != null) {
                planWrapper.le(RentPlans::getDueDate, msgDto.getDueDateEnd());
            }
            List<RentPlans> plans = rentPlansMapper.selectList(planWrapper);

            // 2. 执行你写的核心逻辑，获取本次核销结果
            if (!receipts.isEmpty() && !plans.isEmpty()) {
                WriteOffStat stat = tenantWriteOffService.processTenantWriteOff(tenantName, receipts, plans);
                // 3. 实时汇总到Redis进度
                // 笔数累加
                stringRedisTemplate.opsForHash().increment(redisKey, "totalCount", stat.getCount());
                // 金额累加 (BigDecimal转double满足展示需求)
                stringRedisTemplate.opsForHash()
                        .increment(redisKey, "totalPrincipal", stat.getPrincipal().doubleValue());
                stringRedisTemplate.opsForHash().increment(redisKey, "totalInterest", stat.getInterest().doubleValue());
                log.info("【后台任务】承租人 {} 核销成功，已更新 Redis 统计", tenantName);
            }

            // 标记本条消息处理完成
            stringRedisTemplate.opsForHash().put(redisKey, "state", "SUCCESS");
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
            log.info("任务 {} 处理成功并已更新状态为 SUCCESS", taskId);

        } catch (Exception e) {
            log.error("【后台任务】处理失败", e);
            stringRedisTemplate.opsForHash().put(redisKey, "state", "FAILED");
            // 失败了让消息重回队列
            // channel.basicNack(deliveryTag, false, true);
            // 失败了直接丢弃 不能无限重试
            channel.basicNack(deliveryTag, false, false);
        }
    }
}