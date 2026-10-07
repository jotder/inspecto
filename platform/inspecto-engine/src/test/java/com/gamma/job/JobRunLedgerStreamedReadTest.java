package com.gamma.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code AUDIT-LOG-UNBOUNDED-READ-1}: the three read-backs of {@code jobs_runs.csv} stream the file instead of
 * loading it whole. They are folds (the latest by timestamp, not the last row), so the answers must be the
 * same as before on a large file, with a row out of order, and with a torn trailing line from a hard kill.
 */
class JobRunLedgerStreamedReadTest {

    private static final String HEADER = "run_id,job,type,trigger,start_time,end_time,status,duration_ms,message";
    private static final int ROWS = 60_000;

    /** A large audit: {@code ROWS} rows for {@code big}, one out-of-order newest SUCCESS early on, a torn tail. */
    private static JobRunLedger ledgerOver(Path dir) throws Exception {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        // Written first but the newest by time: a tail read of the last row would miss it.
        sb.append("r-new,big,pipeline,schedule,2026-12-31 23:00:00,2026-12-31 23:00:09,SUCCESS,9,\"a, comma\"\n");
        for (int i = 0; i < ROWS; i++) {
            int m = i % 60;
            String status = i % 3 == 0 ? "FAILED" : "SUCCESS";
            sb.append("r").append(i).append(",big,pipeline,schedule,2026-01-01 00:")
              .append(m < 10 ? "0" : "").append(m).append(":00,2026-01-01 00:")
              .append(m < 10 ? "0" : "").append(m).append(":05,").append(status).append(",5,\"ok\"\n");
        }
        sb.append("s1,small,pipeline,manual,2026-02-02 10:00:00,2026-02-02 10:00:01,SUCCESS,1,\"\"\n");
        // A hard kill mid-append: no newline, start_time cut short, status missing.
        sb.append("r-torn,small,pipeline,manual,2026-09-0");
        Files.writeString(dir.resolve("jobs_runs.csv"), sb.toString(), StandardCharsets.UTF_8);
        return new JobRunLedger(dir.toString(), null);
    }

    @Test
    void lastStartTimesFoldsTheWholeFileAndSkipsATornTail(@TempDir Path dir) throws Exception {
        Map<String, LocalDateTime> starts = ledgerOver(dir).lastStartTimes();
        assertEquals(LocalDateTime.of(2026, 12, 31, 23, 0), starts.get("big"),
                "the newest start wins wherever it sits in the file, not the last row");
        assertEquals(LocalDateTime.of(2026, 2, 2, 10, 0), starts.get("small"),
                "the torn trailing row is skipped as malformed, the complete one before it still counts");
        assertEquals(2, starts.size());
    }

    @Test
    void lastSuccessEndAndRunIdFoldTheWholeFile(@TempDir Path dir) throws Exception {
        JobRunLedger ledger = ledgerOver(dir);
        assertEquals(Optional.of(LocalDateTime.of(2026, 12, 31, 23, 0, 9)), ledger.lastSuccessEnd("big"));
        assertEquals(Optional.of("r-new"), ledger.lastSuccessRunId("big"));
        assertEquals(Optional.of("s1"), ledger.lastSuccessRunId("small"), "the torn row never counts as a success");
        assertEquals(Optional.empty(), ledger.lastSuccessRunId("never"));
    }

    @Test
    void anEqualTimestampKeepsTheFirstRowAsBefore(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jobs_runs.csv"), HEADER + "\n"
                + "a,j,pipeline,manual,2026-03-03 00:00:00,2026-03-03 00:00:01,SUCCESS,1,\"\"\n"
                + "b,j,pipeline,manual,2026-03-03 00:00:00,2026-03-03 00:00:01,SUCCESS,1,\"\"\n",
                StandardCharsets.UTF_8);
        assertEquals(Optional.of("a"), new JobRunLedger(dir.toString(), null).lastSuccessRunId("j"),
                "isAfter, not !isBefore: a tie keeps the earlier row");
    }

    @Test
    void aHeaderOnlyOrAbsentAuditReadsEmpty(@TempDir Path dir) throws Exception {
        JobRunLedger none = new JobRunLedger(dir.resolve("none").toString(), null);
        assertTrue(none.lastStartTimes().isEmpty());
        Files.writeString(dir.resolve("jobs_runs.csv"), HEADER, StandardCharsets.UTF_8);
        JobRunLedger headerOnly = new JobRunLedger(dir.toString(), null);
        assertTrue(headerOnly.lastStartTimes().isEmpty());
        assertEquals(Optional.empty(), headerOnly.lastSuccessEnd("j"));
    }
}
