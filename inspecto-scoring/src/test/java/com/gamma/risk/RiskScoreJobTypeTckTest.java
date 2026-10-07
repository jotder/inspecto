package com.gamma.risk;

import com.gamma.job.JobTypeProvider;
import com.gamma.job.testkit.JobTypeProviderContract;

/** {@link RiskScoreJobType} against the platform's JobTypeProvider TCK (MODULE-REORG-1 P5b). */
class RiskScoreJobTypeTckTest extends JobTypeProviderContract {
    @Override
    protected JobTypeProvider provider() {
        return new RiskScoreJobType();
    }
}
