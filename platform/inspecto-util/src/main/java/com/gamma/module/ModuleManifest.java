package com.gamma.module;

import java.util.List;

/**
 * One module's declaration of what it is, provides and requires (MODULE-REORG-1 P2a; plan §2.1, §5).
 * Read from {@code META-INF/inspecto/module.toon} by {@link ModuleManifests}. Availability is NOT declared
 * here — {@link ModuleActivator} computes it from what actually bound.
 *
 * @param entitlementKey reserved (D-MR3: no licence engine); always nullable and never consulted
 * @param buildId the {@code Inspecto-Build-Id} of the jar this manifest was read from (P3a); {@code null} when
 *                unknown — an exploded directory, an unstamped jar or a {@code dev} build. Filled by the loader,
 *                never declared in {@code module.toon}.
 */
public record ModuleManifest(String id, String title, String buildRole, String offeringRole, String bindingTime,
                             Provides provides, Requires requires, String entitlementKey, String buildId) {

    /** A manifest with no build stamp (tests, parse). */
    public ModuleManifest(String id, String title, String buildRole, String offeringRole, String bindingTime,
                          Provides provides, Requires requires, String entitlementKey) {
        this(id, title, buildRole, offeringRole, bindingTime, provides, requires, entitlementKey, null);
    }

    public ModuleManifest withBuildId(String stamp) {
        return new ModuleManifest(id, title, buildRole, offeringRole, bindingTime, provides, requires, entitlementKey, stamp);
    }


    public static final List<String> BUILD_ROLES = List.of("foundation", "contract", "platform", "implementation");
    public static final List<String> OFFERING_ROLES = List.of("base", "optional", "provider", "internal");
    public static final List<String> BINDING_TIMES = List.of("build", "boot", "space", "run");

    /**
     * @param routes the HTTP surface the module registers, one {@code "METHOD path"} per route, where {@code path} is
     *               the EXACT regex string passed to {@code ApiContext} (P3b) and the order is the registration order
     *               (first-match: a catch-all stays last). Read by the host to synthesise the 503 "not installed"
     *               stubs when the module is absent; kept honest by a parity test in each module.
     */
    public record Provides(List<String> features, List<String> contracts, List<String> capabilities,
                           List<String> configKinds, List<String> storeFamilies, List<String> routes) {
        public static final Provides NONE = new Provides(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public record Requires(List<String> modules, List<String> contracts) {
        public static final Requires NONE = new Requires(List.of(), List.of());
    }
}
