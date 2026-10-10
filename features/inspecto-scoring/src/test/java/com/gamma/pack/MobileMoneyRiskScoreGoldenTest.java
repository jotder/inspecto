package com.gamma.pack;

import com.gamma.alert.AlertRule;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.CollectorProcessor;
import com.gamma.job.JobConfig;
import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.gamma.mask.EvidenceMasker;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.DatasetRelation;
import com.gamma.risk.RiskScoreEvaluator;
import com.gamma.risk.RiskScoreModel;
import com.gamma.util.Scheduler;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code PACK-MOBILE-MONEY-1}: the {@code mobile-money} template's per-agent Risk Score ({@code mm_agent}), run over
 * the committed synthetic corpus (generated and pinned by {@code MobileMoneyPackGoldenTest} in the Reconciliation
 * module — no module carries both add-ons). The template's Pipelines ingest the samples, the three agent typology
 * Jobs build their Datasets for the golden day, and the model is evaluated as the {@code risk.score} Job does. The
 * high-risk set is EXACT: the two round-trip agents, and one agent whose split and round-trip signals each sit AT
 * their own Alert Rule's threshold (silent there) but score 60 together.
 */
class MobileMoneyRiskScoreGoldenTest {

    private static final Path TEMPLATE = Path.of("..", "..", "spaces", "_templates", "mobile-money").toAbsolutePath().normalize();
    private static final List<String> AGENT_JOBS = List.of("mm_agent_split", "mm_round_trip", "mm_commission");
    private static final Map<String, String> DAY1 =
            Map.of("window_start", "2026-07-01 00:00:00", "window_end", "2026-07-02 00:00:00");

    @Test
    void theAgentRiskScoreRatesExactlyThePlantedAgentsHigh(@TempDir Path tmp) throws Exception {
        Path space = copyTemplate(tmp);
        Path cfg = space.resolve("config"), data = space.resolve("data");
        ingest(space, "wallet_txn");
        runAgentJobs(space);

        ComponentStore store = new ComponentStore(cfg.resolve("registry"));
        Map<String, Object> content = store.get("risk-score", "mm_agent").orElseThrow().content();
        RiskScoreModel model = RiskScoreModel.fromMap("mm_agent", content);
        assertEquals(60.0, model.highThreshold());
        var run = RiskScoreEvaluator.evaluate(model, id -> DatasetRelation.relationSql(
                store.get("dataset", id).map(ComponentRegistry.Component::content).orElseThrow(), data, null),
                EvidenceMasker.of(store, cfg, model.datasetIds()));
        RiskScoreEvaluator.write(data, model, RiskScoreEvaluator.version(content), "golden", Instant.now(), run.scored());

        Map<String, Double> high = new TreeMap<>();
        run.scored().stream().filter(s -> s.score() >= model.highThreshold()).forEach(s -> high.put(s.entityKey(), s.score()));
        assertEquals(Map.of("A701", 60.0, "A702", 60.0, "A720", 60.0), high,
                "A701: 4 round-trip wallets x 15; A702: 5 x 15 capped at 60; A720: 3 near-limit cash-ins x 10 + 2 round-trip wallets x 15");

        // Each signal alone stays under the high threshold: the strongest split agent, the largest commission variance.
        Map<String, Double> scores = new TreeMap<>();
        run.scored().forEach(s -> scores.put(s.entityKey(), s.score()));
        assertEquals(50.0, scores.get("A803"), "6 near-limit cash-ins x 10, capped at 50");
        assertEquals(30.0, scores.get("A901"), "50 USD commission variance x 1, capped at 30");
        assertEquals(45.0, scores.get("A703"), "3 round-trip wallets x 15");
        assertEquals(30.0, scores.get("A721"), "one signal at its threshold");
    }

    /** The template's Alert Rule over the score is PENDING until the score first runs (okf spaces §3.5.2). */
    @Test
    void theHighRiskAgentAlertRuleShipsPendingOnTheModelsLatestScores() throws Exception {
        Path pending = TEMPLATE.resolve("config/pending/alert-rules/mm_high_risk_agent.toon");
        Map<String, Object> body = ToonHelper.load(pending.toString());
        assertEquals(Map.of("kind", "risk-score", "model", "mm_agent"), body.get("afterScore"));
        AlertRule rule = AlertRule.fromMap(new java.util.HashMap<>(Map.of("name", "mm_high_risk_agent",
                "dataset", body.get("dataset"), "measure", body.get("measure"), "by", body.get("by"),
                "comparator", body.get("comparator"), "threshold", body.get("threshold"))));
        assertEquals("risk_scores_mm_agent_latest", rule.dataset());
        assertEquals(List.of("model", "entity_key"), rule.by());
        assertFalse(Files.exists(TEMPLATE.resolve("config/registry/alert-rules/mm_high_risk_agent.toon")),
                "the rule must not ship armed: its Dataset does not exist before the first score run");
    }

    private static Path copyTemplate(Path tmp) throws Exception {
        Path space = tmp.resolve("spaces").resolve("mobile-money");
        try (Stream<Path> w = Files.walk(TEMPLATE)) {
            for (Path p : w.toList()) {
                Path to = space.resolve(TEMPLATE.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else Files.copy(p, to);
            }
        }
        return space;
    }

    private static void ingest(Path space, String feed) throws Exception {
        PipelineConfig pc = PipelineConfig.load(space.resolve("config/" + feed + "/" + feed + "_pipeline.toon").toString());
        Path inbox = Files.createDirectories(Path.of(pc.dirs().poll()));
        try (Stream<Path> f = Files.list(space.resolve("data/samples/" + feed))) {
            for (Path p : f.toList()) Files.copy(p, inbox.resolve(p.getFileName()));
        }
        CollectorProcessor.run(pc);
    }

    private static void runAgentJobs(Path space) throws Exception {
        List<JobConfig> jobs = new ArrayList<>();
        for (String j : AGENT_JOBS) jobs.add(JobConfig.load(space.resolve("config/jobs/" + j + "_job.toon").toString()));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(jobs, new ConsignmentEventBus(), s, null,
                     space.resolve("audit").toString(), null, null, space.resolve("data").toString())) {
            js.start();
            for (String j : AGENT_JOBS) {
                assertTrue(js.triggerRun(j, null, DAY1).isPresent(), j);
                Optional<JobRun> r = Optional.empty();
                long deadline = System.nanoTime() + 30_000_000_000L;
                while ((r = js.lastRunOf(j)).isEmpty() && System.nanoTime() < deadline) Thread.sleep(50);
                assertTrue(r.isPresent(), j + " never ran");
                assertEquals("SUCCESS", r.get().status(), j + " failed: " + r.get().message());
            }
        }
    }
}
