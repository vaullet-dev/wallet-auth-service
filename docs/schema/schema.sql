--
-- PostgreSQL database dump
--

\restrict vaulletschemadocs

-- Dumped from database version 18.6
-- Dumped by pg_dump version 18.6

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: identity_schema; Type: SCHEMA; Schema: -; Owner: auth_migrator
--

CREATE SCHEMA identity_schema;


ALTER SCHEMA identity_schema OWNER TO auth_migrator;

--
-- Name: accounts_enforce_invariants(); Type: FUNCTION; Schema: identity_schema; Owner: auth_migrator
--

CREATE FUNCTION identity_schema.accounts_enforce_invariants() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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


ALTER FUNCTION identity_schema.accounts_enforce_invariants() OWNER TO auth_migrator;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: accounts; Type: TABLE; Schema: identity_schema; Owner: auth_migrator
--

CREATE TABLE identity_schema.accounts (
    account_id uuid NOT NULL,
    keycloak_sub uuid,
    external_ref text,
    status text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT accounts_external_ref_not_blank_ck CHECK (((external_ref IS NULL) OR (length(btrim(external_ref)) > 0))),
    CONSTRAINT accounts_natural_key_ck CHECK (((external_ref IS NOT NULL) OR (keycloak_sub IS NOT NULL))),
    CONSTRAINT accounts_status_ck CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'SUSPENDED'::text, 'CLOSED'::text])))
);


ALTER TABLE identity_schema.accounts OWNER TO auth_migrator;

--
-- Name: TABLE accounts; Type: COMMENT; Schema: identity_schema; Owner: auth_migrator
--

COMMENT ON TABLE identity_schema.accounts IS 'Vaullet''s own record of a user: the identifier money is keyed to, and its status. Holds no personal data in either identity mode (ADR-006, ADR-014).';


--
-- Name: COLUMN accounts.account_id; Type: COMMENT; Schema: identity_schema; Owner: auth_migrator
--

COMMENT ON COLUMN identity_schema.accounts.account_id IS 'Permanent. Keys seven years of immutable ledger journal, so it must outlive Keycloak, a realm re-import, or a change of identity provider.';


--
-- Name: COLUMN accounts.keycloak_sub; Type: COMMENT; Schema: identity_schema; Owner: auth_migrator
--

COMMENT ON COLUMN identity_schema.accounts.keycloak_sub IS 'Keycloak user id. NULL = unlinked anchor: provisioned before the user ever authenticated. Set by just-in-time provisioning at first login.';


--
-- Name: COLUMN accounts.external_ref; Type: COMMENT; Schema: identity_schema; Owner: auth_migrator
--

COMMENT ON COLUMN identity_schema.accounts.external_ref IS 'The operator''s own user id. Unique per deployment and immutable once set (ADR-012); immutability is enforced by the accounts_invariants trigger.';


--
-- Name: COLUMN accounts.status; Type: COMMENT; Schema: identity_schema; Owner: auth_migrator
--

COMMENT ON COLUMN identity_schema.accounts.status IS 'ACTIVE | SUSPENDED | CLOSED. Authoritative here, not in Keycloak: a fraud lock the operator''s directory could overrule is not a fraud lock. CLOSED is terminal.';


--
-- Name: flyway_schema_history; Type: TABLE; Schema: identity_schema; Owner: auth_migrator
--

CREATE TABLE identity_schema.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);


ALTER TABLE identity_schema.flyway_schema_history OWNER TO auth_migrator;

--
-- Name: outbox; Type: TABLE; Schema: identity_schema; Owner: auth_migrator
--

CREATE TABLE identity_schema.outbox (
    event_id uuid NOT NULL,
    event_type text NOT NULL,
    schema_version text NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    producer_service text NOT NULL,
    producer_version text,
    correlation_id text,
    causation_id text,
    account_id uuid NOT NULL,
    payload jsonb NOT NULL,
    queued_at timestamp with time zone DEFAULT now() NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL,
    last_attempt_at timestamp with time zone,
    last_error text,
    delivered_at timestamp with time zone,
    CONSTRAINT outbox_attempts_ck CHECK ((attempts >= 0)),
    CONSTRAINT outbox_event_type_ck CHECK ((event_type = 'identity.account-created'::text)),
    CONSTRAINT outbox_payload_ck CHECK ((jsonb_typeof(payload) = 'object'::text))
);


ALTER TABLE identity_schema.outbox OWNER TO auth_migrator;

--
-- Name: TABLE outbox; Type: COMMENT; Schema: identity_schema; Owner: auth_migrator
--

COMMENT ON TABLE identity_schema.outbox IS 'Events committed with the change they describe, delivered afterwards by the relay (#39). Each row is an ADR-007 envelope plus its delivery state. identity.account-created is delivered over HTTP as the ledger''s POST /v1/wallets, REAL then BONUS, until there is Kafka.';


--
-- Name: COLUMN outbox.event_id; Type: COMMENT; Schema: identity_schema; Owner: auth_migrator
--

COMMENT ON COLUMN identity_schema.outbox.event_id IS 'ADR-007 event_id. Consumers dedupe on it.';


--
-- Name: COLUMN outbox.occurred_at; Type: COMMENT; Schema: identity_schema; Owner: auth_migrator
--

COMMENT ON COLUMN identity_schema.outbox.occurred_at IS 'When the account was created: its created_at.';


--
-- Name: COLUMN outbox.producer_version; Type: COMMENT; Schema: identity_schema; Owner: auth_migrator
--

COMMENT ON COLUMN identity_schema.outbox.producer_version IS 'The build that wrote the row. NULL for rows V3 backfilled and for the demo seed.';


--
-- Name: COLUMN outbox.correlation_id; Type: COMMENT; Schema: identity_schema; Owner: auth_migrator
--

COMMENT ON COLUMN identity_schema.outbox.correlation_id IS 'Trace id of the request that created the account, as in its error bodies. NULL where no request was at hand: backfilled, swept and seeded rows.';


--
-- Name: COLUMN outbox.causation_id; Type: COMMENT; Schema: identity_schema; Owner: auth_migrator
--

COMMENT ON COLUMN identity_schema.outbox.causation_id IS 'ADR-007 causation_id: the event_id of the event that caused this one. NULL for identity.account-created, which a request causes, not an event.';


--
-- Name: COLUMN outbox.queued_at; Type: COMMENT; Schema: identity_schema; Owner: auth_migrator
--

COMMENT ON COLUMN identity_schema.outbox.queued_at IS 'When the row was written. Later than occurred_at for rows queued after the account''s own transaction: by V3''s backfill or by the relay''s sweep.';


--
-- Name: COLUMN outbox.next_attempt_at; Type: COMMENT; Schema: identity_schema; Owner: auth_migrator
--

COMMENT ON COLUMN identity_schema.outbox.next_attempt_at IS 'When the entry is next due. Pushed a lease ahead when a relay claims it, a backoff ahead when an attempt fails.';


--
-- Name: COLUMN outbox.last_error; Type: COMMENT; Schema: identity_schema; Owner: auth_migrator
--

COMMENT ON COLUMN identity_schema.outbox.last_error IS 'Why the last attempt failed. Kept after delivery.';


--
-- Name: COLUMN outbox.delivered_at; Type: COMMENT; Schema: identity_schema; Owner: auth_migrator
--

COMMENT ON COLUMN identity_schema.outbox.delivered_at IS 'NULL until the work is done: for identity.account-created, both wallets exist in the ledger or were closed there.';


--
-- Name: accounts accounts_external_ref_uk; Type: CONSTRAINT; Schema: identity_schema; Owner: auth_migrator
--

ALTER TABLE ONLY identity_schema.accounts
    ADD CONSTRAINT accounts_external_ref_uk UNIQUE (external_ref);


--
-- Name: accounts accounts_keycloak_sub_uk; Type: CONSTRAINT; Schema: identity_schema; Owner: auth_migrator
--

ALTER TABLE ONLY identity_schema.accounts
    ADD CONSTRAINT accounts_keycloak_sub_uk UNIQUE (keycloak_sub);


--
-- Name: accounts accounts_pk; Type: CONSTRAINT; Schema: identity_schema; Owner: auth_migrator
--

ALTER TABLE ONLY identity_schema.accounts
    ADD CONSTRAINT accounts_pk PRIMARY KEY (account_id);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: identity_schema; Owner: auth_migrator
--

ALTER TABLE ONLY identity_schema.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: outbox outbox_pk; Type: CONSTRAINT; Schema: identity_schema; Owner: auth_migrator
--

ALTER TABLE ONLY identity_schema.outbox
    ADD CONSTRAINT outbox_pk PRIMARY KEY (event_id);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: identity_schema; Owner: auth_migrator
--

CREATE INDEX flyway_schema_history_s_idx ON identity_schema.flyway_schema_history USING btree (success);


--
-- Name: outbox_account_created_uk; Type: INDEX; Schema: identity_schema; Owner: auth_migrator
--

CREATE UNIQUE INDEX outbox_account_created_uk ON identity_schema.outbox USING btree (account_id) WHERE (event_type = 'identity.account-created'::text);


--
-- Name: outbox_due_ix; Type: INDEX; Schema: identity_schema; Owner: auth_migrator
--

CREATE INDEX outbox_due_ix ON identity_schema.outbox USING btree (next_attempt_at) WHERE (delivered_at IS NULL);


--
-- Name: accounts accounts_invariants; Type: TRIGGER; Schema: identity_schema; Owner: auth_migrator
--

CREATE TRIGGER accounts_invariants BEFORE UPDATE ON identity_schema.accounts FOR EACH ROW EXECUTE FUNCTION identity_schema.accounts_enforce_invariants();


--
-- Name: outbox outbox_account_fk; Type: FK CONSTRAINT; Schema: identity_schema; Owner: auth_migrator
--

ALTER TABLE ONLY identity_schema.outbox
    ADD CONSTRAINT outbox_account_fk FOREIGN KEY (account_id) REFERENCES identity_schema.accounts(account_id);


--
-- Name: SCHEMA identity_schema; Type: ACL; Schema: -; Owner: auth_migrator
--

GRANT USAGE ON SCHEMA identity_schema TO auth_app;


--
-- Name: TABLE accounts; Type: ACL; Schema: identity_schema; Owner: auth_migrator
--

GRANT SELECT,INSERT,UPDATE ON TABLE identity_schema.accounts TO auth_app;


--
-- Name: TABLE outbox; Type: ACL; Schema: identity_schema; Owner: auth_migrator
--

GRANT SELECT,INSERT,UPDATE ON TABLE identity_schema.outbox TO auth_app;


--
-- Name: DEFAULT PRIVILEGES FOR SEQUENCES; Type: DEFAULT ACL; Schema: identity_schema; Owner: auth_migrator
--

ALTER DEFAULT PRIVILEGES FOR ROLE auth_migrator IN SCHEMA identity_schema GRANT SELECT,USAGE ON SEQUENCES TO auth_app;


--
-- Name: DEFAULT PRIVILEGES FOR TABLES; Type: DEFAULT ACL; Schema: identity_schema; Owner: auth_migrator
--

ALTER DEFAULT PRIVILEGES FOR ROLE auth_migrator IN SCHEMA identity_schema GRANT SELECT,INSERT,UPDATE ON TABLES TO auth_app;


--
-- PostgreSQL database dump complete
--

\unrestrict vaulletschemadocs

