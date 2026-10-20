package com.gamma.control;

import com.gamma.alert.AlertRule;
import com.gamma.alert.AlertService;
import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.StatusStore;
import com.gamma.inspector.CollectorProcessor;
import com.gamma.job.JobConfig;
import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.gamma.objects.FakeObjectAccess;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.DatasetMeasureProbe;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.Scheduler;
import com.gamma.workflow.ObjectType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plumbing the {@code telco-bypass} B2 tests share (module {@code inspecto-scoring}): a verbatim copy of the shipped
 * template as a Space, ingest through its own Pipelines, the Jobs as authored, and the production Alert sweep.
 */
final class BypassPack {

    static final Path TEMPLATE = Path.of("..", "..", "spaces", "_templates", "telco-bypass").toAbsolutePath().normalize();

    private BypassPack() {}

    /** The window arguments of one run: {@code day} is the window's date. */
    static Map<String, String> window(String day) {
        String next = java.time.LocalDate.parse(day).plusDays(1).toString();
        return Map.of("window_start", day + " 00:00:00", "window_end", next + " 00:00:00", "day", day);
    }

    static Path copyTemplate(Path tmp) throws Exception {
        Path space = tmp.resolve("spaces").resolve("bypass");
        try (Stream<Path> w = Files.walk(TEMPLATE)) {
            for (Path p : w.toList()) {
                Path to = space.resolve(TEMPLATE.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else Files.copy(p, to);
            }
        }
        return space;
    }

    /** Drop each feed's files in its poll dir and run its Collector; every CSV row must land in the feed's Parquet. */
    static void ingest(Path space, Map<String, String> files) throws Exception {
        DuckDbUtil.loadDriver();
        Map<String, Long> rows = new TreeMap<>();
        for (Map.Entry<String, String> f : files.entrySet()) {
            String feed = f.getKey().substring(0, f.getKey().indexOf('/'));
            PipelineConfig cfg = PipelineConfig.load(space.resolve("config").resolve(feed).resolve(feed + "_pipeline.toon").toString());
            Path poll = Path.of(cfg.dirs().poll()).toAbsolutePath().normalize();
            assertTrue(poll.startsWith(space), feed + " polls outside its Space: " + poll);
            Files.createDirectories(poll);
            Files.writeString(poll.resolve(f.getKey().substring(f.getKey().indexOf('/') + 1)), f.getValue());
            rows.merge(feed, f.getValue().lines().count() - 1, Long::sum);
        }
        for (String feed : rows.keySet()) {
            PipelineConfig cfg = PipelineConfig.load(space.resolve("config").resolve(feed).resolve(feed + "_pipeline.toon").toString());
            Path db = Path.of(cfg.dirs().database()).toAbsolutePath().normalize();
            long before = landed(db);
            CollectorProcessor.run(cfg);
            assertEquals(rows.get(feed).longValue(), landed(db) - before, feed + ": the Pipeline must ingest every row");
        }
    }

    /** Rows the feed's Parquet files hold now (its zero-row schema seed counts as none). */
    private static long landed(Path db) throws Exception {
        if (!Files.isDirectory(db)) return 0;
        try (Stream<Path> w = Files.walk(db)) {
            if (w.noneMatch(p -> p.toString().endsWith(".parquet"))) return 0;
        }
        return count(db, "union_by_name = true");
    }

    static JobConfig job(Path space, String name) throws Exception {
        return JobConfig.load(space.resolve("config").resolve("jobs").resolve(name + "_job.toon").toString());
    }

    /** The shipped Jobs run in their nightly order, each for {@code day}, with {@code overrides} on top (a parameter by name). */
    static void runNight(Path space, String day, Map<String, String> overrides) throws Exception {
        List<JobConfig> jobs = new ArrayList<>();
        for (String j : List.of("tb_features", "tb_factors", "tb_risk_score", "tb_findings")) jobs.add(job(space, j));
        System.setProperty("assist.write.root", space.resolve("config").toString());
        System.setProperty("data.dir", space.resolve("data").toString());
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(jobs, new ConsignmentEventBus(), s, null,
                     space.resolve("audit").toString(), null, null, space.resolve("data").toString())) {
            js.start();
            for (JobConfig j : jobs) {
                Map<String, String> args = new LinkedHashMap<>(window(day));
                // only the parameters a Job declares are passed to it
                overrides.forEach((k, v) -> { if (j.params().containsKey(k)) args.put(k, v); });
                JobRun r = runJob(js, j.name(), args);
                assertEquals("SUCCESS", r.status(), j.name() + " failed on " + day + ": " + r.message());
            }
        } finally {
            System.clearProperty("assist.write.root");
            System.clearProperty("data.dir");
        }
    }

    static JobRun runJob(JobService js, String name, Map<String, String> args) throws Exception {
        String prior = js.lastRunOf(name).map(JobRun::runId).orElse(null);
        assertTrue(js.triggerRun(name, null, args).isPresent(), name);
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (System.nanoTime() < deadline) {
            Optional<JobRun> r = js.lastRunOf(name);
            if (r.isPresent() && !r.get().runId().equals(prior) && !"RUNNING".equals(r.get().status())) return r.get();
            Thread.sleep(50);
        }
        throw new AssertionError(name + ": no finished run within 120s");
    }

    // ------------------------------------------------------------------------------------------- reading

    /** Rows of a store under the data root, keyed by {@code keyColumn} (a duplicate key is an error); hive partitions read. */
    static Map<String, Map<String, Object>> rows(Path data, String store, String keyColumn, String where) throws Exception {
        DuckDbUtil.loadDriver();
        Map<String, Map<String, Object>> out = new TreeMap<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM read_parquet('" + data.resolve(store).toString().replace('\\', '/')
                     + "/**/*.parquet', hive_partitioning = true, union_by_name = true)" + (where == null ? "" : " WHERE " + where))) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                assertNull(out.put(String.valueOf(row.get(keyColumn)), row), store + ": one row per " + keyColumn + ": " + row.get(keyColumn));
            }
        }
        return out;
    }

    static long count(Path dir, String options) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM read_parquet('" + dir.toString().replace('\\', '/') + "/**/*.parquet', " + options + ")")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ------------------------------------------------------------------------------------------- entity-list sidecars

    private static final String SIDECAR_DDL = "CREATE TABLE e (list_id VARCHAR, purpose VARCHAR, entity_type VARCHAR, match VARCHAR, "
            + "entry VARCHAR, lo VARCHAR, hi VARCHAR, expires_at TIMESTAMP, added_at TIMESTAMP, added_by VARCHAR, reason VARCHAR)";

    /** One Entity List entry as the sidecar stores it: {@code match} is key, prefix or range; {@code expiresAt} null = permanent. */
    record Entry(String match, String entry, String lo, String hi, String expiresAt) {
        static Entry key(String k, String expiresAt) { return new Entry("key", k, k, k, expiresAt); }
        static Entry prefix(String p) { return new Entry("prefix", p, p, p, null); }
        static Entry range(String lo, String hi) { return new Entry("range", lo + "-" + hi, lo, hi, null); }
    }

    /** (Re)write {@code <data>/entity_list_<id>/entries.parquet} in the exact shape EntityListSidecar writes (marker included). */
    static void writeSidecar(Path data, String listId, String purpose, List<Entry> entries) throws Exception {
        DuckDbUtil.loadDriver();
        Path dir = data.resolve("entity_list_" + listId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(".entity-list-output"), listId);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute(SIDECAR_DDL);
            for (Entry e : entries)
                st.execute("INSERT INTO e VALUES ('" + listId + "', '" + purpose + "', 'msisdn', '" + e.match() + "', '" + e.entry() + "', '"
                        + e.lo() + "', '" + e.hi() + "', " + (e.expiresAt() == null ? "NULL" : "TIMESTAMP '" + e.expiresAt() + "'")
                        + ", TIMESTAMP '2026-06-30 12:00:00', 'fraud-lead', 'verified line REG-1')");
            st.execute("COPY e TO '" + dir.resolve("entries.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
    }

    // ------------------------------------------------------------------------------------------- the Alert sweep

    /** The production sweep over the Space's own Alert Rules and Datasets; Incidents opened (rule to the keys), cumulative. */
    static final class Sweep {
        final FakeObjectAccess objects = new FakeObjectAccess();
        final ComponentStore store;
        final AlertService svc;
        final List<AlertRule> rules;
        private final DatasetMeasureProbe probe;
        /** Keys of every non-critical rule that has raised its Alert (such a rule opens an Alert, not one Incident per key). */
        private final Map<String, Set<String>> lower = new TreeMap<>();

        Sweep(Path space) {
            Path cfg = space.resolve("config"), data = space.resolve("data");
            store = new ComponentStore(cfg.resolve("registry"));
            rules = store.list("alert-rule").stream().map(c -> AlertRule.fromMap(c.content())).toList();
            svc = new AlertService(rules, noPipelines(), emptyStore(), objects);
            probe = new DatasetMeasureProbe(() -> cfg, () -> data);
            svc.groupedMeasureProbe(r -> probe.breaches(r.dataset(), r.measure(), r.by(), r.comparator(), r.threshold(), r.stormCap()));
        }

        /**
         * Evaluate every rule once and return, per rule, every key it has raised for so far: the Incidents a critical rule
         * opens (one per key) and, for a lower-severity rule, the keys breaching when its one Alert was raised.
         */
        Map<String, Set<String>> evaluate() {
            for (com.gamma.alert.Alert raised : svc.evaluateRules()) {
                if ("CRITICAL".equalsIgnoreCase(raised.severity())) continue;
                AlertRule r = rules.stream().filter(x -> x.name().equals(raised.rule())).findFirst().orElseThrow();
                Set<String> keys = new TreeSet<>();
                probe.breaches(r.dataset(), r.measure(), r.by(), r.comparator(), r.threshold(), r.stormCap()).orElseThrow()
                        .keys().forEach(b -> keys.add(String.valueOf(b.key().get("entity_key"))));
                assertEquals(keys.size(), (int) raised.value(), r.name() + ": the Alert's value is its breached-key count");
                lower.computeIfAbsent(r.name(), k -> new TreeSet<>()).addAll(keys);
            }
            Map<String, Set<String>> out = new TreeMap<>();
            lower.forEach((k, v) -> out.put(k, new TreeSet<>(v)));
            for (FakeObjectAccess.Opened o : objects.opened) {
                if (o.kind() != ObjectType.INCIDENT) continue;
                String rule = String.valueOf(o.attributes().get("rule"));
                out.computeIfAbsent(rule, k -> new TreeSet<>()).add(String.valueOf(o.attributes().get("key.entity_key")));
            }
            return out;
        }

        long incidents() {
            return objects.opened.stream().filter(o -> o.kind() == ObjectType.INCIDENT).count();
        }
    }

    private static ConfigSource noPipelines() {
        return new ConfigSource() {
            @Override public List<PipelineConfig> pipelines() { return List.of(); }
            @Override public List<EnrichmentConfig> enrichments() { return List.of(); }
            @Override public List<SemanticModel> semantics() { return List.of(); }
        };
    }

    private static StatusStore emptyStore() {
        return new StatusStore() {
            @Override public Set<String> committedBatches(PipelineConfig c) { return Set.of(); }
            @Override public List<Map<String, String>> batches(PipelineConfig c) { return List.of(); }
            @Override public List<Map<String, String>> files(PipelineConfig c) { return List.of(); }
            @Override public List<Map<String, String>> lineage(PipelineConfig c, String b) { return List.of(); }
            @Override public List<Map<String, String>> quarantine(PipelineConfig c) { return List.of(); }
        };
    }
}
