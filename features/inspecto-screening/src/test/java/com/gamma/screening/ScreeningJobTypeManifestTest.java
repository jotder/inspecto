package com.gamma.screening;

import com.gamma.job.testkit.JobTypeManifestParity;

/** {@code provides.jobTypes} of this module's manifest equals the Job Types its providers register. */
class ScreeningJobTypeManifestTest extends JobTypeManifestParity {
    @Override
    protected String moduleId() {
        return "screening";
    }

    @Override
    protected Class<?> moduleClass() {
        return ScreeningJobType.class;
    }
}
