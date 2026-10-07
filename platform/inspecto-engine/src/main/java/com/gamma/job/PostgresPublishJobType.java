package com.gamma.job;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.auth.secrets.SecretResolver;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.job.PostgresPublishSql.Col;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.SpaceConfigRoot;
import com.gamma.pipeline.ViewStore;
import com.gamma.pipeline.exec.EgressAllowlist;
import com.gamma.util.egress.EgressPolicy;
import com.gamma.query.DatasetRelation;
import com.gamma.mask.EvidenceMasker;
import com.gamma.util.ColumnClassification;
import com.gamma.signal.Severity;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code publish.postgres} (ASSURE-BI-PUBLICATION-1, WS-17): publish curated Datasets into a Postgres schema a BI
 * tool reads. A Job, not a Step Processor — the hold on new Step Processors stands.
 *
 * <p>The contract, each clause pinned by {@code PostgresPublishJobTest}:
 * <ul>
 *   <li><b>Full refresh</b> fills {@code <table>__inspecto_stage}, then drops the old table and renames the stage
 *       — all Datasets of the run in ONE transaction, so a failure anywhere leaves every old table intact.</li>
 *   <li><b>Partition-incremental</b> fingerprints each partition of {@code partition_column} in DuckDB (row count
 *       + an order-independent row-hash sum), compares with {@code <schema>._inspecto_publication} (written in the
 *       same transaction as the data) and replaces only the changed and vanished partitions.</li>
 *   <li><b>Identifiers</b> are {@link PostgresPublishSql#SAFE_ID}s and always quoted; a Dataset or column name
 *       that is not one refuses the run.</li>
 *   <li><b>Runs as its author.</b> A Dataset the Job's last editor could not view (its sharing envelope) is refused.</li>
 *   <li><b>Data egress — refused by default.</b> A column classified {@link EvidenceMasker#SENSITIVE}
 *       (MSISDN / IMSI / ACCOUNT / PII) is published only when {@code include_sensitive} names it as
 *       {@code dataset.column} AND the author holds {@code canAdminister} now. Otherwise it is dropped when no
 *       allowlist names it, and the run refuses when one does.</li>
 *   <li><b>Egress policy</b>: the JDBC host passes {@link EgressPolicy} with this Space's allowlist — the same one
 *       Action Requests use — and the driver dials the checked address.</li>
 *   <li><b>Audit</b>: every run emits an {@code AUDIT} event {@code publish.postgres.run} with tables and rows.</li>
 * </ul>
 */
public final class PostgresPublishJobType implements JobTypeProvider {

    public static final String TYPE_ID = "publish.postgres";
    public static final String CAN_ADMINISTER = "canAdminister";
    static final String CAN_CONFIGURE_ACCESS = "canConfigureAccess";

    static final String P_CONNECTION = "connection", P_DATASETS = "datasets", P_SCHEMA = "schema", P_MODE = "mode",
            P_PARTITION = "partition_column", P_COLUMNS = "columns", P_SENSITIVE = "include_sensitive",
            P_TIMEOUT = "timeout_seconds", P_RETRIES = "retries";
    static final String FULL = "full-refresh", INCREMENTAL = "partition-incremental";
    static final int BATCH = 1000;

    /** Who a run acts as. {@code open} = no authenticator (Personal / an open dev server): sharing envelopes are
     *  not enforced (as {@code ComponentAccess}), and no one holds {@code canAdminister}. */
    public record Author(String id, Set<String> roles, Set<String> capabilities, boolean open) {
        public static final Author OPEN = new Author(null, Set.of(), Set.of(), true);
    }

    /** The control plane installs this: the author's capabilities as the role table grants them NOW. */
    @FunctionalInterface
    public interface Authority {
        Author authorOf(JobConfig cfg);
    }

    private static volatile Authority authority;

    /** Install (or remove, with {@code null}) the process-wide {@link Authority}; none ⇒ {@link Author#OPEN}. */
    public static void installAuthority(Authority a) { authority = a; }

    public static Authority authority() { return authority; }

    /**
     * Confirms an approval record belongs to a real, still-approved Pending Change (its id + nonce), installed by the
     * control plane. None installed ⇒ every run is refused: an approval nobody can verify is not one.
     */
    @FunctionalInterface
    public interface ApprovalVerifier extends ApprovalFingerprint.Verifier {
        boolean verify(Path configRoot, Map<String, Object> approvalRecord);

        @Override
        default boolean verify(Path configRoot, String job, Map<String, Object> approvalRecord) {
            return verify(configRoot, approvalRecord);
        }
    }

    private static final ApprovalFingerprint.Gate<ApprovalVerifier> APPROVAL_GATE = new ApprovalFingerprint.Gate<>();

    public static void installApprovalVerifier(ApprovalVerifier v) { APPROVAL_GATE.install(v); }

    public static ApprovalVerifier approvalVerifier() { return APPROVAL_GATE.installed(); }

    /** Test seams: the DNS resolver the egress check uses, and how a checked JDBC URL is opened. */
    interface Opener { Connection open(String url, Properties props) throws SQLException; }
    static volatile EgressPolicy.Resolver resolver = EgressPolicy.SYSTEM;
    static volatile Opener opener = DriverManager::getConnection;
    static volatile Runnable beforeCommit = () -> {};

    private final String dataDir;

    public PostgresPublishJobType(String dataDir) { this.dataDir = dataDir; }

    @Override
    public JobTypeDescriptor descriptor() {
        return new JobTypeDescriptor(TYPE_ID, "Publish to Postgres",
                "Publishes curated Datasets into a Postgres schema for BI tools: full refresh (atomic swap) or "
                        + "partition-incremental, with Catalog descriptions as column comments. Sensitive "
                        + "classified columns are never published unless listed and the author holds canAdminister.",
                List.of(ParameterDecl.required(P_CONNECTION, ParamType.STRING,
                                "Id of the db Connection (options.jdbc_url jdbc:postgresql://host[:port]/db)"),
                        ParameterDecl.of(P_DATASETS, ParamType.STRING).required().multi()
                                .description("Dataset ids to publish; each lands in a table named after it").build(),
                        ParameterDecl.required(P_SCHEMA, ParamType.STRING, "Target schema (created if absent)"),
                        ParameterDecl.of(P_MODE, ParamType.STRING).options(FULL, INCREMENTAL).defaultValue(FULL)
                                .description("full-refresh swaps a staged table; partition-incremental replaces "
                                        + "changed partitions only").build(),
                        ParameterDecl.optional(P_PARTITION, ParamType.STRING, null,
                                "Partition column (required for partition-incremental)"),
                        ParameterDecl.of(P_COLUMNS, ParamType.STRING).multi()
                                .description("Column allowlist: 'column' or 'dataset.column'; every column when unset")
                                .build(),
                        ParameterDecl.of(P_SENSITIVE, ParamType.STRING).multi()
                                .description("dataset.column entries of MSISDN/IMSI/ACCOUNT/PII columns to publish "
                                        + "anyway — needs the author to hold canAdminister").build(),
                        ParameterDecl.optional(P_TIMEOUT, ParamType.INTEGER, "300", "Connect and statement timeout, seconds"),
                        ParameterDecl.optional(P_RETRIES, ParamType.INTEGER, "2",
                                "Retries of the whole transaction on a transient (connection/serialization) failure")),
                List.of("publish.postgres.completed"), List.of());
    }

    @Override
    public Job create(JobConfig config) { return new PublishJob(config, dataDir); }

    /** One Dataset's publication plan. */
    record Plan(String datasetId, String table, String relation, String description, List<Col> cols) {}

    static final class PublishJob implements Job {
        private final JobConfig cfg;
        private final String dataDir;

        PublishJob(JobConfig cfg, String dataDir) { this.cfg = cfg; this.dataDir = dataDir; }

        @Override public String name() { return cfg.name(); }
        @Override public String type() { return TYPE_ID; }

        @Override public JobResult run() {
            return JobResult.failed(TYPE_ID + " requires a JobContext", 0L);
        }

        @Override
        public JobResult run(JobContext ctx) {
            long t0 = System.nanoTime();
            // EVERY parameter comes from the saved (four-eyes-approved) Job: a trigger arg, a Signal binding or a
            // run-time override of any of them refuses the run.
            Map<String, String> given = ctx.params().isEmpty() ? ctx.config() : ctx.params();
            Map<String, String> p = savedParams();
            for (Map.Entry<String, String> e : given.entrySet())
                if (!JobConfig.AUTHOR_KEYS.contains(e.getKey()) && !java.util.Objects.equals(blankToNull(e.getValue()), blankToNull(p.get(e.getKey()))))
                    return refuse(ctx, "'" + e.getKey() + "' comes from the approved Job only; a run may not override it", t0);
            // The approval pins CONTENT: the Job, its Connection and each Dataset's definition, as fingerprinted
            // when the Pending Change was approved. Any difference refuses the run.
            Path approvalRoot = SpaceConfigRoot.current();
            if (approvalRoot == null) return refuse(ctx, "no Space config root to read the approval from", t0);
            Map<String, String> now = PublicationApproval.fingerprints(cfg.toMap(), approvalRoot, ConnectionRegistry::find);
            java.util.Optional<Map<String, String>> approved = PublicationApproval.approved(approvalRoot, cfg.name());
            if (approved.isEmpty())
                return refuse(ctx, "this publication was never approved (four-eyes) — re-approve", t0);
            Map<String, Object> record = PublicationApproval.recordOf(approvalRoot, cfg.name()).orElse(Map.of());
            if (!APPROVAL_GATE.honours(approvalRoot, cfg.name(), record))
                return refuse(ctx, "the approval record is not bound to an approved Pending Change (id + nonce) — re-approve", t0);
            List<String> changed = PublicationApproval.changed(approved.get(), now);
            if (!changed.isEmpty())
                return refuse(ctx, "the approved publication changed (" + String.join(" | ", changed) + "); re-approve", t0);
            Map<String, Long> rows = new LinkedHashMap<>();
            String schema = p.get(P_SCHEMA);
            try {
                String msg = publish(ctx, p, rows);
                audit("success", schema, rows, null);
                ctx.signals().emit("publish.postgres.completed", Severity.INFO, Map.of("job", cfg.name(),
                        "schema", String.valueOf(schema), "tables", String.join(",", rows.keySet()),
                        "rows", rows.values().stream().mapToLong(Long::longValue).sum()));
                return JobResult.ok(msg, ms(t0));
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                String why = e.getClass().getSimpleName() + ": " + e.getMessage();
                audit("failure", schema, Map.of(), why);
                ctx.log().error("publish.postgres failed — the transaction was rolled back, every target table "
                        + "is as it was before this run", e, "job", cfg.name());
                return JobResult.failed(TYPE_ID + " failed, nothing published (rolled back): " + why, ms(t0));
            }
        }

        /** The saved Job's parameters over the declared defaults — what four-eyes approved. */
        private Map<String, String> savedParams() {
            Map<String, String> out = new LinkedHashMap<>();
            for (ParameterDecl d : new PostgresPublishJobType(null).descriptor().parameters()) if (d.defaultValue() != null) out.put(d.name(), d.defaultValue());
            cfg.params().forEach((k, v) -> { if (!JobConfig.AUTHOR_KEYS.contains(k)) out.put(k, v); });
            return out;
        }

        private JobResult refuse(JobContext ctx, String why, long t0) {
            audit("refused", cfg.params().get(P_SCHEMA), Map.of(), why);
            ctx.signals().emit("publish.postgres.refused", Severity.WARN, Map.of("job", cfg.name(), "reason", why));
            ctx.log().error("publish.postgres refused: " + why, null, "job", cfg.name());
            return JobResult.failed(TYPE_ID + " refused: " + why, ms(t0));
        }

        private String publish(JobContext ctx, Map<String, String> p, Map<String, Long> rows) throws Exception {
            if (dataDir == null || dataDir.isBlank()) throw new IllegalStateException("no Space data root");
            String schema = required(p, P_SCHEMA);
            PostgresPublishSql.ident(schema, "schema");
            String mode = p.getOrDefault(P_MODE, FULL) == null ? FULL : p.getOrDefault(P_MODE, FULL).trim();
            if (mode.isEmpty()) mode = FULL;
            if (!mode.equals(FULL) && !mode.equals(INCREMENTAL))
                throw new IllegalArgumentException("mode must be " + FULL + " or " + INCREMENTAL + ", got '" + mode + "'");
            String partition = trim(p.get(P_PARTITION));
            if (mode.equals(INCREMENTAL)) {
                if (partition == null) throw new IllegalArgumentException(INCREMENTAL + " needs " + P_PARTITION);
                PostgresPublishSql.ident(partition, "partition column");
            }
            int timeout = bounded(p.get(P_TIMEOUT), 300, 1, 3600, P_TIMEOUT);
            int retries = bounded(p.get(P_RETRIES), 2, 0, 5, P_RETRIES);
            List<String> datasets = csv(p.get(P_DATASETS));
            if (datasets.isEmpty()) throw new IllegalArgumentException("parameter '" + P_DATASETS + "' is required");
            Set<String> allow = new LinkedHashSet<>(csv(p.get(P_COLUMNS)));
            Set<String> sensitiveListed = new LinkedHashSet<>(csv(p.get(P_SENSITIVE)));

            Authority auth = authority;
            Author author = auth == null ? Author.OPEN : auth.authorOf(cfg);
            Path writeRoot = SpaceConfigRoot.requireCurrent(TYPE_ID);
            ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
            ViewStore views = new ViewStore(writeRoot.resolve("views"));

            List<Plan> plans = new ArrayList<>();
            Set<String> tables = new HashSet<>();
            // The sealed connection reaches only what the Datasets' relations read (ENGINE-INMEMORY-UNSEALED-1);
            // a Dataset the author may not view contributes nothing — the loop below refuses it as before.
            List<Path> readRoots = new ArrayList<>(List.of(Path.of(dataDir)));
            for (String id : datasets) {
                Map<String, Object> ds = store.get("dataset", id).map(ComponentRegistry.Component::content).orElse(null);
                if (ds != null && canView(author, ds)) readRoots.addAll(DatasetRelation.readRoots(ds, Path.of(dataDir)));
            }
            try (Connection duck = com.gamma.util.DuckDbUtil.openInMemory(com.gamma.util.DuckDbUtil.spillDirUnder(Path.of(dataDir)), readRoots)) {
                for (String id : datasets) {
                    Map<String, Object> ds = store.get("dataset", id).map(ComponentRegistry.Component::content)
                            .orElse(null);
                    if (ds == null || !canView(author, ds))   // indistinguishable, as the read routes' 404
                        throw new SecurityException("dataset '" + id + "' does not exist or its author '"
                                + author.id() + "' may not read it — refused");
                    String table = PostgresPublishSql.tableFor(id);
                    if (!tables.add(table)) throw new IllegalArgumentException("two datasets publish to table '" + table + "'");
                    String rel = DatasetRelation.relationSql(ds, Path.of(dataDir), views);
                    plans.add(new Plan(id, table, rel, str(ds.get("description")),
                            columns(duck, id, rel, ds, allow, sensitiveListed, author, store, views)));
                }
                if (mode.equals(INCREMENTAL))
                    for (Plan pl : plans) {
                        Col c = pl.cols().stream().filter(x -> x.name().equals(partition)).findFirst().orElseThrow(() ->
                                new IllegalArgumentException("dataset '" + pl.datasetId() + "' publishes no column '"
                                        + partition + "' to partition on"));
                        if (!PostgresPublishSql.partitionable(c.duckType()))
                            throw new IllegalArgumentException("partition column '" + partition + "' is "
                                    + c.duckType() + "; partition on a text, integer, boolean or date column");
                    }

                Target target = target(required(p, P_CONNECTION), timeout, author, writeRoot);
                if (target.insecureTls() && !ctx.dryRun()) auditInsecureTls(target, cfg);
                if (ctx.dryRun())
                    return "dry run: would publish " + plans.size() + " dataset(s) " + mode + " to " + target.display()
                            + "." + schema + "; nothing was sent";

                for (int attempt = 0; ; attempt++) {
                    rows.clear();
                    try (Connection pg = opener.open(target.url(), target.props())) {
                        pg.setAutoCommit(false);
                        try {
                            boolean isPg = pg.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres");
                            if (isPg) {
                                exec(pg, "SET LOCAL statement_timeout = " + (timeout * 1000L), timeout);
                                exec(pg, "SET LOCAL standard_conforming_strings = on", timeout);
                            }
                            exec(pg, "CREATE SCHEMA IF NOT EXISTS " + PostgresPublishSql.ident(schema, "schema"), timeout);
                            for (Plan pl : plans)
                                rows.put(pl.table(), mode.equals(FULL) ? fullRefresh(duck, pg, schema, pl, timeout)
                                        : incremental(duck, pg, schema, pl, partition, ctx.runId(), timeout));
                            beforeCommit.run();
                            pg.commit();
                        } catch (Exception e) {
                            try { pg.rollback(); } catch (SQLException ignored) { /* the connection is gone: nothing committed */ }
                            throw e;
                        }
                    } catch (SQLException e) {
                        if (attempt >= retries || !transient_(e)) throw e;
                        ctx.log().info("transient failure, retrying the publication", "attempt", attempt + 1,
                                "sqlState", String.valueOf(e.getSQLState()));
                        Thread.sleep(1000L << attempt);
                        continue;
                    }
                    break;
                }
                Instant now = Instant.now();
                for (Plan pl : plans) {
                    ctx.log().info("published", "dataset", pl.datasetId(), "table", schema + "." + pl.table(),
                            "rows", rows.get(pl.table()), "mode", mode);
                }
                return "published " + plans.size() + " dataset(s) " + mode + " to " + target.display() + "."
                        + schema + ": " + rows + " at " + now;
            }
        }

        private void audit(String outcome, String schema, Map<String, Long> rows, String error) {
            try {
                EventLog log = EventLog.current();
                if (log == null) return;
                Event.Builder b = Event.builder(EventType.AUDIT).source("audit")
                        .message("publish.postgres " + outcome + ": " + rows)
                        .actor(cfg.params().getOrDefault(JobConfig.UPDATED_BY, "system")).actorType("system")
                        .action("publish.postgres.run").actionCategory("operation").target("job", cfg.name())
                        .attr("outcome", outcome).attr("schema", String.valueOf(schema))
                        .attr("tables", String.join(",", rows.keySet()))
                        .attr("rows", rows.values().stream().mapToLong(Long::longValue).sum());
                if (error != null) b.attr("error", error.length() > 500 ? error.substring(0, 500) : error);
                log.emit(b);
            } catch (RuntimeException ignored) {
                // best effort, like every audit emit — the run row carries the result
            }
        }
    }

    // ── planning ─────────────────────────────────────────────────────────────────────────────

    /** {@code ComponentAccess.level >= VIEW}, decided for the Job's author. */
    static boolean canView(Author a, Map<String, Object> content) {
        if (a.open()) return true;
        String owner = str(content.get("owner"));
        if (!content.containsKey("shares")) return true;
        if ((owner != null && owner.equals(a.id())) || a.capabilities().contains(CAN_CONFIGURE_ACCESS)) return true;
        if (content.get("shares") instanceof List<?> shares)
            for (Object o : shares)
                if (o instanceof Map<?, ?> s) {
                    String type = str(s.get("subjectType")), id = str(s.get("subjectId"));
                    if (id == null) continue;
                    if ("user".equals(type) && id.equals(a.id())) return true;
                    if ("role".equals(type) && a.roles().contains(id.toLowerCase(Locale.ROOT))) return true;
                }
        return false;
    }

    /** The columns of {@code datasetId} this run publishes, after the allowlist and the sensitive-column rule. */
    static List<Col> columns(Connection duck, String datasetId, String relation, Map<String, Object> ds,
                             Set<String> allow, Set<String> sensitiveListed, Author author,
                             ComponentStore store, ViewStore views) throws SQLException {
        Map<String, String> classification = new HashMap<>(), description = new HashMap<>();
        if (ds.get("columns") instanceof List<?> cols)
            for (Object o : cols)
                if (o instanceof Map<?, ?> c && c.get("name") != null) {
                    String n = String.valueOf(c.get("name"));
                    String cl = str(c.get("classification"));
                    if (cl != null) classification.put(n.toLowerCase(Locale.ROOT), cl.trim().toUpperCase(Locale.ROOT));
                    String d = str(c.get("description"));
                    if (d != null) description.put(n.toLowerCase(Locale.ROOT), d);
                }
        // Lineage (static only): the classified columns every OTHER Dataset over the same store declares.
        Map<String, String> inherited = lineageClassification(datasetId, ds, store, views);
        boolean derived = str(ds.get("view")) != null || str(ds.get("sql")) != null;
        boolean wholeDataset = sensitiveListed.contains(datasetId + ".*");
        if (inherited.containsKey(UNKNOWN_LINEAGE) && !derived
                && (!wholeDataset || !author.capabilities().contains(CAN_ADMINISTER)))
            throw new SecurityException("dataset '" + datasetId + "' reads a pipeline store whose column lineage cannot "
                    + "be traced (an unreadable pipeline, a mapping that cannot be parsed, or a step that rewrites "
                    + "the columns), so its classification is unknown; publishing it needs '" + datasetId + ".*' in "
                    + P_SENSITIVE + " and an author holding canAdminister — refused");
        if (derived && !inherited.isEmpty()) {
            // a view or virtual Dataset may rename or compute over a classified column (msisdn AS m), which cannot
            // be traced statically, so it publishes only when the whole Dataset is explicitly released
            if (!wholeDataset || !author.capabilities().contains(CAN_ADMINISTER))
                throw new SecurityException("dataset '" + datasetId + "' is a view/virtual Dataset over a store whose "
                        + "columns " + inherited.keySet() + " are classified; its columns cannot be traced to them, so "
                        + "publishing it needs '" + datasetId + ".*' in " + P_SENSITIVE + " and an author holding "
                        + "canAdminister — refused");
        }
        inherited.remove(UNKNOWN_LINEAGE);
        // same-name columns inherit the classification, strictest wins (ColumnClassification)
        inherited.forEach((col, cl) -> classification.merge(col, cl, ColumnClassification::stricter));
        List<Col> out = new ArrayList<>();
        Set<String> seenAllow = new HashSet<>();
        try (Statement st = duck.createStatement(); ResultSet rs = st.executeQuery("DESCRIBE SELECT * FROM (" + relation + ") t")) {
            while (rs.next()) {
                String name = rs.getString("column_name"), type = rs.getString("column_type");
                boolean listed = allow.isEmpty() || allow.contains(name) || allow.contains(datasetId + "." + name);
                if (!allow.isEmpty() && listed) seenAllow.add(name);
                if (!listed) continue;
                String lc = name.toLowerCase(Locale.ROOT);
                boolean sensitive = EvidenceMasker.SENSITIVE.contains(classification.getOrDefault(lc, ""));
                if (sensitive) {
                    boolean named = wholeDataset || sensitiveListed.contains(datasetId + "." + name);
                    if (!named) {
                        if (allow.isEmpty()) continue;   // default: a sensitive column is simply not published
                        throw new SecurityException("column '" + datasetId + "." + name + "' is classified "
                                + classification.get(lc) + "; publishing it needs it in " + P_SENSITIVE
                                + " and an author holding canAdminister — refused");
                    }
                    if (!author.capabilities().contains(CAN_ADMINISTER))
                        throw new SecurityException("column '" + datasetId + "." + name + "' is classified "
                                + classification.get(lc) + " and listed in " + P_SENSITIVE + ", but the Job's "
                                + "author " + (author.id() == null ? "(none — no authenticator)" : "'" + author.id() + "'")
                                + " does not hold canAdminister — refused");
                }
                PostgresPublishSql.ident(name, "column of dataset '" + datasetId + "'");
                String pg = PostgresPublishSql.pgType(type);
                if (pg == null)
                    throw new IllegalArgumentException("column '" + datasetId + "." + name + "' has type " + type
                            + ", which the publisher does not carry — leave it out with " + P_COLUMNS);
                out.add(new Col(name, type, pg, description.get(lc)));
            }
        }
        for (String a : allow)
            if (a.startsWith(datasetId + ".") && !seenAllow.contains(a.substring(datasetId.length() + 1)))
                throw new IllegalArgumentException(P_COLUMNS + " names '" + a + "', which the dataset does not have");
        if (out.isEmpty()) throw new IllegalArgumentException("dataset '" + datasetId + "' publishes no columns");
        return out;
    }

    /** {@link EvidenceMasker#lineageClassification}, the one lineage resolver publication and evidence share. */
    static Map<String, String> lineageClassification(String datasetId, Map<String, Object> ds, ComponentStore store,
                                                     ViewStore views) {
        return EvidenceMasker.lineageClassification(datasetId, ds, store, views);
    }

    /** Reserved {@link #lineageClassification} key: the store's classification could not be established. */
    static final String UNKNOWN_LINEAGE = EvidenceMasker.UNKNOWN_LINEAGE;

    // ── target ───────────────────────────────────────────────────────────────────────────────

    record Target(String url, Properties props, String display, String sslmode, boolean insecureTls) {}

    private static final Pattern PG_URL = Pattern.compile(
            "jdbc:postgresql://(\\[[0-9A-Fa-f:.]+\\]|[^/:?\\[\\],]+)(?::(\\d{1,5}))?/([A-Za-z0-9_.-]+)(?:\\?(.*))?");
    /** Query parameters a publication URL may carry; anything else (a socket factory, a service, a second host) is
     *  refused. {@code sslrootcert} must be a secret reference. */
    static final Set<String> URL_PARAMS = Set.of("sslmode", "sslrootcert", "ApplicationName");
    static final String VERIFY_FULL = "verify-full";
    static final Set<String> SSL_MODES = Set.of("disable", "allow", "prefer", "require", "verify-ca", VERIFY_FULL);
    /** The Connection option that permits a weaker {@code sslmode} — effective only for a {@code canAdminister} author. */
    static final String INSECURE_TLS = "insecure_tls";

    /**
     * Resolve the Connection, pass its host through the egress policy, and build the driver properties: the URL keeps
     * the AUTHORED host (TLS SNI and the {@code verify-full} hostname check use it) while
     * {@link PublishPinnedSocketFactory} dials the checked address, and {@link PublishSslFactory} does TLS.
     * {@code sslmode} defaults to {@code verify-full}; anything weaker needs the Connection's {@code insecure_tls: true}
     * AND an author holding {@code canAdminister} now.
     */
    static Target target(String connectionId, int timeoutSeconds, Author author, Path configRoot) throws EgressPolicy.Refused {
        ConnectionProfile profile = ConnectionRegistry.find(connectionId).orElseThrow(() ->
                new IllegalStateException("Connection '" + connectionId + "' is not registered in this space"));
        if (!"db".equalsIgnoreCase(profile.connector()))
            throw new IllegalArgumentException("Connection '" + connectionId + "' is a " + profile.connector()
                    + " Connection; publish.postgres needs a db Connection");
        if (profile.tunnel() != null || profile.proxy() != null)
            throw new IllegalStateException("Connection '" + connectionId + "' declares a tunnel or proxy, which the "
                    + "publisher does not dial through — refused rather than bypassed");
        String url = profile.options() == null ? null : profile.options().get("jdbc_url");
        Matcher m = url == null ? null : PG_URL.matcher(url);
        if (m == null || !m.matches())
            throw new IllegalArgumentException("Connection '" + connectionId + "' options.jdbc_url must be "
                    + "jdbc:postgresql://host[:port]/database (one host)");
        String host = m.group(1), port = m.group(2), db = m.group(3), query = m.group(4);
        Map<String, String> params = new LinkedHashMap<>();
        if (query != null && !query.isEmpty())
            for (String kv : query.split("&")) {
                int eq = kv.indexOf('=');
                String k = eq < 0 ? kv : kv.substring(0, eq);
                if (!URL_PARAMS.contains(k))
                    throw new IllegalArgumentException("jdbc_url parameter '" + k + "' is not allowed (only " + URL_PARAMS + ")");
                params.put(k, eq < 0 ? "" : java.net.URLDecoder.decode(kv.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8));
            }
        String sslmode = params.getOrDefault("sslmode", VERIFY_FULL).trim().toLowerCase(Locale.ROOT);
        if (!SSL_MODES.contains(sslmode)) throw new IllegalArgumentException("unknown sslmode '" + sslmode + "'");
        boolean insecure = !VERIFY_FULL.equals(sslmode);
        if (insecure) {
            boolean flagged = "true".equalsIgnoreCase(profile.options().getOrDefault(INSECURE_TLS, "false").trim());
            if (!flagged)
                throw new SecurityException("Connection '" + connectionId + "' asks for sslmode=" + sslmode + ", which does "
                        + "not verify the server's certificate and host name — publish.postgres needs verify-full, or an "
                        + "explicit " + INSECURE_TLS + ": true on the Connection — refused");
        }
        String rootCert = params.get("sslrootcert");
        if (rootCert != null && !SecretResolver.isReference(rootCert))
            throw new IllegalArgumentException("jdbc_url sslrootcert must be a secret reference such as ${KEYSTORE:name}, "
                    + "never a file path");

        String bare = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        EgressPolicy.checkHost(bare);
        if (!PublicationDestinations.permits(configRoot, bare))
            throw new SecurityException("'" + bare + "' is not a publication destination of this Space — an administrator "
                    + "adds it with PUT /settings/publication-destinations (the egress policy alone does not allow a "
                    + "destination) — refused");
        InetAddress to = EgressPolicy.resolve(bare, EgressAllowlist.forCurrentSpace(), resolver);
        Properties props = new Properties();
        props.setProperty("sslmode", sslmode);
        if (params.containsKey("ApplicationName")) props.setProperty("ApplicationName", params.get("ApplicationName"));
        props.setProperty("socketFactory", PublishPinnedSocketFactory.class.getName());
        props.setProperty(PublishPinnedSocketFactory.PINNED, to.getHostAddress());
        props.setProperty("sslfactory", PublishSslFactory.class.getName());
        if (rootCert != null) props.setProperty(PublishSslFactory.ROOT_CERT_REF, rootCert);
        // no ambient credentials: an always-set password keeps pgjdbc from reading ~/.pgpass (PGPASSFILE), no
        // `service` parameter means no pg_service.conf, and GSS encryption / JAAS stay off
        props.setProperty("gssEncMode", "disable");
        props.setProperty("jaasLogin", "false");
        if (profile.username() != null) props.setProperty("user", profile.username());
        String pw = "";
        if (profile.password() != null) {
            pw = SecretResolver.resolve(profile.password());
            if (pw == null) throw new IllegalStateException("Connection '" + connectionId + "' password reference does not resolve");
        }
        props.setProperty("password", pw);
        props.setProperty("connectTimeout", String.valueOf(timeoutSeconds));
        props.setProperty("socketTimeout", String.valueOf(timeoutSeconds));
        props.setProperty("loginTimeout", String.valueOf(timeoutSeconds));
        return new Target("jdbc:postgresql://" + host + (port == null ? "" : ":" + port) + "/" + db, props,
                connectionId + " (" + host + ")", sslmode, insecure);
    }

    /** An insecure-TLS publication is audited on every run (who, which Connection, which sslmode). */
    static void auditInsecureTls(Target t, JobConfig cfg) {
        try {
            EventLog log = EventLog.current();
            if (log == null) return;
            log.emit(Event.builder(EventType.AUDIT).source("audit")
                    .message("publish.postgres runs with sslmode=" + t.sslmode() + " (insecure_tls) to " + t.display())
                    .actor(cfg.params().getOrDefault(JobConfig.UPDATED_BY, "system")).actorType("system")
                    .action("publish.postgres.insecure-tls").actionCategory("operation").target("job", cfg.name())
                    .attr("sslmode", t.sslmode()).attr("connection", t.display()));
        } catch (RuntimeException ignored) {
            // best effort, as every audit emit
        }
    }

    // ── writing ──────────────────────────────────────────────────────────────────────────────

    static long fullRefresh(Connection duck, Connection pg, String schema, Plan pl, int timeout) throws SQLException {
        String stage = pl.table() + PostgresPublishSql.STAGE_SUFFIX;
        exec(pg, "DROP TABLE IF EXISTS " + PostgresPublishSql.qualified(schema, stage), timeout);
        exec(pg, PostgresPublishSql.createTable(schema, stage, pl.cols()), timeout);
        long n = copy(duck, PostgresPublishSql.select(pl.relation(), pl.cols()), pg,
                PostgresPublishSql.insert(schema, stage, pl.cols()), pl.cols().size(), timeout);
        for (String s : PostgresPublishSql.swap(schema, pl.table())) exec(pg, s, timeout);
        for (String s : PostgresPublishSql.comments(schema, pl.table(), pl.description(), pl.cols())) exec(pg, s, timeout);
        return n;
    }

    static final String NULL_PART = "null";

    static long incremental(Connection duck, Connection pg, String schema, Plan pl, String partition, String runId,
                            int timeout) throws SQLException {
        Map<String, String> source = new LinkedHashMap<>();
        try (Statement st = duck.createStatement();
             ResultSet rs = st.executeQuery(PostgresPublishSql.fingerprints(pl.relation(), partition, pl.cols()))) {
            while (rs.next()) {
                String v = rs.getString(1);
                source.put(v == null ? NULL_PART : "v:" + v, rs.getString(2));
            }
        }
        exec(pg, PostgresPublishSql.createState(schema), timeout);
        String state = PostgresPublishSql.qualified(schema, PostgresPublishSql.STATE_TABLE);
        List<String> existingCols = targetColumns(pg, schema, pl.table());
        Map<String, String> published = new HashMap<>();
        if (existingCols.isEmpty()) {
            exec(pg, PostgresPublishSql.createTable(schema, pl.table(), pl.cols()), timeout);
            try (PreparedStatement ps = pg.prepareStatement("DELETE FROM " + state + " WHERE tbl = ?")) {
                ps.setString(1, pl.table());
                ps.executeUpdate();
            }
        } else {
            List<String> want = pl.cols().stream().map(Col::name).toList();
            if (!new HashSet<>(existingCols).equals(new HashSet<>(want)))
                throw new IllegalStateException("table " + schema + "." + pl.table() + " has columns " + existingCols
                        + " but the dataset publishes " + want + " — run a " + FULL + " first");
            try (PreparedStatement ps = pg.prepareStatement("SELECT part, fingerprint FROM " + state + " WHERE tbl = ?")) {
                ps.setString(1, pl.table());
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) published.put(rs.getString(1), rs.getString(2)); }
            }
        }
        Set<String> changed = new LinkedHashSet<>();
        source.forEach((k, fp) -> { if (!fp.equals(published.get(k))) changed.add(k); });
        Set<String> gone = new LinkedHashSet<>(published.keySet());
        gone.removeAll(source.keySet());
        for (String k : concat(changed, gone)) {
            boolean isNull = k.equals(NULL_PART);
            try (PreparedStatement ps = pg.prepareStatement(PostgresPublishSql.deletePartition(schema, pl.table(), partition, isNull))) {
                ps.setQueryTimeout(timeout);
                if (!isNull) ps.setString(1, k.substring(2));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = pg.prepareStatement("DELETE FROM " + state + " WHERE tbl = ? AND part = ?")) {
                ps.setString(1, pl.table());
                ps.setString(2, k);
                ps.executeUpdate();
            }
        }
        long n = 0;
        if (!changed.isEmpty()) {
            try (Statement st = duck.createStatement()) {
                st.execute("CREATE OR REPLACE TEMP TABLE __publish_changed (v VARCHAR, is_null BOOLEAN)");
            }
            try (PreparedStatement ps = duck.prepareStatement("INSERT INTO __publish_changed VALUES (?, ?)")) {
                for (String k : changed) {
                    ps.setString(1, k.equals(NULL_PART) ? null : k.substring(2));
                    ps.setBoolean(2, k.equals(NULL_PART));
                    ps.executeUpdate();
                }
            }
            String p = PostgresPublishSql.ident(partition, "partition column");
            String select = PostgresPublishSql.select(pl.relation(), pl.cols()) + " WHERE CAST(t." + p
                    + " AS VARCHAR) IN (SELECT v FROM __publish_changed WHERE NOT is_null) OR (t." + p
                    + " IS NULL AND EXISTS (SELECT 1 FROM __publish_changed WHERE is_null))";
            n = copy(duck, select, pg, PostgresPublishSql.insert(schema, pl.table(), pl.cols()), pl.cols().size(), timeout);
            try (PreparedStatement ps = pg.prepareStatement("INSERT INTO " + state + " (tbl, part, fingerprint, run_id) VALUES (?, ?, ?, ?)")) {
                for (String k : changed) {
                    ps.setString(1, pl.table());
                    ps.setString(2, k);
                    ps.setString(3, source.get(k));
                    ps.setString(4, runId);
                    ps.executeUpdate();
                }
            }
        }
        for (String s : PostgresPublishSql.comments(schema, pl.table(), pl.description(), pl.cols())) exec(pg, s, timeout);
        return n;
    }

    private static List<String> concat(Set<String> a, Set<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    static List<String> targetColumns(Connection pg, String schema, String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = pg.prepareStatement("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(rs.getString(1)); }
        }
        return out;
    }

    static long copy(Connection duck, String select, Connection pg, String insert, int width, int timeout) throws SQLException {
        long n = 0;
        try (Statement st = duck.createStatement(); ResultSet rs = st.executeQuery(select);
             PreparedStatement ps = pg.prepareStatement(insert)) {
            ps.setQueryTimeout(timeout);
            int pending = 0;
            while (rs.next()) {
                for (int i = 1; i <= width; i++) ps.setObject(i, value(rs.getObject(i)));
                ps.addBatch();
                n++;
                if (++pending == BATCH) { ps.executeBatch(); pending = 0; }
            }
            if (pending > 0) ps.executeBatch();
        }
        return n;
    }

    private static Object value(Object v) {
        if (v instanceof BigInteger b) return new BigDecimal(b);
        if (v instanceof java.util.UUID u) return u.toString();
        return v;
    }

    static void exec(Connection c, String sql, int timeout) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.setQueryTimeout(timeout);
            st.execute(sql);
        }
    }

    /** Connection loss (08), serialization / deadlock (40), resources (53), operator intervention (57P). */
    static boolean transient_(SQLException e) {
        String s = e.getSQLState();
        return s != null && (s.startsWith("08") || s.startsWith("40") || s.startsWith("53") || s.startsWith("57P"));
    }

    // ── small helpers ────────────────────────────────────────────────────────────────────────

    static List<String> csv(String v) {
        List<String> out = new ArrayList<>();
        if (v == null) return out;
        for (String s : v.split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    private static String required(Map<String, String> p, String key) {
        String v = trim(p.get(key));
        if (v == null) throw new IllegalArgumentException("parameter '" + key + "' is required");
        return v;
    }

    private static String trim(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static String str(Object o) { return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o).trim(); }

    private static int bounded(String v, int dflt, int min, int max, String key) {
        if (v == null || v.isBlank()) return dflt;
        int n = Integer.parseInt(v.trim());
        if (n < min || n > max) throw new IllegalArgumentException(key + " must be " + min + ".." + max + ", got " + n);
        return n;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static long ms(long t0) { return (System.nanoTime() - t0) / 1_000_000L; }
}
