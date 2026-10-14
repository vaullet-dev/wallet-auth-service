package dev.vaullet.auth.support;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Makes the outbox refuse the entry of one account, so a test can watch what an account creation
 * does when its second write fails: the proof that the account and its event are one transaction.
 *
 * <p>A trigger, created and dropped by the test, rather than a mocked bean: the point is the real
 * code path — the real transaction, the real rollback — with only the database saying no. It
 * refuses by the account's {@code external_ref}, which the test chooses before the account id
 * exists, and sees the account row because it runs inside the same transaction that inserted it.
 *
 * <pre>{@code
 * FailingOutbox refusing = FailingOutbox.forReference(jdbc, "acme-1");
 * try {
 *     ... create the account, expect a 500 ...
 * } finally {
 *     refusing.close();
 * }
 * }</pre>
 */
public final class FailingOutbox implements AutoCloseable {

    private final JdbcTemplate jdbc;
    private final String name;

    private FailingOutbox(JdbcTemplate jdbc, String name) {
        this.jdbc = jdbc;
        this.name = name;
    }

    /** From now until {@link #close()}, writing the entry of the account with this reference fails. */
    public static FailingOutbox forReference(JdbcTemplate jdbc, String externalRef) {
        String name = "test_outbox_refuses_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("""
                CREATE FUNCTION identity_schema.%1$s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF EXISTS (SELECT 1 FROM identity_schema.accounts
                                WHERE account_id = NEW.account_id AND external_ref = '%2$s') THEN
                        RAISE EXCEPTION 'test: the outbox refuses account %%', NEW.account_id;
                    END IF;
                    RETURN NEW;
                END
                $$""".formatted(name, externalRef.replace("'", "''")));
        jdbc.execute("""
                CREATE TRIGGER %1$s BEFORE INSERT ON identity_schema.outbox
                    FOR EACH ROW EXECUTE FUNCTION identity_schema.%1$s()""".formatted(name));
        return new FailingOutbox(jdbc, name);
    }

    @Override
    public void close() {
        jdbc.execute("DROP TRIGGER IF EXISTS " + name + " ON identity_schema.outbox");
        jdbc.execute("DROP FUNCTION IF EXISTS identity_schema." + name + "()");
    }
}
