package com.example.stock.service;

import com.example.stock.domain.Stock;
import com.example.stock.repository.StockRepository;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class StockServiceTest {

    @Autowired
    private PessimisticLockStockService stockService;

    @Autowired
    private StockRepository stockRepository;

    @BeforeEach
    public void before() {
        stockRepository.saveAndFlush(new Stock(1L,100L));
    }

    @AfterEach
    public void after() {
        stockRepository.deleteAll();
    }

    @Test
    void test() {
    // given
        stockService.decrease(1L,1L);

    // when
        Stock stock = stockRepository.findById(1L).orElseThrow();

    // then
        assertThat(stock.getQuantity()).isEqualTo(99);
    }


    @Test
    void test2() throws InterruptedException {
    // given
        int threadCount  = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i=0;i<threadCount; i++) {
            executorService.submit(() ->
            {
                try {
                    stockService.decrease(1L,1L);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

    // when
        Stock stock = stockRepository.findById(1L).orElseThrow();
    // then
        assertThat(stock.getQuantity()).isEqualTo(0);

        // 레이스 컨디션
    }
}