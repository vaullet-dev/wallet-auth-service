-- Unique, and null for an unlinked anchor — so this never matches an account that has
-- not authenticated yet, which is correct: it has no identity to be found by.
SELECT account_id, keycloak_sub, external_ref, status, created_at, updated_at
  FROM accounts
 WHERE keycloak_sub = ?
