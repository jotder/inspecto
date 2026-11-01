package com.gamma.predictive;

import com.gamma.job.JobTypeProvider;
import com.gamma.job.testkit.JobTypeProviderContract;

/** {@link SegmentJobType} against the platform's JobTypeProvider TCK. */
class SegmentJobTypeTckTest extends JobTypeProviderContract {
    @Override
    protected JobTypeProvider provider() {
        return new SegmentJobType();
    }
}
