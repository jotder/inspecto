package com.gamma.job;

import com.gamma.job.JobTypeProvider;
import com.gamma.job.testkit.JobTypeProviderContract;

/** {@link SqlTemplateJobType} against the platform's JobTypeProvider TCK (MODULE-REORG-1 P5b). */
class SqlTemplateJobTypeTckTest extends JobTypeProviderContract {
    @Override
    protected JobTypeProvider provider() {
        return new SqlTemplateJobType(System.getProperty("java.io.tmpdir"));
    }
}
