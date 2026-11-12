package com.gamma.realtime;

import com.gamma.etl.EditionFeatureProvider;
import com.gamma.etl.EditionFeatures;

import java.util.Set;

/**
 * Declares {@link EditionFeatures#REALTIME} (PA-9d, D-PA44): present wherever this module ships (Standard and above,
 * as the Real-time Detection Add-on), and withheld at run time by a signed entitlement that leaves the module out.
 */
public final class RealtimeEditionFeatures implements EditionFeatureProvider {

    @Override
    public Set<String> features() {
        return Set.of(EditionFeatures.REALTIME);
    }
}
