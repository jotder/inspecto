package com.gamma.control;

import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.CollectorProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bypass App in the Inspecto Telco Composed Space with ONE usage feed ({@code PRODUCT-ASSEMBLY-1} PA-5c, D-PA17). The
 * Space is composed as the bundle composes it ({@code tools/assemble.mjs} + {@code tools/compose-space.mjs}), its
 * {@code telecom_usage} Pipeline ingests the composed sample (RA's and FM's usage records; the bypass template ships none)
 * into {@code voice_cdr}, and bypass's whole nightly chain runs as authored for every day of the feed. RA, FM and the feed
 * itself are held by {@code TelcoComposedSpaceGoldenTest} (inspecto-reconciliation).
 *
 * <p>Held to EXACTLY ONE cross-App finding, pinned: FM's five planted premium-rate callers ({@code 999700012xx}) all
 * originate from one cell ({@code C500}), each dialling a new number almost every call, briefly, receiving nothing, so
 * bypass's cluster rule {@code tb_box_suspected} opens one Incident for {@code cell:C500} on the first night. No SIM-level
 * bypass rule fires (no finding reaches {@code status = alert}: one day of traffic never meets the persistence gate). A
 * second finding, or none, means the corpora or a control changed and must be looked at.
 */
class TelcoComposedBypassTest {

    private static final Path REPO = Path.of("..", "..").toAbsolutePath().normalize();
    private static final List<String> PARTS = List.of("telco-ra", "telco-fraud", "telco-bypass");
    private static final Set<String> BYPASS_RULES = Set.of("tb_sim_high", "tb_allowed_line_changed", "tb_box_suspected");
    private static final List<String> DAYS = List.of("2026-07-01", "2026-07-02", "2026-07-03");

    @Test
    void theOtherAppsTrafficRaisesOnlyThePinnedCrossAppIncident(@TempDir Path tmp) throws Exception {
        Path templates = Files.createDirectories(tmp.resolve("_templates"));
        for (String part : PARTS) copyTree(REPO.resolve("spaces/_templates").resolve(part), templates.resolve(part));
        Path plan = tmp.resolve("plan.json");
        node("tools/assemble.mjs", "--product", "telco", "--edition", "standard", "--out", plan.toString());
        node("tools/compose-space.mjs", "--plan", plan.toString(), "--templates", templates.toString());
        Path space = templates.resolve("telco");
        Path data = space.resolve("data");

        PipelineConfig cfg = PipelineConfig.load(space.resolve("config/telecom_usage/telecom_usage_pipeline.toon").toString());
        Path poll = Files.createDirectories(Path.of(cfg.dirs().poll()).toAbsolutePath().normalize());
        assertTrue(poll.startsWith(space), "the usage feed polls inside its Space: " + poll);
        Path sample = space.resolve("data/samples/telecom_usage/TELECOM_USAGE_20260703.csv");
        Files.copy(sample, poll.resolve(sample.getFileName()));
        CollectorProcessor.run(cfg);
        long records = Files.readAllLines(sample).size() - 1;
        assertEquals(records, BypassPack.count(data.resolve("voice_cdr/database"), "union_by_name = true"),
                "bypass's store holds every usage record");

        BypassPack.Sweep sweep = new BypassPack.Sweep(space, BYPASS_RULES);
        assertEquals(BYPASS_RULES, new TreeSet<>(sweep.rules.stream().map(r -> r.name()).toList()));
        Set<String> boxLike = new TreeSet<>();
        Map<String, Set<String>> raised = Map.of();
        for (String day : DAYS) {
            BypassPack.runNight(space, day, Map.of());
            BypassPack.rows(data, "tb_features", "msisdn", "window_date = DATE '" + day + "' AND box_like").keySet().forEach(boxLike::add);
            Map<String, Map<String, Object>> findings = BypassPack.rows(data, "tb_findings", "entity_key", "window_date = DATE '" + day + "'");
            assertTrue(findings.values().stream().noneMatch(f -> "alert".equals(f.get("status"))), day + ": no SIM finding alerts: " + findings);
            raised = sweep.evaluate();   // cumulative
            assertEquals(Map.of("tb_box_suspected", Set.of("cell:C500")), raised, day + ": the one cross-App finding, from the first night on");
        }
        assertEquals(1, sweep.incidents(), "one bypass Incident, for the cell FM's premium-rate callers share");
        assertEquals(new TreeSet<>(List.of("99970001201", "99970001202", "99970001203", "99970001204", "99970001205", "99970001211")),
                boxLike, "the SIM-day box shape: FM's five premium-rate callers and one more of FM's lines");
    }

    private static String node(String script, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("node", REPO.resolve(script).toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(REPO.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), script + " did not finish");
        assertEquals(0, p.exitValue(), script + ": " + out);
        return out;
    }

    private static void copyTree(Path from, Path to) throws Exception {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target);
            }
        }
    }
}
