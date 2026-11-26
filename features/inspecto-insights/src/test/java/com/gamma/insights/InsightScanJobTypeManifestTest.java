package com.gamma.insights;

import com.gamma.job.testkit.JobTypeManifestParity;

/** {@code provides.jobTypes} of this module's manifest equals the Job Types its providers register. */
class InsightScanJobTypeManifestTest extends JobTypeManifestParity {
    @Override
    protected String moduleId() {
        return "insights";
    }

    @Override
    protected Class<?> moduleClass() {
        return InsightScanJobType.class;
    }
}
