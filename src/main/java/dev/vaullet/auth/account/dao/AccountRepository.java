package dev.vaullet.auth.account.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Every statement this service issues against {@code identity_schema}, and nothing else.
 *
 * <p>The SQL itself lives in {@code db/sql/accounts/}, loaded by {@link AccountStatements}. What is
 * left here is the shape: five statements, what each one is called, and how a row becomes an object.
 *
 * <p>The methods take no decisions. Whether a missing row is an error, which status transitions are
 * allowed, what a repeated natural key means — all of that is {@code AccountService}'s, so the rules
 * can be read in one place and unit-tested without a database. What the database owns is declared in
 * {@code V1__identity.sql} and enforced there.
 *
 * <p>{@link AccountRow} deliberately does not leak upward: the service maps it into its own domain
 * type, so a change of column shape stops at this boundary.
 */
@Repository
public class AccountRepository {

    private final JdbcTemplate jdbc;
    private final AccountStatements sql;

    AccountRepository(JdbcTemplate jdbc, AccountStatements sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    /**
     * The anchor, as stored.
     *
     * <p>{@code status} is the raw column value. Mapping it to an enum is the service's job; a domain
     * type down here would point the dependency the wrong way.
     */
    public static final class AccountRow {

        private final UUID accountId;

        /**
         * Null for an <em>unlinked anchor</em>: an account provisioned before its user ever
         * authenticated. A state the design has, not a hole in it.
         */
        private final @Nullable UUID keycloakSub;

        /**
         * Null when the operator supplied no identifier of their own. The database guarantees at
         * least one of this and {@code keycloakSub} is present ({@code accounts_natural_key_ck}),
         * which is what makes every account addressable by something other than the id we minted.
         */
        private final @Nullable String externalRef;

        private final String status;
        private final Instant createdAt;
        private final Instant updatedAt;

        public AccountRow(
                UUID accountId,
                @Nullable UUID keycloakSub,
                @Nullable String externalRef,
                String status,
                Instant createdAt,
                Instant updatedAt) {
            this.accountId = accountId;
            this.keycloakSub = keycloakSub;
            this.externalRef = externalRef;
            this.status = status;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        public UUID getAccountId() {
            return accountId;
        }

        public @Nullable UUID getKeycloakSub() {
            return keycloakSub;
        }

        public @Nullable String getExternalRef() {
            return externalRef;
        }

        public String getStatus() {
            return status;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public Instant getUpdatedAt() {
            return updatedAt;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof AccountRow other)) {
                return false;
            }
            return accountId.equals(other.accountId)
                    && Objects.equals(keycloakSub, other.keycloakSub)
                    && Objects.equals(externalRef, other.externalRef)
                    && status.equals(other.status)
                    && createdAt.equals(other.createdAt)
                    && updatedAt.equals(other.updatedAt);
        }

        @Override
        public int hashCode() {
            return Objects.hash(accountId, keycloakSub, externalRef, status, createdAt, updatedAt);
        }

        /** No personal data to redact — the anchor holds none — so every field is safe to print. */
        @Override
        public String toString() {
            return "AccountRow[accountId=" + accountId
                    + ", keycloakSub=" + keycloakSub
                    + ", externalRef=" + externalRef
                    + ", status=" + status
                    + ", createdAt=" + createdAt
                    + ", updatedAt=" + updatedAt + "]";
        }
    }

    private static final RowMapper<AccountRow> MAPPER = AccountRepository::mapRow;

    /** Create the anchor. See {@code insert.sql} for why there is no {@code ON CONFLICT}. */
    public AccountRow insert(
            UUID accountId, @Nullable UUID keycloakSub, @Nullable String externalRef, String status) {
        return one(sql.insert, accountId, keycloakSub, externalRef, status)
                // RETURNING on a single-row insert yields exactly one row or throws. An empty result
                // would mean the driver contract changed under us; failing loudly beats returning null.
                .orElseThrow(() -> new IllegalStateException("INSERT ... RETURNING produced no row"));
    }

    public Optional<AccountRow> findById(UUID accountId) {
        return one(sql.findById, accountId);
    }

    public Optional<AccountRow> findByExternalRef(String externalRef) {
        return one(sql.findByExternalRef, externalRef);
    }

    public Optional<AccountRow> findByKeycloakSub(UUID keycloakSub) {
        return one(sql.findByKeycloakSub, keycloakSub);
    }

    /**
     * Move the account to a new status, unless it is closed.
     *
     * <p>The guard lives in the statement rather than in a preceding read, and
     * {@code update-status-if-open.sql} explains why at length — it is the one piece of real
     * concurrency reasoning in this service.
     *
     * @return the updated row, or empty if no account matched and was open
     */
    public Optional<AccountRow> updateStatusIfOpen(UUID accountId, String newStatus) {
        return one(sql.updateStatusIfOpen, newStatus, accountId);
    }

    private Optional<AccountRow> one(String statement, Object... args) {
        return jdbc.query(statement, MAPPER, args).stream().findFirst();
    }

    private static AccountRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AccountRow(
                rs.getObject("account_id", UUID.class),
                // getObject(..., UUID.class) returns null for a SQL NULL, which is exactly what an
                // unlinked anchor is. getString + UUID.fromString would throw on it instead.
                rs.getObject("keycloak_sub", UUID.class),
                rs.getString("external_ref"),
                rs.getString("status"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    /**
     * {@code OffsetDateTime}, not {@code Timestamp}. A {@code timestamptz} read through
     * {@code Timestamp} is silently reinterpreted in the JVM's default zone — a bug that stays hidden
     * on a laptop set to UTC and appears on a server that is not.
     */
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
