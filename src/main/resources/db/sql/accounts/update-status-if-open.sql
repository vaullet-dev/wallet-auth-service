-- Move the account to a new status, unless it is closed.
--
-- THE GUARD IS IN THE WHERE CLAUSE, not in a preceding read. Reading the state, deciding,
-- then writing leaves a window in which another request changes the row in between — the
-- bug in the ledger's release(). One statement means the database evaluates the condition
-- against the row it is about to modify.
--
-- RETURNING makes the outcome unambiguous: a row means the transition happened, empty
-- means the guard refused. Distinguishing "no such account" from "closed" afterwards is
-- race-free only because CLOSED is terminal — both outcomes are permanent, so the answer
-- cannot change under the reader.
--
-- Setting the same status again matches and is a no-op plus a touched updated_at, so a
-- retried request succeeds rather than erroring.
UPDATE identity_schema.accounts
   SET status = ?,
       updated_at = now()
 WHERE account_id = ?
   AND status <> 'CLOSED'
RETURNING account_id, keycloak_sub, external_ref, status, created_at, updated_at
