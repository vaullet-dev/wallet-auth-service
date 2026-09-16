--
-- PostgreSQL database dump
--

\restrict 8SQU5rfyJvQYW7xW9bcJazce2LsSuB7B6Vj4fvyUv30XHU6C9AErWkB1ePhisYS

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
-- Name: identity_schema; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA identity_schema;


--
-- Name: accounts_enforce_invariants(); Type: FUNCTION; Schema: identity_schema; Owner: -
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


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: accounts; Type: TABLE; Schema: identity_schema; Owner: -
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


--
-- Name: TABLE accounts; Type: COMMENT; Schema: identity_schema; Owner: -
--

COMMENT ON TABLE identity_schema.accounts IS 'Vaullet''s own record of a user: the identifier money is keyed to, and its status. Holds no personal data in either identity mode (ADR-006, ADR-014).';


--
-- Name: COLUMN accounts.account_id; Type: COMMENT; Schema: identity_schema; Owner: -
--

COMMENT ON COLUMN identity_schema.accounts.account_id IS 'Permanent. Keys seven years of immutable ledger journal, so it must outlive Keycloak, a realm re-import, or a change of identity provider.';


--
-- Name: COLUMN accounts.keycloak_sub; Type: COMMENT; Schema: identity_schema; Owner: -
--

COMMENT ON COLUMN identity_schema.accounts.keycloak_sub IS 'Keycloak user id. NULL = unlinked anchor: provisioned before the user ever authenticated. Set by just-in-time provisioning at first login.';


--
-- Name: COLUMN accounts.external_ref; Type: COMMENT; Schema: identity_schema; Owner: -
--

COMMENT ON COLUMN identity_schema.accounts.external_ref IS 'The operator''s own user id. Unique per deployment and immutable once set (ADR-012); immutability is enforced by the accounts_invariants trigger.';


--
-- Name: COLUMN accounts.status; Type: COMMENT; Schema: identity_schema; Owner: -
--

COMMENT ON COLUMN identity_schema.accounts.status IS 'ACTIVE | SUSPENDED | CLOSED. Authoritative here, not in Keycloak: a fraud lock the operator''s directory could overrule is not a fraud lock. CLOSED is terminal.';


--
-- Name: flyway_schema_history; Type: TABLE; Schema: identity_schema; Owner: -
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


--
-- Name: accounts accounts_external_ref_uk; Type: CONSTRAINT; Schema: identity_schema; Owner: -
--

ALTER TABLE ONLY identity_schema.accounts
    ADD CONSTRAINT accounts_external_ref_uk UNIQUE (external_ref);


--
-- Name: accounts accounts_keycloak_sub_uk; Type: CONSTRAINT; Schema: identity_schema; Owner: -
--

ALTER TABLE ONLY identity_schema.accounts
    ADD CONSTRAINT accounts_keycloak_sub_uk UNIQUE (keycloak_sub);


--
-- Name: accounts accounts_pk; Type: CONSTRAINT; Schema: identity_schema; Owner: -
--

ALTER TABLE ONLY identity_schema.accounts
    ADD CONSTRAINT accounts_pk PRIMARY KEY (account_id);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: identity_schema; Owner: -
--

ALTER TABLE ONLY identity_schema.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: identity_schema; Owner: -
--

CREATE INDEX flyway_schema_history_s_idx ON identity_schema.flyway_schema_history USING btree (success);


--
-- Name: accounts accounts_invariants; Type: TRIGGER; Schema: identity_schema; Owner: -
--

CREATE TRIGGER accounts_invariants BEFORE UPDATE ON identity_schema.accounts FOR EACH ROW EXECUTE FUNCTION identity_schema.accounts_enforce_invariants();


--
-- PostgreSQL database dump complete
--

\unrestrict 8SQU5rfyJvQYW7xW9bcJazce2LsSuB7B6Vj4fvyUv30XHU6C9AErWkB1ePhisYS

