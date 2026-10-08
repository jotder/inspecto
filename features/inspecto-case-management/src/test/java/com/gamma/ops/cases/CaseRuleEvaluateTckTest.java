package com.gamma.ops.cases;

import com.gamma.job.JobTypeProvider;
import com.gamma.job.testkit.JobTypeProviderContract;

/** {@link CaseRuleEvaluate} against the platform's JobTypeProvider TCK (MODULE-REORG-1 P5b). */
class CaseRuleEvaluateTckTest extends JobTypeProviderContract {
    @Override
    protected JobTypeProvider provider() {
        return new CaseRuleEvaluate();
    }
}
