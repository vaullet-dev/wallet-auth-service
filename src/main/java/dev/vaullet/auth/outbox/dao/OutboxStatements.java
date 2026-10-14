package dev.vaullet.auth.outbox.dao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * The outbox's SQL, read from {@code db/sql/outbox/} at startup — files rather than literals, and
 * read eagerly, for the reasons {@code AccountStatements} gives: a renamed file fails the context,
 * not the first delivery round, and a statement's reasoning travels with it into
 * {@code pg_stat_activity}.
 */
@Component
class OutboxStatements {

    private static final String BASE = "db/sql/outbox/";

    final String insert = read("insert.sql");
    final String claimDue = read("claim-due.sql");
    final String markDelivered = read("mark-delivered.sql");
    final String markFailed = read("mark-failed.sql");
    final String backlog = read("backlog.sql");
    final String queueMissing = read("queue-missing.sql");

    private static String read(String file) {
        var resource = new ClassPathResource(BASE + file);
        try (var in = resource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read SQL statement " + BASE + file, e);
        }
    }
}
