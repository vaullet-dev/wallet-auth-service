-- The operator's own user id. Unique per deployment, so at most one row.
SELECT account_id, keycloak_sub, external_ref, status, created_at, updated_at
  FROM accounts
 WHERE external_ref = ?
