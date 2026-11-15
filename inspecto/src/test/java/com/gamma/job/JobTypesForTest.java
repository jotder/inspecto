package com.gamma.job;

/** Test access, for host-module tests, to {@link JobService}'s package-private Job Type registration seam. */
public final class JobTypesForTest {

    private JobTypesForTest() {}

    /** Register {@code provider} on {@code jobs} (no pack, no owner) - {@link JobService#registerJobType}. */
    public static void register(JobService jobs, JobTypeProvider provider) {
        jobs.registerJobType(provider);
    }
}
