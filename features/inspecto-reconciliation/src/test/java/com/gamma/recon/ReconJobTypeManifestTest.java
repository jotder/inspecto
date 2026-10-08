package com.gamma.recon;

import com.gamma.job.testkit.JobTypeManifestParity;

/** {@code provides.jobTypes} of this module's manifest equals the Job Types its providers register (P4e). */
class ReconJobTypeManifestTest extends JobTypeManifestParity {
    @Override
    protected String moduleId() {
        return "reconciliation";
    }

    @Override
    protected Class<?> moduleClass() {
        return ReconRunJobType.class;
    }
}
