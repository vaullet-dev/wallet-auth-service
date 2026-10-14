-- An attempt that did not finish the entry: count it, say why, and make the entry due again
-- after the backoff, in seconds, that OutboxRelay chose from the attempt count.
--
-- now() rather than a time from the JVM, as in claim-due.sql: every replica then measures
-- leases and backoffs on the one clock they share.
--
-- Only under the lease that made the attempt, as in mark-delivered.sql. A failure that comes
-- back after another relay claimed the entry would otherwise rewrite that relay's lease — due
-- again a backoff from now, which can be long before it is done, so a third relay could claim
-- the entry mid-delivery — and count one attempt twice. Guarded on delivered_at as well.
UPDATE identity_schema.outbox
   SET attempts = attempts + 1,
       last_attempt_at = now(),
       next_attempt_at = now() + make_interval(secs => ?),
       last_error = ?
 WHERE event_id = ?
   AND next_attempt_at = ?
   AND delivered_at IS NULL
