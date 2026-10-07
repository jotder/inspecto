package com.gamma.opsjob;

import com.gamma.job.JobTypeProvider;
import com.gamma.job.testkit.JobTypeProviderContract;

/** {@link ObjectsAnalytics} against the platform's JobTypeProvider TCK (MODULE-REORG-1 P5b). */
class ObjectsAnalyticsTckTest extends JobTypeProviderContract {
    @Override
    protected JobTypeProvider provider() {
        return new OpsJobTypes.ObjectsAnalytics();
    }
}
