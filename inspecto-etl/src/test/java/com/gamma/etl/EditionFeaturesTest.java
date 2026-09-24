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
 * classpath — this module's, like a Personal bundle's — the three Professional+ features are absent, a parsed
 * pipeline using them is refused, and the DuckLake registrar's backstop fails the batch with
 * {@code ERR_EDITION_FEATURE} instead of registering. A declared feature lifts each refusal.
 */
class EditionFeaturesTest {

    @AfterEach
    void restore() {
        EditionFeatures.overrideForTest(null);
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
        assertTrue(boom.getMessage().contains("Professional+"), boom.getMessage());
        assertEquals(List.of(), EditionFeatures.pipelineRefusals(pipeline(dir, false, "DELETE")),
                "the other post-actions and a lake-less pipeline stay Personal");
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
}
