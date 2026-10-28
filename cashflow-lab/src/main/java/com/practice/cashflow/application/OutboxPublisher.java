package com.practice.cashflow.application;

import com.practice.cashflow.application.port.out.OutboxSender;
import com.practice.cashflow.application.port.out.OutboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

public final class OutboxPublisher {
    private static final Logger log =
            LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxStore store;
    private final OutboxSender sender;

    public OutboxPublisher(OutboxStore store, OutboxSender sender) {
        this.store = store;
        this.sender = sender;
    }

    public boolean publishOne() {
        var candidate = store.claimNext();

        if(candidate.isEmpty()) return false;

        var claim = candidate.orElseThrow();

        try {
            sender.send(claim);
        }
        catch (RuntimeException failure) {
            int upper = Math.min(
                    300,
                    1 << Math.min(claim.attempt(), 9)
            );

            int delay = ThreadLocalRandom.current().nextInt(
                    Math.max(1, upper/2),
                    upper + 1
            );

            boolean updated = store.retryOrPark(
                    claim,
                    "SQS_SEND_UNCONFIRMED",
                    delay
            );

            log.warn(
                    "Outbox send unconfirmed messageId={} attempt={} "
                            + "stateUpdated={} exception={}",
                    claim.messageId(),
                    claim.attempt(),
                    updated,
                    failure.getClass().getSimpleName()
            );

            return true;
        }

        // A failure here leaves a reclaimable message after lease expiry.
        boolean marked = store.markPublished(claim);

        if (!marked) {
            log.warn(
                    "Outbox acknowledgement lost ownership messageId={}",
                    claim.messageId()
            );
        }
        return true;
    }
}
