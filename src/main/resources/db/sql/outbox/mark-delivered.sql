-- The entry's work is done. For identity.account-created: both wallets exist in the ledger,
-- or were closed there.
--
-- ONLY UNDER THE LEASE THAT DELIVERED IT: next_attempt_at must still be the lease_end this
-- relay's claim returned. It is not when the lease ran out under a stalled relay and another
-- claimed the entry since — the outcome is then that relay's to record — or when someone made
-- the entry due by hand. Zero rows, and the relay records and counts nothing.
--
-- Guarded on delivered_at as well, so the same lease cannot record a delivery twice.
-- last_error stays: it is why the entry needed more than one attempt.
UPDATE identity_schema.outbox
   SET delivered_at = now(),
       attempts = attempts + 1,
       last_attempt_at = now()
 WHERE event_id = ?
   AND next_attempt_at = ?
   AND delivered_at IS NULL
