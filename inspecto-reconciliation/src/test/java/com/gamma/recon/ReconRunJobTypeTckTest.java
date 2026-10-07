package com.gamma.recon;

import com.gamma.job.JobTypeProvider;
import com.gamma.job.testkit.JobTypeProviderContract;

/** {@link ReconRunJobType} against the platform's JobTypeProvider TCK (MODULE-REORG-1 P5b). */
class ReconRunJobTypeTckTest extends JobTypeProviderContract {
    @Override
    protected JobTypeProvider provider() {
        return new ReconRunJobType();
    }
}
