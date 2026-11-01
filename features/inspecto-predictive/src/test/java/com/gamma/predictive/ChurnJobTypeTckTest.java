package com.gamma.predictive;

import com.gamma.job.JobTypeProvider;
import com.gamma.job.testkit.JobTypeProviderContract;

/** {@link ChurnJobType} against the platform's JobTypeProvider TCK. */
class ChurnJobTypeTckTest extends JobTypeProviderContract {
    @Override
    protected JobTypeProvider provider() {
        return new ChurnJobType();
    }
}
