package com.gamma.job;

import com.gamma.access.Roles;
import com.gamma.control.ControlApi;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.CollectorProcessor;
import com.gamma.recon.ReconBreaks;
import com.gamma.recon.ReconRunJob;
import com.gamma.recon.ReconStateStore;
import com.gamma.pipeline.SpaceConfigRoot;
import com.gamma.service.SpaceManager;
import com.gamma.signal.SignalEmitter;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.RunLog;
import com.gamma.util.Scheduler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The plumbing shared by the {@code partner-settlement} tests: create a Space from the SHIPPED template through
 * {@code POST /spaces} (every seed gate), ingest the synthetic {@link PartnerSettlementCorpus} through the template's own
 * Pipelines (the production Collector path), run the template's own {@code sql.template} Jobs, and read what they wrote.
 */
final class PartnerSettlementSpace {

    static final Path TEMPLATE = Path.of("..", "..", "spaces", "_templates", "partner-settlement").toAbsolutePath().normalize();
    /** The Jobs in chain order. */
    static final List<String> CHAIN = List.of("ps_calendar", "ps_statement_current", "ps_statement_findings", "ps_expected", "ps_billed", "ps_findings",
            "ps_movement", "ps_position", "ps_rollforward");
    /** The first six Jobs (S1 to S3). */
    static final List<String> COMPARE_CHAIN = CHAIN.subList(0, 6);
    /** The S4 Jobs, which read what the compare chain wrote. */
    static final List<String> POSITION_CHAIN = CHAIN.subList(6, 9);
    static final List<String> FEEDS = List.of("partner", "settlement_agreement", "fx_rate", "holiday", "settlement_statement", "rerated_usage", "settlement_payment");

    final Path root;
    final Path space;

    private PartnerSettlementSpace(Path root, Path space) {
        this.root = root;
        this.space = space;
    }

    static void arm() {
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
        Authenticators.forTest(ex -> Optional.of(new Subject("admin-1",
                Set.of(Roles.CAN_ADMINISTER, Roles.CAN_AUTHOR_WORKBENCH, Roles.CAN_AUTHOR_ALERT_RULES))));
        SpaceConfigRoot.clear();
    }

    static void disarm() {
        Authenticators.forTest(null);
        com.gamma.etl.EditionFeatures.overrideForTest(null);
        SpaceConfigRoot.clear();
        System.clearProperty("assist.write.root");
    }

    /** Create the Space from the shipped template through the real route; asserts the template applies (200). */
    static PartnerSettlementSpace create(Path root) throws Exception {
        Files.createDirectories(root);
        copyTree(TEMPLATE, root.resolve("_templates").resolve("partner-settlement"));
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        try {
            api.start();
            HttpResponse<String> created = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + api.port() + "/api/v1/spaces"))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer admin")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"settle\",\"template\":\"partner-settlement\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, created.statusCode(), created.body());
        } finally {
            api.close();
            spaces.close();
        }
        return new PartnerSettlementSpace(root, root.resolve("settle"));
    }

    Path config() { return space.resolve("config"); }

    Path data() { return space.resolve("data"); }

    /** Drop each file in its feed's poll directory and run the feed's Collector; every CSV row must land in the feed's Parquet. */
    void ingest(Map<String, String> files) throws Exception {
        DuckDbUtil.loadDriver();
        Map<String, Long> rows = new TreeMap<>();
        for (Map.Entry<String, String> f : files.entrySet()) {
            String feed = f.getKey().substring(0, f.getKey().indexOf('/'));
            PipelineConfig cfg = pipeline(feed);
            Path poll = Path.of(cfg.dirs().poll()).toAbsolutePath().normalize();
            assertTrue(poll.startsWith(space), feed + " polls outside its Space: " + poll);
            Files.createDirectories(poll);
            Files.writeString(poll.resolve(f.getKey().substring(f.getKey().indexOf('/') + 1)), f.getValue());
            rows.merge(feed, f.getValue().lines().count() - 1, Long::sum);
        }
        for (Map.Entry<String, Long> e : rows.entrySet()) {
            PipelineConfig cfg = pipeline(e.getKey());
            CollectorProcessor.run(cfg);
            Path db = Path.of(cfg.dirs().database()).toAbsolutePath().normalize();
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM read_parquet('" + db.toString().replace('\\', '/')
                         + "/**/*.parquet', union_by_name = true)")) {
                rs.next();
                assertEquals(e.getValue().longValue(), rs.getLong(1), e.getKey() + ": the Pipeline must ingest every row");
            }
        }
    }

    PipelineConfig pipeline(String feed) throws Exception {
        return PipelineConfig.load(config().resolve(feed).resolve(feed + "_pipeline.toon").toString());
    }

    /** Run the given Jobs of the chain in order, each to completion, with the Job-parameter overrides. */
    void run(List<String> jobs, Map<String, String> args) throws Exception {
        List<JobConfig> configs = new ArrayList<>();
        try (Stream<Path> s = Files.list(config().resolve("jobs"))) {
            for (Path p : s.filter(x -> x.getFileName().toString().endsWith("_job.toon")).sorted().toList()) {
                JobConfig j = JobConfig.load(p.toString());
                if ("sql.template".equals(j.type())) configs.add(j);
            }
        }
        try (Scheduler sch = new Scheduler();
             JobService js = new JobService(configs, new ConsignmentEventBus(), sch, null, root.resolve("audit").toString(), null, null, data().toString())) {
            js.start();
            for (String name : jobs) {
                String prior = js.lastRunOf(name).map(JobRun::runId).orElse(null);
                assertTrue(js.triggerRun(name, null, args).isPresent(), name);
                long deadline = System.nanoTime() + 120_000_000_000L;
                JobRun r = null;
                while (System.nanoTime() < deadline) {
                    Optional<JobRun> o = js.lastRunOf(name);
                    if (o.isPresent() && !o.get().runId().equals(prior)) { r = o.get(); break; }
                    Thread.sleep(25);
                }
                assertNotNull(r, name + ": no run within 120s");
                assertEquals("SUCCESS", r.status(), name + ": " + r.message());
            }
        }
    }

    void runChain(Map<String, String> args) throws Exception {
        run(CHAIN, args);
    }

    /** The rows of a Dataset (a snapshot directory) as maps, in {@code orderBy} order. */
    List<Map<String, Object>> read(String dataset, String where, String orderBy) throws Exception {
        DuckDbUtil.loadDriver();
        List<Map<String, Object>> out = new ArrayList<>();
        String glob = data().resolve(dataset).toString().replace('\\', '/') + "/**/*.parquet";
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM read_parquet('" + glob + "', union_by_name = true)"
                     + (where == null ? "" : " WHERE " + where) + " ORDER BY " + orderBy)) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                out.add(row);
            }
        }
        return out;
    }

    /** Run a Reconciliation's recon.run Job for one day; returns the reported Break count. */
    int reconDay(String recon, String day) throws Exception {
        System.setProperty("assist.write.root", config().toString());
        SpaceConfigRoot.clear();
        JobConfig cfg = JobConfig.load(config().resolve("jobs").resolve(recon + "_job.toon").toString());
        assertEquals("recon.run", cfg.type());
        Map<String, String> params = new HashMap<>(cfg.params());
        params.put("day", day);
        JobResult r = new ReconRunJob(cfg, data().toString(), () -> null).run(new Ctx(params));
        assertEquals("SUCCESS", r.status(), r.message());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(": (\\d+) break\\(s\\)").matcher(r.message());
        assertTrue(m.find(), r.message());
        return Integer.parseInt(m.group(1));
    }

    /** Every recorded Break of a Reconciliation as {@code day|type|key}. */
    java.util.Set<String> breaks(String recon) throws Exception {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (ReconBreaks.Break b : new ReconStateStore(config()).read(recon).breaks()) out.add(b.day() + "|" + b.type() + "|" + b.key());
        return out;
    }

    /** A copy of this Space (config and data) under a new root, so a Job can be mutated and re-run without ingesting again. */
    PartnerSettlementSpace copyTo(Path newRoot) throws Exception {
        copyTree(root, newRoot);
        return new PartnerSettlementSpace(newRoot, newRoot.resolve(root.relativize(space).toString()));
    }

    static void copyTree(Path src, Path dst) throws Exception {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.toList()) {
                Path t = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t);
            }
        }
    }

    /** A JobContext that swallows Signals: the Break state store is what the tests read. */
    private record Ctx(Map<String, String> params) implements JobContext {
        @Override public String runId() { return "golden"; }
        @Override public String spaceId() { return "default"; }
        @Override public TriggerInfo trigger() { return null; }
        @Override public Map<String, String> config() { return params; }
        @Override public RunLog log() {
            return new RunLog() {
                @Override public void info(String message, Object... kv) {}
                @Override public void warn(String message, Object... kv) {}
                @Override public void error(String message, Throwable t, Object... kv) {}
            };
        }
        @Override public SignalEmitter signals() { return (type, sev, payload) -> { }; }
        @Override public ArtifactRecorder artifacts() { return null; }
    }
}
