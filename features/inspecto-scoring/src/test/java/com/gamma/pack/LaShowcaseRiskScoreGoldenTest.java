package com.gamma.pack;

import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.CollectorProcessor;
import com.gamma.job.JobConfig;
import com.gamma.mask.EvidenceMasker;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.DatasetRelation;
import com.gamma.risk.RiskScoreEvaluator;
import com.gamma.risk.RiskScoreModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code LA-SUSPICION-FACTORS-DEMO-1}: the {@code la-showcase} template ships the {@code mule_account} Risk Score so
 * the Link Analysis Suspicion score table can open per-node Factors. Over the template's landed synthetic transfers,
 * MULE-HUB-01 is the only high account, and every one of its three factors contributes.
 */
class LaShowcaseRiskScoreGoldenTest {

    private static final Path TEMPLATE = Path.of("..", "..", "spaces", "_templates", "la-showcase").toAbsolutePath().normalize();

    @Test
    void theMuleHubIsTheOnlyHighAccountWithEveryFactorContributing(@TempDir Path tmp) throws Exception {
        Path space = copyTemplate(tmp);
        Path cfg = space.resolve("config"), data = space.resolve("data");
        CollectorProcessor.run(PipelineConfig.load(cfg.resolve("mule/mule_transfers_pipeline.toon").toString()));

        ComponentStore store = new ComponentStore(cfg.resolve("registry"));
        Map<String, Object> content = store.get("risk-score", "mule_account").orElseThrow().content();
        RiskScoreModel model = RiskScoreModel.fromMap("mule_account", content);
        var run = RiskScoreEvaluator.evaluate(model, id -> DatasetRelation.relationSql(
                store.get("dataset", id).map(ComponentRegistry.Component::content).orElseThrow(), data, null),
                EvidenceMasker.of(store, cfg, model.datasetIds()));

        Map<String, Double> high = new TreeMap<>();
        run.scored().stream().filter(s -> s.score() >= model.highThreshold()).forEach(s -> high.put(s.entityKey(), s.score()));
        assertEquals(Map.of("MULE-HUB-01", 100.0), high);
        var hub = run.scored().stream().filter(s -> s.entityKey().equals("MULE-HUB-01")).findFirst().orElseThrow();
        assertEquals(3, hub.factors().size());
        hub.factors().forEach(f -> assertTrue(f.contribution() > 0, f.indicator() + " contributes"));

        JobConfig job = JobConfig.load(cfg.resolve("jobs/mule_risk_score_job.toon").toString());
        assertEquals("risk.score", job.type());
        assertEquals("mule_account", job.params().get("model"));
    }

    private static Path copyTemplate(Path tmp) throws Exception {
        Path space = tmp.resolve("spaces").resolve("la-showcase");
        try (Stream<Path> w = Files.walk(TEMPLATE)) {
            for (Path p : w.toList()) {
                Path to = space.resolve(TEMPLATE.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else Files.copy(p, to);
            }
        }
        return space;
    }
}
