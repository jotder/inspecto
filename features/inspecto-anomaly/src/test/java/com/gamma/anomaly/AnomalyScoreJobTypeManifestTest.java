package com.gamma.anomaly;

import com.gamma.job.testkit.JobTypeManifestParity;

/** {@code provides.jobTypes} of this module's manifest equals the Job Types its providers register. */
class AnomalyScoreJobTypeManifestTest extends JobTypeManifestParity {
    @Override
    protected String moduleId() {
        return "anomaly";
    }

    @Override
    protected Class<?> moduleClass() {
        return AnomalyScoreJobType.class;
    }
}
