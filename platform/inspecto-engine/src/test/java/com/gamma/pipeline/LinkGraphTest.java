package com.gamma.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkGraphTest {

    private static ComponentRegistry.Component c(String type, String name, Map<String, Object> content) {
        return new ComponentRegistry.Component(type, name, null, content);
    }

    @Test
    void censusDeclaresEachLinkOnce() {
        Set<String> seen = new HashSet<>();
        for (LinkCensus.Link l : LinkCensus.LINKS)
            // one key may hold two links to different targets (a virtual Dataset's sql: its store, its columns - LC-5)
            assertTrue(seen.add(l.fromKind() + ":" + l.keyPath() + "->" + l.toKind()), "declared twice: " + l);
        assertEquals("store", LinkCensus.of("dataset", "sql").toKind(), "the two-arg lookup keeps the first link");
        assertThrows(IllegalArgumentException.class, () -> LinkCensus.of("widget", "nope"));
        assertFalse(LinkCensus.of("job", "pipeline_config").enforced(), "an anatomy 'none' row stays unenforced");
    }

    @Test
    void componentIdLinksResolveOrDangleInComponentThenCensusOrder() {
        Map<String, List<ComponentRegistry.Component>> byType = Map.of(
                "dataset", List.of(c("dataset", "orders", Map.of())),
                "widget", List.of(c("widget", "w1", Map.of("datasetId", "orders", "queryId", "gone")),
                        c("widget", "w2", Map.of("datasetId", "missing", "queryId", " "))),
                "dashboard", List.of(c("dashboard", "d", Map.of("tiles", List.of(Map.of("widgetId", "w1"),
                        Map.of("widgetId", "w9"))))));
        LinkGraph g = LinkGraph.ofComponents(byType, null);

        List<String> widgetEdges = g.from("widget").stream().map(e -> e.fromName() + ":" + e.key() + "=" + e.status()).toList();
        assertEquals(List.of("w1:datasetId=RESOLVED", "w1:queryId=DANGLING", "w2:datasetId=DANGLING"), widgetEdges,
                "a blank value is no link; datasetId before queryId within a widget");
        List<LinkGraph.Edge> tiles = g.edges("dashboard", "tiles[].widgetId");
        assertEquals("tiles[1].widgetId", tiles.get(1).key());
        assertEquals(LinkGraph.Status.DANGLING, tiles.get(1).status());
    }

    @Test
    void aScalarPathReadsAListOrMapValueAsItsTextOnlyBracketsSplit() {
        Map<String, List<ComponentRegistry.Component>> byType = Map.of(
                "widget", List.of(c("widget", "w", Map.of("datasetId", Map.of("k", "v"), "queryId", List.of("q")))),
                "reconciliation", List.of(c("reconciliation", "r", Map.of("leftDataset", List.of("a", "q")))));
        List<String> values = LinkGraph.ofComponents(byType, null).dangling().stream()
                .map(e -> e.key() + "=" + e.value()).toList();
        assertEquals(List.of("datasetId={k=v}", "queryId=[q]", "leftDataset=[a, q]"), values);
    }

    @Test
    void reconciliationReadsItsDatasetsListElseLeftAndRight() {
        Map<String, List<ComponentRegistry.Component>> byType = Map.of(
                "reconciliation", List.of(
                        c("reconciliation", "v2", Map.of("datasets", List.of("a", ""), "leftDataset", "ignored",
                                "thirdDataset", "ignored")),
                        c("reconciliation", "v1", Map.of("leftDataset", "a", "rightDataset", "b", "thirdDataset", "c"))),
                "dataset", List.of(c("dataset", "a", Map.of())));
        List<String> dangling = LinkGraph.ofComponents(byType, null).dangling().stream()
                .map(e -> e.fromName() + ":" + e.value()).toList();
        assertEquals(List.of("v2:", "v1:b", "v1:c"), dangling,
                "a blank list side dangles; a v1 thirdDataset is judged like left/right (PIPELINE-LINK-GAPS-1 e)");
    }

    @Test
    void aCommaListOnPipelineIsOneEdgePerMember(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("orders_pipeline.toon"), "id: orders\n");
        Files.writeString(root.resolve("fx_pipeline.toon"), "id: fx\n");
        Files.writeString(root.resolve("both_job.toon"), "job:\n  on_pipeline: \"orders, fx ,gone\"\n");
        LinkGraph g = LinkGraph.of(root, rel -> false);
        assertEquals(List.of("orders=RESOLVED", "fx=RESOLVED", "gone=DANGLING"), g.edges("job", "on_pipeline").stream()
                .map(e -> e.value() + "=" + e.status()).toList(), "JobConfig.onPipelines: split, trimmed, each judged");
    }

    @Test
    void pipelineTargetsHonourTargetTypeAndAKnownUniverse() {
        Map<String, List<ComponentRegistry.Component>> byType = Map.of("expectation", List.of(
                c("expectation", "e1", Map.of("target", " Orders ")),
                c("expectation", "e2", Map.of("target", "nightly", "targetType", "job")),
                c("expectation", "e3", Map.of("target", "gone"))),
                "decision-rule", List.of(c("decision-rule", "r", Map.of("target", "nope", "targetType", "dataset"))));
        List<LinkGraph.Edge> known = LinkGraph.ofComponents(byType, Set.of("ORDERS")).edges("expectation", "target");
        assertEquals(List.of(LinkGraph.Status.RESOLVED, LinkGraph.Status.UNCHECKED, LinkGraph.Status.DANGLING),
                known.stream().map(LinkGraph.Edge::status).toList());
        assertEquals("job", known.get(1).toKind());
        assertTrue(LinkGraph.ofComponents(byType, null).dangling().isEmpty(),
                "no universe, no judgement; a dataset-typed rule target is not judged as a Dataset link");
    }

    /** CENSUS-EXPECTATION-TARGET-PATH-1: an Expectation target is a data path; its first segment is the store. */
    @Test
    void anExpectationTargetIsAStorePathJudgedByItsFirstSegment() {
        Map<String, List<ComponentRegistry.Component>> byType = Map.of(
                "expectation", List.of(
                        c("expectation", "path", Map.of("target", "Orders/database")),
                        c("expectation", "nostore", Map.of("target", "gone/database"))),
                "decision-rule", List.of(c("decision-rule", "r", Map.of("target", "orders/database"))));
        LinkGraph g = LinkGraph.ofComponents(byType, Set.of("orders"));
        assertEquals(List.of(LinkGraph.Status.RESOLVED, LinkGraph.Status.DANGLING),
                g.edges("expectation", "target").stream().map(LinkGraph.Edge::status).toList());
        assertEquals(LinkGraph.Status.DANGLING, g.edges("decision-rule", "target").get(0).status(),
                "a Decision Rule target is a bare id, not a path");
    }

    @Test
    void configRootReadsTheLoaderFileSetAtAnyDepthSkippingReserved(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("orders"));
        Files.createDirectories(root.resolve("jobs"));
        Files.createDirectories(root.resolve(".history"));
        Files.createDirectories(root.resolve("registry/datasets"));
        Files.writeString(root.resolve("orders/orders_pipeline.toon"), "name: Orders\n");
        Files.writeString(root.resolve("orders/daily_enrich.toon"),
                "triggers:\n  on_pipeline: orders\nreferences:\n  dim:\n    ref: nowhere\n    path: data/x.csv\n");
        Files.writeString(root.resolve("jobs/nightly_job.toon"), "job:\n  on_pipeline: orders\n  dataset: sales\n");
        Files.writeString(root.resolve(".history/old_enrich.toon"), "triggers:\n  on_pipeline: orders\n");
        Files.writeString(root.resolve("registry/datasets/sales.toon"), "name: sales\nphysicalRef: orders/database\n");

        LinkGraph g = LinkGraph.of(root, rel -> rel.replace('\\', '/').startsWith(".history"));

        assertEquals(List.of("daily_enrich"), g.edges("enrichment", "triggers.on_pipeline").stream()
                .map(LinkGraph.Edge::fromName).toList(), "a sub-folder file counts; a reserved one does not");
        assertEquals(LinkGraph.Status.RESOLVED, g.edges("enrichment", "triggers.on_pipeline").get(0).status());
        LinkGraph.Edge ref = g.edges("enrichment", "references.*.ref").get(0);
        assertEquals("references.dim.ref", ref.key());
        assertEquals(LinkGraph.Status.DANGLING, ref.status(), "the pipeline universe comes from the pipeline files");
        assertEquals(LinkGraph.Status.UNCHECKED, g.edges("enrichment", "references.*.path").get(0).status());
        assertEquals("orders", g.edges("job", "on_pipeline").get(0).value(), "a Job's keys are read in job:");
        assertEquals(LinkGraph.Status.UNCHECKED, g.edges("job", "dataset").get(0).status(), "not enforced yet");
        assertEquals("orders/database", g.edges("dataset", "physicalRef").get(0).value());
        assertEquals(LinkGraph.Status.UNCHECKED, g.edges("dataset", "physicalRef").get(0).status(), "stores are not judged");
    }

    @Test
    void anAbsentRootIsAnEmptyGraph(@TempDir Path root) {
        assertTrue(LinkGraph.of(root.resolve("nope"), rel -> false).edges().isEmpty());
    }
}
