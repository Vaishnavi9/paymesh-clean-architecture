package com.practice.cashflow.adapter.out.persistence;

import com.practice.cashflow.application.AcceptPaymentCommand;
import com.practice.cashflow.application.IdempotencyConflictException;
import com.practice.cashflow.application.PaymentAcceptance;
import com.practice.cashflow.application.PaymentStatus;
import com.practice.cashflow.application.OutboxReady;
import com.practice.cashflow.application.port.out.PaymentAcceptanceStore;
import com.practice.cashflow.domain.PaymentCurrency;
import com.practice.cashflow.domain.PaymentProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class JdbcPaymentAcceptanceStore implements PaymentAcceptanceStore {

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public JdbcPaymentAcceptanceStore(
            NamedParameterJdbcTemplate jdbc,
            TransactionTemplate transactionTemplate,
            ApplicationEventPublisher eventPublisher
    ) {
        this.namedParameterJdbcTemplate = Objects.requireNonNull(jdbc);
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
    }

    @Override
    public PaymentAcceptance acceptAtomically(AcceptPaymentCommand command) {

        Objects.requireNonNull(command, "Payment command is required");

        UUID merchantId = parseMerchantId(command.merchantId());
        String fingerprint = fingerprint(command);

        return Objects.requireNonNull(transactionTemplate.execute(transactionStatus -> {
            UUID paymentId = UUID.randomUUID();

            var parameters = new MapSqlParameterSource()
                    .addValue("paymentId", paymentId)
                    .addValue("merchantId", merchantId)
                    .addValue("idempotencyKey", command.idempotencyKey())
                    .addValue("fingerprint", fingerprint)
                    .addValue("amountMinor", command.amountMinor())
                    .addValue("currency", command.currency().name())
                    .addValue("provider", command.provider().name())
                    .addValue("paymentMethodToken", command.paymentMethodToken())
                    .addValue("merchantReference", command.merchantReference());

            int insertedRows = namedParameterJdbcTemplate.update(
                    """
                            INSERT INTO paymesh_acceptance.payment_requests (
                            payment_id,
                            merchant_id,
                            idempotency_key,
                            request_fingerprint,
                            amount_minor,
                            currency,
                            provider,
                            payment_method_token,
                            merchant_reference
                            )
                            VALUES (
                            :paymentId,
                            :merchantId,
                            :idempotencyKey,
                            :fingerprint,
                            :amountMinor,
                            :currency,
                            :provider,
                            :paymentMethodToken,
                            :merchantReference
                            )
                            ON CONFLICT (merchant_id, idempotency_key) DO NOTHING
                            """, parameters);

            StoredAcceptance storedAcceptance = Objects.requireNonNull(
                    namedParameterJdbcTemplate.queryForObject(
                            """
                                    SELECT payment_id, request_fingerprint, created_at
                                    FROM paymesh_acceptance.payment_requests
                                    WHERE merchant_id = :merchantId AND 
                                    idempotency_key = :idempotencyKey
                                    """,
                            parameters,
                            (resultSet, rowNumber) -> new StoredAcceptance(
                                    resultSet.getObject("payment_id", UUID.class),
                                    resultSet.getString("request_fingerprint"),
                                    resultSet.getTimestamp("created_at").toInstant()
                            )
                    )
            );

            if(!storedAcceptance.fingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException();
            }

            boolean replayed = insertedRows == 0;

            if (!replayed) {
                insertOutboxMessage(parameters);
                eventPublisher.publishEvent(new OutboxReady(storedAcceptance.paymentId()));
            }
            return new PaymentAcceptance(
                    storedAcceptance.paymentId(),
                    storedAcceptance.acceptedAt(),
                    replayed
            );
        }));
    }

    @Override
    public Optional<PaymentStatus> find(UUID merchantId, UUID paymentId) {
        var parameters = new MapSqlParameterSource()
                .addValue("merchantId", merchantId)
                .addValue("paymentId", paymentId);


        return namedParameterJdbcTemplate.query(
                """
                        SELECT payment_id,
                        amount_minor,
                        currency,
                        provider,
                        merchant_reference,
                        status,
                        created_at
                        FROM paymesh_acceptance.payment_requests
                        WHERE merchant_id = :merchantId
                        AND payment_id = :paymentId
                        """,
                parameters,
                (resultSet, rowNumber) -> new PaymentStatus(
                        resultSet.getObject("payment_id", UUID.class),
                        resultSet.getLong("amount_minor"),
                        PaymentCurrency.valueOf(
                                resultSet.getString("currency")
                        ),
                        PaymentProvider.valueOf(
                                resultSet.getString("provider")
                        ),
                        resultSet.getString("merchant_reference"),
                        resultSet.getString("status"),
                        resultSet.getTimestamp("created_at").toInstant()
                )
        ).stream().findFirst();
    }

    private void insertOutboxMessage(MapSqlParameterSource parameters) {
        parameters.addValue("messageId", UUID.randomUUID());

        namedParameterJdbcTemplate.update(
                """
                                     INSERT INTO paymesh_acceptance.outbox_messages (
                                      message_id,
                                      payment_id,
                                      destination,
                                      message_type,
                                      payload
                                     )
                                     VALUES (
                                     :messageId,
                                     :paymentId,
                                     'SQS',
                                     'payment.execute.v1',
                                     jsonb_build_object (
                                         'messageId', CAST(:messageId AS text),
                                         'type', 'payment.execute.v1',
                                         'schemaVersion', 1,
                                         'paymentId', CAST(:paymentId AS text),
                                         'merchantId', CAST(:merchantId AS text)
                                     )
                        )
                        """, parameters);
    }

    private String fingerprint(AcceptPaymentCommand command) {
        String canonical = String.join(
                "\n",
                "v1",
                Long.toString(command.amountMinor()),
                command.currency().name(),
                command.provider().name(),
                command.paymentMethodToken(),
                command.merchantReference()
        );

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException noSuchAlgorithmException) {
            throw new IllegalStateException("The JVM must provide SHA-256", noSuchAlgorithmException);
        }
    }

    private UUID parseMerchantId(String merchantId) {
        if (merchantId == null) {
            throw new IllegalArgumentException("merchantId is required");
        }

        UUID merchantIdUUID = UUID.fromString(merchantId);

        if(!merchantIdUUID.toString().equalsIgnoreCase(merchantId)) {
            throw new IllegalArgumentException("merchantId must use the standard UUID format");
        }

        return merchantIdUUID;
    }

    private record StoredAcceptance(
            UUID paymentId,
            String fingerprint,
            Instant acceptedAt
    ) {
    }
}
