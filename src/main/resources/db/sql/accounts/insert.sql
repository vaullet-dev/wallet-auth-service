-- Create the anchor and return it as the database stored it.
--
-- No ON CONFLICT: retry-safety is resolved in AccountService against the natural keys
-- before this runs. Two concurrent creates can still race here, and the loser's unique
-- violation is answered as a 409 by AuthApiExceptionHandler. Letting the database
-- arbitrate that race is the design.
--
-- RETURNING rather than a follow-up read, so created_at and updated_at are the values
-- actually written rather than a second round trip that can disagree.
INSERT INTO accounts (account_id, keycloak_sub, external_ref, status)
VALUES (?, ?, ?, ?)
RETURNING account_id, keycloak_sub, external_ref, status, created_at, updated_at
