package com.gamma.control;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ASSURE-ACTION-REQUESTS-1} round-2 finding 1c — <b>every writer that can write a {@code decision-rule} runs
 * {@link DecisionRuleGuard}</b>: the invoke-api gate and the server-stamped makers. The enumeration is
 * {@link ConfigWriteFunnelTest}'s repo-wide writer inventory (every {@code *store*.write|delete(},
 * {@code BundleImporter.writeConfig(} and {@code ActionRequests.save(} call site, by {@code Class#method}), so a NEW
 * writer fails here until it either calls the guard, is named on {@link #GUARDED_BY} with the method that guards it
 * (whose body must call it), or is on {@link #NOT_A_RULE_WRITER} with the reason it cannot write a Decision Rule.
 */
class DecisionRuleWritersTest {

    /** A writer whose guard runs in another method before it — that method's body must call {@code DecisionRuleGuard.}. */
    static final Map<String, Set<String>> GUARDED_BY = new TreeMap<>(Map.of(
            "DecisionRoutes#write", Set.of("DecisionRoutes#create", "DecisionRoutes#update"),
            "BundleRoutes#write", Set.of("BundleRoutes#preparedDecisionRules"),
            "SpaceManager#createFromBundle", Set.of("SpaceRoutes#importSpace"),
            // background template materializer (operator, 2026-10-06); refuses while an approval policy governs
            // alert-rule/dataset — and onRiskScoreProduced runs DecisionRuleGuard.refuseUnattended before its writes
            "PendingAlertRules#ensureLatestDataset", Set.of("PendingAlertRules#onRiskScoreProduced")));

    private static final String FIXED_KIND = "writes a fixed kind other than decision-rule";
    private static final String NOT_CONFIG = "writes no component — operational state or objects";

    /** Writer sites that cannot write a decision-rule, each with its reason. */
    static final Map<String, String> NOT_A_RULE_WRITER = new TreeMap<>(Map.ofEntries(
            Map.entry("AccessRoutes#write", FIXED_KIND + " (access catalog / profiles)"),
            Map.entry("AlertRoutes#write", FIXED_KIND + " (alert-rule)"),
            Map.entry("ExpectationRoutes#write", FIXED_KIND + " (expectation)"),
            Map.entry("ExpectationRoutes#runAndPersist", FIXED_KIND + " (an expectation's lastResult stamp)"),
            Map.entry("DecisionRoutes#simulate", "re-writes the STORED rule with a lastSimulation stamp only "
                    + "(archive=false) — no body content, the stamps and invoke-api consequences are the stored ones"),
            Map.entry("DecisionRoutes#delete", "deletes; a deleted rule's history is purged with it"),
            Map.entry("ComponentRoutes#deleteComponent", "deletes; a deleted rule's history is purged with it"),
            Map.entry("AccessRoutes#deleteProfile", FIXED_KIND + " (access-profile delete)"),
            Map.entry("AlertRoutes#delete", FIXED_KIND + " (alert-rule delete)"),
            Map.entry("ExpectationRoutes#delete", FIXED_KIND + " (expectation delete)"),
            Map.entry("BiTemplates#apply", FIXED_KIND + " (the templates carry widget and dashboard items only)"),
            Map.entry("InvestigationMeasureRoutes#bind", FIXED_KIND + " (alert-rule)"),
            Map.entry("NotificationRoutes#write", FIXED_KIND + " (channel)"),
            Map.entry("NotificationRoutes#deleteChannel", FIXED_KIND + " (channel)"),
            Map.entry("NotificationRoutes#writeRule", FIXED_KIND + " (notification-rule)"),
            Map.entry("NotificationRoutes#deleteRule", FIXED_KIND + " (notification-rule)"),
            Map.entry("RequirementRoutes#write", FIXED_KIND + " (requirement)"),
            Map.entry("RequirementRoutes#createKpi", FIXED_KIND + " (kpi — the constant KPI_TYPE = KpiRoutes.TYPE; the "
                    + "request body supplies only the KPI's content and id, never the kind)"),
            Map.entry("PipelineListRoutes#deletePipeline", FIXED_KIND + " (authored-pipeline)"),
            Map.entry("PipelineRenameRoutes#rewriteDatasetRefs", FIXED_KIND + " (dataset)"),
            Map.entry("PipelineRenameRoutes#rewriteAlertRules", FIXED_KIND + " (alert-rule)"),
            Map.entry("WidgetTags#reproject", FIXED_KIND + " (widget)"),
            Map.entry("ObjectService#discard", NOT_CONFIG), Map.entry("ObjectService#purge", NOT_CONFIG),
            Map.entry("ConsignmentIngestor#parkSource", NOT_CONFIG),
            Map.entry("ConsignmentIngestor#finalizeSource", NOT_CONFIG),
            Map.entry("BackupTask#catalogRow", FIXED_KIND + " (a catalog result stamp)"),
            Map.entry("StorageReportTask#storageCatalog", FIXED_KIND + " (a catalog result stamp)"),
            Map.entry("ObjectsAnalyticsJob#run", FIXED_KIND + " (a catalog result stamp)"),
            Map.entry("MaterializeTask#run", FIXED_KIND + " (dataset provenance keys)"),
            Map.entry("EntityListRoutes#list", NOT_CONFIG), Map.entry("EntityListRoutes#one", NOT_CONFIG),
            Map.entry("EntityListRoutes#create", NOT_CONFIG), Map.entry("EntityListRoutes#members", NOT_CONFIG),
            Map.entry("EntityListRoutes#retire", NOT_CONFIG), Map.entry("InvestigationRoutes#sealList", NOT_CONFIG),
            Map.entry("InvestigationRoutes#sealResolution", NOT_CONFIG),
            Map.entry("ValueMeasures#agents", NOT_CONFIG),   // operator-approved 2026-09-30   // operator-approved 2026-09-30
            Map.entry("EntityListRoutes#registerDataset", FIXED_KIND + " (dataset)"),
            Map.entry("EntityListRoutes#match", NOT_CONFIG), Map.entry("RiskWatchListFeed#check", NOT_CONFIG),
            Map.entry("RiskWatchListFeed#feed", NOT_CONFIG),
            Map.entry("EntityIdentityRoutes#assertIdentity", NOT_CONFIG), Map.entry("EntityIdentityRoutes#retract", NOT_CONFIG),
            Map.entry("EntityIdentityRoutes#importDataset", NOT_CONFIG),   // operator-approved 2026-09-30
            Map.entry("EntityIdentityRoutes#groups", NOT_CONFIG), Map.entry("EntityIdentityRoutes#group", NOT_CONFIG),
            Map.entry("ActionRequestRoutes#propose", NOT_CONFIG),
            Map.entry("ActionRequestRoutes#decide", NOT_CONFIG), Map.entry("ActionRequestRoutes#retry", NOT_CONFIG),
            Map.entry("ActionRequestRoutes#markFailed", NOT_CONFIG),
            Map.entry("Investigator#writeFixDraft", "refuses the decision-rule kind before writing — the agent never "
                    + "drafts a Decision Rule (inspecto-intelligence cannot see DecisionRuleGuard)"),
            Map.entry("ActionDispatcher#fail", NOT_CONFIG), Map.entry("ActionDispatcher#run", NOT_CONFIG)
    ));

    @Test
    void everyWriterThatCanWriteADecisionRuleRunsTheGuard() throws IOException {
        Map<String, Boolean> sites = new TreeMap<>();
        Map<String, String> bodies = new TreeMap<>();   // Class#method → body (overloads concatenated)
        Path reactor = Path.of("..").toAbsolutePath().normalize();
        try (Stream<Path> siblings = Files.list(reactor)) {
            for (Path sibling : siblings.filter(Files::isDirectory).sorted().toList()) {
                Path src = sibling.resolve(Path.of("src", "main", "java"));
                if (!Files.isDirectory(src)) continue;
                try (Stream<Path> files = Files.walk(src)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                        ConfigWriteFunnelTest.writerSites(f, sites);
                        String cls = f.getFileName().toString().replaceFirst("\\.java$", "");
                        ConfigWriteFunnelTest.methodBodies(ConfigWriteFunnelTest.withoutComments(Files.readString(f)))
                                .forEach((m, b) -> bodies.put(cls + "#" + m, b));
                    }
                }
            }
        }
        for (String pinned : new String[] {"ComponentRoutes#writeComponent", "DataSourceRoutes#importBundle",
                "PipelineRenameRoutes#rewriteComponentTargets", "BundleRoutes#write", "SpaceManager#createFromBundle"})
            assertTrue(sites.containsKey(pinned), "the writer scan went blind to " + pinned + ": " + sites.keySet());

        Set<String> open = new LinkedHashSet<>();
        for (String site : sites.keySet()) {
            if (calls(bodies, site) || NOT_A_RULE_WRITER.containsKey(site)) continue;
            Set<String> by = GUARDED_BY.get(site);
            if (by != null && by.stream().allMatch(g -> calls(bodies, g))) continue;
            open.add(site + (by == null ? "" : "  (GUARDED_BY names " + by + ", which do not all call DecisionRuleGuard)"));
        }
        assertTrue(open.isEmpty(), () -> "writers that could write a decision-rule without DecisionRuleGuard — call "
                + "DecisionRuleGuard.prepare/guardImport before the write, or list the site with its reason:\n  "
                + String.join("\n  ", open));

        Set<String> stale = new LinkedHashSet<>(NOT_A_RULE_WRITER.keySet());
        stale.addAll(GUARDED_BY.keySet());
        stale.removeAll(sites.keySet());
        assertTrue(stale.isEmpty(), () -> "rows naming no writer site any more: " + stale);
        Set<String> guardedButListed = new LinkedHashSet<>();
        for (String k : NOT_A_RULE_WRITER.keySet()) if (calls(bodies, k)) guardedButListed.add(k);
        assertTrue(guardedButListed.isEmpty(), () -> "listed as not a rule writer but calls the guard: " + guardedButListed);
    }

    private static boolean calls(Map<String, String> bodies, String site) {
        String b = bodies.get(site);
        return b != null && b.contains("DecisionRuleGuard.");
    }
}
