package com.gamma.enrich;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ReferenceReader#parse} — the join node's {@code reference} string → the shared
 * {@link EnrichmentConfig.Reference} vocabulary, so a {@code transform.join} and an
 * {@code *_enrich.toon} reference reach {@link ReferenceReader#sqlFor} as the same thing (A5 slice 5).
 */
class ReferenceReaderParseTest {

    @Test
    void theReferencePrefixBindsByName() {
        var r = ReferenceReader.parse("reference/region_dim");
        assertTrue(r.byName());
        assertEquals("region_dim", r.ref());
        assertNull(r.path());
        assertFalse(r.hasAsOf(), "a join node carries no as_of — that stays an enrichment binding");
    }

    @Test
    void anythingElseIsAPath_withTheFormatTakenFromTheExtension() {
        var parquet = ReferenceReader.parse("/data/dims/region.parquet");
        assertFalse(parquet.byName());
        assertEquals("/data/dims/region.parquet", parquet.path());
        assertEquals("PARQUET", parquet.format());
        // anything not .parquet reads as CSV — the same default the enrichment reader applies
        assertEquals("CSV", ReferenceReader.parse("/data/dims/region.csv").format());
    }

    @Test
    void aBlankReferenceAndAPrefixNamingNothingBothRefuse() {
        assertThrows(IllegalArgumentException.class, () -> ReferenceReader.parse(null));
        assertThrows(IllegalArgumentException.class, () -> ReferenceReader.parse("  "));
        var e = assertThrows(IllegalArgumentException.class, () -> ReferenceReader.parse("reference/"));
        assertTrue(e.getMessage().contains("names no pipeline"), e.getMessage());
    }
}
