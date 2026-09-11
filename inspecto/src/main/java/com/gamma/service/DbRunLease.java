package com.gamma.service;

import com.gamma.util.JdbcDrivers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A {@link RunLease} backed by one row per {@code (space, pipeline)} with a TTL — phase B of the
 * enterprise scale-out plan (§5.2), the thing that makes "one run per pipeline per trigger" true across
 * N processes instead of within one JVM.
 *
 * <h3>Why a TTL row and ⛔ not a Postgres advisory lock</h3>
 * D5 refused advisory locks and the refusal is load-bearing: an advisory lock dies with its connection,
 * and phase A's connection pool recycles connections. <b>A lease must outlive the connection that took
 * it.</b> A row with an expiry does; a session-scoped lock does not.
 *
 * <h3>🔴 Fencing — and why it works HERE when it could not for Consignments</h3>
 * A TTL alone does not prevent split-brain, it makes it unlikely: a pod paused past its TTL wakes up
 * still believing it holds the lease. The fix is a <b>fencing token</b>, and this class carries one — a
 * monotonically increasing {@code epoch}, bumped on every successful acquisition. Every write this class
 * makes is conditional on {@code owner = me AND epoch = mine}, so:
 * <ul>
 *   <li>a stale owner's {@code close()} <b>cannot release</b> a lease someone else now holds, and</li>
 *   <li>a stale owner's heartbeat <b>cannot extend</b> it either.</li>
 * </ul>
 *
 * <p>⚠ This is the half of D15 that survives its refutation. D15 asked for fencing over "the existing
 * claim surfaces", and that is impossible for Consignment writes because {@code batchId} is the wall
 * clock at second granularity — there is no stable id to fence on
 * ({@code CONSIGNMENT-ID-DETERMINISTIC-1}). A lease is different: {@code (space, pipeline)} <b>is</b>
 * stable, so the token has something to be validated against. ⛔ Do not read this class as discharging
 * D15 — it fences the lease, not the writes a run performs.
 *
 * <h3>The heartbeat is not optional</h3>
 * The TTL is how long before another pod may steal the lease. A run longer than the TTL that did not
 * renew would be stolen mid-flight — a double run, the exact thing this prevents. One daemon thread
 * renews every held claim at a third of the TTL. ⛔ Do not remove it and raise the TTL instead: a long
 * TTL is also how long a genuinely dead pod's pipelines stay frozen.
 *
 * @since 5.x
 */
final class DbRunLease implements RunLease, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DbRunLease.class);

    static final String TABLE = "inspecto_run_lease";
    /** Default TTL. Long enough that a renew failure is a real problem, short enough to free a dead pod. */
    static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private final Connection conn;
    private final String space;
    private final String owner;
    private final long ttlMs;
    /** Pipelines this process currently holds → the epoch it holds them at (its fencing token). */
    private final Map<String, Long> held = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat;

    DbRunLease(Connection conn, String space, String owner, Duration ttl) {
        this.conn = conn;
        this.space = space;
        this.owner = owner;
        this.ttlMs = Math.max(1_000L, ttl.toMillis());
        initSchema();
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "run-lease-heartbeat-" + space);
            t.setDaemon(true);      // ⛔ daemon: a lease renewer must never hold the JVM open
            return t;
        });
        long every = Math.max(200L, ttlMs / 3);
        heartbeat.scheduleAtFixedRate(this::renewAll, every, every, TimeUnit.MILLISECONDS);
    }

    /** Open a lease over {@code url}. {@code owner} defaults to a per-process id when null. */
    static DbRunLease open(String url, String user, String pass, String space, String owner, Duration ttl)
            throws SQLException {
        return new DbRunLease(JdbcDrivers.connect(url, user, pass), space,
                owner == null || owner.isBlank() ? defaultOwner() : owner, ttl);
    }

    /** A stable-per-process owner id: the host's pod name when set, else a random one. */
    static String defaultOwner() {
        String pod = System.getenv("HOSTNAME");
        return (pod == null || pod.isBlank() ? "owner" : pod) + "-" + UUID.randomUUID();
    }

    // ── acquire / release ───────────────────────────────────────────────────────────

    @Override
    public synchronized Claim tryAcquire(String pipeline) {
        // Already ours in THIS process: a claim is not reentrant, so this is a refusal, not a re-grant.
        if (held.containsKey(pipeline)) return null;
        long now = System.currentTimeMillis();
        try {
            insertIfAbsent(pipeline);
            // The one statement that decides it. Wins only when the row is free or its lease has expired;
            // the epoch bump is what makes the previous holder's token stale the instant we take over.
            String sql = "UPDATE " + TABLE + " SET owner = ?, epoch = epoch + 1, acquired_at = ?, expires_at = ? "
                    + "WHERE space = ? AND pipeline = ? AND (owner IS NULL OR expires_at < ?)";
            int won;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, owner);
                ps.setLong(2, now);
                ps.setLong(3, now + ttlMs);
                ps.setString(4, space);
                ps.setString(5, pipeline);
                ps.setLong(6, now);
                won = ps.executeUpdate();
            }
            if (won == 0) return null;
            long epoch = readEpoch(pipeline);
            held.put(pipeline, epoch);
            return claimOf(pipeline, epoch);
        } catch (SQLException e) {
            // ⛔ Fail CLOSED. An unreachable lease table must not be read as "nobody holds it" — that is
            // precisely the split-brain this exists to prevent. Refusing the run is the safe direction.
            log.warn("Run lease unavailable for '{}' — refusing the run rather than risking a double: {}",
                    pipeline, e.getMessage());
            return null;
        }
    }

    /**
     * Block until {@code pipeline} is free.
     *
     * <p>⚠ Bounded, unlike the heap guard's {@code acquireUninterruptibly}. Across processes an unbounded
     * wait would hang on a holder that no longer exists — but it cannot, because a lease EXPIRES: a dead
     * holder's grip lapses within the TTL, so polling terminates on its own. The bound is generous
     * (twice the TTL) and exceeding it means a live holder is genuinely still running.
     */
    @Override
    public Claim acquire(String pipeline) {
        long deadline = System.currentTimeMillis() + 2 * ttlMs;
        while (true) {
            Claim c = tryAcquire(pipeline);
            if (c != null) return c;
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("could not acquire the run lease for '" + pipeline
                        + "' within " + (2 * ttlMs) + "ms — another process is still running it");
            }
            try {
                Thread.sleep(Math.min(250L, ttlMs / 10));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for the run lease for '" + pipeline + "'", ie);
            }
        }
    }

    private Claim claimOf(String pipeline, long epoch) {
        return new Claim() {
            private volatile boolean released;

            @Override
            public void close() {
                // Idempotent for the same reason the heap guard's is: a second release would hand the
                // pipeline to a second runner.
                if (released) return;
                released = true;
                held.remove(pipeline);
                release(pipeline, epoch);
            }

            @Override
            public String toString() {
                return "Claim[" + space + '/' + pipeline + "@" + epoch + ']';
            }
        };
    }

    /**
     * Release, <b>fenced</b>: the {@code epoch} predicate is what stops a paused owner that woke up late
     * from releasing a lease another process has since taken. Without it, a TTL lease is a suggestion.
     */
    private void release(String pipeline, long epoch) {
        String sql = "UPDATE " + TABLE + " SET owner = NULL, expires_at = 0 "
                + "WHERE space = ? AND pipeline = ? AND owner = ? AND epoch = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, space);
            ps.setString(2, pipeline);
            ps.setString(3, owner);
            ps.setLong(4, epoch);
            if (ps.executeUpdate() == 0) {
                // Not an error: it means we no longer held it. Worth a line, because it is the observable
                // symptom of this process having been paused past its TTL.
                log.warn("Run lease for '{}' was no longer ours at release (epoch {}) — it had been taken "
                        + "over; the fencing predicate refused a release that would have freed another "
                        + "owner's lease", pipeline, epoch);
            }
        } catch (SQLException e) {
            log.warn("Could not release the run lease for '{}': {} — it will expire in {}ms",
                    pipeline, e.getMessage(), ttlMs);
        }
    }

    /** Extend every claim this process holds. Fenced identically: a stale owner cannot extend. */
    private void renewAll() {
        if (held.isEmpty()) return;
        long until = System.currentTimeMillis() + ttlMs;
        String sql = "UPDATE " + TABLE + " SET expires_at = ? "
                + "WHERE space = ? AND pipeline = ? AND owner = ? AND epoch = ?";
        for (Map.Entry<String, Long> e : held.entrySet()) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, until);
                ps.setString(2, space);
                ps.setString(3, e.getKey());
                ps.setString(4, owner);
                ps.setLong(5, e.getValue());
                if (ps.executeUpdate() == 0) {
                    log.warn("Run lease for '{}' was stolen while we still believed we held it (epoch {}) "
                            + "— this process was paused or partitioned for longer than the {}ms TTL",
                            e.getKey(), e.getValue(), ttlMs);
                }
            } catch (SQLException ex) {
                log.warn("Could not renew the run lease for '{}': {}", e.getKey(), ex.getMessage());
            }
        }
    }

    // ── diagnostics ─────────────────────────────────────────────────────────────────

    @Override
    public synchronized boolean isRunning(String pipeline) {
        String sql = "SELECT owner, expires_at FROM " + TABLE + " WHERE space = ? AND pipeline = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, space);
            ps.setString(2, pipeline);
            try (ResultSet rs = ps.executeQuery()) {
                // An EXPIRED lease is not running: the holder is gone and the row is merely stale.
                return rs.next() && rs.getString("owner") != null
                        && rs.getLong("expires_at") >= System.currentTimeMillis();
            }
        } catch (SQLException e) {
            log.warn("Could not read the run lease for '{}': {}", pipeline, e.getMessage());
            return false;
        }
    }

    /**
     * ⛔ Deliberately a no-op on the row. Unlike the heap guard — whose {@code forget} stops an in-memory
     * map growing — deleting the row would discard another pod's live lease when THIS pod happens to
     * unregister the pipeline. The row is small, bounded by the pipeline count, and an expired one is
     * already ignored by every read.
     */
    @Override
    public void forget(String pipeline) {
        held.remove(pipeline);
    }

    @Override
    public void close() {
        heartbeat.shutdownNow();
        try {
            conn.close();
        } catch (SQLException e) {
            log.warn("Error closing run-lease DB connection: {}", e.getMessage());
        }
    }

    // ── schema ──────────────────────────────────────────────────────────────────────

    private void insertIfAbsent(String pipeline) throws SQLException {
        // The DbDedupLedger idiom: let the database resolve the race rather than a read-then-write.
        String sql = "INSERT INTO " + TABLE + " (space, pipeline, owner, epoch, acquired_at, expires_at) "
                + "VALUES (?,?,NULL,0,0,0) ON CONFLICT DO NOTHING";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, space);
            ps.setString(2, pipeline);
            ps.executeUpdate();
        }
    }

    private long readEpoch(String pipeline) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT epoch FROM " + TABLE + " WHERE space = ? AND pipeline = ?")) {
            ps.setString(1, space);
            ps.setString(2, pipeline);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private void initSchema() {
        try (Statement st = conn.createStatement()) {
            // ⚠ PRIMARY KEY (space, pipeline) — NOT pipeline alone. A pipeline id is unique only within
            // a Space; keyed on the id alone, two Spaces running an `orders` pipeline would share one
            // lease and each would block the other. See RunLease's class note.
            st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "space VARCHAR, pipeline VARCHAR, owner VARCHAR, epoch BIGINT, "
                    + "acquired_at BIGINT, expires_at BIGINT, "
                    + "PRIMARY KEY (space, pipeline))");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialise the run-lease schema", e);
        }
    }
}
