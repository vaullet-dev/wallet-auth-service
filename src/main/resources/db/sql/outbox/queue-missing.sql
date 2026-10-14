-- The sweep: queue the account-created entry of every account that ought to have one and has
-- none — not CLOSED, and no entry of this type. The relay runs it at start and then every
-- sweep-interval.
--
-- What it finds: an account the previous version created while this one rolled out, after V3's
-- backfill ran and before the last old pod stopped; and any account a later path inserted
-- without queueing its event in the same transaction. Normally nothing, at the cost of one
-- anti-join that outbox_account_created_uk answers.
--
-- It cannot catch a creation half-way: an account and the entry its own transaction writes
-- commit together, so no snapshot holds one without the other.
--
-- ON CONFLICT DO NOTHING, with outbox_account_created_uk as the arbiter — the only unique index
-- a fresh event_id can collide on. Two replicas sweeping at once, or a sweep racing a creation
-- that queues its own entry, then write each account's entry once between them, and neither
-- fails. No conflict target is named, so the event type stays a parameter, chosen in one place
-- in Java, rather than a second literal here.
--
-- occurred_at is the account's created_at, as for every entry. correlation_id is NULL: no
-- request is behind this write.
INSERT INTO identity_schema.outbox
       (event_id, event_type, schema_version, occurred_at, producer_service, producer_version,
        account_id, payload)
SELECT gen_random_uuid(), ?, ?, a.created_at, ?, ?, a.account_id, ?::jsonb
  FROM identity_schema.accounts a
 WHERE a.status <> 'CLOSED'
   AND NOT EXISTS (SELECT 1
                     FROM identity_schema.outbox o
                    WHERE o.account_id = a.account_id
                      AND o.event_type = ?)
ON CONFLICT DO NOTHING
