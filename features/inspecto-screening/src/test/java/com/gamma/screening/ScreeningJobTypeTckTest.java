package com.gamma.screening;

import com.gamma.job.JobTypeProvider;
import com.gamma.job.testkit.JobTypeProviderContract;

/** {@link ScreeningJobType} against the platform's JobTypeProvider TCK. */
class ScreeningJobTypeTckTest extends JobTypeProviderContract {
    @Override
    protected JobTypeProvider provider() {
        return new ScreeningJobType();
    }
}
