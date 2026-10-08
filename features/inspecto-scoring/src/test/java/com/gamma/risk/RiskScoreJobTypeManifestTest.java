package com.gamma.risk;

import com.gamma.job.testkit.JobTypeManifestParity;

/** {@code provides.jobTypes} of this module's manifest equals the Job Types its providers register (P4e). */
class RiskScoreJobTypeManifestTest extends JobTypeManifestParity {
    @Override
    protected String moduleId() {
        return "scoring";
    }

    @Override
    protected Class<?> moduleClass() {
        return RiskScoreJobType.class;
    }
}
