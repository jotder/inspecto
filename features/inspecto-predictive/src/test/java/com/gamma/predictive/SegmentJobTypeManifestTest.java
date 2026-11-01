package com.gamma.predictive;

import com.gamma.job.testkit.JobTypeManifestParity;

/** {@code provides.jobTypes} of this module's manifest equals the Job Types its providers register. */
class SegmentJobTypeManifestTest extends JobTypeManifestParity {
    @Override
    protected String moduleId() {
        return "predictive";
    }

    @Override
    protected Class<?> moduleClass() {
        return SegmentJobType.class;
    }
}
