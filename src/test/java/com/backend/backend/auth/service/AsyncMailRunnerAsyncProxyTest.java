package com.backend.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(AsyncMailRunnerAsyncProxyTest.Config.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class AsyncMailRunnerAsyncProxyTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAsync
    static class Config {
        @Bean
        AsyncMailRunner asyncMailRunner() {
            return new AsyncMailRunner();
        }
    }

    @Autowired
    private AsyncMailRunner runner;

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("[Đối chứng] AsyncMailRunner trong context là proxy của Spring (@Async đã được bật)")
    void runnerIsSpringProxy() {
        assertThat(AopUtils.isAopProxy(runner)).isTrue();
    }

    @Test
    @DisplayName("run(task, onFailure): mail chạy ở luồng KHÁC luồng gọi")
    void runWithOnFailure_executesOnAnotherThread() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();

        runner.run(() -> {
            worker.set(Thread.currentThread());
            started.countDown();
            awaitQuietly(release);
        }, e -> {
        });

        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.get()).isNotSameAs(Thread.currentThread());
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("run(task, onFailure): onFailure cũng chạy ở luồng khác và lỗi không lan về người gọi")
    void runWithOnFailure_failureHandledOnAnotherThread() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<Thread> failureThread = new AtomicReference<>();

        runner.run(() -> {
            throw new RuntimeException("SMTP down");
        }, e -> {
            failureThread.set(Thread.currentThread());
            failed.countDown();
        });

        assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(failureThread.get()).isNotSameAs(Thread.currentThread());
    }

    @Test
    @DisplayName("[GIẢ THUYẾT] run(task): mail cũng phải chạy ở luồng khác (hiện tại gọi qua this.run nên bỏ qua proxy => chạy đồng bộ)")
    void runSingleArg_shouldAlsoExecuteOnAnotherThread() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();

        runner.run(() -> {
            worker.set(Thread.currentThread());
            started.countDown();
            awaitQuietly(release);
        });

        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.get())
                    .as("run(Runnable) đang chạy ngay trên luồng gọi => request bị chặn chờ SMTP")
                    .isNotSameAs(Thread.currentThread());
        } finally {
            release.countDown();
        }
    }
}