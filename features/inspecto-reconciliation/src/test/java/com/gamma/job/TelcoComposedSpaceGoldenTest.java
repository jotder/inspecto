package com.gamma.job;

import com.gamma.alert.AlertRule;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.SpaceConfigRoot;
import com.gamma.query.DatasetMeasureProbe;
import com.gamma.recon.ReconBreaks;
import com.gamma.recon.ReconStateStore;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden test of the Inspecto Telco Composed Space Template with ONE usage feed ({@code PRODUCT-ASSEMBLY-1} PA-5c,
 * decision D-PA17). The Space is composed exactly as the bundle composes it: {@code tools/assemble.mjs} resolves the
 * Telco Product's Space and {@code tools/compose-space.mjs} composes it from the committed {@code telco-ra},
 * {@code telco-fraud} and {@code telco-bypass} templates, swapping their usage feeds ({@code switch_xdr}, {@code cdr},
 * {@code voice_cdr}) for the {@code telecom} Industry's {@code telecom_usage} Pipeline, which writes every record into
 * each App's store. The composed samples are ingested through the composed Space's own Pipelines; then
 * <ul>
 *   <li><b>Revenue Assurance</b>: every control flags exactly its planted leakages ({@link TelcoRaCorpus#planted}, the
 *       oracle {@code TelcoRaGoldenTest} pins over the template alone), so FM's traffic, bridged into RA's chain by the
 *       converter, plants no false leakage;</li>
 *   <li><b>Fraud Management</b>: every typology raises exactly the offenders it raises in a {@code telco-fraud} Space of
 *       its own (the oracle {@code TelcoFraudTemplateGoldenTest} pins), so RA's records stay quiet;</li>
 *   <li><b>bypass</b>: its SIM-day stage runs over the whole feed; its verdict, which needs the scoring module, is held by
 *       {@code TelcoComposedBypassTest} in {@code inspecto-scoring} (its template ships no usage sample;
 *       {@code BypassFeatureGoldenTest} plants its corpus in-test).</li>
 * </ul>
 * Needs {@code node} on the PATH, as the bundle build does.
 */
class TelcoComposedSpaceGoldenTest {

    private static final Path REPO = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Path TEMPLATES = REPO.resolve("spaces").resolve("_templates");
    private static final List<String> PARTS = List.of("telco-ra", "telco-fraud", "telco-bypass");
    private static final TelcoRaCorpus RA = TelcoRaCorpus.generate();
    private static final Map<String, String> DAY1 = Map.of("window_start", "2026-07-01 00:00:00",
            "window_end", "2026-07-02 00:00:00", "day", "2026-07-01");

    @BeforeEach
    void arm() {
        SpaceConfigRoot.clear();
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
    }

    @AfterEach
    void disarm() {
        com.gamma.etl.EditionFeatures.overrideForTest(null);
        SpaceConfigRoot.clear();
    }

    @Test
    void theComposedSpaceIngestsOneUsageFeedAndEveryAppFindsExactlyItsOwn(@TempDir Path root) throws Exception {
        Path templates = Files.createDirectories(root.resolve("_templates"));
        for (String part : PARTS) copyTree(TEMPLATES.resolve(part), templates.resolve(part));
        Path plan = root.resolve("plan.json");
        node("tools/assemble.mjs", "--product", "telco", "--edition", "standard", "--out", plan.toString());
        String out = node("tools/compose-space.mjs", "--plan", plan.toString(), "--templates", templates.toString());
        assertTrue(out.contains("one usage feed, telecom_usage -> switch_xdr, cdr, voice_cdr"), out);

        // ── the composed template: one usage Pipeline, the Apps' stores and every reader kept ──
        Path composed = templates.resolve("telco");
        for (String gone : List.of("switch_xdr", "cdr", "voice_cdr")) {
            assertFalse(Files.exists(composed.resolve("config").resolve(gone)), gone + ": the App's own usage Pipeline is swapped out");
            assertFalse(Files.exists(composed.resolve("data/samples").resolve(gone)), gone + ": its sample is converted, not shipped");
        }
        assertTrue(Files.isRegularFile(composed.resolve("config/telecom_usage/telecom_usage_pipeline.toon")));
        assertTrue(Files.readString(composed.resolve("config/registry/decision-rules/tf_long_roaming_call_escalate.toon"))
                .contains("target: telecom_usage"), "FM's ingest Decision Rule follows the usage feed");
        assertTrue(Files.isDirectory(TEMPLATES.resolve("telco-fraud/config/cdr")), "telco-fraud still ships its own feed");

        try (com.gamma.service.SpaceManager spaces = com.gamma.service.SpaceManager.discover(root)) {
            com.gamma.service.SpaceContext telco = spaces.createFromTemplate(com.gamma.service.SpaceId.of("telco"), null, null, "telco");
            com.gamma.service.SpaceContext fraud = spaces.createFromTemplate(com.gamma.service.SpaceId.of("fraud"), null, null, "telco-fraud");
            Path data = telco.root().base().resolve("data");
            Path config = telco.root().base().resolve("config");

            // ── ingest: every composed sample through the composed Space's own Pipelines ──
            Map<String, Integer> fed = ingest(telco);
            assertEquals(Set.of("balance_ledger", "billed_invoice", "ic_rates", "ic_statement", "mediated_xdr", "payments",
                    "rated_usage", "subscriber_events", "tariff", "telecom_usage"), fed.keySet(), "the feeds the composed Space ingests");
            assertTrue(telco.service().runPipeline("cdr").isEmpty(), "no App's own usage Pipeline is registered");
            ingest(fraud);
            Path usage = composed.resolve("data/samples/telecom_usage/TELECOM_USAGE_20260703.csv");
            long records = Files.readAllLines(usage).size() - 1;
            long mo = Files.readAllLines(usage).stream().skip(1).filter(l -> l.split(",", -1)[2].equals("MO")).count();
            long raRecords = Files.readAllLines(TEMPLATES.resolve("telco-ra/data/samples/switch_xdr/SWITCH_XDR_20260703.csv")).size() - 1;
            long fmRecords = Files.readAllLines(TEMPLATES.resolve("telco-fraud/data/samples/cdr/CDR_20260701.csv")).size() - 1;
            assertEquals(raRecords + fmRecords, records, "the one feed is the union of RA's and FM's usage samples");
            assertEquals(mo, count(data, "switch_xdr"), "RA's switch store holds every originated record");
            assertEquals(records, count(data, "cdr"), () -> "FM's store holds every record: " + listing(data));
            assertEquals(records, count(data, "voice_cdr"), "bypass's store holds every record");

            // ── Revenue Assurance: exactly its planted leakages ──
            JobService js = telco.service().jobService().orElseThrow();
            for (String day : TelcoRaCorpus.DAYS) {
                long done = runs(js, "ra_xdr_completeness");
                assertTrue(js.triggerRun("ra_xdr_completeness", null, Map.of("day", day)).isPresent(), day);
                JobRun run = await(() -> runs(js, "ra_xdr_completeness") > done ? js.lastRunOf("ra_xdr_completeness").orElse(null) : null);
                assertEquals("SUCCESS", run.status(), day + ": " + run.message());
            }
            for (String j : List.of("ra_xdr_lost", "ra_rated_vs_billed", "ra_rerating")) {
                assertTrue(js.triggerRun(j, null).isPresent(), j);
                assertEquals("SUCCESS", firstRun(js, j).status(), j);
            }
            for (String j : List.of("ra_rollforward", "ra_settlement", "ra_leakage", "ra_data_quality"))   // the on_signal chain
                assertEquals("SUCCESS", firstRun(js, j).status(), j);
            for (String control : List.of("ra_xdr_lost", "ra_rerating", "ra_rollforward", "ra_settlement")) {
                String store = "ra_xdr_lost".equals(control) ? control : control + "_findings";
                assertEquals(new TreeSet<>(RA.planted.get(control)), new TreeSet<>(rows(data, store, "ITEM_KEY || '|' || REASON")), control);
            }
            for (String recon : List.of("ra_xdr_completeness", "ra_rated_vs_billed")) {
                Set<String> flagged = new TreeSet<>();
                for (ReconBreaks.Break b : new ReconStateStore(config).read(recon).breaks())
                    flagged.add(b.pair() + "|" + b.type() + "|" + b.key());
                assertEquals(new TreeSet<>(RA.planted.get(recon)), flagged, recon);
            }
            assertEquals(17, rows(data, "ra_leakage", "CONTROL").size(), "leakage rows, as over the template alone");
            assertEquals(17, rows(data, "ra_data_quality", "CONTROL").size(), "data-quality rows, as over the template alone");

            // ── Fraud Management: each typology raises what it raises in a telco-fraud Space of its own ──
            JobService fjs = fraud.service().jobService().orElseThrow();
            Path fraudData = fraud.root().base().resolve("data");
            Path fraudConfig = fraud.root().base().resolve("config");
            List<String> fmJobs = sqlJobs(fraudConfig).stream().filter(n -> n.startsWith("fraud_")).toList();
            assertEquals(14, fmJobs.size(), "one detection Job per typology");
            // Every day of the composed feed: RA's records span 07-01…07-03, FM's 07-01 and 07-02.
            for (String day : TelcoRaCorpus.DAYS) {
                String next = java.time.LocalDate.parse(day).plusDays(1).toString();
                Map<String, String> window = Map.of("window_start", day + " 00:00:00", "window_end", next + " 00:00:00", "day", day);
                for (String j : fmJobs) {
                    assertEquals("SUCCESS", runJob(fjs, j, window).status(), "fraud alone: " + j + " " + day);
                    assertEquals("SUCCESS", runJob(js, j, window).status(), "composed: " + j + " " + day);
                }
            }
            Map<String, Set<String>> alone = raised(fraudConfig, fraudData);
            Map<String, Set<String>> together = raised(config, data);
            assertEquals(14, alone.size());
            assertTrue(alone.values().stream().allMatch(s -> !s.isEmpty()), "every typology raises its planted offenders: " + alone);
            assertEquals(alone, together, "every typology raises exactly what it raises over FM's own feed");
            for (String sink : fmJobs)   // each detection Job's sink is named for it
                assertEquals(allRows(fraudData, sink), allRows(data, sink), sink + ": the same rows as over FM's own feed");

            // ── bypass: its SIM-day stage reads the whole feed. Its verdict (risk score, findings, Alerts) needs the scoring
            //    module, so TelcoComposedBypassTest (inspecto-scoring) holds it. ──
            assertEquals("SUCCESS", runJob(js, "tb_features", DAY1).status(), "tb_features");
            assertTrue(count(data, "tb_features") > 0, "the SIM-day stage scores the feed's SIMs");
        }
    }

    // ── helpers ──

    /** Runs {@code node <script> <args>} from the repository root; the output, or a failure carrying it. */
    private static String node(String script, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("node", REPO.resolve(script).toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(REPO.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), script + " did not finish");
        assertEquals(0, p.exitValue(), script + ": " + out);
        return out;
    }

    /** Every samples feed of the Space into its inbox, then its Pipeline: each file ingested, none failed. */
    private static Map<String, Integer> ingest(com.gamma.service.SpaceContext space) throws Exception {
        Path data = space.root().base().resolve("data");
        Map<String, Integer> fed = new TreeMap<>();
        try (Stream<Path> feeds = Files.list(data.resolve("samples"))) {
            for (Path feed : feeds.sorted().toList()) {
                String name = feed.getFileName().toString();
                Path inbox = Files.createDirectories(data.resolve("inbox").resolve(name));
                int files = 0;
                try (Stream<Path> s = Files.list(feed)) {
                    for (Path f : s.sorted().toList()) { Files.copy(f, inbox.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING); files++; }
                }
                var r = space.service().runPipeline(name);
                assertTrue(r.isPresent(), name + ": the Pipeline is registered");
                // One run, one Consignment of the feed's files (an App's sample and the converter's bridging rows together).
                assertEquals(new com.gamma.inspector.MultiCollectorProcessor.RunResult(1, 0), r.get(), name + " ingests its files");
                fed.put(name, files);
            }
        }
        return fed;
    }

    /** Each Alert Rule's breached keys over the Space's findings Datasets. */
    private static Map<String, Set<String>> raised(Path config, Path data) {
        DatasetMeasureProbe probe = new DatasetMeasureProbe(() -> config, () -> data);
        Map<String, Set<String>> out = new TreeMap<>();
        for (ComponentRegistry.Component c : new ComponentStore(config.resolve("registry")).list("alert-rule")) {
            AlertRule r = AlertRule.fromMap(c.content());
            if (!r.name().startsWith("fraud_")) continue;
            DatasetMeasureProbe.Breaches b = probe.breaches(r.dataset(), r.measure(), r.by(), r.comparator(), r.threshold(), r.stormCap())
                    .orElseThrow(() -> new AssertionError(r.name() + ": probe failed"));
            Set<String> keys = new TreeSet<>();
            for (DatasetMeasureProbe.Breach br : b.keys()) keys.add(String.valueOf(br.key().get(r.by().get(0))));
            out.put(r.name(), keys);
        }
        return out;
    }

    private static List<String> sqlJobs(Path config) throws Exception {
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(config.resolve("jobs"))) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith("_job.toon")).sorted().toList()) {
                JobConfig j = JobConfig.fromMap(Map.of("job", ToonHelper.requireSection(ToonHelper.load(f.toString()), "job")));
                if ("sql.template".equals(j.type())) out.add(j.name());
            }
        }
        return out;
    }

    private static long runs(JobService js, String job) {
        return js.runsFor(job).stream().filter(r -> !"SKIPPED".equals(r.status())).count();
    }

    private static JobRun firstRun(JobService js, String job) throws Exception {
        return await(() -> js.runsFor(job).stream().filter(r -> !"SKIPPED".equals(r.status())).findFirst().orElse(null));
    }

    private static JobRun runJob(JobService js, String name, Map<String, String> args) throws Exception {
        String prior = js.lastRunOf(name).map(JobRun::runId).orElse(null);
        assertTrue(js.triggerRun(name, null, args).isPresent(), name);
        return await(() -> {
            Optional<JobRun> r = js.lastRunOf(name);
            return r.isPresent() && !r.get().runId().equals(prior) ? r.get() : null;
        });
    }

    private static long count(Path data, String store) throws Exception {
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM read_parquet('" + glob(data, store) + "', union_by_name = true)")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Every value of {@code expr} over the store, duplicates kept; {@code expr} may carry a trailing WHERE. */
    private static List<String> rows(Path data, String store, String expr) throws Exception {
        String select = expr.contains(" WHERE ") ? expr.substring(0, expr.indexOf(" WHERE ")) : expr;
        String where = expr.contains(" WHERE ") ? expr.substring(expr.indexOf(" WHERE ")) : "";
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + select + " FROM read_parquet('" + glob(data, store) + "', union_by_name = true)" + where)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    /** Every row of the store as one sorted string per row. */
    private static List<String> allRows(Path data, String store) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM read_parquet('" + glob(data, store) + "', union_by_name = true)")) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= n; i++) sb.append(rs.getMetaData().getColumnName(i)).append('=').append(rs.getString(i)).append(';');
                out.add(sb.toString());
            }
        }
        out.sort(null);
        return out;
    }

    private static String listing(Path data) {
        try (Stream<Path> s = Files.walk(data)) {
            return String.join(" | ", s.filter(Files::isRegularFile).map(p -> data.relativize(p).toString()).filter(p -> !p.startsWith("samples") && !p.startsWith("inbox")).sorted().toList());
        } catch (Exception e) { return e.toString(); }
    }

    private static String glob(Path data, String store) {
        return data.resolve(store).toString().replace('\\', '/') + "/**/*.parquet";
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
        long deadline = System.nanoTime() + 60_000_000_000L;
        JobRun r;
        while ((r = s.get()) == null && System.nanoTime() < deadline) Thread.sleep(50);
        assertNotNull(r, "expected a job run within 60s");
        return r;
    }
}
