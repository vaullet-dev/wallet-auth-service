-- Tables are schema-qualified throughout. Relying on search_path would make correctness depend
-- on how the connection happened to be built: Boot's @ServiceConnection derives the test URL
-- from the container and drops the ?currentSchema the application URL carries, so unqualified
-- SQL resolves to public and fails. Qualifying also removes any chance of landing in
-- keycloak_schema, which shares this database and is emphatically not ours (ADR-003).
-- Create the anchor and return it as the database stored it.
--
-- No ON CONFLICT: retry-safety is resolved in AccountService against the natural keys
-- before this runs. Two concurrent creates can still race here, and the loser's unique
-- violation is answered as a 409 by AuthApiExceptionHandler. Letting the database
-- arbitrate that race is the design.
--
-- RETURNING rather than a follow-up read, so created_at and updated_at are the values
-- actually written rather than a second round trip that can disagree.
INSERT INTO identity_schema.accounts (account_id, keycloak_sub, external_ref, status)
VALUES (?, ?, ?, ?)
RETURNING account_id, keycloak_sub, external_ref, status, created_at, updated_at
