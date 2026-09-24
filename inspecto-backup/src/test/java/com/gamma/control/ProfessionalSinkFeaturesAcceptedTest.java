package com.gamma.control;

import com.gamma.config.spec.Finding;
import com.gamma.config.spec.FindingCodes;
import com.gamma.etl.EditionFeatures;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The PROFESSIONAL half of `PROCESSOR-RELEASE-READINESS-1` G9 for the two sink features: with this module on
 * the classpath its real {@code BackupEditionFeatures} provider is discovered — no test override — and the
 * save gate that core's {@code EditionFeatureGateTest} proves refusing on Personal accepts a Collector's
 * {@code post_action: MOVE} archive and an enabled DuckLake block.
 */
class ProfessionalSinkFeaturesAcceptedTest {

    @Test
    void theModuleDeclaresBothSinkFeaturesAndTheSaveGateAcceptsThem() {
        assertTrue(EditionFeatures.present(EditionFeatures.SINK_ARCHIVE), "declared by inspecto-backup");
        assertTrue(EditionFeatures.present(EditionFeatures.SINK_DUCKLAKE), "declared by inspecto-backup");

        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("name", "P");
        draft.put("active", false);
        draft.put("collector", Map.of("connector", "sftp",
                "post_action", Map.of("on_success", "MOVE", "archive_path", "archive/yyyy")));
        draft.put("output", Map.of("format", "PARQUET",
                "ducklake", Map.of("enabled", true, "catalog_url", "lake.ducklake", "table", "t")));
        List<Finding> edition = SaveGate.check(null, "pipeline", draft, null, null, SaveGate.Referents.MUST_EXIST)
                .stream().filter(f -> FindingCodes.ERR_EDITION_FEATURE.equals(f.code())).toList();
        assertEquals(List.of(), edition);
    }
}
