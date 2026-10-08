package com.gamma.opsjob;

import com.gamma.job.testkit.JobTypeManifestParity;

/** {@code provides.jobTypes} of this module's manifest equals the Job Types its providers register (P4e). */
class OpsJobTypeManifestTest extends JobTypeManifestParity {
    @Override
    protected String moduleId() {
        return "ops";
    }

    @Override
    protected Class<?> moduleClass() {
        return OpsJobTypes.class;
    }
}
