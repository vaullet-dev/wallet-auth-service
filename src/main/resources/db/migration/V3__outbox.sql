-- The outbox: events this service has committed to, written in the same transaction as the
-- change they describe and delivered afterwards by a relay (#39).
--
-- WHY AN OUTBOX. ADR-014 §7 has every new account announce itself as
-- identity.account-created.v1, which is how the ledger learns to give it its wallets.
-- Publishing after the commit loses the event whenever the process dies between the two
-- (ADR-014 §7's note, R8 in the TODO register). Written here, in the account's own
-- transaction, the event exists exactly when the account does; delivering it can then fail
-- and be retried for as long as it takes.
--
-- WHY NOT KAFKA. There is none in the cluster yet. Until there is, the relay delivers the
-- one event this table holds over HTTP, as the two calls the ledger's consumer would make:
-- POST /v1/wallets for REAL, then for BONUS (decided 2026-10-14, #39). Each row is still
-- the ADR-007 §3 envelope, column for field, as ADR-016 §2 has it, so that when Kafka
-- arrives the relay changes and this table does not.
--
-- NOTHING IS DELETED. A delivered row stays: auth_app may not DELETE here either, because
-- V2's default privileges give a new table SELECT, INSERT and UPDATE and nothing more.
-- Retention is a follow-up of its own.
--
-- TWO SECONDS TO TAKE A LOCK, then give up. The foreign key below takes SHARE ROW EXCLUSIVE
-- on accounts and holds it until this migration commits, which is after the backfill at the
-- bottom. The previous version is still serving all the while, and every INSERT or UPDATE it
-- makes on accounts waits for that commit; reads do not. With today's accounts that is
-- milliseconds. It grows with the number of accounts the backfill copies.
--
-- This setting bounds the other wait: the one to TAKE the lock. Without it a transaction that
-- happens to hold accounts would keep the migration queued behind it, and every account write
-- arriving after the migration queued behind that, for as long as the first takes. Two
-- seconds, under the running service's 3 s statement_timeout, so the wait alone cannot push a
-- queued write past its own timeout. Then the migration fails and rolls back whole, the Job
-- tries again (its backoffLimit), and if every try fails, so does the sync, and the running
-- version carries on as it was. LOCAL: this migration's transaction, not Flyway's connection.
SET LOCAL lock_timeout = '2s';

CREATE TABLE outbox (
    -- The envelope (ADR-007 §3). ------------------------------------------------------------

    -- What makes a consumer idempotent: Kafka is at-least-once, and so is this relay.
    event_id         UUID        NOT NULL,

    -- 'identity.account-created'. The topic is this plus the major of schema_version:
    -- identity.account-created.v1.
    event_type       TEXT        NOT NULL,
    schema_version   TEXT        NOT NULL,

    -- When the account was created: its created_at, to the microsecond.
    occurred_at      TIMESTAMPTZ NOT NULL,

    -- ADR-007's producer object, flattened. NULL version means no running build wrote the
    -- row: this migration's backfill, or the demo seed's SQL.
    producer_service TEXT        NOT NULL,
    producer_version TEXT        NULL,

    -- The trace id of the request that created the account, the same id its error bodies
    -- carry as trace_id. NULL where no request is at hand: the backfill below, the relay's
    -- sweep, the demo seed.
    correlation_id   TEXT        NULL,

    -- The event_id of the event that caused this one (ADR-007 §3). NULL for every
    -- identity.account-created: a request creates an account, never another event, and the
    -- request is what correlation_id names. The column is here because the envelope is, so
    -- the first event type that has a cause needs no migration to say so. TEXT, like
    -- correlation_id: the cause may be another producer's event, and its id theirs.
    causation_id     TEXT        NULL,

    account_id       UUID        NOT NULL,

    -- Empty for identity.account-created 1.0.0: account_id and occurred_at above are the
    -- whole fact. A field added later is a minor version (ADR-007 §4).
    payload          JSONB       NOT NULL,

    -- Delivery: the relay's bookkeeping, no part of any event. --------------------------------

    -- When the row was written. Equal to occurred_at when the account's own transaction wrote
    -- it, later when something queued it afterwards — the backfill below, or the relay's sweep
    -- — so the age of the oldest pending entry is measured from here.
    queued_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Attempts that finished, successful or not.
    attempts         INTEGER     NOT NULL DEFAULT 0,

    -- When the entry is next due. A relay that claims an entry moves this a lease ahead,
    -- which is what keeps a second replica off it while the first is delivering; a failed
    -- attempt moves it a backoff ahead.
    next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_attempt_at  TIMESTAMPTZ NULL,

    -- Why the last attempt failed, for whoever reads this table during an incident. Kept
    -- after delivery: it is why the entry needed more than one attempt.
    last_error       TEXT        NULL,

    -- NULL until the entry's work is done — for identity.account-created, until both
    -- wallets exist in the ledger or were closed there.
    delivered_at     TIMESTAMPTZ NULL,

    CONSTRAINT outbox_pk PRIMARY KEY (event_id),

    -- Within this schema, so the database itself says what the column is. Its price is paid
    -- here: creating it locks accounts against writes until this migration commits (the lock
    -- timeout at the top). After that, each entry written checks its account under a FOR KEY
    -- SHARE row lock, which nothing this service does to an account waits for: it updates
    -- status, never a key column, and never deletes one (V2 withholds DELETE).
    CONSTRAINT outbox_account_fk FOREIGN KEY (account_id) REFERENCES accounts (account_id),

    -- One event type today. Another is a migration, which is also when it gets a reviewer.
    CONSTRAINT outbox_event_type_ck CHECK (event_type IN ('identity.account-created')),
    CONSTRAINT outbox_attempts_ck CHECK (attempts >= 0),
    CONSTRAINT outbox_payload_ck CHECK (jsonb_typeof(payload) = 'object')
);

-- An account is created once, so it has one account-created event. Partial, because a
-- later event type may well happen to an account many times.
CREATE UNIQUE INDEX outbox_account_created_uk
    ON outbox (account_id)
    WHERE event_type = 'identity.account-created';

-- What the relay asks every few seconds: which entries are due. Partial, so it holds the
-- pending rows only and stays small however many delivered rows accumulate.
CREATE INDEX outbox_due_ix
    ON outbox (next_attempt_at)
    WHERE delivered_at IS NULL;

COMMENT ON TABLE  outbox                  IS 'Events committed with the change they describe, delivered afterwards by the relay (#39). Each row is an ADR-007 envelope plus its delivery state. identity.account-created is delivered over HTTP as the ledger''s POST /v1/wallets, REAL then BONUS, until there is Kafka.';
COMMENT ON COLUMN outbox.event_id         IS 'ADR-007 event_id. Consumers dedupe on it.';
COMMENT ON COLUMN outbox.occurred_at      IS 'When the account was created: its created_at.';
COMMENT ON COLUMN outbox.producer_version IS 'The build that wrote the row. NULL for rows V3 backfilled and for the demo seed.';
COMMENT ON COLUMN outbox.correlation_id   IS 'Trace id of the request that created the account, as in its error bodies. NULL where no request was at hand: backfilled, swept and seeded rows.';
COMMENT ON COLUMN outbox.causation_id     IS 'ADR-007 causation_id: the event_id of the event that caused this one. NULL for identity.account-created, which a request causes, not an event.';
COMMENT ON COLUMN outbox.queued_at        IS 'When the row was written. Later than occurred_at for rows queued after the account''s own transaction: by V3''s backfill or by the relay''s sweep.';
COMMENT ON COLUMN outbox.next_attempt_at  IS 'When the entry is next due. Pushed a lease ahead when a relay claims it, a backoff ahead when an attempt fails.';
COMMENT ON COLUMN outbox.last_error       IS 'Why the last attempt failed. Kept after delivery.';
COMMENT ON COLUMN outbox.delivered_at     IS 'NULL until the work is done: for identity.account-created, both wallets exist in the ledger or were closed there.';

-- The backfill: every account that already exists gets the event it would have had, so the
-- relay opens its wallets too. CLOSED accounts are left out: closure is terminal, so such an
-- account will never hold money and has nothing to open a wallet for.
--
-- In the migration's own transaction, like everything above, so the table never exists
-- half-filled. An account the previous version creates while this release rolls out, after
-- this has run, has no row until the relay's sweep (db/sql/outbox/queue-missing.sql) queues
-- it: at the new version's start, and every sweep-interval after.
INSERT INTO outbox (event_id, event_type, schema_version, occurred_at, producer_service, account_id, payload)
SELECT gen_random_uuid(), 'identity.account-created', '1.0.0', created_at, 'auth-service', account_id, '{}'
  FROM accounts
 WHERE status <> 'CLOSED';
