CREATE SCHEMA paymesh_acceptance;

CREATE TABLE paymesh_acceptance.payment_requests (
                                                     payment_id uuid PRIMARY KEY,
                                                     merchant_id uuid NOT NULL,

                                                     idempotency_key varchar(64) NOT NULL
                                                         CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,64}$'),

    request_fingerprint char(64) NOT NULL,

    amount_minor bigint NOT NULL
        CHECK (amount_minor BETWEEN 1 AND 99999999),

    currency varchar(3) NOT NULL
        CHECK (currency IN ('EUR', 'USD')),

    provider varchar(16) NOT NULL
        CHECK (provider IN ('STRIPE', 'ADYEN', 'PAYPAL')),

    payment_method_token varchar(64) NOT NULL,
    merchant_reference varchar(80) NOT NULL,

    status varchar(32) NOT NULL DEFAULT 'ACCEPTED'
        CHECK (status = 'ACCEPTED'),

    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),

    UNIQUE (merchant_id, idempotency_key)
);

CREATE TABLE paymesh_acceptance.outbox_messages (
                                                    message_id uuid PRIMARY KEY,

                                                    payment_id uuid NOT NULL
                                                        REFERENCES paymesh_acceptance.payment_requests(payment_id),

                                                    destination varchar(16) NOT NULL
                                                        CHECK (destination IN ('SQS', 'KAFKA')),

                                                    message_type varchar(80) NOT NULL,

                                                    payload jsonb NOT NULL
                                                        CHECK (jsonb_typeof(payload) = 'object'),

                                                    status varchar(16) NOT NULL DEFAULT 'PENDING'
                                                        CHECK (status IN ('PENDING', 'PUBLISHED', 'DEAD')),

                                                    publish_attempts integer NOT NULL DEFAULT 0
                                                        CHECK (publish_attempts >= 0),

                                                    available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
                                                    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
                                                    published_at timestamptz,

                                                    UNIQUE (payment_id, message_type)
);

CREATE INDEX pending_outbox_delivery
    ON paymesh_acceptance.outbox_messages(available_at, message_id)
    WHERE status = 'PENDING';