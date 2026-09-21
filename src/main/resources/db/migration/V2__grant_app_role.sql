-- Privileges for the role the RUNNING SERVICE holds.
--
-- V1 created this schema as auth_migrator, so auth_migrator owns everything in
-- it. Without this migration the application connects successfully and then
-- cannot read its own table -- a failure that looks like a bug in the DAO.
--
-- This lives in a migration rather than in gitops because ALTER DEFAULT
-- PRIVILEGES is scoped to the role that CREATES the objects. Only auth_migrator
-- can set it for objects auth_migrator will create, and only a migration runs as
-- auth_migrator. The ROLE itself still belongs to gitops (CloudNativePG
-- managed.roles): the database and the roles are the platform's, the schema and
-- everything in it are the service's.
--
-- The role must EXIST for these grants to parse, and it does not exist
-- everywhere. In a cluster CloudNativePG creates it from managed.roles, with a
-- password from OpenBao. In a Testcontainers PostgreSQL there is no platform at
-- all, so the first draft of this file failed CI with
--     ERROR: role "auth_app" does not exist
--
-- So: ensure it, without owning it. NOLOGIN and no password, because the
-- credential is emphatically the platform's business -- a migration that could
-- set a login password would be a migration that could grant itself access.
-- Where the platform already made the role, this is a no-op and its LOGIN and
-- password are left exactly as they are.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'auth_app') THEN
        CREATE ROLE auth_app NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA identity_schema TO auth_app;

-- Named per table, NOT "ON ALL TABLES". That form would also grant on
-- flyway_schema_history, and an application able to UPDATE the migration history
-- can defeat validate-on-migrate -- leaving a schema nobody can vouch for on the
-- table every balance is keyed to.
--
-- No DELETE, deliberately. ADR-014 makes closure a status transition and the
-- accounts_invariants trigger makes CLOSED terminal, so no code path should ever
-- remove an account row. Withholding the privilege makes that structural rather
-- than a convention a future contributor can forget.
GRANT SELECT, INSERT, UPDATE ON identity_schema.accounts TO auth_app;

-- Tables a LATER migration creates get the same treatment without anyone
-- remembering to add a grant.
--
-- No FOR ROLE clause, deliberately. Default privileges attach to the role that
-- CREATES the object, and that role is whoever is running this migration --
-- auth_migrator in a cluster, the container's own user under Testcontainers.
-- Naming auth_migrator explicitly was the first draft and it failed with
--     ERROR: role "auth_migrator" does not exist
-- in any environment the platform did not build. Omitting it means CURRENT_USER,
-- which is the correct answer in both.
ALTER DEFAULT PRIVILEGES IN SCHEMA identity_schema
    GRANT SELECT, INSERT, UPDATE ON TABLES TO auth_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA identity_schema
    GRANT USAGE, SELECT ON SEQUENCES TO auth_app;
