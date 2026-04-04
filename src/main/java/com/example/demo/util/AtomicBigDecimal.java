package com.example.demo.util;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 线程安全的 BigDecimal 累加器（基于CAS自旋）
 */
public class AtomicBigDecimal {

    private final AtomicReference<BigDecimal> ref;

    public AtomicBigDecimal() {
        this.ref = new AtomicReference<>(BigDecimal.ZERO);
    }

    /**
     * 线程安全地累加
     */
    public void add(BigDecimal value) {
        ref.accumulateAndGet(value, BigDecimal::add);
    }

    /**
     * 获取当前值
     */
    public BigDecimal get() {
        return ref.get();
    }
}
