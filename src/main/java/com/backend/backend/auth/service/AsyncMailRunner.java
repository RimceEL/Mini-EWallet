package com.backend.backend.auth.service;

import java.util.function.Consumer;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class AsyncMailRunner {

    @Async
    public void run(Runnable mailTask, Consumer<Exception> onFailure) {
        try {
            mailTask.run();
        } catch (Exception e) {
            log.error("Gửi mail thất bại", e);
            try {
                onFailure.accept(e);
            } catch (Exception inner) {
                log.error("onFailure cũng bị lỗi", inner);
            }
        }
    }

    public void run(Runnable mailTask) {
        run(mailTask, e -> {
        });
    }
}
