package com.gamma.job;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The S1-1 seam contract (platform-services plan §3): grant filtering is honest (a registered but
 * undeclared service is invisible — R4), absence is modeled ({@code find()} empty, never a throw),
 * {@code get()} names the missing grant, and the registry fails closed on collisions and on granting
 * an id this build does not hold.
 */
class PlatformServicesTest {

    interface Greeting { String hello(); }
    interface Counting { int next(); }

    private static PlatformServiceRegistry registryWithBoth() {
        PlatformServiceRegistry reg = new PlatformServiceRegistry();
        reg.register("greeting", Greeting.class, () -> "hi");
        reg.register("counting", Counting.class, () -> 42);
        return reg;
    }

    @Test
    void grantFiltersToExactlyTheDeclaredSet() {
        PlatformServices granted = registryWithBoth().grant(Set.of("greeting"));
        assertEquals("hi", granted.get(Greeting.class).hello());
        assertEquals(Set.of(Greeting.class), granted.granted());
        // Registered but NOT declared -> invisible (R4: grants are honest because undeclared lookups fail).
        assertTrue(granted.find(Counting.class).isEmpty());
    }

    @Test
    void absentServiceIsEmptyNeverAThrowOnFind() {
        PlatformServices granted = registryWithBoth().grant(Set.of());
        assertTrue(granted.find(Greeting.class).isEmpty());
        assertTrue(granted.granted().isEmpty());
    }

    @Test
    void getNamesTheMissingGrant() {
        PlatformServices granted = registryWithBoth().grant(Set.of("greeting"));
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> granted.get(Counting.class));
        assertTrue(ex.getMessage().contains("Counting"), ex.getMessage());
        assertTrue(ex.getMessage().contains("requires:"), ex.getMessage());
    }

    @Test
    void grantingAnUnknownIdFailsClosedNamingIt() {
        PlatformServiceRegistry reg = registryWithBoth();
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> reg.grant(Set.of("no.such.service")));
        assertTrue(ex.getMessage().contains("no.such.service"), ex.getMessage());
    }

    @Test
    void registrationCollisionsFailClosedOnIdAndOnInterface() {
        PlatformServiceRegistry reg = registryWithBoth();
        assertThrows(IllegalStateException.class,
                () -> reg.register("greeting", Counting.class, () -> 1));           // id already bound
        assertThrows(IllegalStateException.class,
                () -> reg.register("greeting2", Greeting.class, () -> "again"));    // interface already bound
        assertEquals(Set.of("greeting", "counting"), reg.ids());                    // registry unchanged
        assertTrue(reg.has("greeting"));
    }

    @Test
    void noneIsTheEmptyGrantAndTheJobContextDefault() {
        PlatformServices none = PlatformServices.none();
        assertTrue(none.find(Greeting.class).isEmpty());
        assertTrue(none.granted().isEmpty());
        assertThrows(IllegalStateException.class, () -> none.get(Greeting.class));

        JobContext minimal = new JobContext() {
            @Override public String runId()                             { return "r1"; }
            @Override public String spaceId()                           { return "default"; }
            @Override public TriggerInfo trigger()                      { return TriggerInfo.parse("manual"); }
            @Override public java.util.Map<String, String> config()     { return java.util.Map.of(); }
            @Override public java.util.Map<String, String> params()     { return java.util.Map.of(); }
            @Override public RunLog log()                               { return null; }
            @Override public com.gamma.signal.SignalEmitter signals()   { return null; }
            @Override public ArtifactRecorder artifacts()               { return null; }
        };
        assertTrue(minimal.services().granted().isEmpty());
        assertTrue(minimal.services().find(Greeting.class).isEmpty());
    }
}
