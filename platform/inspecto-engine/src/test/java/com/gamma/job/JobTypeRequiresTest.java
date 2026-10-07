package com.gamma.job;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The S1-2 declaration contract: a Job Type's {@code requires:} list resolves fail-closed at
 * registration — an unknown or build-absent Platform Service id refuses the type with a message
 * naming both. A bare registry (no platform wired) refuses any third-party {@code requires:}; the
 * S1-7 exception is a built-in, whose service ships in the same build. What registers cleanly serves
 * its grants through {@link JobTypeDescriptor#toMap()}.
 */
class JobTypeRequiresTest {

    interface Notifying { void notifyIt(); }

    private static JobTypeProvider type(String id, List<String> requires) {
        return JobTypeProvider.of(
                new JobTypeDescriptor(id, id, "test type", List.of(), List.of(), List.of(), requires),
                c -> null);
    }

    private static PlatformServiceRegistry platformWithNotifications() {
        PlatformServiceRegistry platform = new PlatformServiceRegistry();
        platform.register("notifications", Notifying.class, () -> { });
        return platform;
    }

    @Test
    void aSatisfiableRequiresRegistersAndServesItsGrants() {
        JobTypeRegistry registry = new JobTypeRegistry(platformWithNotifications());
        registry.register(type("acme.notify", List.of("notifications")));

        assertTrue(registry.has("acme.notify"));
        JobTypeDescriptor d = registry.descriptor("acme.notify").orElseThrow();
        assertEquals(List.of("notifications"), d.requires());
        assertEquals(List.of("notifications"), d.toMap().get("requires"));
    }

    @Test
    void anUnknownServiceIdRefusesTheTypeNamingBoth() {
        JobTypeRegistry registry = new JobTypeRegistry(platformWithNotifications());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> registry.register(type("acme.bad", List.of("no.such.service"))));

        assertTrue(ex.getMessage().contains("acme.bad"), ex.getMessage());
        assertTrue(ex.getMessage().contains("no.such.service"), ex.getMessage());
        assertFalse(registry.has("acme.bad"), "a refused type must not be registered");
    }

    @Test
    void aBareRegistryRefusesAThirdPartyRequiresButAcceptsGrantlessTypes() {
        JobTypeRegistry registry = new JobTypeRegistry();   // no platform: nothing is wired
        assertThrows(IllegalStateException.class,
                () -> registry.register(type("acme.notify", List.of("notifications")), "acme-pack"));
        assertThrows(IllegalStateException.class,
                () -> registry.registerClasspath(type("acme.cp", List.of("notifications"))));
        registry.register(type("acme.plain", List.of()));
        assertEquals(Set.of("acme.plain"), registry.ids());
    }

    @Test
    void aBareRegistryStillAcceptsABuiltInsRequires() {
        // S1-7: a built-in's service ships in the same build, so a lean/embedded JobService with no
        // platform registry wired must still register it — the Job tolerates the empty lookup. Only
        // the host wiring is absent, which is not the same as an unknown service id.
        JobTypeRegistry registry = new JobTypeRegistry();
        registry.register(type("sample.hello", List.of("notifications")));

        assertTrue(registry.has("sample.hello"));
        assertEquals(List.of("notifications"), registry.descriptor("sample.hello").orElseThrow().requires(),
                "the declaration is kept honest even when nothing can satisfy it here");
    }
}
