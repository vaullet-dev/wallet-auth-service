-- Claim up to ? due entries for one delivery round, and lease them for ? seconds.
--
-- THE LEASE IS next_attempt_at. Pushing it a lease ahead is what takes the claimed rows out
-- of every other relay's view while this one delivers them, and what returns them if this
-- relay dies: they simply come due again when the lease ends. No claimed_by column, no lock
-- held across the calls to the ledger.
--
-- AND ITS VALUE IS THE TOKEN. It comes back as lease_end, and each outcome is recorded only
-- while next_attempt_at still holds it (mark-delivered.sql, mark-failed.sql). A claim made
-- after the lease ended sets a later one, so an answer that comes back too late finds the
-- entry under somebody else's lease and changes nothing.
--
-- ONE STATEMENT, so its transaction lasts exactly as long as it does. The row locks it takes
-- are released at its commit, milliseconds later; the deliveries happen after that, outside
-- any transaction, and each outcome is a statement of its own.
--
-- FOR UPDATE SKIP LOCKED: two relays claiming at the same moment skip each other's rows
-- instead of queueing behind them. A row another relay leased a moment ago no longer
-- qualifies either: under READ COMMITTED, PostgreSQL re-checks next_attempt_at <= now()
-- against the row as that relay committed it.
--
-- MATERIALIZED, so the locked set is computed once and the UPDATE touches exactly those rows.
-- Oldest due first, so one entry that keeps failing cannot starve the ones behind it: its
-- backoff moves it to the back.
WITH due AS MATERIALIZED (
    SELECT event_id
      FROM identity_schema.outbox
     WHERE delivered_at IS NULL
       AND next_attempt_at <= now()
     ORDER BY next_attempt_at
     LIMIT ?
       FOR UPDATE SKIP LOCKED
)
UPDATE identity_schema.outbox AS o
   SET next_attempt_at = now() + make_interval(secs => ?)
  FROM due
 WHERE o.event_id = due.event_id
RETURNING o.event_id, o.event_type, o.account_id, o.correlation_id, o.attempts, o.queued_at,
          o.next_attempt_at AS lease_end
