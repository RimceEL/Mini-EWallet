package com.backend.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class AsyncMailRunnerTest {

    private final AsyncMailRunner runner = new AsyncMailRunner();

    @Test
    @DisplayName("run: mailTask thành công => task được chạy đúng 1 lần và không gọi onFailure")
    void run_success_runsTaskOnce_andDoesNotInvokeOnFailure() {
        AtomicInteger runs = new AtomicInteger();
        AtomicBoolean failed = new AtomicBoolean(false);

        runner.run(runs::incrementAndGet, e -> failed.set(true));

        assertThat(runs.get()).isEqualTo(1);
        assertThat(failed).isFalse();
    }

    @Test
    @DisplayName("run: mailTask ném lỗi => onFailure nhận đúng exception, đúng 1 lần, không lan lỗi ra ngoài")
    void run_mailFailure_invokesOnFailureOnce_andSwallowsException() {
        RuntimeException boom = new RuntimeException("SMTP down");
        AtomicReference<Exception> captured = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();

        assertThatCode(() -> runner.run(() -> {
            throw boom;
        }, e -> {
            calls.incrementAndGet();
            captured.set(e);
        })).doesNotThrowAnyException();

        assertThat(captured.get()).isSameAs(boom);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("run: mail chạy TRƯỚC, onFailure chạy SAU (hoàn lượt chỉ được làm khi mail đã lỗi)")
    void run_onFailureRunsAfterMailTask() {
        List<String> events = new ArrayList<>();

        runner.run(() -> {
            events.add("mail");
            throw new RuntimeException("SMTP down");
        }, e -> events.add("onFailure"));

        assertThat(events).containsExactly("mail", "onFailure");
    }

    @Test
    @DisplayName("run: bản thân onFailure cũng ném lỗi => vẫn bị nuốt an toàn")
    void run_onFailureThrows_isSwallowedSafely() {
        assertThatCode(() -> runner.run(
                () -> {
                    throw new RuntimeException("mail failed");
                },
                e -> {
                    throw new IllegalStateException("refund failed");
                })).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("run: onFailure = null mà mail lỗi => không ném lỗi ra ngoài")
    void run_nullOnFailure_doesNotThrow() {
        assertThatCode(() -> runner.run(() -> {
            throw new RuntimeException("SMTP down");
        }, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("run: mailTask = null => coi như mail lỗi, onFailure nhận NullPointerException, không ném ra ngoài")
    void run_nullTask_reportsNullPointerToOnFailure() {
        AtomicReference<Exception> captured = new AtomicReference<>();

        assertThatCode(() -> runner.run(null, captured::set)).doesNotThrowAnyException();

        assertThat(captured.get()).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("[Thiết kế] run: chỉ bắt Exception, còn Error (vd AssertionError, OutOfMemoryError) vẫn lan ra và KHÔNG gọi onFailure")
    void run_error_isNotSwallowed_andOnFailureNotInvoked() {
        AtomicBoolean failed = new AtomicBoolean(false);

        assertThatThrownBy(() -> runner.run(() -> {
            throw new AssertionError("fatal");
        }, e -> failed.set(true))).isInstanceOf(AssertionError.class);

        assertThat(failed).isFalse();
    }

    @Test
    @DisplayName("run(task): task thành công => được chạy đúng 1 lần")
    void runSingleArg_success_runsTaskOnce() {
        AtomicInteger runs = new AtomicInteger();

        runner.run(runs::incrementAndGet);

        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("run(task): task ném lỗi => bị nuốt, không lan ra người gọi (login/reset password không được hỏng vì mail)")
    void runSingleArg_failure_isSwallowed() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatCode(() -> runner.run(() -> {
            attempts.incrementAndGet();
            throw new RuntimeException("SMTP down");
        })).doesNotThrowAnyException();

        assertThat(attempts.get()).isEqualTo(1);
    }
}