package com.paymesh.paypal;

import com.paymesh.provider.ProviderSettings;
import com.paymesh.provider.application.WebhookDelivery;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.*;

@ApplicationScoped
public class WebhookWorker {
    private static final System.Logger log = System.getLogger(WebhookWorker.class.getName());
    private final WebhookDelivery delivery;
    private final ProviderSettings settings;
    private final Semaphore capacity = new Semaphore(4);
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), Thread.ofPlatform().name("paypal-webhook-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    public WebhookWorker(WebhookDelivery delivery, ProviderSettings settings) { this.delivery = delivery; this.settings = settings; }
    @Scheduled(every="1s", concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        if (!settings.workersEnabled()) return;
        // Claim only as much work as this process can execute; backlog stays durable in MongoDB.
        for (int slot = 0; slot < 4; slot++) {
            if (!capacity.tryAcquire()) return;
            submitOne();
        }
    }
    private void submitOne() {
        try {
            var job = delivery.claim();
            if (job == null) { capacity.release(); return; }
            executor.execute(() -> {
                try { delivery.deliver(job); }
                catch (RuntimeException error) { log.log(System.Logger.Level.WARNING, "Webhook attempt failed: " + error.getClass().getSimpleName()); }
                finally { capacity.release(); }
            });
        } catch (RuntimeException error) {
            capacity.release();
            log.log(System.Logger.Level.WARNING, "Webhook claim failed: " + error.getClass().getSimpleName());
        }
    }
    @PreDestroy void shutdown() {
        executor.shutdown();
        try { if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException interrupted) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
