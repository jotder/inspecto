package com.gamma.etl;

import java.util.Set;

/**
 * An optional module's declaration of the Professional+ features it brings to the build
 * ({@code PROCESSOR-RELEASE-READINESS-1} G9, operator decision 2026-09-24: "gate the code").
 *
 * <p>For the three features {@link EditionFeatures} names, the executing code is core — Alert Rules run in
 * {@code AlertService}, a {@code post_action: MOVE} in the Collector, a DuckLake registration in
 * {@link DuckLakeRegistrar} — so there is no module whose absence removes them. What decides the edition is
 * the house mechanism all the same: a Professional/Enterprise-only module registers a provider under
 * {@code META-INF/services/com.gamma.etl.EditionFeatureProvider}, and Personal, which never bundles that
 * module, discovers none. The classpath entry IS the switch — there is no flag to mis-set.
 *
 * @since 4.0.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public interface EditionFeatureProvider {

    /** The feature ids this module brings — {@link EditionFeatures#ALERT_DISPATCH} and its siblings. */
    Set<String> features();
}
