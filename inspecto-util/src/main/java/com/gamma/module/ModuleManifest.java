package com.gamma.module;

import java.util.List;

/**
 * One module's declaration of what it is, provides and requires (MODULE-REORG-1 P2a; plan §2.1, §5).
 * Read from {@code META-INF/inspecto/module.toon} by {@link ModuleManifests}. Availability is NOT declared
 * here — {@link ModuleActivator} computes it from what actually bound.
 *
 * @param entitlementKey reserved (D-MR3: no licence engine); always nullable and never consulted
 */
public record ModuleManifest(String id, String title, String buildRole, String offeringRole, String bindingTime,
                             Provides provides, Requires requires, String entitlementKey) {

    public static final List<String> BUILD_ROLES = List.of("foundation", "contract", "platform", "implementation");
    public static final List<String> OFFERING_ROLES = List.of("base", "optional", "provider", "internal");
    public static final List<String> BINDING_TIMES = List.of("build", "boot", "space", "run");

    public record Provides(List<String> features, List<String> contracts, List<String> capabilities,
                           List<String> configKinds, List<String> storeFamilies) {
        public static final Provides NONE = new Provides(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public record Requires(List<String> modules, List<String> contracts) {
        public static final Requires NONE = new Requires(List.of(), List.of());
    }
}
