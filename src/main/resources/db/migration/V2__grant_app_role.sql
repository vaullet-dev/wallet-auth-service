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
-- If auth_app does not exist this fails, and that is correct. A conditional skip
-- would leave a service that starts, connects, and 42501s on every request.

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
-- remembering to add a grant. Scoped to auth_migrator because that is who will
-- create them.
ALTER DEFAULT PRIVILEGES FOR ROLE auth_migrator IN SCHEMA identity_schema
    GRANT SELECT, INSERT, UPDATE ON TABLES TO auth_app;

ALTER DEFAULT PRIVILEGES FOR ROLE auth_migrator IN SCHEMA identity_schema
    GRANT USAGE, SELECT ON SEQUENCES TO auth_app;
