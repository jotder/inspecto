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
    void opsDeclaresTheDashboardWidgetFlagButItIsNotARouteFeature() {
        EditionFeatures.overrideForTest(null);
        assertTrue(EditionFeatures.present(EditionFeatures.LA_WIDGETS), "Standard and up bring the LA dashboard widgets (D-LAE2)");
        assertFalse(EditionFeatures.gatesRoutes(EditionFeatures.LA_WIDGETS), "a UI flag only: no route is gated by it");
    }
}
