package com.gamma.consignment;

import com.gamma.api.PublicApi;
import com.gamma.job.Job;
import com.gamma.job.JobConfig;
import com.gamma.job.JobContext;
import com.gamma.job.JobResult;
import com.gamma.job.JobTypeDescriptor;
import com.gamma.job.JobTypeProvider;
import com.gamma.job.ParamType;
import com.gamma.job.ParameterDecl;
import com.gamma.job.RunLog;
import com.gamma.signal.SignalEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Function;

/**
 * <b>§14.2 — the framework half.</b> The built-in {@code consignment.process} Job Type that resolves which
 * Consignment a run is about, narrows everything to it, and calls a third-party {@link ConsignmentProcessor}.
 *
 * <p><b>No new registry and no new {@code ServiceLoader} contract for Jobs.</b> Registration reuses the existing
 * {@link JobTypeProvider} seam (a class-based provider, as {@code SqlTemplateJobType} already is), and
 * {@code JobTypeRegistry}'s duplicate-id guard means a Job Pack can never displace this built-in.
 *
 * <p><b>How the author avoids knowing about Signals.</b> The Consignment id is declared as a
 * {@link ParameterDecl} whose {@code deduce} expression is {@code $signal.batchId} — resolved by the existing
 * {@code ParameterResolver} against the firing Signal's payload, which {@code JobService.mirrorPipelineCommit}
 * populates for every {@code pipeline.commit}. So a processor triggered by a commit receives the right
 * Consignment without declaring anything about signals, and the same Job triggered manually works by binding
 * {@code consignment_id} in config. A required parameter that resolves to nothing fails the run before any
 * author code executes.
 */
@PublicApi(since = "5.0.0")
public final class ConsignmentProcessJobType implements JobTypeProvider {

    /** The Job Type id — the {@code type:} string in a {@code *_job.toon}. */
    public static final String TYPE_ID = "consignment.process";

    static final String P_CONSIGNMENT = "consignment_id";
    static final String P_PROCESSOR = "processor";

    private final Function<String, ConsignmentProcessor> lookup;

    /** Production: processors are discovered by {@link ServiceLoader}. */
    public ConsignmentProcessJobType() {
        this(ConsignmentProcessJobType::fromServiceLoader);
    }

    /** Test/embedder seam: resolve a processor id without going through {@code META-INF/services}. */
    ConsignmentProcessJobType(Function<String, ConsignmentProcessor> lookup) {
        this.lookup = lookup;
    }

    @Override
    public JobTypeDescriptor descriptor() {
        return new JobTypeDescriptor(TYPE_ID, "Consignment Processor",
                "Runs a ConsignmentProcessor over one committed Consignment, with a read-only view of the "
                        + "files it wrote and §7.2-guarded summary emission.",
                List.of(
                        new ParameterDecl(P_CONSIGNMENT, ParamType.STRING, true, "$signal.batchId", null,
                                "The Consignment to process. Deduced from the firing pipeline.commit Signal; "
                                        + "bind it explicitly for a manual run."),
                        ParameterDecl.required(P_PROCESSOR, ParamType.STRING,
                                "The id() of the ConsignmentProcessor to run.")),
                List.of(), List.of());
    }

    @Override
    public Job create(JobConfig config) {
        return new ProcessJob(config.name(), lookup);
    }

    /** The first registered processor whose {@link ConsignmentProcessor#id()} matches, or {@code null}. */
    private static ConsignmentProcessor fromServiceLoader(String id) {
        if (id == null || id.isBlank()) return null;
        for (ConsignmentProcessor p : ServiceLoader.load(ConsignmentProcessor.class))
            if (id.equals(p.id())) return p;
        return null;
    }

    // ── the run ──────────────────────────────────────────────────────────────────

    private static final class ProcessJob implements Job {

        private final String name;
        private final Function<String, ConsignmentProcessor> lookup;

        ProcessJob(String name, Function<String, ConsignmentProcessor> lookup) {
            this.name = name;
            this.lookup = lookup;
        }

        @Override public String name() { return name; }

        @Override public String type() { return TYPE_ID; }

        /** This type is parameter-driven, so the legacy no-arg entry point cannot do the work. */
        @Override
        public JobResult run() {
            return JobResult.failed(TYPE_ID + " requires a JobContext (its parameters carry the Consignment id)", 0L);
        }

        @Override
        public JobResult run(JobContext ctx) throws Exception {
            long t0 = System.nanoTime();
            String consignmentId = ctx.params().get(P_CONSIGNMENT);
            if (consignmentId == null || consignmentId.isBlank())
                return JobResult.failed("no " + P_CONSIGNMENT + ": nothing was bound and $signal.batchId did "
                        + "not resolve — a manual run must bind it in config", ms(t0));

            String processorId = ctx.params().get(P_PROCESSOR);
            ConsignmentProcessor processor = lookup.apply(processorId);
            if (processor == null)
                return JobResult.failed("no ConsignmentProcessor registered with id '" + processorId
                        + "' — declare it in META-INF/services/" + ConsignmentProcessor.class.getName(), ms(t0));

            // The registry is default-off; an absent store means no readable relations, NOT that the
            // Consignment wrote nothing. The manifest remains authoritative for existence (§11.3).
            DbConsignmentOutputStore store = ConsignmentOutputStores.shared();
            List<ConsignmentOutput> outputs = (store == null) ? List.of() : store.outputs(consignmentId);
            if (store == null)
                ctx.log().warn("consignment output registry is disabled — the processor gets no readable "
                        + "relations", "consignment_id", consignmentId);

            GuardedSummaryEmitter summaries = new GuardedSummaryEmitter();
            try (ConsignmentReader reader = SandboxConsignmentReader.over(outputs)) {
                ProcessorResult result = processor.process(
                        new AdaptedContext(consignmentId, outputs, reader, summaries, ctx));

                // §7.2's free reconciliation — reported, never thrown: summarising a filtered subset is legal.
                summaries.reconcile(outputs).ifPresent(diff ->
                        ctx.log().warn("summary count does not reconcile against detail rows",
                                "consignment_id", consignmentId, "detail", diff));

                if (result == null)
                    return JobResult.failed("processor '" + processorId + "' returned no result", ms(t0));
                return new JobResult(result.status(), result.message(), ms(t0));
            }
        }

        private static long ms(long t0) {
            return (System.nanoTime() - t0) / 1_000_000L;
        }
    }

    /**
     * The {@link ProcessorContext} the adapter hands the author: Consignment-scoped, with the Job surface
     * delegated member-by-member rather than exposed wholesale.
     */
    private record AdaptedContext(String consignmentId, List<ConsignmentOutput> outputs,
                                  ConsignmentReader read, SummaryEmitter summaries,
                                  JobContext job) implements ProcessorContext {

        @Override public RunLog log() { return job.log(); }

        @Override public boolean dryRun() { return job.dryRun(); }

        /** Stamps {@code consignment_id} into every payload so an author never re-states it. */
        @Override
        public SignalEmitter signals() {
            return (type, severity, payload) -> {
                Map<String, Object> stamped = new LinkedHashMap<>();
                if (payload != null) stamped.putAll(payload);
                stamped.putIfAbsent("consignment_id", consignmentId);
                job.signals().emit(type, severity, stamped);
            };
        }
    }
}
