package com.gamma.catalog;

import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Studio BI half of the metadata graph — the {@code DATASET} / {@code WIDGET} /
 * {@code DASHBOARD} nodes {@code MetadataGraphBuilder.addStudioLayer} reads through
 * {@link ConfigSource#components}, and the {@code BINDS_TO} edge that joins a Studio Dataset to the
 * catalog origin it reads.
 *
 * <p>The interesting cases are all the ones where the bridge does <em>not</em> resolve, because a
 * Studio Dataset is a separate persistence system ({@code registry/datasets/*.toon}) whose reference
 * to the catalog is a name, not an id: it may name a Job output store the catalog has no node kind
 * for, or nothing at all ({@code kind: virtual}, {@code physicalRef: null}). Those Datasets still get
 * a node, carrying {@code resolved=false} — a missing node is the silent incompleteness this layer
 * exists to end.
 */
class MetadataGraphStudioLayerTest {

    /** The pipeline {@link PipelineConfigBatchTest#writePipeline} writes, hence the origin name. */
    private static final String ORIGIN = "mini_etl";

    /** A {@link ConfigSource} whose Studio half is settable, unlike {@code MetadataGraphServiceTest}'s. */
    private static final class Fixture implements ConfigSource {
        final List<PipelineConfig> pipelines = new ArrayList<>();
        final Map<String, List<ComponentRegistry.Component>> components = new LinkedHashMap<>();
        public List<PipelineConfig> pipelines() { return pipelines; }
        public List<EnrichmentConfig> enrichments() { return List.of(); }
        public List<SemanticModel> semantics() { return List.of(); }
        public List<ComponentRegistry.Component> components(String type) {
            return components.getOrDefault(type, List.of());
        }
    }

    private Fixture fixture(Path dir) throws Exception {
        Fixture f = new Fixture();
        f.pipelines.add(PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString()));
        return f;
    }

    /** Registers one component, mirroring how {@code ComponentStore} names a {@code registry/} file. */
    private static void component(Fixture f, String type, String name, Map<String, Object> content) {
        f.components.computeIfAbsent(type, k -> new ArrayList<>()).add(new ComponentRegistry.Component(
                type, name, Path.of("registry", type + "s", name + ".toon"), content));
    }

    private static MetadataGraph build(Fixture f) {
        return new MetadataGraphService(f).structural();
    }

    private static boolean hasEdge(MetadataGraph g, String from, String to, EdgeKind kind) {
        return g.edges().stream().anyMatch(e -> e.from().equals(from) && e.to().equals(to) && e.kind() == kind);
    }

    private static MetadataNode node(MetadataGraph g, String id) {
        return g.nodes().stream().filter(n -> n.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no node " + id + " in " + g.nodes().stream()
                        .map(MetadataNode::id).toList()));
    }

    private static List<MetadataEdge> bindsTo(MetadataGraph g, String from) {
        return g.edges().stream()
                .filter(e -> e.kind() == EdgeKind.BINDS_TO && e.from().equals(from)).toList();
    }

    // ── the bridge resolves ────────────────────────────────────────────────────────

    @Test
    void physicalRefHeadBindsToTheStreamItNames(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        // The shipped shape of spaces/demo orders_dataset: a bare physicalRef naming the origin.
        component(f, "dataset", "orders_dataset", Map.of("physicalRef", ORIGIN));
        MetadataGraph g = build(f);

        MetadataNode ds = node(g, "dataset:orders_dataset");
        assertEquals(NodeKind.DATASET, ds.kind());
        assertEquals("orders_dataset", ds.label());
        assertEquals(Boolean.TRUE, ds.attrs().get("resolved"));
        assertEquals("physicalRef", ds.attrs().get("binding"));
        assertTrue(hasEdge(g, "dataset:orders_dataset", "stream:" + ORIGIN, EdgeKind.BINDS_TO));
    }

    @Test
    void onlyTheHeadSegmentOfAPathRefIsTheOrigin(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        // spaces/demo payments_dataset's shape: "<origin>/database" — the tail is not part of the name.
        component(f, "dataset", "payments_dataset", Map.of("physicalRef", ORIGIN + "/database"));
        MetadataGraph g = build(f);

        assertEquals(Boolean.TRUE, node(g, "dataset:payments_dataset").attrs().get("resolved"));
        assertTrue(hasEdge(g, "dataset:payments_dataset", "stream:" + ORIGIN, EdgeKind.BINDS_TO));
    }

    @Test
    void aPhysicalRefIsOperatorTypedSoTheMatchIsCaseInsensitive(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        component(f, "dataset", "shouty", Map.of("physicalRef", ORIGIN.toUpperCase() + "/DATABASE"));
        MetadataGraph g = build(f);

        assertEquals(Boolean.TRUE, node(g, "dataset:shouty").attrs().get("resolved"));
        assertTrue(hasEdge(g, "dataset:shouty", "stream:" + ORIGIN, EdgeKind.BINDS_TO));
    }

    @Test
    void aVirtualDatasetBindsByItsSourceName(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        // spaces/ucc sites_active: kind: virtual, physicalRef: null — the binding is sourceName, and
        // TOON parses the literal `null` to the string "null", which reads as absent.
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("kind", "virtual");
        content.put("sourceName", ORIGIN);
        content.put("view", "mini_active");
        content.put("physicalRef", "null");
        component(f, "dataset", "sites_active", content);
        MetadataGraph g = build(f);

        MetadataNode ds = node(g, "dataset:sites_active");
        assertEquals(Boolean.TRUE, ds.attrs().get("resolved"));
        assertEquals("view", ds.attrs().get("binding"), "a view: binding outranks the absent physicalRef");
        assertEquals(Boolean.TRUE, ds.attrs().get("resolved"), "binding and resolution are separate questions");
        assertNull(ds.attrs().get("physicalRef"), "the literal \"null\" is absent, not a value");
        assertTrue(hasEdge(g, "dataset:sites_active", "stream:" + ORIGIN, EdgeKind.BINDS_TO));
    }

    /**
     * ⛔ Regression for a mirror drift. {@code PipelineDependents.datasets} and
     * {@code PipelineRenameRoutes.rewriteDatasetRefs} check {@code sourceName} <em>and</em>
     * {@code physicalRef}; an earlier revision of the builder let a {@code sourceName} shadow the
     * {@code physicalRef}, so a Dataset naming an unmodelled sourceName lost a binding the
     * dependents route reports.
     */
    @Test
    void anUnresolvableSourceNameDoesNotHideAResolvablePhysicalRef(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("sourceName", "no_such_origin");
        content.put("physicalRef", ORIGIN + "/database");
        component(f, "dataset", "both_fields", content);
        MetadataGraph g = build(f);

        assertEquals(Boolean.TRUE, node(g, "dataset:both_fields").attrs().get("resolved"));
        assertTrue(hasEdge(g, "dataset:both_fields", "stream:" + ORIGIN, EdgeKind.BINDS_TO));
    }

    @Test
    void aDatasetNamingTwoOriginsBindsToBoth(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        // The helper hardcodes MINI_ETL, so a second origin means a second name over the same dirs.
        Path first = dir.resolve("mini_pipeline.toon");
        Path second = dir.resolve("other_pipeline.toon");
        Files.writeString(second, Files.readString(first).replace("name: MINI_ETL", "name: OTHER_ETL"));
        f.pipelines.add(PipelineConfig.load(second.toString()));
        assertNotNull(node(build(f), "stream:other_etl"), "premise: the second pipeline is a second origin");

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("sourceName", ORIGIN);
        content.put("physicalRef", "other_etl/database");
        component(f, "dataset", "two_origins", content);
        MetadataGraph g = build(f);

        assertEquals(List.of("stream:" + ORIGIN, "stream:other_etl"),
                bindsTo(g, "dataset:two_origins").stream().map(MetadataEdge::to).toList(),
                "both fields are candidates, so a Dataset naming two origins binds to two");
    }

    /**
     * ⚠ {@code binding} is {@code BiRoutes.datasets}' vocabulary and answers on KEY PRESENCE, so a
     * {@code physicalRef: null} reads as {@code "physicalRef"} on both routes. An earlier revision
     * answered {@code "unbound"} here, making the same Dataset describe itself two ways.
     */
    @Test
    void bindingAnswersOnKeyPresenceLikeTheBiRoute(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("physicalRef", "null");
        component(f, "dataset", "null_ref", content);
        MetadataGraph g = build(f);

        MetadataNode ds = node(g, "dataset:null_ref");
        assertEquals("physicalRef", ds.attrs().get("binding"), "the KEY is present, so the binding is declared");
        assertEquals(Boolean.FALSE, ds.attrs().get("resolved"), "…but it resolves to nothing");
        assertTrue(bindsTo(g, "dataset:null_ref").isEmpty());
    }

    // ── the bridge does NOT resolve ────────────────────────────────────────────────

    @Test
    void aJobOutputStoreHasNoNodeKindSoTheDatasetIsUnresolved(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        // spaces/demo orders_rollup_dataset: physicalRef "rollup" is a Job output store, which the
        // catalog does not model. The Dataset is still a node — that is the whole point.
        component(f, "dataset", "orders_rollup_dataset", Map.of("physicalRef", "rollup"));
        MetadataGraph g = build(f);

        MetadataNode ds = node(g, "dataset:orders_rollup_dataset");
        assertEquals(NodeKind.DATASET, ds.kind());
        assertEquals(Boolean.FALSE, ds.attrs().get("resolved"));
        assertEquals("rollup", ds.attrs().get("physicalRef"), "the unresolved ref is still readable");
        assertTrue(bindsTo(g, "dataset:orders_rollup_dataset").isEmpty(),
                "an edge is drawn only to a node that exists");
    }

    @Test
    void aDatasetWithNoBindingAtAllIsUnbound(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        component(f, "dataset", "orphan", Map.of("description", "bound to nothing yet"));
        MetadataGraph g = build(f);

        MetadataNode ds = node(g, "dataset:orphan");
        assertEquals(Boolean.FALSE, ds.attrs().get("resolved"));
        assertEquals("unbound", ds.attrs().get("binding"));
        assertEquals("bound to nothing yet", ds.description().text());
        assertEquals(Provenance.MANUAL, ds.description().provenance());
        assertTrue(bindsTo(g, "dataset:orphan").isEmpty());
    }

    // ── the consumer chain ─────────────────────────────────────────────────────────

    @Test
    void dashboardConsumesWidgetConsumesDataset(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        component(f, "dataset", "orders_dataset", Map.of("physicalRef", ORIGIN));
        component(f, "widget", "orders_by_day", Map.of("vizType", "bar", "datasetId", "orders_dataset"));
        component(f, "dashboard", "ops", Map.of("tiles", List.of(Map.of("widgetId", "orders_by_day"))));
        MetadataGraph g = build(f);

        assertEquals(NodeKind.WIDGET, node(g, "widget:orders_by_day").kind());
        assertEquals(NodeKind.DASHBOARD, node(g, "dashboard:ops").kind());
        assertEquals("bar", node(g, "widget:orders_by_day").attrs().get("vizType"));

        // The full walk a user asks for: dashboard → widget → dataset → the origin it reads.
        assertTrue(hasEdge(g, "dashboard:ops", "widget:orders_by_day", EdgeKind.CONSUMES));
        assertTrue(hasEdge(g, "widget:orders_by_day", "dataset:orders_dataset", EdgeKind.CONSUMES));
        assertTrue(hasEdge(g, "dataset:orders_dataset", "stream:" + ORIGIN, EdgeKind.BINDS_TO));
    }

    @Test
    void aDashboardDrawsOneEdgePerTile(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        component(f, "widget", "w1", Map.of("vizType", "bar"));
        component(f, "widget", "w2", Map.of("vizType", "line"));
        component(f, "dashboard", "ops", Map.of("tiles",
                List.of(Map.of("widgetId", "w1"), Map.of("widgetId", "w2"))));
        MetadataGraph g = build(f);

        assertTrue(hasEdge(g, "dashboard:ops", "widget:w1", EdgeKind.CONSUMES));
        assertTrue(hasEdge(g, "dashboard:ops", "widget:w2", EdgeKind.CONSUMES));
    }

    /**
     * A broken component reference is {@code ComponentIntegrity}'s job to report, not this builder's.
     * The builder's contract is narrower: never draw an edge to a node that does not exist.
     */
    @Test
    void aDanglingComponentReferenceDrawsNoEdge(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        component(f, "widget", "orphan_widget", Map.of("datasetId", "no_such_dataset"));
        component(f, "dashboard", "ops", Map.of("tiles", List.of(Map.of("widgetId", "no_such_widget"))));
        MetadataGraph g = build(f);

        assertEquals(NodeKind.WIDGET, node(g, "widget:orphan_widget").kind());
        assertEquals(NodeKind.DASHBOARD, node(g, "dashboard:ops").kind());
        assertTrue(g.edges().stream().noneMatch(e -> e.to().startsWith("dataset:no_such")
                        || e.to().startsWith("widget:no_such")),
                "no edge may point at a node the graph does not contain");
        // …and the dangling ids stay readable on the node, so the UI can say what is broken.
        assertEquals("no_such_dataset", node(g, "widget:orphan_widget").attrs().get("datasetId"));
    }

    // ── the ETL half is untouched ──────────────────────────────────────────────────

    @Test
    void aConfigSourceWithNoStudioComponentsBuildsExactlyTheEtlGraph(@TempDir Path dir) throws Exception {
        MetadataGraph g = build(fixture(dir));

        assertTrue(g.nodes().stream().noneMatch(n -> n.kind() == NodeKind.DATASET
                        || n.kind() == NodeKind.WIDGET || n.kind() == NodeKind.DASHBOARD),
                "components() defaults to empty, so the Studio pass must add nothing");
        assertTrue(g.edges().stream().noneMatch(e -> e.kind() == EdgeKind.BINDS_TO));
        assertNotNull(node(g, "stream:" + ORIGIN));
    }
}
