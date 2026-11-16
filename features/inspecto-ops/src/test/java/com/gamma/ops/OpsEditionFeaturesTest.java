package com.gamma.ops;

import com.gamma.etl.EditionFeatures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-EDITIONS-1 phase 3 (ED-4): {@code inspecto-ops} - the module only Standard, Advanced and Preview bundle - is what brings
 * the Link Analysis Investigation surface to a build. On this module's classpath (as on every Standard bundle) the
 * {@link EditionFeatures#INVESTIGATIONS} route feature is present, discovered through ServiceLoader, beside Alert Rules.
 */
class OpsEditionFeaturesTest {

    @Test
    void opsDeclaresAlertRulesAndTheInvestigationSurface() {
        EditionFeatures.overrideForTest(null);
        assertTrue(EditionFeatures.present(EditionFeatures.ALERT_DISPATCH));
        assertTrue(EditionFeatures.present(EditionFeatures.INVESTIGATIONS), "Standard and up bring Investigations through inspecto-ops");
        assertTrue(EditionFeatures.gatesRoutes(EditionFeatures.INVESTIGATIONS));
    }

    @Test
    void opsDeclaresTheGeoMapRouteFeatureToo() {
        EditionFeatures.overrideForTest(null);
        assertTrue(EditionFeatures.present(EditionFeatures.GEO_MAP), "Standard and up bring the Geo map through inspecto-ops (D-LAE5)");
        assertTrue(EditionFeatures.gatesRoutes(EditionFeatures.GEO_MAP));
    }

    @Test
    void opsDeclaresTheEntityPagesRouteFeatureToo() {
        EditionFeatures.overrideForTest(null);
        assertTrue(EditionFeatures.present(EditionFeatures.ENTITY_PAGES), "Standard and up bring the Entity profile pages through inspecto-ops (D-LAE4)");
        assertTrue(EditionFeatures.gatesRoutes(EditionFeatures.ENTITY_PAGES));
    }

    @Test
    void opsDeclaresPipelinesThatRunOnTheirOwn() {
        EditionFeatures.overrideForTest(null);
        assertTrue(EditionFeatures.present(EditionFeatures.PIPELINE_AUTOMATION),
                "Standard and up keep an import up to date and keep a retention policy (operator 2026-11-05)");
        assertFalse(EditionFeatures.gatesRoutes(EditionFeatures.PIPELINE_AUTOMATION), "the feed and storage routes check it themselves");
    }

    /** DATA-TIERS-1 P2: Standard and up offer transformation Steps, outbound delivery and remote systems in their screens. */
    @Test
    void opsDeclaresTheDataTierScreenGates() {
        EditionFeatures.overrideForTest(null);
        for (String f : EditionFeatures.SCREEN_FLAGS.values()) {
            assertTrue(EditionFeatures.present(f), f + " is declared where inspecto-ops ships (Standard and up)");
            assertFalse(EditionFeatures.gatesRoutes(f), f + " is a screen gate, never a route feature");
        }
        for (String flag : EditionFeatures.SCREEN_FLAGS.keySet())
            assertFalse(EditionFeatures.lockedReasons().containsKey(flag), flag + " is not withheld on Standard");
    }

    @Test
    void opsDeclaresTheDashboardWidgetFlagButItIsNotARouteFeature() {
        EditionFeatures.overrideForTest(null);
        assertTrue(EditionFeatures.present(EditionFeatures.LA_WIDGETS), "Standard and up bring the LA dashboard widgets (D-LAE2)");
        assertFalse(EditionFeatures.gatesRoutes(EditionFeatures.LA_WIDGETS), "a UI flag only: no route is gated by it");
    }
}
