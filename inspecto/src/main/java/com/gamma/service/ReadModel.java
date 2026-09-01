package com.gamma.service;

import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.MetadataGraphService;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.StatusStore;
import com.gamma.event.EventLog;
import com.gamma.event.EventStore;
import com.gamma.job.JobService;
import com.gamma.ops.ObjectService;
import com.gamma.report.ReportService;
import com.gamma.util.BrowsableStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The read-only slice of {@link CollectorService} that its satellite collaborators actually use.
 *
 * <p>Six classes hold or receive a {@code CollectorService} purely to read from it — {@code ReportService},
 * {@code MetricsService}, {@code DataSourceBundleResolver}, {@code ContextBroker}, {@code InspectoTools} and
 * {@code OperationalActions}. Each compiles against a 1693-line, 60+-method class to call between one and
 * seven accessors. This interface is that union and nothing more, so a collaborator's signature states what
 * it needs, and a test can supply a fake instead of a live {@code CollectorService}.
 *
 * <h2>Update — the agent SPIs take this type too (operator decision #1, 2026-08-27)</h2>
 *
 * <p>{@code AssistAgent.init} and {@code IntelligenceAgent.init} now receive a {@code ReadModel} instead of
 * the concrete class. A usage census of every consumer the agents hand the handle to (the three
 * implementors, {@code InspectoPack} → {@code InspectoToolProvider} → {@code InspectoTools},
 * {@code ContextBroker}, {@code Investigator}, {@code OperationalActions} and the action previewer) found
 * <b>zero mutating calls</b> — the act tools write through the audited control plane, never through this
 * handle. Five read accessors the agents use were widened onto this interface for that:
 * {@link #catalog()}, {@link #reports()}, {@link #configSource()}, {@link #eventLog()},
 * {@link #objects()}. All five return types were already imported by this package, so the widening adds
 * no package edge.
 *
 * <p><b>Every member here is a pure read.</b> Nothing that registers, schedules, starts, runs, pauses or
 * closes belongs on this type — that is the distinction it exists to draw. {@code ApiContext},
 * {@code ControlApi} and {@code SpaceContext} legitimately need the mutation and lifecycle surface and
 * deliberately keep taking the concrete class.
 *
 * <h2>Why this lives in {@code com.gamma.service} (decided 2026-08-27)</h2>
 *
 * ⚠ <b>It buys no package-graph improvement, and it was never able to.</b> The reason it was proposed —
 * cutting the {@code report -> service} edge of the C3 cycle — does not survive contact with the code:
 * that edge had three independent holders and this interface removes only one.
 *
 * <p>So the choice was between the package the read surface is already written in and a package that would
 * have to import it anyway. The only homes shared by the consumers are {@code com.gamma.etl} and
 * {@code com.gamma.job}; {@code service -> etl} and {@code service -> job} already exist, so hosting this
 * there would <b>create</b> a cycle to buy nothing. This package is the one honest home.
 *
 * <p>⛔ Consequently: do not report this type as reducing coupling between packages. It reduces <i>fan-in on
 * a god class</i> and makes six collaborators testable. That is the whole claim.
 *
 * <h2>Update 4.0.0 — the nested-record holder is gone, the edge is not</h2>
 *
 * <p>{@link PipelineView} / {@link PipelineRun} / {@link InboxStatus} were promoted out of
 * {@code CollectorService} into top-level records (operator decision #4: {@code @PublicApi} types may
 * relocate on a major bump). That removed the second holder and dropped {@code CollectorService}'s fan-in
 * from 25 files to 16 — every caller of {@link #pipelines()} used to spell
 * {@code CollectorService.PipelineView} and so imported the concrete class no matter what type its
 * receiver had.
 *
 * <p>⚠ <b>The {@code report -> service} package edge still stands.</b> The promoted records live in
 * {@code com.gamma.service} too, so {@code ReportService} now imports {@code PipelineView} instead of
 * {@code CollectorService} — a different holder, the same edge — and it independently imports
 * {@code EnrichmentService}. Cutting the edge would additionally need a role interface for
 * {@code EnrichmentService} and a home outside this package for the view records. Neither is done.
 */
public interface ReadModel {

    /** Every registered pipeline's identity + current state. */
    List<PipelineView> pipelines();

    /** The parsed config for a pipeline, or empty when no pipeline of that name is registered. */
    Optional<PipelineConfig> configFor(String pipelineName);

    /** The on-disk config file backing a pipeline, or empty when it is not registered. */
    Optional<Path> pathFor(String pipelineName);

    /** The batch/run status store shared by every pipeline. */
    StatusStore statusStore();

    /** The signal/event store. */
    EventStore events();

    /** The in-process batch event bus. */
    ConsignmentEventBus eventBus();

    /** The operational event/audit log (read side; agents subscribe and query, never emit through this). */
    EventLog eventLog();

    /** The Catalog metadata graph. */
    MetadataGraphService catalog();

    /** The status/report roll-up service. */
    ReportService reports();

    /** Where config files came from — the Catalog's config-origin index. */
    ConfigSource configSource();

    /** The operational-object service (Incidents/Cases read surface). */
    ObjectService objects();

    /** The job service, or empty when this deployment runs no jobs. */
    Optional<JobService> jobService();

    /** The enrichment service, or empty when no enrichment is configured. */
    Optional<EnrichmentService> enrichmentService();

    /** Root of the space's data tree. */
    Path dataRoot();

    /** Every store the Catalog/browse surfaces may read from. */
    List<BrowsableStore> browsableStores();
}
