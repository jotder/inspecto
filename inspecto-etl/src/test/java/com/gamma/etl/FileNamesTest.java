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
