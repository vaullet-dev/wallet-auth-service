-- Vaullet identity — the account anchor.
--
-- ADR-006 introduced this table; ADR-014 amended it. Four columns of meaning plus two
-- timestamps, and every one of them is here for a reason that is written down:
--
--   account_id    ADR-004 — keys seven years of immutable journal, so it must outlive Keycloak
--   keycloak_sub  ADR-014 — nullable, because an account can exist before an identity does
--   external_ref  ADR-012 — the operator's own user id, promised unique and immutable
--   status        ADR-006 — the fraud lock, authoritative here and nowhere else
--
-- WHAT IS DELIBERATELY ABSENT: email, name, phone, date of birth, documents, credentials,
-- MFA secrets, session state, role assignments. Those are Keycloak's, in its own schema in
-- this same database, and this service reads them over the Admin REST API rather than
-- keeping a copy (ADR-014 §4). A column added here is a copy of somebody else's record,
-- and copies drift.
--
-- NO FOREIGN KEY CROSSES INTO keycloak_schema. keycloak_sub is a plain UUID on purpose: a
-- Keycloak major upgrade migrates its own tables, and a reference from here would make that
-- upgrade our problem (ADR-006, "Database placement").

CREATE TABLE accounts (
    account_id   UUID        NOT NULL,

    -- The Keycloak user id. NULL means an *unlinked anchor*: the operator provisioned an
    -- account for someone who has not authenticated yet. A state the design has, not a hole
    -- in it (ADR-014 §5, closing open item B2) — but every reader must handle it, and
    -- nothing downstream may assume a linked identity exists.
    --
    -- Updatable, unlike external_ref below. ADR-006 allows a deliberate re-point of the
    -- anchor when an operator migrates identity provider; that is the entire reason the
    -- ledger keys on account_id instead of on this.
    keycloak_sub UUID        NULL,

    -- The operator's own user id, so an integrator need not store a mapping. ADR-012 tells
    -- operators it is unique and immutable; both are constraints below rather than hopes
    -- (ADR-014 §5, closing open item B3).
    external_ref TEXT        NULL,

    -- ACTIVE | SUSPENDED | CLOSED. ADR-006 wrote LOCKED; ADR-014 renamed it, because ADR-012
    -- had already published SUSPENDED to operators AND because Keycloak uses "locked" for a
    -- user its own brute-force detection locked out — a different thing entirely, in the
    -- same deployment.
    status       TEXT        NOT NULL,

    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT accounts_pk PRIMARY KEY (account_id),

    -- PostgreSQL treats NULLs as DISTINCT in a unique constraint, which is exactly what
    -- these two need: any number of accounts may be unlinked or unnamed, but no two may
    -- claim the same identity or the same operator reference. NULLS NOT DISTINCT would
    -- break the unlinked anchor on the second row.
    CONSTRAINT accounts_keycloak_sub_uk UNIQUE (keycloak_sub),
    CONSTRAINT accounts_external_ref_uk UNIQUE (external_ref),

    CONSTRAINT accounts_status_ck CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),

    -- Every account must be addressable by something other than the id in its own response.
    -- This is what makes POST /v1/accounts retry-safe without an Idempotency-Key: a retry is
    -- resolved against whichever natural key the caller supplied. Without this constraint a
    -- create with neither key would be the one un-retryable call in the API.
    CONSTRAINT accounts_natural_key_ck
        CHECK (external_ref IS NOT NULL OR keycloak_sub IS NOT NULL),

    -- An empty string is not a missing value, and allowing both gives two spellings of
    -- "no operator reference" — one of which silently defeats the constraint above.
    CONSTRAINT accounts_external_ref_not_blank_ck
        CHECK (external_ref IS NULL OR length(btrim(external_ref)) > 0)
);

-- Two invariants the database owns, because "the service will not do that" is not an
-- invariant — it is a habit, and habits are not enforceable against the next code path.
--
-- A trigger rather than a RULE: a rule with DO INSTEAD NOTHING makes a forbidden write
-- succeed SILENTLY, which is a worse failure than the one it prevents. The ledger has two
-- of those. These raise check_violation (SQLSTATE 23514), so Spring surfaces them as
-- DataIntegrityViolationException and the caller gets a refusal rather than a lie.
CREATE FUNCTION accounts_enforce_invariants() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    -- ADR-012 promises operators that external_ref never changes under them. Once set it is
    -- frozen; setting it on a row that had none is still allowed.
    IF OLD.external_ref IS NOT NULL AND NEW.external_ref IS DISTINCT FROM OLD.external_ref THEN
        RAISE EXCEPTION 'external_ref is immutable once set (account %)', OLD.account_id
            USING ERRCODE = 'check_violation';
    END IF;

    -- A closed account does not reopen. Closure is an erasure event with an audit trail
    -- behind it (ADR-014 §7); reviving the row would leave that trail describing something
    -- that is no longer true. A returning user gets a new account_id.
    IF OLD.status = 'CLOSED' AND NEW.status <> 'CLOSED' THEN
        RAISE EXCEPTION 'account % is CLOSED and cannot be reopened', OLD.account_id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER accounts_invariants
    BEFORE UPDATE ON accounts
    FOR EACH ROW EXECUTE FUNCTION accounts_enforce_invariants();

COMMENT ON TABLE  accounts               IS 'Vaullet''s own record of a user: the identifier money is keyed to, and its status. Holds no personal data in either identity mode (ADR-006, ADR-014).';
COMMENT ON COLUMN accounts.account_id    IS 'Permanent. Keys seven years of immutable ledger journal, so it must outlive Keycloak, a realm re-import, or a change of identity provider.';
COMMENT ON COLUMN accounts.keycloak_sub  IS 'Keycloak user id. NULL = unlinked anchor: provisioned before the user ever authenticated. Set by just-in-time provisioning at first login.';
COMMENT ON COLUMN accounts.external_ref  IS 'The operator''s own user id. Unique per deployment and immutable once set (ADR-012); immutability is enforced by the accounts_invariants trigger.';
COMMENT ON COLUMN accounts.status        IS 'ACTIVE | SUSPENDED | CLOSED. Authoritative here, not in Keycloak: a fraud lock the operator''s directory could overrule is not a fraud lock. CLOSED is terminal.';
