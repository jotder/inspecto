package com.gamma.entitystore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LA-EMBED-VIEW-1 (operator 2026-10-12 D4): the {@code embed} block of {@code link-analysis.toon} and its shipped defaults. */
class LinkAnalysisEmbedSettingsTest {

    private static LinkAnalysisSettings read(Path dir, String toon) throws Exception {
        Files.writeString(dir.resolve(LinkAnalysisSettings.FILE), toon);
        return LinkAnalysisSettings.forRoot(dir);
    }

    @Test
    void absentBlockInheritsSeventyTwoHoursAndAThirtyDayCeilingAndRefusesNothing(@TempDir Path dir) throws Exception {
        LinkAnalysisSettings.Embed e = read(dir, "masking_mode: none\n").effectiveEmbed();
        assertEquals(72, e.defaultTtlHoursInForce());
        assertEquals(720, e.maxTtlHoursInForce());
        assertEquals(List.of(), e.refuseClassificationsInForce());
        assertTrue(e.allows(1) && e.allows(720));
        assertFalse(e.allows(0) || e.allows(721), "the shipped cap is 30 d");
        assertTrue(read(dir, "embed:\n  max_ttl_hours: 2000\n").effectiveEmbed().allows(2000), "a Space may raise it");
    }

    @Test
    void aStatedBlockRoundTrips(@TempDir Path dir) throws Exception {
        new LinkAnalysisSettings(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                new LinkAnalysisSettings.Embed(12, 96, List.of("MSISDN", "IMSI"))).write(dir.resolve(LinkAnalysisSettings.FILE));
        LinkAnalysisSettings.Embed e = LinkAnalysisSettings.forRoot(dir).effectiveEmbed();
        assertEquals(12, e.defaultTtlHoursInForce());
        assertEquals(96, e.maxTtlHoursInForce());
        assertEquals(List.of("MSISDN", "IMSI"), e.refuseClassificationsInForce());
    }

    @Test
    void aHandEditCannotRaiseTheCeilingAndADefaultAboveTheCapIsClamped(@TempDir Path dir) throws Exception {
        assertEquals(720, read(dir, "embed:\n  max_ttl_hours: 9000\n").effectiveEmbed().maxTtlHoursInForce(), "above the 365 d ceiling reads as the default");
        assertEquals(8760, read(dir, "embed:\n  max_ttl_hours: 8760\n").effectiveEmbed().maxTtlHoursInForce());
        assertEquals(24, read(dir, "embed:\n  default_ttl_hours: 100\n  max_ttl_hours: 24\n").effectiveEmbed().defaultTtlHoursInForce(),
                "a default longer than the cap is the cap");
        assertNull(read(dir, "embed:\n").embed(), "an empty key arrives as {}: no knob");
        assertEquals(72, read(dir, "embed:\n  default_ttl_hours: 0\n").effectiveEmbed().defaultTtlHoursInForce(), "a non-positive knob costs only that knob");
    }

    @Test
    void frameAncestorsAreCanonicalOriginsAndABadEntryIsDroppedNeverWidened(@TempDir Path dir) throws Exception {
        assertEquals(List.of(), read(dir, "masking_mode: none").effectiveEmbed().frameAncestorsInForce(), "nobody by default");
        new LinkAnalysisSettings(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                new LinkAnalysisSettings.Embed(null, null, null, List.of("https://Portal.Example.com", "*", "http://evil.example", "https://a.example/p")))
                .write(dir.resolve(LinkAnalysisSettings.FILE));
        assertEquals(List.of("https://portal.example.com"), LinkAnalysisSettings.forRoot(dir).effectiveEmbed().frameAncestorsInForce());
        assertEquals("http://127.0.0.1:4200", LinkAnalysisSettings.Embed.normaliseOrigin("http://127.0.0.1:4200"));
        assertNull(LinkAnalysisSettings.Embed.normaliseOrigin("https://a.example;frame-src *"));
    }
}
