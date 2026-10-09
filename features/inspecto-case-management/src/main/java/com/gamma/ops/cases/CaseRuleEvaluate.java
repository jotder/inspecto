package com.gamma.ops.cases;

import com.gamma.job.Job;
import com.gamma.job.JobConfig;
import com.gamma.job.JobTypeDescriptor;
import com.gamma.job.JobTypeProvider;
import com.gamma.job.ParamType;
import com.gamma.job.ParameterDecl;
import com.gamma.opsjob.OpsJobTypes;

import java.util.List;

/**
 * {@code caserule.evaluate} - group matching Incidents into a Case from a saved Case Rule (MODULE-REORG-P7: moved
 * here from {@code OpsJobTypes}). Contributed through {@code JobService}'s {@code ServiceLoader} loop like every
 * optional Job Type; with this module absent the type is simply unknown.
 */
public final class CaseRuleEvaluate implements JobTypeProvider {
    @Override
    public JobTypeDescriptor descriptor() {
        return new JobTypeDescriptor("caserule.evaluate", "Case Rule Evaluation",
                "Evaluates a saved Case Rule, grouping matching Incidents into a Case; emits a completion signal.",
                List.of(ParameterDecl.required("rule", ParamType.STRING, "Saved case rule name")),
                List.of(CaseSignals.CASERULE_EVALUATE_COMPLETED), List.of(), OpsJobTypes.OBJECTS);
    }

    /** No supplier: the job resolves its Space's engine from the {@code JobContext} at run time. */
    @Override
    public Job create(JobConfig config) {
        return new CaseRuleEvalJob(config, null);
    }
}
