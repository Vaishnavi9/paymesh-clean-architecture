package com.practice.cashflow.adapter.in.web;

import com.practice.cashflow.application.PaymentStatus;
import com.practice.cashflow.application.port.in.AcceptPaymentUseCase;
import com.practice.cashflow.application.port.in.GetPaymentStatusUseCase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/v1/payments")
@Profile("db & acceptance")
public class PaymentSubmissionController {

    private final AcceptPaymentUseCase acceptPaymentUseCase;
    private final GetPaymentStatusUseCase getPaymentStatusUseCase;
    private final UUID merchantId;

    public PaymentSubmissionController(
            AcceptPaymentUseCase acceptPaymentUseCase,
            GetPaymentStatusUseCase getPaymentStatusUseCase,
            @Value("${app.payments.demo-merchant-id}") UUID merchantId
    ) {
        this.acceptPaymentUseCase = acceptPaymentUseCase;
        this.getPaymentStatusUseCase = getPaymentStatusUseCase;
        this.merchantId = merchantId;
    }

    @PostMapping
    public ResponseEntity<PaymentSubmissionResponse> submit(
            @RequestHeader HttpHeaders httpHeaders,
            @Valid @RequestBody PaymentSubmissionRequest paymentSubmissionRequest,
            HttpServletRequest httpServletRequest
    ) {
        var keys = httpHeaders.get("Idempotency-Key");

        if (keys == null || keys.size() != 1) {
            throw new IllegalArgumentException(
                    "Exactly one Idempotency-Key header is required"
            );
        }

            var acceptance = acceptPaymentUseCase.accept(
                    paymentSubmissionRequest.toCommand(
                            merchantId.toString(),
                            keys.getFirst()
                    )
            );

            String statusUrl = httpServletRequest.getContextPath()
                    + "/v1/payments/"
                    + acceptance.paymentId();

            var response = new PaymentSubmissionResponse(
                    acceptance.paymentId(),
                    "ACCEPTED",
                    statusUrl,
                    acceptance.acceptedAt()
            );

            return ResponseEntity.accepted()
                    .location(URI.create(statusUrl))
                    .cacheControl(CacheControl.noStore())
                    .header("Idempotency-Replayed",
                            Boolean.toString(acceptance.replayed())
                    )
                    .body(response);
        }

        @GetMapping("/{paymentId}")
        public ResponseEntity<PaymentStatus> status (
                @PathVariable UUID paymentId
        ) {

            var payment = getPaymentStatusUseCase.find(merchantId, paymentId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Payment not found"
                    ));

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(payment);
        }
}

