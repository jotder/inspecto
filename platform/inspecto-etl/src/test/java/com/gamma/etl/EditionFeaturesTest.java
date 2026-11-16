package com.gamma.etl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link EditionFeatures} (`PROCESSOR-RELEASE-READINESS-1` G9): with no {@link EditionFeatureProvider} on the
 * classpath — this module's, like a Basic bundle's — the three Standard+ features are absent, a parsed
 * pipeline using them is refused, and the DuckLake registrar's backstop fails the batch with
 * {@code ERR_EDITION_FEATURE} instead of registering. A declared feature lifts each refusal.
 */
class EditionFeaturesTest {

    @AfterEach
    void restore() {
        EditionFeatures.overrideForTest(null);
        System.clearProperty(EditionFeatures.LA_INVESTIGATIONS_SETTING);
    }

    private static PipelineConfig pipeline(Path dir, boolean lake, String onSuccess) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "EDITION_ETL");
        m.put("dirs", Map.of("poll", dir.resolve("in").toString(), "database", dir.resolve("out").toString()));
        m.put("processing", Map.of("threads", 1));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("format", "parquet");
        if (lake) output.put("ducklake", Map.of("enabled", true, "catalog_url", dir.resolve("l.ducklake").toString(),
                "data_path", dir.resolve("l-data").toString(), "table", "t"));
        m.put("output", output);
        if (onSuccess != null) m.put("collector", Map.of("connector", "sftp",
                "post_action", Map.of("on_success", onSuccess, "archive_path", "archive")));
        return PipelineConfig.fromMap(m);
    }

    @Test
    void gatesRoutesIsExactlyTheRouteFeatureSetAndEveryMemberHasItsOwnRefusalText() {
        assertTrue(EditionFeatures.ROUTE_FEATURES.contains(EditionFeatures.INVESTIGATIONS));
        for (String id : EditionFeatures.ROUTE_FEATURES) {
            assertTrue(EditionFeatures.gatesRoutes(id), id + " is a route feature the host enforces");
            assertFalse(EditionFeatures.refusal(id).startsWith("'" + id + "'"),
                    id + " needs a refusal() text of its own, not the generic fallback: " + EditionFeatures.refusal(id));
        }
        // config features are enforced by the save / load gates, never by the route gate; LA_WIDGETS is a UI flag only (D-LAE2: its
        // routes stay open); unknown ids and null never gate
        for (String id : List.of(EditionFeatures.ALERT_DISPATCH, EditionFeatures.SINK_ARCHIVE,
                EditionFeatures.SINK_DUCKLAKE, EditionFeatures.PII_MASK, EditionFeatures.LA_WIDGETS, "geoLink", "ops", "no.such.feature"))
            assertFalse(EditionFeatures.gatesRoutes(id), id);
        assertFalse(EditionFeatures.gatesRoutes(null));
    }

    @Test
    void noProviderMeansPersonal() {
        assertFalse(EditionFeatures.present(EditionFeatures.ALERT_DISPATCH));
        assertFalse(EditionFeatures.present(EditionFeatures.SINK_ARCHIVE));
        assertFalse(EditionFeatures.present(EditionFeatures.SINK_DUCKLAKE));
    }

    @Test
    void aParsedPipelineUsingEitherSinkFeatureIsRefusedByName(@TempDir Path dir) throws Exception {
        List<EditionFeatures.Refusal> r = EditionFeatures.pipelineRefusals(pipeline(dir, true, "MOVE"));
        assertEquals(List.of(EditionFeatures.SINK_ARCHIVE, EditionFeatures.SINK_DUCKLAKE),
                r.stream().map(EditionFeatures.Refusal::feature).toList());
        IllegalStateException boom = assertThrows(IllegalStateException.class,
                () -> EditionFeatures.requirePipeline(pipeline(dir, true, null)));
        assertTrue(boom.getMessage().startsWith(EditionFeatures.CODE + ": pipeline 'edition_etl'"), boom.getMessage());
        assertTrue(boom.getMessage().contains("Standard+"), boom.getMessage());
        assertEquals(List.of(), EditionFeatures.pipelineRefusals(pipeline(dir, false, "DELETE")),
                "the other post-actions and a lake-less pipeline stay Basic");
    }

    @Test
    void theRegistrarRefusesAnEnabledLakeOnPersonal(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = pipeline(dir, true, null);
        String out = dir.resolve("out").resolve("t").resolve("part-0.parquet").toString();
        IllegalStateException boom = assertThrows(IllegalStateException.class,
                () -> DuckLakeRegistrar.register(List.of(out), "t", cfg));
        assertTrue(boom.getMessage().startsWith(EditionFeatures.CODE), boom.getMessage());
        assertTrue(boom.getMessage().contains("sink.ducklake"), boom.getMessage());
    }

    @Test
    void aDeclaredFeatureLiftsItsRefusalOnly(@TempDir Path dir) throws Exception {
        EditionFeatures.overrideForTest(Set.of(EditionFeatures.SINK_DUCKLAKE));
        assertEquals(List.of(EditionFeatures.SINK_ARCHIVE), EditionFeatures.pipelineRefusals(pipeline(dir, true, "MOVE"))
                .stream().map(EditionFeatures.Refusal::feature).toList());
    }

    /** SEC-08 (2026-10-06): a mask step is Advanced-only — refused in a draft's chain AND inside a route branch. */
    @Test
    void aMaskStepIsRefusedOutsideEnterpriseEvenInsideABranch() {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("steps", List.of(
                Map.of("mask", Map.of("columns", List.of("msisdn"))),
                Map.of("route", Map.of("branches", List.of(Map.of("key", "b",
                        "steps", List.of(Map.of("mask", Map.of("columns", List.of("imsi"))))))))));
        List<EditionFeatures.Refusal> r = EditionFeatures.pipelineRefusals(draft);
        assertEquals(List.of("steps[0].mask", "steps[1].route.branches[0].steps[0].mask"),
                r.stream().map(EditionFeatures.Refusal::field).toList());
        assertTrue(r.get(0).message().contains("Advanced"), r.get(0).message());
        assertFalse(EditionFeatures.present(EditionFeatures.PII_MASK), "no provider in this module: absent");
        EditionFeatures.overrideForTest(Set.of(EditionFeatures.PII_MASK));
        assertEquals(List.of(), EditionFeatures.pipelineRefusals(draft), "a declared feature lifts the refusal");
        EditionFeatures.overrideForTest(Set.of(EditionFeatures.SINK_ARCHIVE, EditionFeatures.SINK_DUCKLAKE));
        assertEquals(2, EditionFeatures.pipelineRefusals(draft).size(), "Standard features do not lift it");
    }

    /**
     * {@code LA-ONBOARD-1} OB-5: Investigations needs the edition (a declaring module) AND the add-on, which the install holds
     * unless {@code addons.laInvestigations} says otherwise; the add-on never lifts an edition that lacks the feature, and it
     * withholds nothing else. The refusal and the short locked reason name the add-on on an install that lacks only it.
     */
    @Test
    void investigationsNeedTheEditionAndTheRuntimeAddon() {
        EditionFeatures.overrideForTest(Set.of(EditionFeatures.INVESTIGATIONS, EditionFeatures.GEO_MAP,
                EditionFeatures.ENTITY_PAGES, EditionFeatures.PIPELINE_AUTOMATION, EditionFeatures.PIPELINE_TRANSFORM,
                EditionFeatures.PIPELINE_EGRESS, EditionFeatures.REMOTE_SYSTEMS));   // Standard
        assertTrue(EditionFeatures.holdsInvestigationsAddon(), "held by default");
        assertTrue(EditionFeatures.present(EditionFeatures.INVESTIGATIONS));
        assertEquals(Map.of(), EditionFeatures.lockedReasons(), "nothing withheld");

        for (String off : List.of("false", "FALSE", " false ", "no")) {
            System.setProperty(EditionFeatures.LA_INVESTIGATIONS_SETTING, off);
            assertFalse(EditionFeatures.present(EditionFeatures.INVESTIGATIONS), "'" + off + "' removes the add-on");
        }
        assertTrue(EditionFeatures.present(EditionFeatures.GEO_MAP), "the add-on withholds Investigations only");
        assertTrue(EditionFeatures.refusal(EditionFeatures.INVESTIGATIONS).contains("need the Investigations add-on ("
                + EditionFeatures.LA_INVESTIGATIONS_ORDER_CODE + ")"), EditionFeatures.refusal(EditionFeatures.INVESTIGATIONS));
        assertEquals(Map.of(EditionFeatures.INVESTIGATIONS, "Needs the Investigations add-on, which this installation does not hold."),
                EditionFeatures.lockedReasons());

        System.setProperty(EditionFeatures.LA_INVESTIGATIONS_SETTING, "true");
        EditionFeatures.overrideForTest(Set.of());   // Basic
        assertFalse(EditionFeatures.present(EditionFeatures.INVESTIGATIONS), "the add-on never lifts Basic");
        assertTrue(EditionFeatures.refusal(EditionFeatures.INVESTIGATIONS).contains("available in Standard and above, with the "
                + "Investigations add-on"), EditionFeatures.refusal(EditionFeatures.INVESTIGATIONS));
        assertEquals(List.of(EditionFeatures.ENTITY_PAGES, EditionFeatures.GEO_MAP, EditionFeatures.INVESTIGATIONS,
                        "pipelineAutomation", "pipelineEgress", "pipelineTransform", "remoteSystems"),
                List.copyOf(EditionFeatures.lockedReasons().keySet()));
        assertEquals("Entity profile pages are in Standard and above.", EditionFeatures.lockedReason(EditionFeatures.ENTITY_PAGES));
        assertTrue(EditionFeatures.refusal(EditionFeatures.ENTITY_PAGES).contains("available in Standard and above"),
                EditionFeatures.refusal(EditionFeatures.ENTITY_PAGES));
        assertEquals("Investigations are in Standard and above, with the Investigations add-on.",
                EditionFeatures.lockedReason(EditionFeatures.INVESTIGATIONS));
    }

    /**
     * DATA-TIERS-1 P2: the data-layer SCREEN gates. Basic withholds all four, each reported under its /bootstrap features key
     * with a short line of its own and a full refusal naming the feature; Standard withholds none. They are UI flags only:
     * never a route feature, and a pipeline using a transformation Step or a remote Collector is not refused by them (§5.3).
     * Falsified: with the SCREEN_FLAGS loop removed from lockedReasons the Basic map loses the four keys (asserted below).
     */
    @Test
    void theDataTierScreenGatesAreLockedWithAReasonOnBasicAndRefuseNothing(@TempDir Path dir) throws Exception {
        Map<String, String> flags = EditionFeatures.SCREEN_FLAGS;
        assertEquals(List.of("pipelineAutomation", "pipelineTransform", "pipelineEgress", "remoteSystems"), List.copyOf(flags.keySet()));
        assertEquals(List.of(EditionFeatures.PIPELINE_AUTOMATION, EditionFeatures.PIPELINE_TRANSFORM, EditionFeatures.PIPELINE_EGRESS,
                EditionFeatures.REMOTE_SYSTEMS), List.copyOf(flags.values()));

        EditionFeatures.overrideForTest(Set.of());   // Basic
        Map<String, String> reasons = EditionFeatures.lockedReasons();
        for (Map.Entry<String, String> e : flags.entrySet()) {
            String feature = e.getValue();
            assertFalse(EditionFeatures.present(feature), feature);
            assertFalse(EditionFeatures.gatesRoutes(feature), feature + " is a screen gate, never a route feature");
            assertEquals(e.getKey(), EditionFeatures.flagOf(feature));
            String line = reasons.get(e.getKey());
            assertNotNull(line, e.getKey() + " has a featureReasons line on Basic: " + reasons);
            assertEquals(EditionFeatures.lockedReason(feature), line);
            assertTrue(line.contains("Standard and above"), line);
            assertNotEquals(EditionFeatures.refusal(feature), line, feature + " has a short line of its own");
            assertTrue(EditionFeatures.refusal(feature).contains(feature) && EditionFeatures.refusal(feature).contains("Standard+"),
                    "the full refusal names the feature and the edition: " + EditionFeatures.refusal(feature));
        }
        assertTrue(reasons.get("remoteSystems").contains("copies Postgres tables"), "Basic keeps the Postgres snapshot (DT-4 as narrowed)");
        assertNull(EditionFeatures.flagOf(EditionFeatures.GEO_MAP));
        assertEquals(Set.of("objectstore.export", "publish.postgres"), EditionFeatures.EGRESS_JOB_TYPES);

        // gate in screens, not on load: a remote Collector and a sql Step are not refused on Basic
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("name", "EDITION_SCREEN");
        draft.put("collector", Map.of("connector", "sftp", "connection", "sftp_prod"));
        draft.put("steps", List.of(Map.of("sql", Map.of("query", "SELECT 1"))));
        assertEquals(List.of(), EditionFeatures.pipelineRefusals(draft));

        EditionFeatures.overrideForTest(Set.copyOf(flags.values()));   // Standard
        for (String flag : flags.keySet()) assertFalse(EditionFeatures.lockedReasons().containsKey(flag), flag);
    }
}
