package com.paymesh.provider.web;

import com.paymesh.provider.application.PaymentService;
import com.paymesh.provider.domain.ApiResult;
import org.bson.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

@RestController
@ConditionalOnProperty(name = "provider.kind", havingValue = "stripe", matchIfMissing = true)
public class StripeController {
    private final PaymentService payments;
    public StripeController(PaymentService payments) { this.payments = payments; }

    @PostMapping(value = "/v1/payment_intents", consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<Document> create(@RequestHeader("Idempotency-Key") String key,
            @RequestParam long amount, @RequestParam String currency,
            @RequestParam(name = "metadata[order_reference]") String reference,
            @RequestParam(name = "payment_method", required = false) String token,
            @RequestParam(defaultValue = "false") boolean confirm,
            @RequestParam(name = "capture_method", defaultValue = "automatic") String captureMethod) {
        if (!java.util.Set.of("manual", "automatic").contains(captureMethod))
            throw new IllegalArgumentException("capture_method must be manual or automatic");
        return response(payments.create(key, amount, currency.toUpperCase(Locale.ROOT), reference,
                token, confirm, captureMethod.equals("manual")));
    }

    @GetMapping("/v1/payment_intents/{id}")
    public Document get(@PathVariable String id) { return ProviderViews.stripe(payments.get(id)); }

    @PostMapping("/v1/payment_intents/{id}/confirm")
    public ResponseEntity<Document> confirm(@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key,
            @RequestParam(name = "payment_method", required = false) String token) {
        return response(payments.confirm(id, key, token));
    }

    @PostMapping("/v1/payment_intents/{id}/capture")
    public ResponseEntity<Document> capture(@PathVariable String id, @RequestHeader("Idempotency-Key") String key) {
        return response(payments.capture(id, key));
    }

    private ResponseEntity<Document> response(ApiResult result) {
        if (result.status() == 402) {
            return SpringResponses.response(result.map(p -> new Document("error", new Document("type", "card_error")
                    .append("code", "card_declined").append("decline_code", p.getString("declineReason"))
                    .append("payment_intent", ProviderViews.stripe(p)))));
        }
        return SpringResponses.response(result.map(ProviderViews::stripe));
    }
}
