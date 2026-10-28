package com.practice.cashflow.application.port.out;

import java.util.Optional;
import java.util.UUID;

public interface OutboxStore {

    Optional<Claim> claimNext();

    boolean markPublished(Claim claim);

    boolean retryOrPark(
            Claim claim,
            String errorCode,
            int delaySeconds
    );

    record Claim(
            UUID messageId,
            UUID claimToken,
            String payload,
            int attempt
    ){}
}
