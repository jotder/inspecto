package com.gamma.job;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** D8: the retry policy's defaults, backoff and the transient/permanent split. */
class RetryPolicyTest {

    @Test
    void scheduledSqlReportAndScoringTypesRetryByDefaultOthersDoNot() {
        for (String t : new String[]{"sql.template", "report", "risk.score", "anomaly.score"})
            assertEquals(RetryPolicy.DEFAULT_RETRIES, RetryPolicy.resolve(t, Map.of()).retries(), t);
        assertEquals(0, RetryPolicy.resolve("maintenance", Map.of()).retries());
    }

    @Test
    void authoredValuesOverrideAndRetriesZeroSwitchesItOff() {
        assertEquals(0, RetryPolicy.resolve("sql.template", Map.of("retries", "0")).retries());
        RetryPolicy p = RetryPolicy.resolve("enrich", Map.of("retries", "3", "retry_backoff_seconds", "5"));
        assertEquals(3, p.retries());
        assertEquals(Duration.ofSeconds(5), p.backoff());
        assertEquals(RetryPolicy.DEFAULT_RETRIES, RetryPolicy.resolve("sql.template", Map.of("retries", "99")).retries(),
                "out of range falls back to the default");
        assertEquals(RetryPolicy.DEFAULT_RETRIES, RetryPolicy.resolve("report", Map.of("retries", "x")).retries());
    }

    @Test
    void backoffDoublesPerRetryAndIsCapped() {
        RetryPolicy p = new RetryPolicy(5, Duration.ofSeconds(30));
        assertEquals(Duration.ofSeconds(30), p.waitBefore(1));
        assertEquals(Duration.ofSeconds(60), p.waitBefore(2));
        assertEquals(Duration.ofSeconds(120), p.waitBefore(3));
        assertEquals(Duration.ofSeconds(RetryPolicy.MAX_BACKOFF_SECONDS), p.waitBefore(6));
    }

    @Test
    void onlyIoAndSqlFailuresAreTransientAGuardOrConfigRefusalIsPermanent() {
        assertTrue(RetryPolicy.transientFailure(new IOException("disk")));
        assertTrue(RetryPolicy.transientFailure(new RuntimeException("wrapped", new SQLException("db busy"))));
        assertFalse(RetryPolicy.transientFailure(
                new IllegalArgumentException("sql.template job 'x' refused: only SELECT allowed")));
        assertFalse(RetryPolicy.transientFailure(new IllegalStateException("risk.score: model not configured")));
    }
}
