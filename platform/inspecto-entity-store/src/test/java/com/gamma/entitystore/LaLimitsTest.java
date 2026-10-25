package com.gamma.entitystore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code LA-CONFIG-LIMITS-1} P1: the {@link LaLimit} catalogue, {@link LaLimits} reads and writes, and the raw-map writers. */
class LaLimitsTest {

    private static final Set<String> UNITS = Set.of("count", "nodes", "edges", "rows", "bytes", "ms", "s", "min", "h", "days",
            "chars", "hops", "per_s", "pct_ram", "pct", "mb");

    @AfterEach
    void uninstall() {
        LaLimits.installDocPath(null);
    }

    private static LaLimitValues write(Path root, String toon) throws Exception {
        Files.writeString(root.resolve(LinkAnalysisSettings.FILE), toon);
        return LaLimits.forSpace(root);
    }

    // ── the catalogue ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void everyItemIsWellFormedAndItsDefaultIsInsideItsRange() {
        Set<String> paths = new HashSet<>();
        List<String> bad = new ArrayList<>();
        for (LaLimit l : LaLimit.values()) {
            if (!paths.add(l.path())) bad.add("duplicate " + l.path());
            if (!l.path().matches("[a-z_]+(\\.[a-zA-Z_]+)?")) bad.add("path " + l.path());
            if (!UNITS.contains(l.unit())) bad.add("unit " + l.path() + " " + l.unit());
            if (l.browser() && l.layer() != LaLimit.Layer.SPACE) bad.add("browser item not SPACE " + l.path());
            if (l.description() == null || l.description().isBlank()) bad.add("description " + l.path());
            if (l.min().doubleValue() > l.max().doubleValue()) bad.add("range " + l.path());
            if (!l.nullable() && !l.accepts(l.defaultValue().doubleValue())) bad.add("default outside range " + l.path());
            if (l.path().contains(".") && !l.path().startsWith(l.group() + ".")) bad.add("group " + l.path() + " " + l.group());
        }
        assertTrue(bad.isEmpty(), bad.toString());
        Set<LaLimit> nullable = new HashSet<>();
        for (LaLimit l : LaLimit.values()) if (l.nullable()) nullable.add(l);
        assertEquals(Set.of(LaLimit.FOUR_EYES_BUDGET_ABOVE, LaLimit.FOUR_EYES_FAN_OUT_ABOVE), nullable);
        assertEquals(17, java.util.Arrays.stream(LaLimit.values()).filter(l -> l.path().startsWith("browser_algorithms.")).count(),
                "one per graph algorithm the browser consults; suspicionScore's cap is suspicion_node_cap");
    }

    @Test
    void everySpaceItemIsDeclaredInTheSettingsSpec() {
        Set<String> declared = new HashSet<>();
        com.gamma.config.spec.ConfigSpecs.linkAnalysisSettings().fields().forEach(f -> declared.add(f.path()));
        List<String> undeclared = new ArrayList<>();
        for (LaLimit l : LaLimit.values())
            if (l.layer() == LaLimit.Layer.SPACE && !declared.contains(l.path().split("\\.")[0])) undeclared.add(l.path());
        assertTrue(undeclared.isEmpty(), "link-analysis.toon keys missing from ConfigSpecs.linkAnalysisSettings(): " + undeclared);
    }

    @Test
    void theDefaultsAreTodaysValues() {
        // the constants this module owns; the la-* modules pin theirs beside their own constants
        Map<LaLimit, Long> today = new EnumMap<>(LaLimit.class);
        today.put(LaLimit.MERGED_DISTINCT_CAP, (long) LinkAnalysisSettings.DEFAULT_MERGED_DISTINCT_CAP);
        today.put(LaLimit.SEED_BY_DISTINCT_CAP, (long) LinkAnalysisSettings.DEFAULT_SEED_BY_DISTINCT_CAP);
        today.put(LaLimit.MAX_SET_BYTES, (long) LinkAnalysisSettings.DEFAULT_MAX_SET_BYTES);
        today.put(LaLimit.MAX_INVESTIGATION_BYTES, LinkAnalysisSettings.DEFAULT_MAX_INVESTIGATION_BYTES);
        today.put(LaLimit.HUB_THRESHOLD, (long) LinkAnalysisSettings.DEFAULT_HUB_THRESHOLD);
        today.put(LaLimit.GRAPH_RUN_MAX_RESULT_ITEMS, (long) LinkAnalysisSettings.GraphRun.DEFAULT_MAX_RESULT_ITEMS);
        today.put(LaLimit.INDEX_KEEP_VERSIONS, (long) LinkAnalysisSettings.Index.DEFAULT_KEEP_VERSIONS);
        today.put(LaLimit.INDEX_MAX_AGE_HOURS, (long) LinkAnalysisSettings.Index.DEFAULT_MAX_AGE_HOURS);
        today.put(LaLimit.DRAFTS_MAX_OPEN, (long) LinkAnalysisSettings.Drafts.DEFAULT_MAX_OPEN);
        today.put(LaLimit.DRAFTS_HIBERNATE_AFTER_MINUTES, (long) LinkAnalysisSettings.Drafts.DEFAULT_HIBERNATE_AFTER_MINUTES);
        today.put(LaLimit.DRAFTS_EXPIRE_AFTER_DAYS, (long) LinkAnalysisSettings.Drafts.DEFAULT_EXPIRE_AFTER_DAYS);
        today.put(LaLimit.EMBED_DEFAULT_TTL_HOURS, (long) LinkAnalysisSettings.Embed.DEFAULT_TTL_HOURS);
        today.put(LaLimit.EMBED_MAX_TTL_HOURS, (long) LinkAnalysisSettings.Embed.DEFAULT_MAX_TTL_HOURS);
        today.put(LaLimit.INPUT_ENTITY_TYPES, 64L);
        today.forEach((l, v) -> assertEquals(v, l.defaultValue(), l.path()));
        // the ranges the existing PUT /settings/link-analysis enforces, so a value it accepted reads as before
        assertEquals(LinkAnalysisSettings.Embed.CEILING_TTL_HOURS, LaLimit.EMBED_MAX_TTL_HOURS.max().intValue());
        assertEquals(100_000L, LaLimit.PROJECTION_NODE_CAP.max());
        assertEquals(10_000_000L, LaLimit.GRAPH_RUN_MAX_NODES.max());
        assertEquals(0.5, LaLimit.RATE_LINK_ANALYSIS_PER_S.defaultValue());
        assertEquals(100L, LaLimit.DISPLAY_RADIAL_MAX_NODES.defaultValue(), "N1: the measured Radial cap, ~1 s");
        assertEquals("display.radial_max_nodes", LaLimit.DISPLAY_RADIAL_MAX_NODES.path());
        assertEquals(100L, LaLimit.DISPLAY_RADIAL_MAX_NODES.parse(100));
        assertThrows(IllegalArgumentException.class, () -> LaLimit.DISPLAY_RADIAL_MAX_NODES.parse(0));
        assertThrows(IllegalArgumentException.class, () -> LaLimit.DISPLAY_RADIAL_MAX_NODES.parse(3_001));
        assertEquals(80L, LaLimit.INDEX_BUILD_MEMORY_PCT.defaultValue(), "CL-8: an index build's own memory share, 80 % of RAM");
    }

    @Test
    void parseRefusesAnythingOutsideTheRangeNamingTheKeyAndTheRange() {
        assertEquals(7L, LaLimit.TRAVERSAL_TIMEOUT_S.parse(7));
        assertNull(LaLimit.TRAVERSAL_TIMEOUT_S.parse(null), "null = clear");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> LaLimit.TRAVERSAL_TIMEOUT_S.parse(301));
        assertTrue(e.getMessage().contains("traversal.timeout_s") && e.getMessage().contains("1..300"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> LaLimit.TRAVERSAL_TIMEOUT_S.parse(0));
        assertThrows(IllegalArgumentException.class, () -> LaLimit.TRAVERSAL_TIMEOUT_S.parse(2.5), "a whole-number item");
        assertThrows(IllegalArgumentException.class, () -> LaLimit.TRAVERSAL_TIMEOUT_S.parse("7"), "a JSON number, not a string");
        assertEquals(0.25, LaLimit.RATE_LINK_ANALYSIS_PER_S.parse(0.25), "a rate takes a fraction");
        assertEquals(0L, LaLimit.INDEX_MAX_DISK_BYTES.parse(0), "0 = no limit is inside its range");
    }

    // ── reading ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void anAbsentDocumentReadsEveryDefault(@TempDir Path root) {
        LaLimitValues v = LaLimits.forSpace(root);
        for (LaLimit l : LaLimit.values()) {
            assertNull(v.stated(l), l.path());
            assertEquals(l.defaultValue(), v.inForce(l), l.path());
        }
        assertNull(v.getNullable(LaLimit.FOUR_EYES_BUDGET_ABOVE), "no threshold");
        assertThrows(IllegalStateException.class, () -> v.get(LaLimit.FOUR_EYES_BUDGET_ABOVE));
        assertEquals(LaLimitValues.DEFAULTS.get(LaLimit.PROJECTION_NODE_CAP), LaLimits.forSpace(null).get(LaLimit.PROJECTION_NODE_CAP));
    }

    @Test
    void aStatedValueInsideItsRangeIsInForceAndOneOutsideReadsAsTheDefault(@TempDir Path root) throws Exception {
        LaLimitValues v = write(root, "projection_node_cap: 900\nfour_eyes_budget_above: 300\ntraversal:\n  timeout_s: 7\n"
                + "  depth_max: 999\n  paths_max: lots\nbrowser_algorithms:\n  closenessCentrality: 1500\n");
        assertEquals(900, v.get(LaLimit.PROJECTION_NODE_CAP));
        assertEquals(300L, v.getNullable(LaLimit.FOUR_EYES_BUDGET_ABOVE));
        assertEquals(7, v.get(LaLimit.TRAVERSAL_TIMEOUT_S));
        assertEquals(1500, v.get(LaLimit.BROWSER_CLOSENESS_CENTRALITY));
        assertEquals(20, v.get(LaLimit.TRAVERSAL_DEPTH_MAX), "999 is outside 1..50: a hand edit cannot pass the range");
        assertNull(v.stated(LaLimit.TRAVERSAL_DEPTH_MAX));
        assertEquals(20_000, v.get(LaLimit.TRAVERSAL_PATHS_MAX), "not a number: the default");
    }

    @Test
    void theCacheIsKeyedOnContentSoASameSizedRewriteIsSeen(@TempDir Path root) throws Exception {
        Path f = root.resolve(LinkAnalysisSettings.FILE);
        assertEquals(700, write(root, "projection_node_cap: 700\n").get(LaLimit.PROJECTION_NODE_CAP));
        java.nio.file.attribute.FileTime t = Files.getLastModifiedTime(f);
        Files.writeString(f, "projection_node_cap: 800\n");
        Files.setLastModifiedTime(f, t);   // same size, same mtime: only the content changed
        assertEquals(800, LaLimits.forSpace(root).get(LaLimit.PROJECTION_NODE_CAP));
    }

    @Test
    void installItemsReadFromTheInstalledDocumentNeverFromTheSpace(@TempDir Path root, @TempDir Path sys) throws Exception {
        write(root, "graph_run:\n  ceiling_nodes: 9\n");
        assertEquals(500_000, LaLimits.forSpace(root).get(LaLimit.GRAPH_RUN_CEILING_NODES), "no install document: the default");
        Path doc = sys.resolve(LaLimits.INSTALL_FILE);
        Files.writeString(doc, "graph_run:\n  ceiling_nodes: 600000\nrate:\n  link_analysis_per_s: 0.25\nprojection_node_cap: 3\n");
        LaLimits.installDocPath(doc);
        LaLimitValues v = LaLimits.forSpace(root);
        assertEquals(600_000, v.get(LaLimit.GRAPH_RUN_CEILING_NODES));
        assertEquals(0.25, v.getDecimal(LaLimit.RATE_LINK_ANALYSIS_PER_S));
        assertEquals(1_000, v.get(LaLimit.PROJECTION_NODE_CAP), "a SPACE item is never read from the install document");
    }

    @Test
    void anUnorderedDraftsBlockReadsAsDefaultsLikeTheSettingsReader(@TempDir Path root) throws Exception {
        LaLimitValues v = write(root, "drafts:\n  max_open: 9\n  hibernate_after_minutes: 2880\n  expire_after_days: 1\n");
        assertEquals(50, v.get(LaLimit.DRAFTS_MAX_OPEN));
        assertEquals(LinkAnalysisSettings.forRoot(root).effectiveDrafts().maxOpenInForce(), v.get(LaLimit.DRAFTS_MAX_OPEN));
        assertTrue(LaLimits.consistencyProblem(LaLimits.loadRaw(root.resolve(LinkAnalysisSettings.FILE))).contains("drafts.expire_after_days"));
    }

    // ── writing: both writers keep each other's keys ─────────────────────────────────────────────────────────

    @Test
    void theSettingsWriteKeepsAKeyTheLimitsWriteWrote(@TempDir Path root) throws Exception {
        Map<LaLimit, Number> c = new HashMap<>();
        c.put(LaLimit.TRAVERSAL_TIMEOUT_S, 7L);
        c.put(LaLimit.INDEX_FRONTIER_CAP, 40L);
        LaLimits.writeSpace(root, new EnumMap<>(c));
        new LinkAnalysisSettings(900, null, null, "all", null, null, null, null, null, null,
                new LinkAnalysisSettings.Index(null, null, 3, null), null, null, null, null)
                .write(root.resolve(LinkAnalysisSettings.FILE));
        LaLimitValues v = LaLimits.forSpace(root);
        assertEquals(7, v.get(LaLimit.TRAVERSAL_TIMEOUT_S), "a key the settings record does not model survives its write");
        assertEquals(40, v.get(LaLimit.INDEX_FRONTIER_CAP), "... inside a block it does model, too");
        assertEquals(3, v.get(LaLimit.INDEX_KEEP_VERSIONS));
        assertEquals(900, v.get(LaLimit.PROJECTION_NODE_CAP));
        // and the settings write still REPLACES what it models: a second write without the cap clears it
        LinkAnalysisSettings.EMPTY.write(root.resolve(LinkAnalysisSettings.FILE));
        v = LaLimits.forSpace(root);
        assertNull(v.stated(LaLimit.PROJECTION_NODE_CAP));
        assertNull(v.stated(LaLimit.INDEX_KEEP_VERSIONS));
        assertEquals(7, v.get(LaLimit.TRAVERSAL_TIMEOUT_S));
        assertEquals(40, v.get(LaLimit.INDEX_FRONTIER_CAP));
    }

    @Test
    void theLimitsWriteKeepsEveryKeyTheSettingsWriteWrote(@TempDir Path root) throws Exception {
        LinkAnalysisSettings s = new LinkAnalysisSettings(null, null, null, "all", 300, null, EntityTypes.DEFAULTS, null, null,
                null, null, new LinkAnalysisSettings.Drafts(7, 15, 3), null, null, null,
                new LinkAnalysisSettings.Embed(null, null, List.of("SECRET")));
        s.write(root.resolve(LinkAnalysisSettings.FILE));
        Map<LaLimit, Number> c = new EnumMap<>(LaLimit.class);
        c.put(LaLimit.TRAVERSAL_TIMEOUT_S, 9L);
        c.put(LaLimit.DRAFTS_MAX_OPEN, 8L);
        c.put(LaLimit.FOUR_EYES_BUDGET_ABOVE, null);   // clear
        LaLimits.writeSpace(root, c);
        LinkAnalysisSettings back = LinkAnalysisSettings.forRoot(root);
        assertEquals("all", back.maskingMode());
        assertEquals(EntityTypes.DEFAULTS, back.entityTypes());
        assertEquals(List.of("SECRET"), back.effectiveEmbed().refuseClassificationsInForce());
        assertEquals(new LinkAnalysisSettings.Drafts(8, 15, 3), back.drafts());
        assertNull(back.fourEyesBudgetAbove(), "a null clears the key");
        assertEquals(9, LaLimits.forSpace(root).get(LaLimit.TRAVERSAL_TIMEOUT_S));
        // clearing the last key of a block removes the block
        c.clear();
        c.put(LaLimit.TRAVERSAL_TIMEOUT_S, null);
        LaLimits.writeSpace(root, c);
        assertFalse(Files.readString(root.resolve(LinkAnalysisSettings.FILE)).contains("traversal"));
    }

    @Test
    void aWriteRefusesADocumentItCannotRead(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(LinkAnalysisSettings.FILE), "entity_types: [unterminated\n  :: ::\n");
        Map<LaLimit, Number> c = new EnumMap<>(LaLimit.class);
        c.put(LaLimit.TRAVERSAL_TIMEOUT_S, 9L);
        assertThrows(java.io.IOException.class, () -> LaLimits.writeSpace(root, c));
        assertTrue(Files.readString(root.resolve(LinkAnalysisSettings.FILE)).contains("unterminated"), "nothing replaced");
    }

    // ── the effective*() accessors delegate to the catalogue ─────────────────────────────────────────────────

    @Test
    void theEffectiveAccessorsApplyTheCatalogueRange(@TempDir Path root) throws Exception {
        write(root, "merged_distinct_cap: 200000\nhub_threshold: 700\nindex:\n  keep_versions: 500\n  max_age_hours: 48\n");
        LinkAnalysisSettings s = LinkAnalysisSettings.forRoot(root);
        assertEquals(20_000, s.effectiveMergedDistinctCap(), "outside 1..100000: the default, as the registry reads it");
        assertEquals(700, s.effectiveHubThreshold());
        assertEquals(2, s.effectiveIndex().keepVersionsInForce(), "outside 1..100");
        assertEquals(48, s.effectiveIndex().maxAgeHoursInForce());
        LaLimitValues v = LaLimits.forSpace(root);
        assertEquals(v.get(LaLimit.MERGED_DISTINCT_CAP), s.effectiveMergedDistinctCap());
        assertEquals(v.get(LaLimit.INDEX_KEEP_VERSIONS), s.effectiveIndex().keepVersionsInForce());
    }
}
