package com.gamma.job;

import com.gamma.job.JobTypeProvider;
import com.gamma.job.testkit.JobTypeProviderContract;

/** {@link PostgresPublishJobType} against the platform's JobTypeProvider TCK (MODULE-REORG-1 P5b). */
class PostgresPublishJobTypeTckTest extends JobTypeProviderContract {
    @Override
    protected JobTypeProvider provider() {
        return new PostgresPublishJobType(System.getProperty("java.io.tmpdir"));
    }
}
