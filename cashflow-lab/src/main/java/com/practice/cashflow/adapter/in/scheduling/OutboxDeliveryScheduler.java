package com.practice.cashflow.adapter.in.scheduling;

import com.practice.cashflow.application.OutboxPublisher;
import com.practice.cashflow.application.OutboxReady;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class OutboxDeliveryScheduler implements AutoCloseable{

    private static final Logger log =
            LoggerFactory.getLogger(OutboxDeliveryScheduler.class);

    private static final int MAX_PER_PASS = 20;

    private final OutboxPublisher publisher;

    private final ThreadPoolExecutor executor =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(1),
                    Thread.ofPlatform()
                            .name("outbox-publisher-", 0)
                            .factory(),
                    new ThreadPoolExecutor.AbortPolicy()
            );

    public OutboxDeliveryScheduler(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(OutboxReady publisher) {
        wakeUp();
    }

    @Scheduled(fixedDelayString = "${app.outbox.scan-delay-ms:1000}")
    public void recoveryScan() {
        wakeUp();
    }

    private void wakeUp() {
        try {
            executor.execute(this::drain);
        } catch (RejectedExecutionException busyOrStopping) {
            // Work remains durable; a queued pass or recovery scan will retry.
            log.debug("Outbox wake-up coalesced or application stopping");
        }
    }

    private void drain() {
        try {
            int processed = 0;

            while (processed < MAX_PER_PASS
            && !Thread.currentThread().isInterrupted()
            && publisher.publishOne()) {
                processed++;
            }
            if (processed == MAX_PER_PASS) {
                wakeUp();
            }
        } catch (RuntimeException failure) {
            log.error(
                    "Outbox pass failed; recovery will retry. exception={}",
                    failure.getClass().getSimpleName()
            );
        }
    }
    @Override
    public void close() {
        executor.shutdown();

        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }

        } catch (InterruptedException interruptedException) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
