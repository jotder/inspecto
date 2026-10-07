package com.gamma.pipeline.exec;

import com.gamma.etl.TypeFlow;
import com.gamma.event.EventLog;
import com.gamma.job.PlatformServices;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineNodeType;
import com.gamma.pipeline.PipelineNodeTypes;
import com.gamma.pipeline.PipelineRel;
import com.gamma.signal.Ref;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import com.gamma.signal.SignalEmitter;
import com.gamma.util.RunLog;
import com.gamma.util.SqlIdent;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs one contributed {@link StepExecutor} node (S2-3): the failure grain and the watchdog.
 *
 * <ul>
 *   <li><b>Watchdog.</b> {@code execute} runs on its own virtual thread with a deadline: the kind's
 *       {@link StepExecutor#timeout()}, overridden by the node's {@code timeout_seconds}, capped by
 *       {@code -Dpipeline.step.timeoutCeilingSeconds} (default 1800). On expiry the context closes, the
 *       engine-held input statement is cancelled and the thread is interrupted. A thread still alive after
 *       {@link #ABANDON_GRACE} is <b>abandoned</b>: it keeps its pack pinned while it lives, its kind is
 *       disabled (D-7), and a CRITICAL {@code pipeline.step.abandoned} Signal says so. This JVM cannot kill a
 *       pure-Java loop that ignores interrupts; the engine does not claim to.</li>
 *   <li><b>Failure grain.</b> Any throw, or a timeout, drops every table the Step created and fails the
 *       batch with a {@link StepFailure}. No node is ever half-applied.</li>
 *   <li><b>Relations.</b> After a clean run, every declared {@code data} / {@code reject:*} relation the Step
 *       did not emit to is created empty with the input's columns, so downstream nodes and provenance see a
 *       zero count instead of a missing branch.</li>
 * </ul>
 */
final class StepRunner {

    /** The per-kind default deadline (D-7). */
    static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
    /** The system ceiling on any deadline (D-7), unless {@code -Dpipeline.step.timeoutCeilingSeconds} says otherwise. */
    static final long DEFAULT_CEILING_SECONDS = 1800;
    /** How long a timed-out Step gets to stop after the interrupt before it is abandoned. */
    static final Duration ABANDON_GRACE = Duration.ofSeconds(1);

    private static final Logger LOG = LoggerFactory.getLogger("com.gamma.pipeline.step");

    /** Column types a Step can append, and so can declare on an explicit {@link StepContext#emit(String, List)}. */
    private static final Set<String> DECLARABLE =
            Set.of("BOOLEAN", "INTEGER", "BIGINT", "DOUBLE", "VARCHAR", "DATE", "TIMESTAMP");

    private StepRunner() {}

    static List<RowShaper.Relation> run(Connection conn, PipelineNode node, String input, String outPrefix,
                                        StepExecutors.Registration reg, RowShaper.ExecutionContext exec)
            throws SQLException {
        String type = node.type();
        String disabled = StepExecutors.disabledReason(type).orElse(null);
        if (disabled != null)
            throw new StepFailure(StepFailure.STEP_DISABLED, "Step kind '" + type + "' is disabled until its pack "
                    + "is replaced: " + disabled, null);
        Duration deadline = deadline(reg.executor(), node);
        Ctx ctx = new Ctx(conn, node, input, outPrefix, reg, exec);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().name("step-" + node.id()).unstarted(() -> {
            // The thread holds its own pack lease for as long as it lives — so an ABANDONED Step keeps its
            // classloader open after the walk has moved on.
            // ⚠ A DuckDB appender is confined to the thread that created it — closed from any other thread it
            // refuses to flush — so this thread, which opened them, also closes them.
            try (PackRunLeases.Lease lease = PackRunLeases.acquire(reg.owner())) {
                reg.executor().execute(ctx);
                ctx.closeOutputs();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                ctx.closeOutputsQuietly();
                done.countDown();
            }
        });
        worker.start();

        boolean finished;
        try {
            finished = done.await(deadline.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            stop(ctx, worker, done);
            ctx.discard();
            throw new StepFailure(StepFailure.STEP_FAILED, where(node) + " was interrupted", ie);
        }
        if (!finished) {
            boolean stopped = stop(ctx, worker, done);
            if (!stopped) abandon(node, exec, deadline);
            ctx.discard();
            throw new StepFailure(StepFailure.STEP_TIMEOUT, where(node) + " exceeded its " + deadline
                    + " deadline" + (stopped ? "" : " and ignored the interrupt; its thread was abandoned and the kind disabled"),
                    null);
        }
        Throwable t = thrown.get();
        if (t != null) {
            ctx.discard();
            if (t instanceof VirtualMachineError vme) throw vme;
            throw new StepFailure(StepFailure.STEP_FAILED, where(node) + " threw " + t, t);
        }
        try {
            return ctx.finish();
        } catch (SQLException | RuntimeException e) {
            ctx.discard();
            throw new StepFailure(StepFailure.STEP_FAILED, where(node) + " could not complete its output: " + e, e);
        }
    }

    /** The node's deadline: the kind's default, the node's {@code timeout_seconds} override, then the ceiling. */
    static Duration deadline(StepExecutor executor, PipelineNode node) {
        Duration d = executor.timeout();
        Object raw = node.cfg("timeout_seconds");
        if (raw != null) {
            double seconds;
            try {
                seconds = raw instanceof Number n ? n.doubleValue() : Double.parseDouble(raw.toString().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("node '" + node.id() + "': timeout_seconds '" + raw + "' is not a number");
            }
            if (!(seconds > 0))
                throw new IllegalArgumentException("node '" + node.id() + "': timeout_seconds must be positive, was " + raw);
            d = Duration.ofNanos((long) (seconds * 1e9));
        }
        if (d == null || d.isNegative() || d.isZero()) d = DEFAULT_TIMEOUT;
        Duration ceiling = Duration.ofSeconds(Long.getLong("pipeline.step.timeoutCeilingSeconds", DEFAULT_CEILING_SECONDS));
        return d.compareTo(ceiling) > 0 ? ceiling : d;
    }

    /** Close the context, cancel its statement, interrupt the thread; whether it ended within the grace. */
    private static boolean stop(Ctx ctx, Thread worker, CountDownLatch done) {
        ctx.close();
        ctx.cancel();
        worker.interrupt();
        try {
            return done.await(ABANDON_GRACE.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void abandon(PipelineNode node, RowShaper.ExecutionContext exec, Duration deadline) {
        String reason = "a run of node '" + node.id() + "' ignored the interrupt after its " + deadline + " deadline";
        StepExecutors.disable(node.type(), reason);
        LOG.error("[STEP] {} — thread abandoned, kind '{}' disabled until its pack is replaced", reason, node.type());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("node", node.id());
        payload.put("type", node.type());
        payload.put("deadline", deadline.toString());
        if (exec.pipeline() != null) payload.put("pipeline", exec.pipeline());
        EventLog.current().emit(new Signal(null, "pipeline.step.abandoned", null, Severity.CRITICAL,
                Ref.of("step", node.type()), null, exec.consignmentId(), null, null, null,
                "Step '" + node.type() + "' abandoned: " + reason + "; the kind is disabled until its pack is replaced",
                payload, 1).toEvent());
    }

    private static String where(PipelineNode node) {
        return "Step '" + node.id() + "' (" + node.type() + ")";
    }

    // ── the context ──────────────────────────────────────────────────────────────────────────────

    /** Column kinds a {@link StepOutput#copyRow} can carry across. */
    private enum Kind { BOOLEAN, INTEGER, BIGINT, DOUBLE, VARCHAR, DATE, TIMESTAMP, DECIMAL, OTHER }

    private static Kind kindOf(String duckType) {
        String t = duckType.toUpperCase(java.util.Locale.ROOT);
        if (t.startsWith("DECIMAL")) return Kind.DECIMAL;
        return switch (t) {
            case "BOOLEAN" -> Kind.BOOLEAN;
            case "INTEGER" -> Kind.INTEGER;
            case "BIGINT" -> Kind.BIGINT;
            case "DOUBLE" -> Kind.DOUBLE;
            case "VARCHAR" -> Kind.VARCHAR;
            case "DATE" -> Kind.DATE;
            case "TIMESTAMP" -> Kind.TIMESTAMP;
            default -> Kind.OTHER;
        };
    }

    private static final class Ctx implements StepContext {
        private final Connection conn;
        private final PipelineNode node;
        private final String input;
        private final String outPrefix;
        private final boolean dryRun;
        private final String correlationId;
        private final List<TypeFlow.Column> schema;
        private final Kind[] kinds;
        private final RunLog log;
        private final PlatformServices services;
        private final Map<String, Out> outputs = new LinkedHashMap<>();
        private final List<String> created = new ArrayList<>();
        private volatile boolean closed;
        private volatile PreparedStatement statement;
        private In in;

        Ctx(Connection conn, PipelineNode node, String input, String outPrefix,
            StepExecutors.Registration reg, RowShaper.ExecutionContext exec) throws SQLException {
            this.conn = conn;
            this.node = node;
            this.input = input;
            this.outPrefix = outPrefix;
            this.dryRun = exec.dryRun();
            this.correlationId = exec.consignmentId();
            this.schema = describe(conn, input);
            this.kinds = new Kind[schema.size()];
            for (int i = 0; i < kinds.length; i++) kinds[i] = kindOf(schema.get(i).type());
            this.log = new StepLog(node);
            this.services = reg.grant().services(dryRun, log);
        }

        private static List<TypeFlow.Column> describe(Connection conn, String table) throws SQLException {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM " + SqlIdent.q(table) + " LIMIT 0")) {
                ResultSetMetaData md = rs.getMetaData();
                List<TypeFlow.Column> cols = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++)
                    cols.add(new TypeFlow.Column(md.getColumnLabel(i), md.getColumnTypeName(i)));
                return List.copyOf(cols);
            }
        }

        private void open() {
            if (closed) throw new IllegalStateException("the Step context for node '" + node.id() + "' is closed");
        }

        @Override public String nodeId() { return node.id(); }
        @Override public Map<String, Object> attributes() { return node.config(); }
        @Override public List<TypeFlow.Column> schema() { return schema; }
        @Override public RunLog log() { return log; }
        @Override public boolean dryRun() { return dryRun; }
        @Override public PlatformServices services() { return services; }

        @Override
        public synchronized StepInput in() throws SQLException {
            open();
            if (in == null) {
                PreparedStatement ps = conn.prepareStatement("SELECT * FROM " + SqlIdent.q(input));
                statement = ps;
                in = new In(ps.executeQuery());
            }
            return in;
        }

        @Override
        public StepOutput emit(String rel) throws SQLException {
            return emit(rel, null);
        }

        @Override
        public synchronized StepOutput emit(String rel, List<TypeFlow.Column> columns) throws SQLException {
            open();
            if (!declares(rel))
                throw new IllegalArgumentException("node '" + node.id() + "' (" + node.type() + ") cannot emit '" + rel
                        + "': its node type does not declare it in emits()");
            Out existing = outputs.get(rel);
            if (existing != null) {
                if (columns != null && !columns.equals(existing.columns))
                    throw new IllegalArgumentException("relation '" + rel + "' is already open with columns " + existing.columns);
                return existing;
            }
            Out out = columns == null ? openLikeInput(rel) : openDeclared(rel, columns);
            outputs.put(rel, out);
            return out;
        }

        private boolean declares(String rel) {
            PipelineNodeType t = PipelineNodeTypes.get(node.type()).orElse(null);
            if (t == null || rel == null) return false;
            return t.emits().contains(rel) || (PipelineRel.isRoute(rel) && t.emitsNamedRoutes());
        }

        private Out openLikeInput(String rel) throws SQLException {
            for (int i = 0; i < kinds.length; i++)
                if (kinds[i] == Kind.OTHER)
                    throw new IllegalArgumentException("input column '" + schema.get(i).name() + "' has type "
                            + schema.get(i).type() + ", which a Step cannot write — cast it upstream");
            String table = RowShaper.table(outPrefix, rel);
            exec("CREATE TABLE " + SqlIdent.q(table) + " AS SELECT * FROM " + SqlIdent.q(input) + " LIMIT 0");
            created.add(table);
            return new Out(rel, table, schema, kinds, true, appender(table));
        }

        private Out openDeclared(String rel, List<TypeFlow.Column> columns) throws SQLException {
            if (columns.isEmpty()) throw new IllegalArgumentException("relation '" + rel + "' needs at least one column");
            StringBuilder ddl = new StringBuilder();
            Kind[] ks = new Kind[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                TypeFlow.Column c = columns.get(i);
                String t = c.type() == null ? "" : c.type().trim().toUpperCase(java.util.Locale.ROOT);
                if (!DECLARABLE.contains(t))
                    throw new IllegalArgumentException("column '" + c.name() + "' type '" + c.type()
                            + "' is not one a Step can write: " + DECLARABLE);
                ks[i] = kindOf(t);
                if (i > 0) ddl.append(", ");
                ddl.append(SqlIdent.q(c.name())).append(' ').append(t);   // t is from the closed set above
            }
            String table = RowShaper.table(outPrefix, rel);
            exec("CREATE TABLE " + SqlIdent.q(table) + " (" + ddl + ")");
            created.add(table);
            return new Out(rel, table, List.copyOf(columns), ks, false, appender(table));
        }

        private DuckDBAppender appender(String table) throws SQLException {
            return conn.unwrap(DuckDBConnection.class).createAppender("", table);
        }

        private void exec(String sql) throws SQLException {
            try (Statement st = conn.createStatement()) { st.execute(sql); }
        }

        /** Flush and close every appender. Called on the Step's own thread (appenders are thread-confined). */
        synchronized void closeOutputs() throws SQLException {
            for (Out o : outputs.values()) if (o != null) o.appender.close();
        }

        /** As {@link #closeOutputs}, swallowing failures — the teardown half. */
        synchronized void closeOutputsQuietly() {
            for (Out o : outputs.values())
                if (o != null) try { o.appender.close(); } catch (Exception ignore) { /* failing anyway */ }
        }

        /** Close the input; create empty tables for declared relations never emitted. Appenders are closed. */
        synchronized List<RowShaper.Relation> finish() throws SQLException {
            closed = true;
            closeInput();
            PipelineNodeType t = PipelineNodeTypes.get(node.type()).orElse(null);
            if (t != null)
                for (String rel : t.emits())
                    if ((PipelineRel.DATA.equals(rel) || PipelineRel.isReject(rel)) && !outputs.containsKey(rel)) {
                        String table = RowShaper.table(outPrefix, rel);
                        exec("CREATE TABLE " + SqlIdent.q(table) + " AS SELECT * FROM " + SqlIdent.q(input) + " LIMIT 0");
                        created.add(table);
                        outputs.put(rel, null);
                    }
            List<RowShaper.Relation> rels = new ArrayList<>();
            for (String rel : outputs.keySet()) rels.add(new RowShaper.Relation(rel, RowShaper.table(outPrefix, rel)));
            return rels;
        }

        /** Mark closed; data calls from here on throw. */
        void close() {
            closed = true;
        }

        /** Cancel the engine-held input statement, if one is running. Safe from another thread. */
        void cancel() {
            PreparedStatement ps = statement;
            if (ps != null) try { ps.cancel(); } catch (SQLException ignore) { /* already finished */ }
        }

        /** Tear down after a failure: close everything and drop every table this Step created. */
        void discard() {
            closed = true;
            closeInput();   // the Step's own thread closed its appenders; an abandoned one keeps them
            for (String table : created)
                try { exec("DROP TABLE IF EXISTS " + SqlIdent.q(table)); }
                catch (SQLException e) { LOG.warn("[STEP] could not drop {} after a failed Step: {}", table, e.toString()); }
            created.clear();
        }

        private void closeInput() {
            PreparedStatement ps = statement;
            statement = null;
            if (in != null) try { in.rs.close(); } catch (SQLException ignore) { /* closing */ }
            if (ps != null) try { ps.close(); } catch (SQLException ignore) { /* closing */ }
        }

        @Override
        public SignalEmitter signals() {
            return (type, severity, payload) -> {
                if (dryRun) {
                    log.info("dry run: would emit signal", "type", type, "severity", severity);
                    return;
                }
                if (type != null && type.startsWith("exchange."))
                    throw new IllegalArgumentException("a Step cannot emit '" + type + "': the exchange.* Signal "
                            + "namespace is written only by the Exchange");
                EventLog.current().emit(new Signal(null, type, null, severity, Ref.of("step", node.type()),
                        null, correlationId, null, null, null, type,
                        payload == null ? Map.of() : new LinkedHashMap<>(payload), 1).toEvent());
            };
        }

        // ── input ────────────────────────────────────────────────────────────────────────────

        private final class In implements StepInput {
            private final ResultSet rs;

            In(ResultSet rs) { this.rs = rs; }

            @Override public List<TypeFlow.Column> columns() { return schema; }

            @Override
            public int column(String name) {
                for (int i = 0; i < schema.size(); i++) if (schema.get(i).name().equals(name)) return i;
                throw new IllegalArgumentException("input has no column '" + name + "' (columns: " + schema + ")");
            }

            @Override
            public boolean next() throws SQLException {
                open();
                return rs.next();
            }

            @Override public boolean wasNull() throws SQLException { return rs.wasNull(); }
            @Override public long getLong(int c) throws SQLException { return rs.getLong(c + 1); }
            @Override public int getInt(int c) throws SQLException { return rs.getInt(c + 1); }
            @Override public double getDouble(int c) throws SQLException { return rs.getDouble(c + 1); }
            @Override public boolean getBoolean(int c) throws SQLException { return rs.getBoolean(c + 1); }
            @Override public String getString(int c) throws SQLException { return rs.getString(c + 1); }
            @Override public LocalDate getDate(int c) throws SQLException { return rs.getObject(c + 1, LocalDate.class); }
            @Override public LocalDateTime getTimestamp(int c) throws SQLException {
                return rs.getObject(c + 1, LocalDateTime.class);
            }
        }

        // ── output ───────────────────────────────────────────────────────────────────────────

        private final class Out implements StepOutput {
            private final String rel;
            private final List<TypeFlow.Column> columns;
            private final Kind[] kinds;
            private final boolean likeInput;
            private final DuckDBAppender appender;

            Out(String rel, String table, List<TypeFlow.Column> columns, Kind[] kinds, boolean likeInput,
                DuckDBAppender appender) {
                this.rel = rel;
                this.columns = columns;
                this.kinds = kinds;
                this.likeInput = likeInput;
                this.appender = appender;
            }

            @Override public String rel() { return rel; }
            @Override public List<TypeFlow.Column> columns() { return columns; }

            @Override
            public StepOutput beginRow() throws SQLException {
                open();
                appender.beginRow();
                return this;
            }

            @Override public StepOutput append(long v) throws SQLException { appender.append(v); return this; }
            @Override public StepOutput append(int v) throws SQLException { appender.append(v); return this; }
            @Override public StepOutput append(double v) throws SQLException { appender.append(v); return this; }
            @Override public StepOutput append(boolean v) throws SQLException { appender.append(v); return this; }

            @Override
            public StepOutput append(String v) throws SQLException {
                if (v == null) appender.appendNull(); else appender.append(v);
                return this;
            }

            @Override
            public StepOutput append(LocalDate v) throws SQLException {
                if (v == null) appender.appendNull(); else appender.append(v);
                return this;
            }

            @Override
            public StepOutput append(LocalDateTime v) throws SQLException {
                if (v == null) appender.appendNull(); else appender.append(v);
                return this;
            }

            @Override public StepOutput appendNull() throws SQLException { appender.appendNull(); return this; }

            @Override
            public StepOutput endRow() throws SQLException {
                open();
                appender.endRow();
                return this;
            }

            @Override
            public StepOutput copyRow(StepInput from) throws SQLException {
                open();
                if (!likeInput || from != in)
                    throw new IllegalArgumentException("copyRow needs a relation opened with emit(rel) — the input's "
                            + "columns — and this context's own input");
                ResultSet rs = ((In) from).rs;
                appender.beginRow();
                for (int i = 0; i < kinds.length; i++) {
                    int c = i + 1;
                    switch (kinds[i]) {
                        case BIGINT -> { long v = rs.getLong(c); if (rs.wasNull()) appender.appendNull(); else appender.append(v); }
                        case INTEGER -> { int v = rs.getInt(c); if (rs.wasNull()) appender.appendNull(); else appender.append(v); }
                        case DOUBLE -> { double v = rs.getDouble(c); if (rs.wasNull()) appender.appendNull(); else appender.append(v); }
                        case BOOLEAN -> { boolean v = rs.getBoolean(c); if (rs.wasNull()) appender.appendNull(); else appender.append(v); }
                        case DATE -> { LocalDate v = rs.getObject(c, LocalDate.class); if (v == null) appender.appendNull(); else appender.append(v); }
                        case TIMESTAMP -> { LocalDateTime v = rs.getObject(c, LocalDateTime.class); if (v == null) appender.appendNull(); else appender.append(v); }
                        case DECIMAL -> { BigDecimal v = rs.getBigDecimal(c); if (v == null) appender.appendNull(); else appender.append(v); }
                        default -> { String v = rs.getString(c); if (v == null) appender.appendNull(); else appender.append(v); }
                    }
                }
                appender.endRow();
                return this;
            }
        }
    }

    /** A Step's {@link RunLog}: the engine log, tagged with the node. */
    private record StepLog(PipelineNode node) implements RunLog {
        @Override public void info(String message, Object... kv) { LOG.info("[STEP {}] {} {}", node.id(), message, kv(kv)); }
        @Override public void warn(String message, Object... kv) { LOG.warn("[STEP {}] {} {}", node.id(), message, kv(kv)); }
        @Override public void error(String message, Throwable t, Object... kv) {
            LOG.error("[STEP " + node.id() + "] " + message + " " + kv(kv), t);
        }

        private static String kv(Object[] kv) {
            if (kv == null || kv.length == 0) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i + 1 < kv.length; i += 2) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(kv[i]).append('=').append(kv[i + 1]);
            }
            return sb.toString();
        }
    }
}
