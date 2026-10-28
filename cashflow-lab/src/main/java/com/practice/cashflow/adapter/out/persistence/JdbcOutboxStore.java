package com.practice.cashflow.adapter.out.persistence;

import com.practice.cashflow.application.port.out.OutboxStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;


public class JdbcOutboxStore implements OutboxStore {
    private static final int MAX_ATTEMPTS = 8;

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public JdbcOutboxStore (
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            TransactionTemplate transactionTemplate
    ) {
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }


    @Override
    public Optional<Claim> claimNext() {
        return Objects.requireNonNull(
                transactionTemplate.execute(transaction -> {
                    // Also handles a crash during the final allowed attempt.
                    namedParameterJdbcTemplate.update("""
                            UPDATE paymesh_acceptance.outbox_messages
                            SET status = 'DEAD',
                                claim_token = NULL,
                                lease_until = NULL,
                                last_error_code = 'ATTEMPTS_EXHAUSTED'
                            WHERE destination = 'SQS'
                              AND status = 'PENDING'
                              AND publish_attempts >= :maxAttempts
                              AND available_at <= clock_timestamp()
                              AND (
                                  lease_until IS NULL
                                  OR lease_until <= clock_timestamp()
                              )
                            """, Map.of("maxAttempts", MAX_ATTEMPTS));

                    UUID token = UUID.randomUUID();

                    var parameters = new MapSqlParameterSource()
                            .addValue("claimToken", token)
                            .addValue("maxAttempts", MAX_ATTEMPTS);

                    return namedParameterJdbcTemplate.query("""
                            WITH candidate AS (
                                SELECT message_id
                                FROM paymesh_acceptance.outbox_messages
                                WHERE destination = 'SQS'
                                  AND status = 'PENDING'
                                  AND publish_attempts < :maxAttempts
                                  AND available_at <= clock_timestamp()
                                  AND (
                                      lease_until IS NULL
                                      OR lease_until <= clock_timestamp()
                                  )
                                ORDER BY available_at, message_id
                                LIMIT 1
                                FOR UPDATE SKIP LOCKED
                            )
                            UPDATE paymesh_acceptance.outbox_messages AS o
                            SET claim_token = :claimToken,
                                lease_until =
                                    clock_timestamp() + interval '60 seconds',
                                publish_attempts = o.publish_attempts + 1
                            FROM candidate AS c
                            WHERE o.message_id = c.message_id
                            RETURNING o.message_id,
                                      o.payload::text AS payload,
                                      o.publish_attempts
                            """,
                            parameters,
                            (resultSet, rowNumber) -> new Claim(
                                    resultSet.getObject(
                                            "message_id",
                                            UUID.class
                                    ),
                                    token,
                                    resultSet.getString("payload"),
                                    resultSet.getInt("publish_attempts")
                            )
                    ).stream().findFirst();
                })
        );
    }

    @Override
    public boolean markPublished(Claim claim) {
        int changed = namedParameterJdbcTemplate.update("""
                UPDATE paymesh_acceptance.outbox_messages
                SET status = 'PUBLISHED',
                    published_at = clock_timestamp(),
                    claim_token = NULL,
                    lease_until = NULL,
                    last_error_code = NULL
                WHERE message_id = :messageId
                  AND claim_token = :claimToken
                  AND status = 'PENDING'
                """, identity(claim));

        return changed == 1;
    }

    @Override
    public boolean retryOrPark(
            Claim claim,
            String errorCode,
            int delaySeconds
    ) {
        if (delaySeconds < 1 || delaySeconds > 300) {
            throw new IllegalArgumentException(
                    "Retry delay must be between 1 and 300 seconds"
            );
        }

        var parameters = identity(claim)
                .addValue("errorCode", errorCode)
                .addValue("delaySeconds", delaySeconds)
                .addValue("maxAttempts", MAX_ATTEMPTS);

        int changed = namedParameterJdbcTemplate.update("""
                UPDATE paymesh_acceptance.outbox_messages
                SET status = CASE
                        WHEN publish_attempts >= :maxAttempts
                        THEN 'DEAD'
                        ELSE 'PENDING'
                    END,
                    available_at = clock_timestamp()
                        + (:delaySeconds * interval '1 second'),
                    claim_token = NULL,
                    lease_until = NULL,
                    last_error_code = :errorCode
                WHERE message_id = :messageId
                  AND claim_token = :claimToken
                  AND status = 'PENDING'
                """, parameters);

        return changed == 1;
    }

    private static MapSqlParameterSource identity(Claim claim) {
        return new MapSqlParameterSource()
                .addValue("messageId", claim.messageId())
                .addValue("claimToken", claim.claimToken());
    }

}
