package com.gamma.la.store.pg;

import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.la.core.DraftLifecycle;
import com.gamma.la.core.DraftStore;
import com.gamma.la.core.InvestigationEvaluator;
import com.gamma.la.core.InvestigationStore;
import com.gamma.la.core.InvestigationVersionConflictException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The PostgreSQL {@link InvestigationStore} (design {@code docs/archived-documents/plans-archive/investigation-store-design.md}, slice S2; decisions
 * D-IS3 to D-IS5, D-IS8, D-IS9, D-IS12).
 *
 * <ul>
 *   <li><b>Serialisation is a row lock in a SHORT transaction</b> ({@code SELECT ... FOR NO KEY UPDATE} on the Investigation row, or on
 *       the Draft row for a Draft's own log). Never an advisory lock, never a lock held across a request: every port method is one
 *       transaction, and the preconditions ({@code expectedVersion}, the two prefix hashes) are checked INSIDE it, so "verify, then
 *       write" is atomic across pods. {@code FOR NO KEY UPDATE} (not {@code FOR UPDATE}) because a Draft append inserts log rows that
 *       take a {@code FOR KEY SHARE} on the Investigation row through their foreign key; the weaker lock still excludes every other
 *       writer but does not make that insert wait on a promote that itself waits for the Draft: no deadlock. Lock order, everywhere:
 *       Space row, then Investigation row, then Draft row.</li>
 *   <li><b>Lines, sets, headers and markers are {@code text}</b>, never {@code jsonb} (D-IS5): the sealed hashes are defined over the exact
 *       bytes ({@code DraftStore.prefixHash}, the {@code sealedSet} re-hash), and {@code jsonb} would reorder keys and collapse whitespace.</li>
 *   <li><b>The Space cap (D-IS9)</b> is checked and the seat taken under a lock on the one {@code la_space} row, so N pods cannot admit
 *       N x cap Drafts.</li>
 *   <li><b>Tenancy (D-IS8)</b>: one schema per Space; the schema name is validated, never concatenated from user input.</li>
 *   <li><b>A promote cannot be interrupted half way</b> (one transaction), so {@link #recoverDrafts} has nothing to recover.</li>
 * </ul>
 * Fails closed: a database that cannot be reached is an {@link IOException} (the route answers 503); this class never falls back to
 * another backend.
 *
 * <p>NOT wired anywhere yet: the filesystem store is still the only one {@code InvestigationStores.of} returns (slice S6).
 */
public final class PgInvestigationStore implements InvestigationStore {

    /** Where connections come from (a pool, or {@code DriverManager} in a test). Each call returns a connection this class closes. */
    @FunctionalInterface
    public interface ConnectionSource {
        Connection open() throws SQLException;
    }

    private static final Pattern SCHEMA = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    /** {@link #touchDraft} writes at most this often, as {@code DraftLifecycle.PERSIST_EVERY} does on the filesystem. */
    private static final Duration TOUCH_EVERY = Duration.ofMinutes(5);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String LIVE = "state IN ('open','hibernated')";

    /** Runs a body on a connection it borrows and gives back (a pool's {@code with}, or open-use-close). */
    @FunctionalInterface
    private interface Borrow {
        <T> T with(com.gamma.util.ConnectionSource.SqlFunction<T> body) throws SQLException;
    }

    /** An {@link IOException} thrown by a body running inside {@link Borrow#with}, which can only throw {@link SQLException}. */
    private static final class Carried extends RuntimeException {
        Carried(IOException cause) {
            super(cause);
        }
    }

    private final Borrow borrow;
    private final String schema;
    private final java.util.function.LongSupplier maxSetBytes;
    private final java.util.function.LongSupplier maxInvestigationBytes;

    /** A connection per call from {@code connections} (tests, {@code DriverManager}). */
    public PgInvestigationStore(ConnectionSource connections, String schema) throws IOException {
        this(schema, new Borrow() {
            @Override
            public <T> T with(com.gamma.util.ConnectionSource.SqlFunction<T> body) throws SQLException {
                try (Connection c = connections.open()) {
                    return body.apply(c);
                }
            }
        }, com.gamma.la.core.WorkingSetSizeLimit.DEFAULT, com.gamma.la.core.InvestigationSetBudget.DEFAULT);
    }

    /** Connections borrowed from a pool (reentrant: a nested borrow on one thread reuses its connection, so one operation is one connection). */
    public PgInvestigationStore(com.gamma.util.ConnectionSource pool, String schema) throws IOException {
        this(schema, pool::with, com.gamma.la.core.WorkingSetSizeLimit.DEFAULT, com.gamma.la.core.InvestigationSetBudget.DEFAULT);
    }

    /**
     * As above, with the Space's per-set size limit ({@link com.gamma.la.core.WorkingSetSizeLimit}) and per-Investigation total set budget
     * ({@link com.gamma.la.core.InvestigationSetBudget}), each read per write.
     */
    public PgInvestigationStore(com.gamma.util.ConnectionSource pool, String schema, java.util.function.LongSupplier maxSetBytes,
                                java.util.function.LongSupplier maxInvestigationBytes) throws IOException {
        this(schema, pool::with, maxSetBytes, maxInvestigationBytes);
    }

    private PgInvestigationStore(String schema, Borrow borrow, java.util.function.LongSupplier maxSetBytes,
                                 java.util.function.LongSupplier maxInvestigationBytes) throws IOException {
        if (schema == null || !SCHEMA.matcher(schema).matches())
            throw new IllegalArgumentException("schema must match " + SCHEMA.pattern());
        this.borrow = borrow;
        this.schema = schema;
        this.maxSetBytes = maxSetBytes;
        this.maxInvestigationBytes = maxInvestigationBytes;
        bootstrap();
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection c) throws SQLException, IOException;
    }

    /**
     * One short transaction. A RuntimeException (a precondition conflict) rolls it back and propagates as itself. A database that cannot be
     * reached or has gone away is {@code 503 CAPABILITY_UNAVAILABLE} (fail closed: never another backend); any other SQL failure is an
     * {@link IOException}.
     */
    private <T> T tx(Work<T> work) throws IOException {
        try {
            return borrow.with(c -> {
                c.setAutoCommit(false);
                try {
                    T out = work.run(c);
                    c.commit();
                    return out;
                } catch (SQLException | RuntimeException failed) {
                    rollback(c, failed);
                    throw failed;
                } catch (IOException failed) {
                    rollback(c, failed);
                    throw new Carried(failed);
                }
            });
        } catch (Carried carried) {
            throw (IOException) carried.getCause();
        } catch (SQLException e) {
            if (unreachable(e))
                throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "Investigation database unavailable: " + e.getMessage());
            throw new IOException("investigation store (postgres) failed: " + e.getMessage(), e);
        }
    }

    private static void rollback(Connection c, Exception failed) {
        try {
            c.rollback();
        } catch (SQLException suppressed) {
            failed.addSuppressed(suppressed);
        }
    }

    /** Connection-class failures: SQLSTATE 08 (connection), 28 (authentication), 53 (resources), 57P (shutdown), and a pool that cannot lend. */
    static boolean unreachable(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLTransientConnectionException || t instanceof java.sql.SQLNonTransientConnectionException) return true;
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                String st = sql.getSQLState();
                if (st.startsWith("08") || st.startsWith("28") || st.startsWith("53") || st.startsWith("57P")) return true;
            }
        }
        return false;
    }

    private String t(String table) {
        return schema + "." + table;
    }

    private static PreparedStatement bind(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof Instant at) ps.setObject(i + 1, OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
            else ps.setObject(i + 1, a);
        }
        return ps;
    }

    private static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = bind(c, sql, args)) {
            return ps.executeUpdate();
        }
    }

    private static List<String> strings(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = bind(c, sql, args); ResultSet rs = ps.executeQuery()) {
            List<String> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getString(1));
            return out;
        }
    }

    private static String one(Connection c, String sql, Object... args) throws SQLException {
        List<String> rows = strings(c, sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static long number(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = bind(c, sql, args); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private void bootstrap() throws IOException {
        String[] ddl = {
            "CREATE SCHEMA IF NOT EXISTS " + schema,
            "CREATE TABLE IF NOT EXISTS " + t("la_space") + " (one boolean PRIMARY KEY DEFAULT true CHECK (one))",
            "INSERT INTO " + t("la_space") + " (one) VALUES (true) ON CONFLICT DO NOTHING",
            "CREATE TABLE IF NOT EXISTS " + t("la_investigation") + " (id text PRIMARY KEY, header text NOT NULL, mask_key bytea, case_link text)",
            // set_bytes: the running total of this Investigation's sets (main + live Drafts); NULL = not counted yet (a table made before the
            // counter existed): computed once, lazily, under the row lock, then kept in the SAME transaction as every write that changes a set.
            "ALTER TABLE " + t("la_investigation") + " ADD COLUMN IF NOT EXISTS set_bytes bigint",
            "CREATE TABLE IF NOT EXISTS " + t("la_log") + " (inv text NOT NULL REFERENCES " + t("la_investigation") + "(id), draft text NOT NULL DEFAULT '', "
                    + "seq int NOT NULL, line text NOT NULL, PRIMARY KEY (inv, draft, seq))",
            "CREATE TABLE IF NOT EXISTS " + t("la_set") + " (inv text NOT NULL REFERENCES " + t("la_investigation") + "(id), draft text NOT NULL DEFAULT '', "
                    + "step int NOT NULL, body text NOT NULL, PRIMARY KEY (inv, draft, step))",
            "CREATE TABLE IF NOT EXISTS " + t("la_member") + " (inv text NOT NULL REFERENCES " + t("la_investigation") + "(id), seq int NOT NULL, "
                    + "line text NOT NULL, PRIMARY KEY (inv, seq))",
            "CREATE TABLE IF NOT EXISTS " + t("la_reference") + " (inv text NOT NULL REFERENCES " + t("la_investigation") + "(id), seq int NOT NULL, "
                    + "key text NOT NULL, line text NOT NULL, PRIMARY KEY (inv, seq), UNIQUE (inv, key))",
            "CREATE TABLE IF NOT EXISTS " + t("la_alert_binding") + " (inv text NOT NULL REFERENCES " + t("la_investigation") + "(id), rule text NOT NULL, "
                    + "body text NOT NULL, PRIMARY KEY (inv, rule))",
            "CREATE TABLE IF NOT EXISTS " + t("la_pending") + " (inv text NOT NULL REFERENCES " + t("la_investigation") + "(id), request_id text NOT NULL, "
                    + "body text NOT NULL, PRIMARY KEY (inv, request_id))",
            "CREATE TABLE IF NOT EXISTS " + t("la_template") + " (id text PRIMARY KEY, body text NOT NULL)",
            "CREATE TABLE IF NOT EXISTS " + t("la_draft") + " (inv text NOT NULL REFERENCES " + t("la_investigation") + "(id), id text NOT NULL, "
                    + "header text NOT NULL, actor text NOT NULL, state text NOT NULL CHECK (state IN ('open','hibernated','discarded','promoted')), "
                    + "marker text, last_access timestamptz NOT NULL, version bigint NOT NULL DEFAULT 0, PRIMARY KEY (inv, id))",
            // Append-only (design section 7): the sealed MAIN log, its sets, the members and the references are never changed or removed
            // by anything but dropping the Space's schema. A trigger, not a REVOKE: the application connects as the table owner, which a
            // REVOKE does not bind. A Draft's own rows (draft <> '') ARE deleted (close, rebase), so the log/set row triggers carry a WHEN.
            "CREATE OR REPLACE FUNCTION " + t("la_append_only") + "() RETURNS trigger LANGUAGE plpgsql AS $fn$ BEGIN "
                    + "RAISE EXCEPTION '% is append-only: % refused', TG_TABLE_NAME, TG_OP USING ERRCODE = 'integrity_constraint_violation'; END $fn$",
            appendOnly("la_log", "BEFORE UPDATE OR DELETE", "FOR EACH ROW WHEN (OLD.draft = '')"),
            appendOnly("la_set", "BEFORE UPDATE OR DELETE", "FOR EACH ROW WHEN (OLD.draft = '')"),
            appendOnly("la_member", "BEFORE UPDATE OR DELETE", "FOR EACH ROW"),
            appendOnly("la_reference", "BEFORE UPDATE OR DELETE", "FOR EACH ROW"),
            appendOnly("la_log", "BEFORE TRUNCATE", "FOR EACH STATEMENT"),
            appendOnly("la_set", "BEFORE TRUNCATE", "FOR EACH STATEMENT"),
            appendOnly("la_member", "BEFORE TRUNCATE", "FOR EACH STATEMENT"),
            appendOnly("la_reference", "BEFORE TRUNCATE", "FOR EACH STATEMENT")
        };
        // First start of a Space on N pods at once: the DDL runs under a transaction-scoped advisory lock, so the pods take turns and the
        // later ones find everything made (CREATE ... IF NOT EXISTS alone races on the catalog's unique index). Scoped to the transaction,
        // it cannot outlive its connection or be orphaned, so the objection to a long-lived advisory lock (design section 6) does not apply.
        tx(c -> {
            try (PreparedStatement lock = bind(c, "SELECT pg_advisory_xact_lock(hashtext(?))", "inspecto-la-store:" + schema)) {
                lock.execute();
            }
            try (Statement st = c.createStatement()) {
                for (String sql : ddl) st.execute(sql);
            }
            return null;
        });
    }

    private String appendOnly(String table, String when, String scope) {
        String name = table + (when.endsWith("TRUNCATE") ? "_no_truncate" : "_append_only");
        return "CREATE OR REPLACE TRIGGER " + name + " " + when + " ON " + t(table) + " " + scope + " EXECUTE FUNCTION " + t("la_append_only") + "()";
    }

    private void lockInvestigation(Connection c, String id) throws SQLException, IOException {
        if (one(c, "SELECT id FROM " + t("la_investigation") + " WHERE id = ? FOR NO KEY UPDATE", id) == null)
            throw new IOException("no such investigation '" + id + "'");
    }

    /** Lock a Draft's row and return its state, or null when there is no such Draft. */
    private String lockDraft(Connection c, String inv, String draft) throws SQLException {
        return one(c, "SELECT state FROM " + t("la_draft") + " WHERE inv = ? AND id = ? FOR NO KEY UPDATE", inv, draft);
    }

    /**
     * The total bytes of this Investigation's sets: the counter, else (once) the sum, stored. The caller holds the Investigation's row
     * lock ({@link #lockInvestigation}), so the counter and the rows change together or not at all.
     */
    private long setTotal(Connection c, String inv) throws SQLException {
        try (PreparedStatement ps = bind(c, "SELECT set_bytes FROM " + t("la_investigation") + " WHERE id = ?", inv); ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                long v = rs.getLong(1);
                if (!rs.wasNull()) return v;
            }
        }
        long sum = number(c, "SELECT COALESCE(SUM(octet_length(body)), 0) FROM " + t("la_set") + " WHERE inv = ?", inv);
        update(c, "UPDATE " + t("la_investigation") + " SET set_bytes = ? WHERE id = ?", sum, inv);
        return sum;
    }

    /** Move the counter by {@code delta} (call {@link #setTotal} first in the transaction so it is not NULL). */
    private void adjustSetTotal(Connection c, String inv, long delta) throws SQLException {
        if (delta != 0) update(c, "UPDATE " + t("la_investigation") + " SET set_bytes = set_bytes + ? WHERE id = ? AND set_bytes IS NOT NULL", delta, inv);
    }

    private long draftSetBytes(Connection c, String inv, String draft) throws SQLException {
        return number(c, "SELECT COALESCE(SUM(octet_length(body)), 0) FROM " + t("la_set") + " WHERE inv = ? AND draft = ?", inv, draft);
    }

    private static String draftOf(Scope scope) {
        return scope.isDraft() ? scope.draftId() : "";
    }

    private static boolean closed(String state) {
        return "discarded".equals(state) || "promoted".equals(state);
    }

    // ── identity ────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public boolean create(String id, String headerJson) throws IOException {
        return tx(c -> update(c, "INSERT INTO " + t("la_investigation") + " (id, header) VALUES (?, ?) ON CONFLICT DO NOTHING", id, headerJson) == 1);
    }

    @Override
    public Optional<String> header(String id) throws IOException {
        return Optional.ofNullable(tx(c -> one(c, "SELECT header FROM " + t("la_investigation") + " WHERE id = ?", id)));
    }

    @Override
    public List<String> ids() throws IOException {
        return tx(c -> strings(c, "SELECT id FROM " + t("la_investigation") + " ORDER BY id COLLATE \"C\""));
    }

    @Override
    public boolean createFork(String id, String headerJson, List<String> lines, List<String> sets) throws IOException {
        com.gamma.la.core.WorkingSetSizeLimit.enforceAll(maxSetBytes, id, 1, sets);
        long forkBytes = com.gamma.la.core.InvestigationSetBudget.bytes(sets);
        com.gamma.la.core.InvestigationSetBudget.enforce(maxInvestigationBytes, id, 0, forkBytes);   // a new Investigation: nothing stored, nothing to race
        return tx(c -> {
            if (update(c, "INSERT INTO " + t("la_investigation") + " (id, header, set_bytes) VALUES (?, ?, ?) ON CONFLICT DO NOTHING", id, headerJson, forkBytes) != 1) return false;
            insertLines(c, id, "", 0, lines);
            for (int i = 0; i < sets.size(); i++)
                update(c, "INSERT INTO " + t("la_set") + " (inv, draft, step, body) VALUES (?, '', ?, ?)", id, i + 1, sets.get(i));
            return true;   // one transaction: a failure above leaves nothing under the id
        });
    }

    private void insertLines(Connection c, String inv, String draft, int after, List<String> lines) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + t("la_log") + " (inv, draft, seq, line) VALUES (?, ?, ?, ?)")) {
            for (int i = 0; i < lines.size(); i++) {
                ps.setString(1, inv);
                ps.setString(2, draft);
                ps.setInt(3, after + i + 1);
                ps.setString(4, lines.get(i));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ── the sealed log ──────────────────────────────────────────────────────────────────────────────────

    private long count(Connection c, String inv, String draft) throws SQLException {
        return number(c, "SELECT COALESCE(MAX(seq), 0) FROM " + t("la_log") + " WHERE inv = ? AND draft = ?", inv, draft);
    }

    private List<String> lines(Connection c, String inv, String draft) throws SQLException {
        return strings(c, "SELECT line FROM " + t("la_log") + " WHERE inv = ? AND draft = ? ORDER BY seq", inv, draft);
    }

    @Override
    public long version(Scope scope) throws IOException {
        return tx(c -> count(c, scope.investigationId(), draftOf(scope)));
    }

    @Override
    public List<String> log(Scope scope) throws IOException {
        return tx(c -> lines(c, scope.investigationId(), draftOf(scope)));
    }

    @Override
    public Optional<String> set(String investigationId, int step) throws IOException {
        return Optional.ofNullable(tx(c -> one(c, "SELECT body FROM " + t("la_set") + " WHERE inv = ? AND draft = '' AND step = ?", investigationId, step)));
    }

    @Override
    public void append(Scope scope, long expectedVersion, int step, String lineJson, String setJson) throws IOException {
        com.gamma.la.core.WorkingSetSizeLimit.enforce(maxSetBytes, scope.investigationId(), step, setJson);
        String inv = scope.investigationId(), draft = draftOf(scope);
        tx(c -> {
            lockInvestigation(c, inv);   // the Investigation first (its set counter), THEN the Draft: the one order everywhere
            if (scope.isDraft()) {
                String state = lockDraft(c, inv, draft);
                if (state == null) throw new IOException("no such draft '" + draft + "'");
                if (closed(state)) throw new DraftClosedException(draft);
            }
            long actual = count(c, inv, draft);
            if (actual != expectedVersion) throw new InvestigationVersionConflictException(inv, expectedVersion, actual);
            long total = setTotal(c, inv), added = com.gamma.la.core.InvestigationSetBudget.bytes(setJson);
            com.gamma.la.core.InvestigationSetBudget.enforce(maxInvestigationBytes, inv, total, total + added);   // before any row is written
            insertLines(c, inv, draft, (int) actual, List.of(lineJson));   // the log line and its set are ONE commit
            update(c, "INSERT INTO " + t("la_set") + " (inv, draft, step, body) VALUES (?, ?, ?, ?)", inv, draft, step, setJson);
            adjustSetTotal(c, inv, added);
            if (scope.isDraft()) update(c, "UPDATE " + t("la_draft") + " SET version = version + 1 WHERE inv = ? AND id = ?", inv, draft);
            return null;
        });
    }

    // ── members and references ──────────────────────────────────────────────────────────────────────────

    @Override
    public List<String> members(String investigationId) throws IOException {
        return tx(c -> strings(c, "SELECT line FROM " + t("la_member") + " WHERE inv = ? ORDER BY seq", investigationId));
    }

    @Override
    public void appendMember(String investigationId, long expectedCount, String lineJson) throws IOException {
        tx(c -> {
            lockInvestigation(c, investigationId);
            long actual = number(c, "SELECT COUNT(*) FROM " + t("la_member") + " WHERE inv = ?", investigationId);
            if (actual != expectedCount) throw new InvestigationVersionConflictException(investigationId, expectedCount, actual);
            update(c, "INSERT INTO " + t("la_member") + " (inv, seq, line) VALUES (?, ?, ?)", investigationId, (int) actual + 1, lineJson);
            return null;
        });
    }

    @Override
    public Appended appendReference(String investigationId, String key, String lineJson, int maxCount) throws IOException {
        return tx(c -> {
            lockInvestigation(c, investigationId);
            long have = number(c, "SELECT COUNT(*) FROM " + t("la_reference") + " WHERE inv = ?", investigationId);
            if (have >= maxCount) return Appended.FULL;
            if (one(c, "SELECT key FROM " + t("la_reference") + " WHERE inv = ? AND key = ?", investigationId, key) != null) return Appended.DUPLICATE;
            update(c, "INSERT INTO " + t("la_reference") + " (inv, seq, key, line) VALUES (?, ?, ?, ?)", investigationId, (int) have + 1, key, lineJson);
            return Appended.ADDED;
        });
    }

    @Override
    public List<String> references(String investigationId) throws IOException {
        return tx(c -> strings(c, "SELECT line FROM " + t("la_reference") + " WHERE inv = ? ORDER BY seq", investigationId));
    }

    // ── workflow records ────────────────────────────────────────────────────────────────────────────────

    @Override
    public void bindAlertRule(String investigationId, String rule, String json) throws IOException {
        tx(c -> update(c, "INSERT INTO " + t("la_alert_binding") + " (inv, rule, body) VALUES (?, ?, ?) "
                + "ON CONFLICT (inv, rule) DO UPDATE SET body = EXCLUDED.body", investigationId, rule, json));
    }

    @Override
    public Optional<String> alertRuleBinding(String investigationId, String rule) throws IOException {
        return Optional.ofNullable(tx(c -> one(c, "SELECT body FROM " + t("la_alert_binding") + " WHERE inv = ? AND rule = ?", investigationId, rule)));
    }

    @Override
    public void writePending(String investigationId, String requestId, String json) throws IOException {
        tx(c -> update(c, "INSERT INTO " + t("la_pending") + " (inv, request_id, body) VALUES (?, ?, ?) "
                + "ON CONFLICT (inv, request_id) DO UPDATE SET body = EXCLUDED.body", investigationId, requestId, json));
    }

    @Override
    public Optional<String> pending(String investigationId, String requestId) throws IOException {
        return Optional.ofNullable(tx(c -> one(c, "SELECT body FROM " + t("la_pending") + " WHERE inv = ? AND request_id = ?", investigationId, requestId)));
    }

    @Override
    public List<String> listPending(String investigationId) throws IOException {
        // the filesystem order: shorter request ids first (they are numbered), then by name
        return tx(c -> strings(c, "SELECT body FROM " + t("la_pending") + " WHERE inv = ? ORDER BY length(request_id), request_id COLLATE \"C\"", investigationId));
    }

    @Override
    public void writeCaseLink(String investigationId, String json) throws IOException {
        tx(c -> {
            if (update(c, "UPDATE " + t("la_investigation") + " SET case_link = ? WHERE id = ?", json, investigationId) != 1)
                throw new IOException("no such investigation '" + investigationId + "'");
            return null;
        });
    }

    @Override
    public Optional<String> caseLink(String investigationId) throws IOException {
        return Optional.ofNullable(tx(c -> one(c, "SELECT case_link FROM " + t("la_investigation") + " WHERE id = ?", investigationId)));
    }

    @Override
    public boolean deleteCaseLink(String investigationId) throws IOException {
        return tx(c -> update(c, "UPDATE " + t("la_investigation") + " SET case_link = NULL WHERE id = ? AND case_link IS NOT NULL", investigationId) == 1);
    }

    @Override
    public boolean createTemplate(String id, String json) throws IOException {
        return tx(c -> update(c, "INSERT INTO " + t("la_template") + " (id, body) VALUES (?, ?) ON CONFLICT DO NOTHING", id, json) == 1);
    }

    @Override
    public Optional<String> template(String id) throws IOException {
        return Optional.ofNullable(tx(c -> one(c, "SELECT body FROM " + t("la_template") + " WHERE id = ?", id)));
    }

    @Override
    public List<String> templates() throws IOException {
        return tx(c -> strings(c, "SELECT body FROM " + t("la_template") + " ORDER BY id COLLATE \"C\""));
    }

    @Override
    public boolean replacePending(String investigationId, String requestId, String expectedJson, String newJson) throws IOException {
        // one conditional UPDATE: a second decider waits on the row, re-reads it, no longer matches, and changes nothing
        return tx(c -> update(c, "UPDATE " + t("la_pending") + " SET body = ? WHERE inv = ? AND request_id = ? AND body = ?",
                newJson, investigationId, requestId, expectedJson) == 1);
    }

    @Override
    public byte[] maskKey(String investigationId) throws IOException {
        return tx(c -> {
            byte[] minted = new byte[32];
            RANDOM.nextBytes(minted);
            update(c, "UPDATE " + t("la_investigation") + " SET mask_key = ? WHERE id = ? AND mask_key IS NULL", minted, investigationId);
            try (PreparedStatement ps = bind(c, "SELECT mask_key FROM " + t("la_investigation") + " WHERE id = ?", investigationId);
                 ResultSet rs = ps.executeQuery()) {
                byte[] key = rs.next() ? rs.getBytes(1) : null;
                if (key == null) throw new IOException("no such investigation '" + investigationId + "'");
                return key;
            }
        });
    }

    // ── per-pod cache identity ──────────────────────────────────────────────────────────────────────────

    @Override
    public String cacheKey(Scope scope) {
        return "pg:" + schema + ":" + scope.investigationId() + "/" + draftOf(scope);
    }

    @Override
    public String logToken(Scope scope) throws IOException {
        return tx(c -> {
            if (!scope.isDraft()) return "m:" + count(c, scope.investigationId(), "");
            String v = one(c, "SELECT version || ':' || state FROM " + t("la_draft") + " WHERE inv = ? AND id = ?", scope.investigationId(), scope.draftId());
            return "d:" + (v == null ? "absent" : v);
        });
    }

    // ── Drafts ──────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public DraftCreation createDraft(String investigationId, String draftId, String headerJson, String actor, int spaceCap) throws IOException {
        return tx(c -> {
            one(c, "SELECT one::text FROM " + t("la_space") + " FOR UPDATE");   // the Space-wide cap is checked and the seat taken as one act
            String live = one(c, "SELECT id FROM " + t("la_draft") + " WHERE inv = ? AND actor = ? AND " + LIVE + " ORDER BY id COLLATE \"C\" LIMIT 1",
                    investigationId, actor);
            if (live != null) return new DraftCreation(DraftCreation.Created.ACTOR_HAS_LIVE, live);
            long open = number(c, "SELECT COUNT(*) FROM " + t("la_draft") + " WHERE " + LIVE);
            if (open >= spaceCap) return new DraftCreation(DraftCreation.Created.SPACE_FULL, Long.toString(open));
            if (update(c, "INSERT INTO " + t("la_draft") + " (inv, id, header, actor, state, last_access) VALUES (?, ?, ?, ?, 'open', ?) ON CONFLICT DO NOTHING",
                    investigationId, draftId, headerJson, actor, DraftLifecycle.now()) != 1)
                return new DraftCreation(DraftCreation.Created.ID_TAKEN, draftId);
            return new DraftCreation(DraftCreation.Created.CREATED, draftId);
        });
    }

    @Override
    public Optional<String> draftHeader(String investigationId, String draftId) throws IOException {
        return Optional.ofNullable(tx(c -> one(c, "SELECT header FROM " + t("la_draft") + " WHERE inv = ? AND id = ?", investigationId, draftId)));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> draftHeaders(String investigationId) throws IOException {
        return tx(c -> {
            Map<String, Map<String, Object>> out = new TreeMap<>();
            try (PreparedStatement ps = bind(c, "SELECT id, header FROM " + t("la_draft") + " WHERE inv = ?", investigationId); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> h = new LinkedHashMap<>(InvestigationEvaluator.CANONICAL.readValue(rs.getString(2), Map.class));
                    h.remove("baseLogHash");
                    h.remove("rebases");
                    out.put(rs.getString(1), h);
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
                throw new IOException("a draft header is not JSON", unreadable);
            }
            return out;
        });
    }

    @Override
    public List<String> openDraftIds(String investigationId) {
        return unchecked(() -> tx(c -> strings(c, "SELECT id FROM " + t("la_draft") + " WHERE inv = ? AND " + LIVE + " ORDER BY id COLLATE \"C\"", investigationId)));
    }

    @Override
    public int openDraftCount() {
        return unchecked(() -> tx(c -> (int) number(c, "SELECT COUNT(*) FROM " + t("la_draft") + " WHERE " + LIVE)));
    }

    @Override
    public DraftState draftState(String investigationId, String draftId) {
        String state = unchecked(() -> tx(c -> one(c, "SELECT state FROM " + t("la_draft") + " WHERE inv = ? AND id = ?", investigationId, draftId)));
        return state == null ? DraftState.OPEN : DraftState.valueOf(state.toUpperCase(java.util.Locale.ROOT));
    }

    @Override
    public boolean draftExpired(String investigationId, String draftId) {
        try {
            Optional<String> raw = discardMarker(investigationId, draftId);
            return raw.isPresent() && Boolean.TRUE.equals(InvestigationEvaluator.CANONICAL.readValue(raw.get(), Map.class).get("expired"));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public Optional<String> discardMarker(String investigationId, String draftId) throws IOException {
        return Optional.ofNullable(tx(c -> one(c, "SELECT marker FROM " + t("la_draft") + " WHERE inv = ? AND id = ? AND state = 'discarded'", investigationId, draftId)));
    }

    @Override
    public Optional<String> promoteMarker(String investigationId, String draftId) throws IOException {
        return Optional.ofNullable(tx(c -> one(c, "SELECT marker FROM " + t("la_draft") + " WHERE inv = ? AND id = ? AND state = 'promoted'", investigationId, draftId)));
    }

    @Override
    public Optional<String> draftSet(String investigationId, String draftId, int step) throws IOException {
        return Optional.ofNullable(tx(c -> one(c, "SELECT body FROM " + t("la_set") + " WHERE inv = ? AND draft = ? AND step = ?", investigationId, draftId, step)));
    }

    @Override
    public void touchDraft(String investigationId, String draftId) {
        try {
            Instant now = DraftLifecycle.now();
            tx(c -> update(c, "UPDATE " + t("la_draft") + " SET last_access = ? WHERE inv = ? AND id = ? AND " + LIVE + " AND last_access < ?",
                    now, investigationId, draftId, now.minus(TOUCH_EVERY)));
        } catch (IOException | RuntimeException ignored) {
            // idle accounting only: never fails the read it rides on
        }
    }

    @Override
    public boolean rehydrateDraft(String investigationId, String draftId) throws IOException {
        return tx(c -> update(c, "UPDATE " + t("la_draft") + " SET state = 'open' WHERE inv = ? AND id = ? AND state = 'hibernated'", investigationId, draftId) == 1);
    }

    @Override
    public Instant draftLastAccess(String investigationId, String draftId) {
        try {
            Instant at = tx(c -> {
                try (PreparedStatement ps = bind(c, "SELECT last_access FROM " + t("la_draft") + " WHERE inv = ? AND id = ?", investigationId, draftId);
                     ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getObject(1, OffsetDateTime.class).toInstant() : null;
                }
            });
            return at != null ? at : DraftLifecycle.now();
        } catch (IOException | RuntimeException unreadable) {
            return DraftLifecycle.now();   // unknown idleness is not evidence of idleness: never expire on a read failure
        }
    }

    @Override
    public Duration draftIdle(String investigationId, String draftId) {
        Duration d = Duration.between(draftLastAccess(investigationId, draftId), DraftLifecycle.now());
        return d.isNegative() ? Duration.ZERO : d;
    }

    @Override
    public boolean hibernateDraft(String investigationId, String draftId, Duration after) throws IOException {
        return tx(c -> {
            String state = lockDraft(c, investigationId, draftId);
            if (state == null || closed(state) || draftIdle(c, investigationId, draftId).compareTo(after) < 0) return false;
            return update(c, "UPDATE " + t("la_draft") + " SET state = 'hibernated' WHERE inv = ? AND id = ? AND state = 'open'", investigationId, draftId) == 1;
        });
    }

    private Duration draftIdle(Connection c, String inv, String draft) throws SQLException {
        try (PreparedStatement ps = bind(c, "SELECT last_access FROM " + t("la_draft") + " WHERE inv = ? AND id = ?", inv, draft); ResultSet rs = ps.executeQuery()) {
            Duration d = rs.next() ? Duration.between(rs.getObject(1, OffsetDateTime.class).toInstant(), DraftLifecycle.now()) : Duration.ZERO;
            return d.isNegative() ? Duration.ZERO : d;
        }
    }

    @Override
    public Optional<Boolean> closeDraft(String investigationId, String draftId, Duration idleAtLeast, Function<List<String>, String> marker) throws IOException {
        return tx(c -> {
            one(c, "SELECT id FROM " + t("la_investigation") + " WHERE id = ? FOR NO KEY UPDATE", investigationId);   // the counter's lock first (promote's order)
            String state = lockDraft(c, investigationId, draftId);
            if (state == null || "promoted".equals(state)) return Optional.empty();
            if (idleAtLeast != null && (closed(state) || draftIdle(c, investigationId, draftId).compareTo(idleAtLeast) < 0)) return Optional.empty();
            if ("discarded".equals(state)) return Optional.of(false);   // already discarded: idempotent
            String text = marker.apply(lines(c, investigationId, draftId));
            update(c, "UPDATE " + t("la_draft") + " SET state = 'discarded', marker = ?, version = version + 1 WHERE inv = ? AND id = ?", text, investigationId, draftId);
            setTotal(c, investigationId);
            deleteEvidence(c, investigationId, draftId);   // the marker first, then the sealed rows go: one transaction
            return Optional.of(true);
        });
    }

    /** Delete a Draft's rows and take their bytes off the Investigation's set counter (the caller has called {@link #setTotal} in this transaction). */
    private void deleteEvidence(Connection c, String inv, String draft) throws SQLException {
        long freed = draftSetBytes(c, inv, draft);
        update(c, "DELETE FROM " + t("la_log") + " WHERE inv = ? AND draft = ?", inv, draft);
        update(c, "DELETE FROM " + t("la_set") + " WHERE inv = ? AND draft = ?", inv, draft);
        adjustSetTotal(c, inv, -freed);
    }

    /** Both logs still hold what the caller computed over (the same two checks the filesystem store makes under its two monitors). */
    private void verifyPreconditions(Connection c, String inv, String draft, long expectedMainVersion, String expectedMainHash, String expectedDraftLogHash)
            throws SQLException {
        List<String> main = lines(c, inv, "");
        if (main.size() != expectedMainVersion || !DraftStore.prefixHash(main, main.size()).equals(expectedMainHash))
            throw new InvestigationVersionConflictException(inv, expectedMainVersion, main.size());
        List<String> own = lines(c, inv, draft);
        if (!DraftStore.prefixHash(own, own.size()).equals(expectedDraftLogHash))
            throw new InvestigationVersionConflictException(inv, own.size(), own.size());
    }

    @Override
    public void promoteDraft(String investigationId, String draftId, long expectedMainVersion, String expectedMainHash, String expectedDraftLogHash,
                             List<String> lines, List<String> sets, String markerJson) throws IOException {
        com.gamma.la.core.WorkingSetSizeLimit.enforceAll(maxSetBytes, investigationId, (int) expectedMainVersion + 1, sets);
        tx(c -> {
            lockInvestigation(c, investigationId);   // the main log, THEN the Draft: the one order everywhere
            String state = lockDraft(c, investigationId, draftId);
            if (state == null) throw new IOException("no such draft '" + draftId + "'");
            if (closed(state)) throw new DraftClosedException(draftId);
            verifyPreconditions(c, investigationId, draftId, expectedMainVersion, expectedMainHash, expectedDraftLogHash);
            int from = (int) expectedMainVersion;
            long total = setTotal(c, investigationId), freed = draftSetBytes(c, investigationId, draftId), added = 0;
            for (int i = 0; i < lines.size(); i++)   // a null entry seals the Draft's own set as it is: same bytes, main's now
                added += sets.get(i) != null ? com.gamma.la.core.InvestigationSetBudget.bytes(sets.get(i))
                        : number(c, "SELECT COALESCE(SUM(octet_length(body)), 0) FROM " + t("la_set") + " WHERE inv = ? AND draft = ? AND step = ?",
                                investigationId, draftId, from + i + 1);
            // the Draft's sets are removed by this same promote, so the total it leaves is net of them
            com.gamma.la.core.InvestigationSetBudget.enforce(maxInvestigationBytes, investigationId, total, total - freed + added);
            insertLines(c, investigationId, "", from, lines);
            for (int i = 0; i < lines.size(); i++) {
                int step = from + i + 1;
                if (sets.get(i) == null) {   // seal the Draft's own set as it is: a server-side copy, same bytes (D-IS12 a)
                    if (update(c, "INSERT INTO " + t("la_set") + " (inv, draft, step, body) SELECT inv, '', step, body FROM " + t("la_set")
                            + " WHERE inv = ? AND draft = ? AND step = ?", investigationId, draftId, step) != 1)
                        throw new IOException("draft '" + draftId + "' has no sealed set for step " + step);
                } else {
                    update(c, "INSERT INTO " + t("la_set") + " (inv, draft, step, body) VALUES (?, '', ?, ?)", investigationId, step, sets.get(i));
                }
            }
            adjustSetTotal(c, investigationId, added);
            update(c, "UPDATE " + t("la_draft") + " SET state = 'promoted', marker = ?, version = version + 1 WHERE inv = ? AND id = ?", markerJson, investigationId, draftId);
            deleteEvidence(c, investigationId, draftId);
            return null;   // one commit: the main log never holds a part of a promote
        });
    }

    @Override
    public void replaceDraft(String investigationId, String draftId, long expectedMainVersion, String expectedMainHash, String expectedDraftLogHash,
                             String headerJson, List<String> lines, List<String> sets, List<Integer> setSteps) throws IOException {
        for (int i = 0; i < sets.size(); i++)
            com.gamma.la.core.WorkingSetSizeLimit.enforce(maxSetBytes, investigationId, setSteps.get(i), sets.get(i));
        tx(c -> {
            lockInvestigation(c, investigationId);
            String state = lockDraft(c, investigationId, draftId);
            if (state == null) throw new IOException("no such draft '" + draftId + "'");
            if (closed(state)) throw new DraftClosedException(draftId);
            verifyPreconditions(c, investigationId, draftId, expectedMainVersion, expectedMainHash, expectedDraftLogHash);
            long total = setTotal(c, investigationId), added = com.gamma.la.core.InvestigationSetBudget.bytes(sets);
            // the old Draft sets are replaced, not added to: the total it leaves is net of them
            com.gamma.la.core.InvestigationSetBudget.enforce(maxInvestigationBytes, investigationId, total, total - draftSetBytes(c, investigationId, draftId) + added);
            deleteEvidence(c, investigationId, draftId);
            insertLines(c, investigationId, draftId, 0, lines);
            for (int i = 0; i < sets.size(); i++)
                update(c, "INSERT INTO " + t("la_set") + " (inv, draft, step, body) VALUES (?, ?, ?, ?)", investigationId, draftId, setSteps.get(i), sets.get(i));
            adjustSetTotal(c, investigationId, added);
            update(c, "UPDATE " + t("la_draft") + " SET header = ?, version = version + 1 WHERE inv = ? AND id = ?", headerJson, investigationId, draftId);
            return null;
        });
    }

    /** A transaction cannot leave a partial promote or a stray scratch directory, so there is nothing to recover. */
    @Override
    public void recoverDrafts(String investigationId) { }

    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    private static <T> T unchecked(IoSupplier<T> body) {
        try {
            return body.get();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
