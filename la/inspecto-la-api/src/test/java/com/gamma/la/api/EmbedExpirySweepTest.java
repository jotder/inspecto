package com.gamma.la.api;

import com.gamma.la.core.FsInvestigationStore;
import com.gamma.la.core.InvestigationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LA-EMBED-EXPIRY-SWEEP-1: the sweep drops an expired grant's hash with NO mint and NO list call. */
class EmbedExpirySweepTest {

    @TempDir Path root;
    private final Instant t0 = Instant.parse("2026-10-12T10:00:00Z");

    @BeforeEach
    void on() {
        System.setProperty(EmbedGrants.ENABLED_PROPERTY, "true");
    }

    @AfterEach
    void off() {
        System.clearProperty(EmbedGrants.ENABLED_PROPERTY);
    }

    private InvestigationStore seeded(EmbedGrants.Minted[] out) throws Exception {
        InvestigationStore s = new FsInvestigationStore(root);
        assertTrue(s.create("inv-1", "{\"id\":\"inv-1\"}"));
        out[0] = EmbedGrants.mint(s, "inv-1", "u1", t0, 1, "short", "mask");     // expires t0+1h
        out[1] = EmbedGrants.mint(s, "inv-1", "u1", t0, 100, "long", "mask");    // expires t0+100h
        return s;
    }

    private static Map<String, Object> rec(InvestigationStore s, EmbedGrants.Minted m) throws Exception {
        return EmbedGrants.parse(s.embedGrant(String.valueOf(m.record().get("grantId"))).orElseThrow());
    }

    @Test
    void anExpiredGrantLosesItsHashAndAFreshOneIsUntouched() throws Exception {
        EmbedGrants.Minted[] m = new EmbedGrants.Minted[2];
        InvestigationStore s = seeded(m);
        assertNotNull(rec(s, m[0]).get("tokenSha256"));

        assertEquals(1, EmbedExpirySweep.sweep(root, t0.plusSeconds(2 * 3600)));

        Map<String, Object> expired = rec(s, m[0]);
        assertNull(expired.get("tokenSha256"), "the expired hash is gone");
        assertEquals("short", expired.get("label"), "the row stays");
        assertNotNull(expired.get("purgedAt"));
        assertNotNull(rec(s, m[1]).get("tokenSha256"), "a live grant keeps its hash");
        assertNull(rec(s, m[1]).get("purgedAt"));
    }

    @Test
    void theKillSwitchOffDoesNothing() throws Exception {
        EmbedGrants.Minted[] m = new EmbedGrants.Minted[2];
        InvestigationStore s = seeded(m);
        System.clearProperty(EmbedGrants.ENABLED_PROPERTY);

        assertEquals(0, EmbedExpirySweep.sweep(root, t0.plusSeconds(2 * 3600)));
        assertNotNull(rec(s, m[0]).get("tokenSha256"), "off: the expired hash is untouched");
    }

    @Test
    void aSpaceWithNoInvestigationsIsCheapAndANullRootIsHarmless() {
        assertEquals(0, EmbedExpirySweep.sweep(root, t0));
        assertEquals(0, EmbedExpirySweep.sweep(null, t0));
    }
}
