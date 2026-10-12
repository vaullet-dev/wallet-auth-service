-- A page of users found in Keycloak, joined back to their anchors in one round trip rather
-- than one query per user. = ANY over an array rather than IN (?, ?, ...): one statement text
-- whatever the page size, so the plan is prepared once, and a page of a hundred binds one
-- parameter rather than a hundred. Users with no anchor simply produce no row.
SELECT account_id, keycloak_sub, external_ref, status, created_at, updated_at
  FROM identity_schema.accounts
 WHERE keycloak_sub = ANY (?)
