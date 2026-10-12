package com.gamma.geolink;

import com.gamma.la.core.FsInvestigationStore;
import com.gamma.la.core.InvestigationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** LA-EMBED-EXPIRY-SWEEP-1: the per-Space {@code geoLink} gate and the "never throws" promise of one tick. */
class EmbedExpirySweepModuleTest {

    @TempDir Path tmp;

    @BeforeEach
    void on() {
        System.setProperty("la.embed.enabled", "true");
    }

    @AfterEach
    void off() {
        System.clearProperty("la.embed.enabled");
    }

    private Path space(String name, boolean geoLinkOff) throws Exception {
        Path config = tmp.resolve(name).resolve("config");
        Files.createDirectories(config);
        if (geoLinkOff) Files.writeString(config.resolve("modules.toon"), "disabled[1]: geoLink\n");
        seedExpired(config);
        return config;
    }

    private static final String GRANT = "Abcdefgh12345678";

    /** An Investigation with one grant that expires an hour from now (so a sweep 3 h on finds it expired). */
    private static void seedExpired(Path config) throws Exception {
        InvestigationStore s = new FsInvestigationStore(config);
        s.create("inv-1", "{\"id\":\"inv-1\"}");
        s.createEmbedGrant("inv-1", GRANT, "{\"grantId\":\"" + GRANT + "\",\"expiresAt\":\""
                + Instant.now().plusSeconds(3600) + "\",\"tokenSha256\":\"abc\"}");
    }

    private static boolean hashKept(Path config) throws Exception {
        return new FsInvestigationStore(config).embedGrant(GRANT).orElseThrow().contains("tokenSha256");
    }

    @Test
    void aSpaceWithTheModuleOnIsSweptAndOneWithItOffIsNot() throws Exception {
        Path on = space("a", false), off = space("b", true);
        Instant later = Instant.now().plusSeconds(3 * 3600);

        assertEquals(1, EmbedExpirySweepModule.sweepRoots(List.of(on, off), later));
        assertEquals(false, hashKept(on), "the module-on Space lost the expired hash");
        assertEquals(true, hashKept(off), "the module-off Space is untouched");
    }

    @Test
    void aNullRootOrAFailingSpaceNeverStopsTheTick() throws Exception {
        Path ok = space("c", false);
        Path broken = tmp.resolve("not-a-dir");
        Files.writeString(broken, "x");                                  // a file where a config root should be
        assertEquals(1, EmbedExpirySweepModule.sweepRoots(Arrays.asList(null, broken, ok), Instant.now().plusSeconds(3 * 3600)));
        assertEquals(false, hashKept(ok));
    }
}
