package dev.vaullet.auth.account.dao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * The SQL this service issues, read from {@code db/sql/accounts/} at startup.
 *
 * <p>The statements are files rather than string literals for the ordinary reason that they are
 * SQL: an IDE highlights and formats a {@code .sql} file, can validate it against a configured
 * datasource, and will not let a stray {@code +} concatenation silently glue two keywords together.
 * A repository whose methods read as one line each is also a repository whose <em>shape</em> is
 * reviewable — you can see that there are five statements and what each one is for, without reading
 * five paragraphs of embedded SQL.
 *
 * <p>The reasoning behind each statement lives in the file with it, in SQL comments, which means it
 * travels with the query rather than with the Java that happens to call it today. Those comments do
 * reach the server and appear in {@code pg_stat_activity} and the slow-query log — which is a small
 * benefit rather than a cost, because a slow statement then identifies its own source file.
 *
 * <p>A Spring bean, and eager: every file is read in the constructor, so a renamed or missing one
 * fails the context at startup with the path in the message. Loading lazily on first use would turn
 * a typo into a 500 on whichever endpoint happened to be called first, in production, at the worst
 * possible moment.
 */
@Component
class AccountStatements {

    private static final String BASE = "db/sql/accounts/";

    final String insert = read("insert.sql");
    final String findById = read("find-by-id.sql");
    final String findByExternalRef = read("find-by-external-ref.sql");
    final String findByKeycloakSub = read("find-by-keycloak-sub.sql");
    final String updateStatusIfOpen = read("update-status-if-open.sql");

    private static String read(String file) {
        var resource = new ClassPathResource(BASE + file);
        try (var in = resource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read SQL statement " + BASE + file, e);
        }
    }
}
