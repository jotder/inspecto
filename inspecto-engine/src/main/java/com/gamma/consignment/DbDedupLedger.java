package com.gamma.consignment;

import com.gamma.api.PublicApi;
import com.gamma.util.JdbcDrivers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>D-9 — the cross-Consignment windowed record-dedup ledger.</b> Answers "have I already admitted this
 * business key inside the declared window?" across Consignments, which
 * {@code RowShaper.dedup}'s {@code QUALIFY ROW_NUMBER()} cannot: that dedups within ONE Consignment, in
 * one DuckDB connection, with no cross-run state.
 *
 * <p>Mirrors {@link DbConsignmentOutputStore}/{@link DbFileStageStore}: plain JDBC over the bundled
 * DuckDB engine, one shared {@link Connection}, schema created on open, every mutator
 * {@code synchronized}.
 *
 * <h3>The three answers D8 required before this could return to the board</h3>
 * <ul>
 *   <li><b>Where it persists</b> — its own {@code OperationalDb.Family}, per-space, default {@code duckdb}.
 *       ⛔ NOT the {@code consignment_outputs} registry (file-grained, no business-key column) and NOT
 *       {@code CommitLog} (per-batch, no day column).</li>
 *   <li><b>Winner policy</b> — <b>first COMMITTED wins</b>, enforced by the {@code (pipeline, key_hash,
 *       window_start)} primary key: {@link #claim} inserts and reports only the rows it actually won.</li>
 *   <li><b>Window advance</b> — {@link #prune} drops whole elapsed windows, driven by a maintenance task
 *       and aged by the record's own <b>event time</b>, never file mtime.</li>
 * </ul>
 *
 * <h3>Two decisions that are load-bearing, both operator-approved 2026-09-01</h3>
 * <ul>
 *   <li>🔴 <b>Keys are HASHED, never stored verbatim.</b> A business key is customer data (an MSISDN, an
 *       account id); a durable operational table full of them is a data-protection surface none of the
 *       other ledgers has. {@link #hash} is SHA-256 over the joined key values, so the ledger can answer
 *       "seen before" without ever holding the value. ⚠ It follows that this table cannot tell you WHICH
 *       key collided — that is the deliberate cost, and the reject stream still carries the real rows.</li>
 *   <li>🔴 <b>Insert-wins on the primary key, not read-then-write.</b> Two Consignments ingesting
 *       concurrently both consult this ledger, and nothing in the ingest path holds a cross-batch lock. A
 *       check-then-insert would let both see "absent" and both admit the row. Inserting first and keeping
 *       only what the database accepted makes "first seen" mean "first committed" — the only definition
 *       two racing batches can agree on without a lock.</li>
 * </ul>
 *
 * <h3>🔴 Reprocess must retract, or re-ingested rows vanish forever</h3>
 * A reprocess is a WHOLE-CONSIGNMENT supersede-and-re-ingest under a fresh batch id
 * ({@code ReprocessCommand}: delete outputs, restore members, {@code registry.supersede(batchId)}, poll
 * again). There is no row-level retraction anywhere in the system. So a ledger keyed only on "have I seen
 * this key" would answer <em>yes</em> to every re-ingested row and drop the lot — silently and
 * permanently. Every row therefore carries its producing {@code consignment_id}, and {@link #retract}
 * removes that Consignment's claims so a reprocess can re-admit them.
 */
@PublicApi(since = "4.0.0")
public final class DbDedupLedger implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DbDedupLedger.class);

    /** One row per (pipeline, hashed key, window). The PK is what makes the claim atomic. */
    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS inspecto_dedup_keys (
                pipeline        VARCHAR NOT NULL,
                key_hash        VARCHAR NOT NULL,
                window_start    DATE    NOT NULL,
                consignment_id  VARCHAR NOT NULL,
                first_seen      TIMESTAMP NOT NULL,
                PRIMARY KEY (pipeline, key_hash, window_start)
            )""";

    /** ASCII unit separator — see {@link #hash}. Cannot occur in a parsed field value. */
    private static final String SEP = "\u001f";

    private final Connection conn;

    public DbDedupLedger(String jdbcUrl) throws SQLException {
        this(JdbcDrivers.connect(jdbcUrl));
    }

    /** Test/embedder seam: bring your own connection (an in-memory DuckDB, typically). */
    public DbDedupLedger(Connection conn) throws SQLException {
        this.conn = conn;
        try (Statement st = conn.createStatement()) {
            st.execute(DDL);
        }
        log.info("[DEDUP] ledger open");
    }

    /**
     * SHA-256 of the key values, joined by a separator that cannot occur in a value read from a column.
     *
     * <p>⚠ The separator matters: joining with something a value could contain would let
     * {@code ["a","bc"]} and {@code ["ab","c"]} hash alike and silently dedup two different records
     * against each other. {@code \\u001f} (ASCII unit separator) is the conventional choice and cannot
     * appear in a parsed field value.
     */
    public static String hash(List<String> keyValues) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(String.join(SEP, keyValues).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JDK and is missing", e);
        }
    }

    /**
     * Claim {@code keyHashes} for {@code pipeline} in the window starting {@code windowStart}, on behalf
     * of {@code consignmentId}. Returns <b>only the hashes this call actually won</b> — the ones inserted.
     * A hash already present (this window, this pipeline) is a duplicate and is absent from the result.
     *
     * <p>The insert is {@code ON CONFLICT DO NOTHING}, so a concurrent claim of the same key resolves in
     * the database rather than in a race between two reads.
     */
    public synchronized Set<String> claim(String pipeline, java.time.LocalDate windowStart,
                                          String consignmentId, List<String> keyHashes) throws SQLException {
        Set<String> won = new LinkedHashSet<>();
        if (keyHashes.isEmpty()) return won;
        String sql = "INSERT INTO inspecto_dedup_keys "
                + "(pipeline, key_hash, window_start, consignment_id, first_seen) "
                + "VALUES (?, ?, ?, ?, now()) ON CONFLICT DO NOTHING";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String h : keyHashes) {
                ps.setString(1, pipeline);
                ps.setString(2, h);
                ps.setObject(3, windowStart);
                ps.setString(4, consignmentId);
                if (ps.executeUpdate() == 1) won.add(h);
            }
        }
        return won;
    }

    /**
     * Drop every claim made by {@code consignmentId} — the reprocess path. Returns the row count, so a
     * caller can log that a supersede actually released something rather than assuming it did.
     */
    public synchronized int retract(String consignmentId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM inspecto_dedup_keys WHERE consignment_id = ?")) {
            ps.setString(1, consignmentId);
            return ps.executeUpdate();
        }
    }

    /**
     * Advance the window: drop every claim whose window started before {@code cutoff}.
     *
     * <p>⚠ Aged by the record's own <b>event time</b> (the window it was filed under), never by file
     * mtime — a late-arriving file must not evict keys that are still inside the declared window.
     */
    public synchronized int prune(java.time.LocalDate cutoff) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM inspecto_dedup_keys WHERE window_start < ?")) {
            ps.setObject(1, cutoff);
            return ps.executeUpdate();
        }
    }

    /** Rows currently held, for tests and the ops surface. */
    public synchronized long size() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM inspecto_dedup_keys")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /** The Consignments holding claims, newest window first — the ops answer to "who owns these keys". */
    public synchronized List<String> claimants() throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT DISTINCT consignment_id FROM inspecto_dedup_keys ORDER BY consignment_id")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            log.warn("[DEDUP] closing the ledger failed: {}", e.toString());
        }
    }
}
