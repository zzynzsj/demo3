package com.example.demo;

import cn.hutool.core.util.IdUtil;
import com.example.demo.domain.entity.BankReceipt;
import com.example.demo.domain.entity.RentPlans;
import com.example.demo.service.BankReceiptService;
import com.example.demo.service.RentPlansService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

@SpringBootTest
class Demo3ApplicationTests {

    @Autowired
    private RentPlansService rentPlansService;

    @Autowired
    private BankReceiptService bankReceiptService;

    // 直接复用你写好的处理核销的线程池
    @Autowired
    @Qualifier("writeOffExecutor")
    private Executor writeOffExecutor;

    @Test
    void fastGenerateMillionData() throws InterruptedException {
        int threadCount = 20;       // 开启 20 个并发线程
        int batchSize = 50000;      // 每个线程负责造 5 万个客户的数据 (合计 100万客户)

        // CountDownLatch 用于阻塞主线程，直到所有子线程干完活
        CountDownLatch latch = new CountDownLatch(threadCount);
        long startTime = System.currentTimeMillis();

        System.out.println("========== 🚀 开始多线程极速造数据 ==========");

        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            writeOffExecutor.execute(() -> {
                try {
                    // 初始化大集合，避免频繁扩容影响性能
                    List<RentPlans> plansList = new ArrayList<>(batchSize);
                    List<BankReceipt> receiptList = new ArrayList<>(batchSize);

                    for (int j = 0; j < batchSize; j++) {
                        // 保证每个客户名字全局唯一 (如: 极速客户_1_49999)
                        String lesseeName = "极速客户_" + threadIndex + "_" + j;

                        // 随机生成金额 (本金 1000~10000)
                        double randomPrincipal = 1000 + Math.random() * 9000;
                        BigDecimal principal = new BigDecimal(randomPrincipal).setScale(2, RoundingMode.HALF_UP);
                        BigDecimal interest =
                                principal.multiply(new BigDecimal("0.1")).setScale(2, RoundingMode.HALF_UP);
                        BigDecimal total = principal.add(interest);

                        // 1. 构造应收计划单
                        RentPlans plan = new RentPlans();
                        plan.setId(IdUtil.fastSimpleUUID());
                        plan.setLesseeName(lesseeName);
                        plan.setDueDate(new Date()); // 统一设置为今天，方便测试
                        plan.setTotalAmount(total);
                        plan.setPrincipalAmount(principal);
                        plan.setInterestAmount(interest);
                        plan.setReceivedPrincipal(BigDecimal.ZERO);
                        plan.setReceivedInterest(BigDecimal.ZERO);
                        plan.setVerificationStatus(0);
                        plansList.add(plan);

                        // 2. 构造银行收款单 (金额上下浮动)
                        BigDecimal receiptAmount = total.multiply(new BigDecimal(0.8 + Math.random() * 0.5))
                                .setScale(2, RoundingMode.HALF_UP);
                        BankReceipt receipt = new BankReceipt();
                        receipt.setId(IdUtil.fastSimpleUUID());
                        receipt.setPayerAccountName(lesseeName);
                        receipt.setPayerBankName("招商银行");
                        receipt.setPayerCardNumber("6222020000000000");
                        receipt.setReceiptAmount(receiptAmount);
                        receipt.setUsedAmount(BigDecimal.ZERO);
                        receipt.setUseStatus(0);
                        receipt.setReceiptDate(LocalDate.now());
                        receipt.setReceiptTime(LocalTime.now());
                        receiptList.add(receipt);
                    }

                    // 核心提速点：MyBatis-Plus 的批量插入功能，每 5000 条拼接成一个大 SQL 提交一次
                    rentPlansService.saveBatch(plansList, 1000);
                    bankReceiptService.saveBatch(receiptList, 1000);

                    System.out.println("线程 " + threadIndex + " 负责的 5万条数据已落库！");
                } finally {
                    // 当前线程干完活，计数器减 1
                    latch.countDown();
                }
            });
        }

        // 主线程在这里死等，直到 latch 变成 0
        latch.await();
        long costTime = System.currentTimeMillis() - startTime;
        System.out.println("========== ✅ 100万数据多线程生成完毕！总耗时: " + (costTime / 1000.0) + " 秒 ==========");
    }
}