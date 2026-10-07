package com.gamma.job;

import com.gamma.job.JobTypeProvider;
import com.gamma.job.testkit.JobTypeProviderContract;

/** {@link ConsignmentProcessJobType} against the platform's JobTypeProvider TCK (MODULE-REORG-1 P5b). */
class ConsignmentProcessJobTypeTckTest extends JobTypeProviderContract {
    @Override
    protected JobTypeProvider provider() {
        return new ConsignmentProcessJobType(System.getProperty("java.io.tmpdir"));
    }
}
