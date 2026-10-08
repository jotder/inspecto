package com.gamma.opsjob;

import com.gamma.job.Job;
import com.gamma.job.JobConfig;
import com.gamma.job.JobTypeDescriptor;
import com.gamma.job.JobTypeProvider;
import com.gamma.job.ParamType;
import com.gamma.job.ParameterDecl;
import com.gamma.objects.ObjectAccess;
import com.gamma.ops.ObjectService;
import com.gamma.ops.ObjectServiceAccess;

import java.util.List;

/**
 * The operational-object Job Type(s), contributed by this module (EDG-01 cell 7, 2026-09-08).
 *
 * <p>{@code caserule.evaluate} (now in inspecto-case-management) and {@code objects.analytics} were hard-coded in {@code JobService} and
 * could not stay: they call {@code ObjectService.evaluateCaseRule} and the analytics rollups, which the
 * {@link ObjectAccess} seam deliberately does not expose. They are whole operational-object features, not
 * narrow consumers, so widening the seam for them would have defeated the extraction.
 *
 * <p>⚠ They arrive through {@code JobService}'s existing {@code ServiceLoader.load(JobTypeProvider.class)}
 * loop, which runs <b>after</b> every built-in registration and warns-and-skips an id collision. So a
 * contributed type can never shadow a built-in — the ordering was already fail-closed and is relied on
 * rather than rebuilt.
 *
 * <p>🔴 <b>How a contributed Job Type reaches the engine.</b> A {@code JobTypeProvider} is constructed with
 * no arguments and {@code create} receives only a {@code JobConfig} — neither the engine nor the data root.
 * A <b>running</b> job has more: {@code JobContext.services()}, where core registers the {@code ObjectAccess}
 * seam, and {@code spaceId()}, which this module maps to its own per-Space data root. So resolution is
 * deferred to run time, and on a bundle whose engine is missing the job fails with a clear message instead
 * of the provider failing to load.
 *
 * <p>🔴 {@code JobContext.services()} holds only what the descriptor {@code requires:} (platform-services S1-2 —
 * grants are honest). Both types must declare {@link #OBJECTS}: without it every run in a deployed build failed
 * "needs the space Object Engine", while the direct-construction tests passed ({@code OpsJobTypesRunInAServiceTest}).
 */
public final class OpsJobTypes {

    private OpsJobTypes() {}

    /** The Platform Service id core registers the {@link ObjectAccess} seam under ({@code CollectorService}). */
    public static final List<String> OBJECTS = List.of("objects");

    /** The engine for a running job's Space, or {@code null} when none is installed. */
    public static ObjectService engineFor(com.gamma.job.JobContext ctx) {
        return ctx.services().find(ObjectAccess.class)
                .filter(ObjectServiceAccess.class::isInstance)
                .map(a -> ((ObjectServiceAccess) a).service())
                .orElse(null);
    }

    /** {@code objects.analytics} — materialize the Alert/Incident/Case/Task rollups as Parquet samples. */
    public static final class ObjectsAnalytics implements JobTypeProvider {
        @Override
        public JobTypeDescriptor descriptor() {
            return new JobTypeDescriptor("objects.analytics", "Object Analytics Sample",
                    "Samples Incident/Case/Task analytics into the ops_analytics Dataset for Studio/BI.",
                    List.of(ParameterDecl.optional("types", ParamType.STRING, null,
                                    "CSV of INCIDENT | CASE | TASK (default: all three)"),
                            ParameterDecl.optional("retention_days", ParamType.INTEGER, "0",
                                    "Forget samples older than N days (0 = keep forever)")),
                    List.of("objects.analytics.completed"), List.of(), OBJECTS);
        }

        /** ⚠ No supplier and no data root: both are resolved from the {@code JobContext} at run time. */
        @Override
        public Job create(JobConfig config) {
            return new ObjectsAnalyticsJob(config, null, null);
        }
    }
}
