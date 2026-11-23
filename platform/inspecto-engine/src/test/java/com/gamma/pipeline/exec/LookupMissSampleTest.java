package com.gamma.pipeline.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** LOOKUP-1 §4.6 — the stored form of an unmatched value keeps a short prefix and masks the rest. */
class LookupMissSampleTest {

    @Test
    void theShapeKeepsAtMostAThirdAndNeverMoreThanFourCharacters() {
        assertEquals("4479••••••••", LookupMissSample.shape("447912345678"));
        assertEquals("33••••••", LookupMissSample.shape("33612345"), "8 characters keep 2");
        assertEquals("9••", LookupMissSample.shape("999"));
        assertEquals("••", LookupMissSample.shape("ab"), "under 3 characters: fully masked");
        assertEquals("", LookupMissSample.shape(""));
    }

    @Test
    void anEntryNeverCarriesTheValue(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var toon = com.gamma.etl.TestConfigs.csv(dir.resolve("cfg"), com.gamma.etl.PipelineConfigBatchTest.miniSchema()).write();
        var cfg = com.gamma.etl.PipelineConfig.load(toon.toString());
        var e = LookupMissSample.of(cfg, java.util.List.of(new RowShaper.LookupMiss("msisdn", "447912345678", 3))).get(0);
        assertEquals("msisdn", e.mapping());
        assertEquals(12, e.length());
        assertEquals(3, e.n());
        assertTrue(e.fingerprint().matches("fp:[0-9a-f]{16}"), e.fingerprint());
        assertEquals(com.gamma.etl.FailureText.fingerprint(cfg, "447912345678"), e.fingerprint(),
                "the rejected rows' own salted fingerprint, so the two can be compared");
        assertEquals("4479••••••••", e.shape());
    }
}
