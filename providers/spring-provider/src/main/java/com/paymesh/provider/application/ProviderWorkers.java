package com.paymesh.provider.application;

import com.paymesh.provider.ProviderSettings;
import jakarta.annotation.PreDestroy;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Component
public class ProviderWorkers {
    private static final Logger log = LoggerFactory.getLogger(ProviderWorkers.class);
    private final PaymentService payments;
    private final WebhookDelivery webhooks;
    private final ProviderSettings settings;
    private final Semaphore capacity = new Semaphore(4);
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), Thread.ofPlatform().name("provider-worker-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    public ProviderWorkers(PaymentService payments, WebhookDelivery webhooks, ProviderSettings settings) {
        this.payments = payments; this.webhooks = webhooks; this.settings = settings;
    }
    @Scheduled(fixedDelay = 250, initialDelay = 500)
    public void poll() {
        if (!settings.workersEnabled()) return;
        if (settings.kind().equals("adyen")) submit(payments::claim, payments::processClaim);
        submit(webhooks::claim, webhooks::deliver);
    }
    private void submit(Supplier<Document> claim, Consumer<Document> operation) {
        if (!capacity.tryAcquire()) return;
        try {
            Document job = claim.get();
            if (job == null) { capacity.release(); return; }
            executor.execute(() -> {
                try { operation.accept(job); }
                catch (RuntimeException error) { log.warn("Background operation failed: {}", error.getClass().getSimpleName()); }
                finally { capacity.release(); }
            });
        } catch (RuntimeException error) {
            capacity.release();
            log.warn("Background job claim failed: {}", error.getClass().getSimpleName());
        }
    }
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try { if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException error) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
