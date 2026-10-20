package com.gamma.ops;

import com.gamma.etl.EditionFeatureProvider;
import com.gamma.etl.EditionFeatures;

import java.util.Set;

/**
 * Declares Alert Rules ({@code SP-CTL-07}) present wherever this module ships — Standard and Advanced
 * (`PROCESSOR-RELEASE-READINESS-1` G9). A fired rule opens an ALERT object in this module's store; a Basic
 * build, which never bundles it, refuses Alert Rules at every authoring door and at boot.
 *
 * <p>Also declares the Link Analysis Investigation surface ({@link EditionFeatures#INVESTIGATIONS}, {@code LA-EDITIONS-1}
 * ED-4): Investigations are evidence-grade, team work that links Cases and raises Alerts — this module's objects — so they
 * ride with it. Basic ships the Link Analysis explorer without it, and the host answers the Investigation routes 503.
 *
 * <p>Also declares the Geo map, routes and co-location ({@link EditionFeatures#GEO_MAP}, D-LAE5, operator 2026-10-20): Standard
 * and up, by a route feature of its own on {@code GeoRoutes}, never through {@code geoLink}. And the Link Analysis dashboard
 * widgets ({@link EditionFeatures#LA_WIDGETS}, D-LAE2): a UI flag only, the routes behind them stay open.
 */
public final class OpsEditionFeatures implements EditionFeatureProvider {

    @Override
    public Set<String> features() {
        return Set.of(EditionFeatures.ALERT_DISPATCH, EditionFeatures.INVESTIGATIONS,
                EditionFeatures.GEO_MAP, EditionFeatures.LA_WIDGETS);
    }
}
