ALTER TABLE paymesh_acceptance.outbox_messages
    ADD COLUMN claim_token uuid,
    ADD COLUMN lease_until timestamptz,
    ADD COLUMN last_error_code varchar(64),
    ADD CONSTRAINT outbox_claim_lease_pair CHECK (
        (claim_token IS NULL AND lease_until IS NULL)
        OR (claim_token IS NOT NULL AND lease_until IS NOT NULL)
    );
