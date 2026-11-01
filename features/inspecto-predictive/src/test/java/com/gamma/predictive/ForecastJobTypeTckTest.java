package com.gamma.predictive;

import com.gamma.job.JobTypeProvider;
import com.gamma.job.testkit.JobTypeProviderContract;

/** {@link ForecastJobType} against the platform's JobTypeProvider TCK. */
class ForecastJobTypeTckTest extends JobTypeProviderContract {
    @Override
    protected JobTypeProvider provider() {
        return new ForecastJobType();
    }
}
