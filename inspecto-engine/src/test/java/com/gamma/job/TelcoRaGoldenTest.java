package com.gamma.job;

import com.gamma.etl.ConsignmentEventBus;
import com.gamma.query.ReconBreaks;
import com.gamma.query.ReconStateStore;
import com.gamma.signal.SignalEmitter;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.RunLog;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden test of the {@code telco-ra} Space Template ({@code ASSURE-PACK-TELCO-RA-1}): the template's OWN
 * shipped configuration (Reconciliations, {@code recon.run} and {@code sql.template} Jobs, their tolerances) runs
 * over the fixed-seed synthetic corpus, and every control flags EXACTLY its planted leakages — the exact count, the
 * exact keys, so every benign look-alike stays silent (zero false positives).
 *
 * <p>The corpus is loaded straight into the Pipelines' stores ({@code data/<feed>/database}) rather than ingested
 * through the eight Pipelines — the Pipelines' own shape is covered by {@code RepoSpacesConfigValidationTest}.
 * Regenerate the committed samples with {@code -Dtelcora.regenerate=true}.
 */
class TelcoRaGoldenTest {

    private static final Path TEMPLATE = Path.of("..", "spaces", "_templates", "telco-ra").toAbsolutePath().normalize();
    private static final TelcoRaCorpus CORPUS = TelcoRaCorpus.generate();

    @BeforeAll
    static void regenerateOnAsk() throws Exception {
        if (Boolean.getBoolean("telcora.regenerate")) CORPUS.writeTo(TEMPLATE.resolve("data").resolve("samples"));
    }

    @AfterEach
    void clearWriteRoot() {
        System.clearProperty("assist.write.root");
    }

    @Test
    void theCommittedSamplesAreExactlyTheGeneratorsOutput() throws Exception {
        Path samples = TEMPLATE.resolve("data").resolve("samples");
        Set<String> committed;
        try (Stream<Path> s = Files.walk(samples)) {
            committed = s.filter(Files::isRegularFile).map(p -> samples.relativize(p).toString().replace('\\', '/'))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
        assertEquals(new TreeSet<>(CORPUS.files.keySet()), committed, "the sample file set");
        for (Map.Entry<String, String> f : CORPUS.files.entrySet())
            assertEquals(f.getValue(), Files.readString(samples.resolve(f.getKey()), StandardCharsets.UTF_8),
                    f.getKey() + " drifted from the fixed-seed generator — regenerate with -Dtelcora.regenerate=true");
        assertEquals(CORPUS.files, TelcoRaCorpus.generate().files, "the generator is deterministic");
    }

    @Test
    void everyControlFlagsExactlyItsPlantedLeakages(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("config");
        Path dataDir = dir.resolve("data");
        loadCorpus(dataDir);
        copyTree(TEMPLATE.resolve("config").resolve("registry"), writeRoot.resolve("registry"));
        System.setProperty("assist.write.root", writeRoot.toString());

        // ── Reconciliations, through the template's own recon.run Jobs ──
        for (String recon : List.of("ra_xdr_completeness", "ra_rated_vs_billed")) {
            JobConfig cfg = JobConfig.load(templateJob(recon).toString());
            assertEquals("recon.run", cfg.type());
            JobResult r = new ReconRunJob(cfg, dataDir.toString(), () -> null).run(new Ctx(cfg.params()));
            assertEquals("SUCCESS", r.status(), r.message());
            Set<String> flagged = new TreeSet<>();
            for (ReconBreaks.Break b : new ReconStateStore(writeRoot).read(recon).breaks())
                flagged.add(b.pair() + "|" + b.type() + "|" + b.key());
            assertEquals(new TreeSet<>(CORPUS.planted.get(recon)), flagged, recon + ": exactly the planted Breaks");
            for (String benign : CORPUS.benign.get(recon))
                assertTrue(flagged.stream().noneMatch(k -> k.endsWith("|" + benign)), recon + " flagged benign " + benign);
        }

        // ── sql.template controls, through the template's own Jobs and tolerances ──
        List<JobConfig> jobs = new ArrayList<>();
        for (String j : List.of("ra_rerating", "ra_rollforward", "ra_settlement", "ra_leakage"))
            jobs.add(JobConfig.load(templateJob(j).toString()));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(jobs, new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString(), null, null, dataDir.toString())) {
            js.start();
            for (JobConfig j : jobs) {
                assertTrue(js.triggerRun(j.name(), null).isPresent(), j.name());
                JobRun run = await(() -> js.lastRunOf(j.name()).orElse(null));
                assertEquals("SUCCESS", run.status(), j.name() + ": " + run.message());
            }
        }
        long plantedTotal = 0;
        for (String control : List.of("ra_rerating", "ra_rollforward", "ra_settlement")) {
            Set<String> flagged = column(dataDir, control + "_exceptions", "ITEM_KEY");
            assertEquals(new TreeSet<>(CORPUS.planted.get(control)), flagged, control + ": exactly the planted leakages");
            for (String benign : CORPUS.benign.get(control))
                assertFalse(flagged.contains(benign), control + " flagged benign look-alike " + benign);
            plantedTotal += CORPUS.planted.get(control).size();
        }
        assertEquals(plantedTotal, column(dataDir, "ra_leakage", "CONTROL || ':' || ITEM_KEY").size(),
                "the leakage Dataset carries every control's findings");
    }

    /** The golden counts, pinned: a corpus change that moves them must be deliberate. */
    @Test
    void theGoldenCountsArePinned() {
        assertEquals(15, CORPUS.planted.get("ra_xdr_completeness").size(), "4 dropped at mediation (AB+AC), 3 at rating (AC), 2 truncated (AB+AC)");
        assertEquals(4, CORPUS.planted.get("ra_rated_vs_billed").size(), "1 unbilled, 3 under-billed");
        assertEquals(5, CORPUS.planted.get("ra_rerating").size());
        assertEquals(3, CORPUS.planted.get("ra_rollforward").size());
        assertEquals(2, CORPUS.planted.get("ra_settlement").size());
        assertEquals(4 + 3 + 5 + 3 + 2, CORPUS.benign.values().stream().mapToInt(Set::size).sum(), "benign look-alikes");
    }

    /** The recovery view reads the impact ledger's LATEST snapshot only — summing every snapshot would overcount. */
    @Test
    void recoveryReadsOnlyTheLatestImpactLedgerSnapshot(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("data");
        Path ledger = dataDir.resolve("impact_ledger");
        Files.createDirectories(ledger);
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            for (int snap = 1; snap <= 2; snap++)
                st.execute("COPY (SELECT 'INC-1' AS object_id, 'INCIDENT' AS object_type, 'RESOLVED' AS status,"
                        + " 'RECOVERED' AS disposition, 'revenue' AS category, 'USD' AS currency,"
                        + " 10.0::DECIMAL(21,6) AS suspected, 10.0::DECIMAL(21,6) AS confirmed,"
                        + " (" + snap + " * 4.0)::DECIMAL(21,6) AS recovered, 0.0::DECIMAL(21,6) AS prevented,"
                        + " (10.0 - " + snap + " * 4.0)::DECIMAL(21,6) AS outstanding,"
                        + " TIMESTAMP '2026-07-0" + snap + " 00:00:00' AS sampled_at) TO '"
                        + ledger.resolve("impact_" + snap + "_out.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        JobConfig job = JobConfig.load(templateJob("ra_recovery").toString());
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString(), null, null, dataDir.toString())) {
            js.start();
            js.triggerRun(job.name(), null);
            JobRun run = await(() -> js.lastRunOf(job.name()).orElse(null));
            assertEquals("SUCCESS", run.status(), run.message());
        }
        assertEquals(Set.of("8.000000"), column(dataDir, "ra_recovery", "CAST(recovered AS VARCHAR)"));
    }

    // ── helpers ──

    private static Path templateJob(String name) {
        return TEMPLATE.resolve("config").resolve("jobs").resolve(name + "_job.toon");
    }

    /** Each feed's CSV → {@code <dataDir>/<feed>/database/data.parquet}, typed as the feed's schema declares. */
    private static void loadCorpus(Path dataDir) throws Exception {
        Path samples = dir(dataDir.resolve("samples"));
        CORPUS.writeTo(samples);
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            for (String file : CORPUS.files.keySet()) {
                String feed = file.substring(0, file.indexOf('/'));
                Path out = dir(dataDir.resolve(feed).resolve("database")).resolve("data.parquet");
                st.execute("COPY (SELECT * FROM read_csv('" + samples.resolve(file).toString().replace('\\', '/')
                        + "', header = true, types = " + types(feed) + ")) TO '"
                        + out.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
            }
        }
    }

    /** The column types the feed's shipped schema declares, as a DuckDB {@code read_csv} types struct. */
    private static String types(String feed) throws Exception {
        List<String> lines = Files.readAllLines(TEMPLATE.resolve("config").resolve(feed).resolve(feed + "_schema.toon"));
        StringBuilder sb = new StringBuilder("{");
        boolean in = false;
        for (String l : lines) {
            if (l.trim().startsWith("fields[") && l.contains("{name,")) { in = true; continue; }
            if (in && !l.startsWith("    ")) break;
            if (in) {
                String[] parts = l.trim().split(",");
                if (sb.length() > 1) sb.append(", ");
                sb.append('\'').append(parts[0]).append("': '").append(parts[2]).append('\'');
            }
        }
        return sb.append('}').toString();
    }

    private static Set<String> column(Path dataDir, String store, String expr) throws Exception {
        Set<String> out = new TreeSet<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + expr + " FROM read_parquet('"
                     + dataDir.resolve(store).toString().replace('\\', '/') + "/*.parquet')")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static Path dir(Path p) throws Exception {
        return Files.createDirectories(p);
    }

    private static void copyTree(Path from, Path to) throws Exception {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static JobRun await(Supplier<JobRun> s) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        JobRun r;
        while ((r = s.get()) == null && System.nanoTime() < deadline) Thread.sleep(50);
        assertNotNull(r, "expected a job run within 30s");
        return r;
    }

    /** A JobContext that swallows Signals — the Break state store is what this test reads. */
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
