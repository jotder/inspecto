package com.gamma.la.api;

import com.gamma.la.core.FsInvestigationStore;
import com.gamma.la.core.InvestigationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-EMBED-VIEW-1 remainder (d): real expiry, driven by an INJECTED clock (every grant call takes {@code now}), including a lifetime
 * above the 30 d default cap a Space may allow. The boundary is exact: a grant is live strictly before {@code expiresAt}.
 */
class EmbedGrantsExpiryClockTest {

    @TempDir Path root;
    private final Instant t0 = Instant.parse("2026-10-14T10:00:00Z");

    private InvestigationStore store() throws Exception {
        InvestigationStore s = new FsInvestigationStore(root);
        assertTrue(s.create("inv-1", "{\"id\":\"inv-1\"}"));
        return s;
    }

    @Test
    void aGrantIsLiveUntilTheSecondItExpiresThenExpiredThenPurgedAfterTheSweep() throws Exception {
        InvestigationStore s = store();
        EmbedGrants.Minted m = EmbedGrants.mint(s, "inv-1", "u1", t0, 2, "x", "typed");
        assertTrue(EmbedGrants.resolve(s, m.token(), t0).ok());
        assertTrue(EmbedGrants.resolve(s, m.token(), t0.plusSeconds(2 * 3600 - 1)).ok(), "one second before: live");
        assertEquals(EmbedGrants.Denied.EXPIRED, EmbedGrants.resolve(s, m.token(), t0.plusSeconds(2 * 3600)).denied(), "at expiresAt: expired");
        assertEquals("expired", EmbedGrants.status(EmbedGrants.parse(s.embedGrant(String.valueOf(m.record().get("grantId"))).orElseThrow()),
                t0.plusSeconds(3 * 3600)));
        EmbedGrants.purgeExpired(s, "inv-1", t0.plusSeconds(3 * 3600));
        assertEquals(EmbedGrants.Denied.PURGED, EmbedGrants.resolve(s, m.token(), t0.plusSeconds(3 * 3600)).denied(), "the hash is gone after the sweep");
    }

    @Test
    void aLifetimeAboveTheThirtyDayDefaultCapIsLiveUntilItsOwnEndAndTheSweepLeavesItAlone() throws Exception {
        InvestigationStore s = store();
        EmbedGrants.Minted m = EmbedGrants.mint(s, "inv-1", "u1", t0, 2000, "long", "typed");   // a Space that raised max_ttl_hours to 2000
        Instant day31 = t0.plusSeconds(31L * 24 * 3600);
        assertTrue(EmbedGrants.resolve(s, m.token(), day31).ok(), "day 31 is past the default cap and still live");
        EmbedGrants.purgeExpired(s, "inv-1", day31);
        assertTrue(EmbedGrants.resolve(s, m.token(), day31).ok(), "a sweep at day 31 leaves it");
        assertTrue(EmbedGrants.resolve(s, m.token(), t0.plusSeconds(2000L * 3600 - 1)).ok());
        assertEquals(EmbedGrants.Denied.EXPIRED, EmbedGrants.resolve(s, m.token(), t0.plusSeconds(2000L * 3600)).denied());
        assertNull(EmbedGrants.parse(s.embedGrant(String.valueOf(m.record().get("grantId"))).orElseThrow()).get("revokedAt"));
    }
}
