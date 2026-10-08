package com.gamma.ops.cases;

import com.gamma.job.testkit.JobTypeManifestParity;

/** {@code provides.jobTypes} of this module's manifest equals the Job Types its providers register (P4e). */
class CaseRuleJobTypeManifestTest extends JobTypeManifestParity {
    @Override
    protected String moduleId() {
        return "case-management";
    }

    @Override
    protected Class<?> moduleClass() {
        return CaseRuleEvaluate.class;
    }
}
