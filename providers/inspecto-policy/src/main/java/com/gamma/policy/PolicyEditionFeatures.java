package com.gamma.policy;

import com.gamma.etl.EditionFeatureProvider;
import com.gamma.etl.EditionFeatures;

import java.util.Set;

/**
 * Declares PII masking ({@code quality.pii.mask}, the {@code steps:} {@code mask} Step) present wherever this module
 * ships. That is Enterprise (and the never-customer-facing Preview) only: board SEC-08, operator 2026-09-02, built
 * 2026-10-06. Classification-driven masking is policy, and this is the policy module. Personal, Standard and
 * Professional refuse the Step at save and at run with {@code ERR_EDITION_FEATURE}.
 */
public final class PolicyEditionFeatures implements EditionFeatureProvider {

    @Override
    public Set<String> features() {
        return Set.of(EditionFeatures.PII_MASK);
    }
}
