package com.gamma.control;

import com.gamma.etl.EditionFeatureProvider;
import com.gamma.etl.EditionFeatures;

import java.util.Set;

/**
 * Test-classpath stand-in for {@code inspecto-ops}'s declaration of {@link EditionFeatures#INVESTIGATIONS} and
 * {@link EditionFeatures#GEO_MAP}, {@link EditionFeatures#LA_WIDGETS} and {@link EditionFeatures#ENTITY_PAGES}
 * (LA-EDITIONS-1 phase 3): this module's tests drive the Link Analysis Investigation routes as a Standard build does,
 * without putting the whole ops module on the test classpath. A test proving the Basic refusal pins the feature absent
 * with {@link EditionFeatures#overrideForTest}.
 */
public final class InvestigationsEditionForTests implements EditionFeatureProvider {

    @Override
    public Set<String> features() {
        return Set.of(EditionFeatures.INVESTIGATIONS, EditionFeatures.GEO_MAP, EditionFeatures.LA_WIDGETS,
                EditionFeatures.ENTITY_PAGES);
    }
}
