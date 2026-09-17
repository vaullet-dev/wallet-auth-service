SELECT account_id, keycloak_sub, external_ref, status, created_at, updated_at
  FROM identity_schema.accounts
 WHERE account_id = ?
