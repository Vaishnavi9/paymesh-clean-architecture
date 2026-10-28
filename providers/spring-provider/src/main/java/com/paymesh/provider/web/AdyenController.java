package com.paymesh.provider.web;

import com.paymesh.provider.application.PaymentService;
import com.paymesh.provider.domain.ProviderError;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.bson.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "provider.kind", havingValue = "adyen")
public class AdyenController {
    private final PaymentService payments;
    public AdyenController(PaymentService payments) { this.payments = payments; }
    public record Amount(@Min(1) long value, @NotBlank String currency) {}
    public record PaymentMethod(@NotBlank String type, @NotBlank String storedPaymentMethodId) {}
    public record PaymentRequest(@NotBlank String merchantAccount, @NotBlank String reference,
                                 @NotNull @Valid Amount amount, @NotNull @Valid PaymentMethod paymentMethod) {}
    public record CaptureRequest(@NotBlank String merchantAccount, @NotBlank String reference,
                                 @NotNull @Valid Amount amount) {}

    @PostMapping("/v72/payments")
    public ResponseEntity<Document> create(@RequestHeader("Idempotency-Key") String key,
                                           @Valid @RequestBody PaymentRequest request) {
        if (!"merchant-demo".equals(request.merchantAccount()))
            throw new ProviderError(403, "merchant_forbidden", "This credential belongs to merchant-demo");
        if (!"scheme".equals(request.paymentMethod().type()))
            throw new IllegalArgumentException("This simulator supports type scheme");
        return SpringResponses.response(payments.create(key, request.amount().value(), request.amount().currency(),
                request.reference(), request.paymentMethod().storedPaymentMethodId(), false, true)
                .map(ProviderViews::adyen));
    }
    @PostMapping("/v72/payments/{id}/captures")
    public ResponseEntity<Document> capture(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                            @Valid @RequestBody CaptureRequest request) {
        if (!"merchant-demo".equals(request.merchantAccount()))
            throw new ProviderError(403, "merchant_forbidden", "This credential belongs to merchant-demo");
        return SpringResponses.response(payments.requestAdyenCapture(id, key, request.amount().value(), request.amount().currency(), request.reference())
                .map(p -> new Document("paymentPspReference", p.getString("_id"))
                        .append("pspReference", p.getString("captureId")).append("reference", p.getString("captureReference"))
                        .append("merchantAccount", "merchant-demo").append("status", "received")
                        .append("amount", new Document("value", p.getLong("amount")).append("currency", p.getString("currency"))))
                );
    }
    // Explicit simulator extension. Adyen Checkout has no equivalent generic GET /payments/{id}.
    @GetMapping("/__sim/payments/{id}/result")
    public Document result(@PathVariable String id) { return ProviderViews.adyen(payments.get(id)); }
}
