package com.gamma.entitystore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** LA-DRAFT-PROMOTE-COST-1: the {@code drafts} block of {@code link-analysis.toon} and its shipped defaults. */
class LinkAnalysisDraftsSettingsTest {

    private static LinkAnalysisSettings read(Path dir, String toon) throws Exception {
        Path f = dir.resolve(LinkAnalysisSettings.FILE);
        Files.writeString(f, toon);
        return LinkAnalysisSettings.forRoot(dir);
    }

    @Test
    void absentBlockInheritsTheShippedDefaults(@TempDir Path dir) throws Exception {
        LinkAnalysisSettings s = read(dir, "masking_mode: none\n");
        assertNull(s.drafts());
        assertEquals(50, s.effectiveDrafts().maxOpenInForce());
        assertEquals(Duration.ofHours(1), s.effectiveDrafts().hibernateAfterInForce());
        assertEquals(Duration.ofDays(30), s.effectiveDrafts().expireAfterInForce());
    }

    @Test
    void aStatedBlockRoundTripsAndIsInForce(@TempDir Path dir) throws Exception {
        new LinkAnalysisSettings(null, null, null, null, null, null, null, null, null, null, null,
                new LinkAnalysisSettings.Drafts(7, 15, 3)).write(dir.resolve(LinkAnalysisSettings.FILE));
        LinkAnalysisSettings.Drafts d = LinkAnalysisSettings.forRoot(dir).effectiveDrafts();
        assertEquals(7, d.maxOpenInForce());
        assertEquals(Duration.ofMinutes(15), d.hibernateAfterInForce());
        assertEquals(Duration.ofDays(3), d.expireAfterInForce());
    }

    @Test
    void anEmptyBlockAndAnUnorderedHandEditReadAsDefaults(@TempDir Path dir) throws Exception {
        // an empty TOON key arrives as {}: no knob, so the defaults
        assertEquals(50, read(dir, "drafts:\n").effectiveDrafts().maxOpenInForce());
        // positive control: the same keys, ordered, are honoured ...
        assertEquals(9, read(dir, "drafts:\n  max_open: 9\n  hibernate_after_minutes: 60\n  expire_after_days: 2\n")
                .effectiveDrafts().maxOpenInForce());
        // ... and a hand-edited expiry (1 day) not longer than the hibernation (2880 min) reads as all defaults, never expiry-before-sleep
        LinkAnalysisSettings bad = read(dir, "drafts:\n  max_open: 9\n  hibernate_after_minutes: 2880\n  expire_after_days: 1\n");
        assertNull(bad.drafts());
        assertEquals(50, bad.effectiveDrafts().maxOpenInForce());
    }

    @Test
    void aNonPositiveKnobCostsOnlyThatKnob(@TempDir Path dir) throws Exception {
        LinkAnalysisSettings.Drafts d = read(dir, "drafts:\n  max_open: 0\n  expire_after_days: 5\n").effectiveDrafts();
        assertEquals(50, d.maxOpenInForce());
        assertEquals(Duration.ofDays(5), d.expireAfterInForce());
    }
}
