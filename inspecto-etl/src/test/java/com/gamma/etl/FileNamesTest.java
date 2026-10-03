package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Never store values, file-name half: {@link FileNames#safe} keeps a name stable and unique but value-free. */
class FileNamesTest {

    private static PipelineConfig cfg(Path dir, String sub) throws Exception {
        return PipelineConfig.load(PipelineConfigBatchTest.writePipeline(Files.createDirectories(dir.resolve(sub)), "").toString());
    }

    @Test
    void aDigitFormValueIsFingerprintedAndTheRestOfTheNameSurvives(@TempDir Path dir) throws Exception {
        PipelineConfig c = cfg(dir, "a");
        String pan = "4111111111111111", msisdn = "919876543210";
        String s = FileNames.safe(c, "in/cdr_" + msisdn + "_" + pan + ".csv");
        assertFalse(s.contains(msisdn), s);
        assertFalse(s.contains(pan), s);
        assertTrue(s.matches("in/cdr_<fp:[0-9a-f]{16}>_<fp:[0-9a-f]{16}>\\.csv"), s);
    }

    @Test
    void theResultIsStableUniquePerValueAndPerSpace(@TempDir Path dir) throws Exception {
        PipelineConfig a = cfg(dir, "a"), b = cfg(dir, "b");
        String n = "sub_919876543210.csv";
        assertEquals(FileNames.safe(a, n), FileNames.safe(a, n), "same name, same key");
        assertNotEquals(FileNames.safe(a, n), FileNames.safe(a, "sub_919876543211.csv"), "a different value, a different key");
        assertNotEquals(FileNames.safe(a, n), FileNames.safe(b, n), "salted per Space");
    }

    @Test
    void aValueSplitByDashesOrSpacesIsCaughtAndAgreesWithItsPlainSpelling(@TempDir Path dir) throws Exception {
        PipelineConfig c = cfg(dir, "a");
        String card = FileNames.safe(c, "pay_4111-1111-1111-1111.csv");
        assertTrue(card.matches("pay_<fp:[0-9a-f]{16}>\\.csv"), card);
        assertEquals(card, FileNames.safe(c, "pay_4111111111111111.csv"), "one value, one fingerprint");
        assertTrue(FileNames.safe(c, "sub 91 98765 43210.csv").matches("sub <fp:[0-9a-f]{16}>\\.csv"));
        // a date-led chain, dashed dates and ordinals are stamps, not values
        for (String n : new String[]{"feed_2026-10-03.csv", "feed_2026-10-03-1.csv", "feed_2026-10-03 12-30-45.csv",
                "part_0001_0002_0003.csv", "v1.2.3.4.csv"})
            assertEquals(n, FileNames.safe(c, n));
        // a date next to a value: only the value goes
        String s = FileNames.safe(c, "cdr-20261003-919876543210.csv");
        assertTrue(s.matches("cdr-20261003-<fp:[0-9a-f]{16}>\\.csv"), s);
    }

    @Test
    void safeIsIdempotentSoAFingerprintIsNeverReFingerprinted(@TempDir Path dir) throws Exception {
        PipelineConfig c = cfg(dir, "a");
        for (int i = 0; i < 200; i++) {
            String once = FileNames.safe(c, "in/x_" + (91987654000L + i) + "_" + (4111111111111000L + i) + ".csv");
            assertEquals(once, FileNames.safe(c, once));
        }
    }

    @Test
    void aFileNameQuotedInFailureTextIsFingerprintedButAPlainCountIsNot(@TempDir Path dir) throws Exception {
        PipelineConfig c = cfg(dir, "a");
        String t = FailureText.scrub("DuckDB read_csv failed for sub_919876543210.csv: could not open; read 12345678 bytes", c);
        assertFalse(t.contains("919876543210"), t);
        assertTrue(t.contains("read 12345678 bytes"), t);
        assertEquals("Empty file: plain.csv", FailureText.scrub("Empty file: plain.csv", c));
    }

    @Test
    void timestampsAndOrdinaryNamesAreUnchangedSoAnExistingLedgerStillMatches(@TempDir Path dir) throws Exception {
        PipelineConfig c = cfg(dir, "a");
        for (String n : new String[]{"a.csv", "feed_20261003.csv", "feed_202610031230.csv", "feed_20261003123045.csv",
                "dir/x_1234567.csv", "export_2026-10-03.csv"})
            assertEquals(n, FileNames.safe(c, n));
        assertNull(FileNames.safe(c, null));
        // a long digit run that is NOT a valid date-time is a value
        assertNotEquals("feed_99999999.csv", FileNames.safe(c, "feed_99999999.csv"));
        assertNotEquals("feed_20261303.csv", FileNames.safe(c, "feed_20261303.csv"), "month 13");
    }
}
