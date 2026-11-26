package com.gamma.insights;

import com.gamma.job.JobTypeProvider;
import com.gamma.job.testkit.JobTypeProviderContract;

/** {@link InsightScanJobType} against the platform's JobTypeProvider TCK. */
class InsightScanJobTypeTckTest extends JobTypeProviderContract {
    @Override
    protected JobTypeProvider provider() {
        return new InsightScanJobType();
    }
}
