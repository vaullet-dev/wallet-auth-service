SELECT account_id, keycloak_sub, external_ref, status, created_at, updated_at
  FROM accounts
 WHERE account_id = ?
