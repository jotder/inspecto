package com.gamma.anomaly;

import com.gamma.job.JobTypeProvider;
import com.gamma.job.testkit.JobTypeProviderContract;

/** {@link AnomalyScoreJobType} against the platform's JobTypeProvider TCK. */
class AnomalyScoreJobTypeTckTest extends JobTypeProviderContract {
    @Override
    protected JobTypeProvider provider() {
        return new AnomalyScoreJobType();
    }
}
