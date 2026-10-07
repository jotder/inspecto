package com.gamma.job;

import com.gamma.job.JobTypeProvider;
import com.gamma.job.testkit.JobTypeProviderContract;

/** {@link ObjectStoreExportJobType} against the platform's JobTypeProvider TCK (MODULE-REORG-1 P5b). */
class ObjectStoreExportJobTypeTckTest extends JobTypeProviderContract {
    @Override
    protected JobTypeProvider provider() {
        return new ObjectStoreExportJobType(System.getProperty("java.io.tmpdir"));
    }
}
