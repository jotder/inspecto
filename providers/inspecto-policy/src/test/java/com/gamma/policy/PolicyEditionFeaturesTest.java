package com.gamma.policy;

import com.gamma.etl.EditionFeatures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SEC-08 (operator 2026-09-02, built 2026-10-06): PII masking is Enterprise-only. This module ships only in the
 * Enterprise (and Preview) flavours, and its ServiceLoader-declared provider is what makes {@code quality.pii.mask}
 * present — so a mask step saves clean here.
 */
class PolicyEditionFeaturesTest {

    @Test
    void thePolicyModuleDeclaresPiiMasking() {
        assertTrue(EditionFeatures.present(EditionFeatures.PII_MASK),
                "inspecto-policy's EditionFeatureProvider must be discoverable via META-INF/services");
        assertEquals(List.of(), EditionFeatures.pipelineRefusals(
                Map.of("steps", List.of(Map.of("mask", Map.of("columns", List.of("msisdn")))))));
    }
}
