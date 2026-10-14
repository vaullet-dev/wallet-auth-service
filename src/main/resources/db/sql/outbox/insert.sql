-- Queue an event. Always inside the transaction that made the change it describes: Outbox
-- refuses to run outside one (Propagation.MANDATORY), so the account and its event commit
-- or roll back together and neither can exist without the other.
--
-- queued_at, attempts and next_attempt_at take their defaults: a new entry is due at once.
-- The payload arrives as text and is cast here, so the Java side needs no JSON type.
INSERT INTO identity_schema.outbox
       (event_id, event_type, schema_version, occurred_at, producer_service, producer_version,
        correlation_id, account_id, payload)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
