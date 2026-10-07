package com.gamma.job;

import com.gamma.job.JobTypeProvider;
import com.gamma.job.testkit.JobTypeProviderContract;

/** {@link MailSendJobType} against the platform's JobTypeProvider TCK (MODULE-REORG-1 P5b). */
class MailSendJobTypeTckTest extends JobTypeProviderContract {
    @Override
    protected JobTypeProvider provider() {
        return new MailSendJobType();
    }
}
