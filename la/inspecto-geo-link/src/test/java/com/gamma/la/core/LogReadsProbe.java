package com.gamma.la.core;

/**
 * Same-package accessor so a test in another module can read {@code FsInvestigationStore}'s package-private read counter
 * without the production class exposing a public static. TEST CODE ONLY.
 */
public final class LogReadsProbe {

    private LogReadsProbe() {}

    /** Committed-log reads served by the filesystem store so far. */
    public static long sum() {
        return FsInvestigationStore.LOG_READS.sum();
    }
}
