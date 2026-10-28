package com.paymesh.provider.web;

import com.paymesh.provider.domain.ApiResult;
import com.paymesh.provider.domain.ProviderError;
import org.bson.Document;
import org.springframework.http.ResponseEntity;

final class SpringResponses {
    private SpringResponses() {}
    static ResponseEntity<Document> response(ApiResult result) {
        if (result.simulateLostResponse())
            throw new ProviderError(504, "simulated_response_lost",
                    "Provider committed; retry the SAME operation key to recover the result");
        return ResponseEntity.status(result.status()).header("X-Simulator", "paymesh-local")
                .header("X-Idempotent-Replay", Boolean.toString(result.replayed())).body(result.body());
    }
}
