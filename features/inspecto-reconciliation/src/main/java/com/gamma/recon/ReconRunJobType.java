package com.gamma.recon;

import com.gamma.job.Job;
import com.gamma.job.JobConfig;
import com.gamma.job.JobTypeDescriptor;
import com.gamma.job.JobTypeProvider;
import com.gamma.job.ParamType;
import com.gamma.job.ParameterDecl;

import java.util.List;

/**
 * The {@code recon.run} Job Type, contributed by this module (MODULE-REORG-1 P7). It used to be a built-in
 * hard-coded in {@code JobService}; it now arrives through the engine's {@code ServiceLoader} loop, which runs
 * after every built-in and refuses an id collision, so it can never shadow one.
 *
 * <p>A {@link JobTypeProvider} is constructed with no arguments and {@link #create} receives only a
 * {@link JobConfig}, so the Job resolves the Space's data root and the Incident seam at run time. The
 * descriptor declares {@code requires: [objects]} — grants are honest, an undeclared Platform Service is
 * invisible to a running Job. On a bundle without {@code inspecto-ops} the grant is empty and the Job stays
 * signal-only, exactly as before.
 */
public final class ReconRunJobType implements JobTypeProvider {

    @Override
    public JobTypeDescriptor descriptor() {
        return new JobTypeDescriptor("recon.run", "Reconciliation Run",
                "Runs a saved Reconciliation over its Datasets and emits a signal carrying the Break counts.",
                List.of(ParameterDecl.required("reconciliation", ParamType.STRING, "Saved reconciliation component id"),
                        ParameterDecl.optional("day", ParamType.DATE, null,
                                "The day to reconcile (yyyy-mm-dd); absent = the latest day present on any side")),
                List.of(ReconSignals.RECON_RUN_COMPLETED), List.of(), List.of("objects"));
    }

    @Override
    public Job create(JobConfig config) {
        return new ReconRunJob(config, null, null);
    }
}
