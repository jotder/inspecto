package com.gamma.job;

import java.nio.file.Path;
import java.util.Map;
import java.time.Instant;

/**
 * How a Job records its Run Artifacts — queryable result metadata (R7, {@code docs/job-framework-design.md}
 * §10). Obtained from {@link JobContext#artifacts()} and called during {@code run(ctx)}. Each recorded
 * artifact is persisted per Run and is queryable two ways: the Control API
 * ({@code GET /jobs/{name}/runs/{runId}/artifacts}, {@code GET /jobs/{name}/artifacts/latest}) and the
 * Parameter Context ({@code $upstream(<job>).artifact(<name>).<attr>}, §7.3) that lets a downstream Job
 * bind to what its predecessor produced.
 *
 * <p>Recording metadata is the P1d scope; auto-registering a queryable {@code ViewDefinition} for a
 * dataset artifact (§10, the {@code ViewStore} bridge) is a deferred follow-on.
 */
public interface ArtifactRecorder {

    /** A produced/updated Dataset (Table | Derived Table | View). */
    void dataset(String name, String datasetRef, ResultSetMeta resultSet, long rows, Instant watermark);

    /** A produced file (export, report output, …). */
    void file(String name, Path path, long bytes);

    /**
     * The run's parameter RECEIPT (`DUCKLE-C4-PARAM-PROVENANCE-1`): for every resolved parameter, which layer
     * supplied it and which lower layers it overrode — {@code {name → {source, overrode:[…]}}}. Layer names
     * only, never values, so a {@code secret} needs no masking here. Default no-op so a recorder that has
     * nowhere to put it (dry-run wrappers, tests) is not forced to care.
     */
    default void params(Map<String, Map<String, Object>> provenance) {}
}
