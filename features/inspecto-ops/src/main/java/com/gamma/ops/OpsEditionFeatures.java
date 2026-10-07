package com.gamma.ops;

import com.gamma.etl.EditionFeatureProvider;
import com.gamma.etl.EditionFeatures;

import java.util.Set;

/**
 * Declares Alert Rules ({@code SP-CTL-07}) present wherever this module ships — Professional and Enterprise
 * (`PROCESSOR-RELEASE-READINESS-1` G9). A fired rule opens an ALERT object in this module's store; a Personal
 * build, which never bundles it, refuses Alert Rules at every authoring door and at boot.
 */
public final class OpsEditionFeatures implements EditionFeatureProvider {

    @Override
    public Set<String> features() {
        return Set.of(EditionFeatures.ALERT_DISPATCH);
    }
}
