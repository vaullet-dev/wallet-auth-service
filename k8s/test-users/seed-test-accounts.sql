-- DEMO ONLY. The account anchor for Keycloak's test-end-user.
--
-- Only END_USER holds a wallet, so only that test user gets a row. The staff roles
-- (SUPPORT_AGENT, FRAUD_REVIEWER, FINANCE, CONFIG_ADMIN, SUPER_ADMIN) administer accounts
-- and own none: an account is "whose wallet is it" (ADR-014 §1), and a staff member with a
-- wallet row would be a second, fictional customer.
--
-- keycloak_sub stays NULL. Keycloak mints a user's id when the user is created, so it cannot be
-- known here in advance. This is ADR-014's unlinked anchor, the same shape an operator gets from
-- POST /v1/accounts, and just-in-time provisioning links it at first login once that exists
-- (build order step 2). external_ref is the Keycloak username, so the two are easy to correlate.
--
-- account_id is fixed, so tests and the Bruno collection can address the account directly.
--
-- Runs on every sync. ON CONFLICT DO NOTHING makes that safe, and it deliberately never resets
-- the row: a status a test changed stays changed, and CLOSED is terminal anyway (the
-- accounts_invariants trigger). Runs as auth_app, which may INSERT and nothing more.
INSERT INTO identity_schema.accounts (account_id, external_ref, status)
VALUES ('7e57a11c-0000-4000-8000-000000000001', 'test-end-user', 'ACTIVE')
ON CONFLICT DO NOTHING;
