-- The operator's own user id. Unique per deployment, so at most one row.
SELECT account_id, keycloak_sub, external_ref, status, created_at, updated_at
  FROM identity_schema.accounts
 WHERE external_ref = ?
