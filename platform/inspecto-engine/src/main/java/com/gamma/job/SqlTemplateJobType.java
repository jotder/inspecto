package com.gamma.job;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link JobTypeProvider} for the {@code sql.template} Job Type (§15.1, P3b). Its
 * {@link #parameters(JobConfig)} is config-aware: the authored SQL's {@code $name} tokens
 * <i>are</i> its runtime parameter contract (scanned by {@link SqlParamScanner}), surfaced on top of the
 * static config keys so {@code GET /jobs/types/sql.template} drives an authoring form that adapts to the
 * SQL. Constructed with the space's {@code dataDir} (the built-in injection convention).
 */
final class SqlTemplateJobType implements JobTypeProvider {

    static final JobTypeDescriptor DESCRIPTOR = new JobTypeDescriptor("sql.template", "Templated SQL",
            "Runs an authored SQL template over source Datasets and materializes the result as a queryable Dataset.",
            // expressions: false — the SQL body owns its own $-namespace (its $name tokens ARE this Job's
            // parameter contract, scanned below), so it is exempt from Expression evaluation
            // (job-parameter-contract §6.1, scoped evaluation). Runtime Expressions reach the SQL through
            // the *values* of those template parameters: `WHERE dt = $from` with `params: {from: $yesterday}`.
            List.of(ParameterDecl.of("sql", ParamType.TEXT).required().noExpressions()
                            .description("SQL SELECT template; its $name tokens are the runtime parameters").build(),
                    ParameterDecl.required("sink_dataset", ParamType.STRING, "Output Dataset (store dir under the data root)"),
                    ParameterDecl.optional("sources", ParamType.STRING, null, "CSV of source store names to register as views")),
            List.of(com.gamma.signal.SignalType.JOB_DATASET_PRODUCED),
            List.of(ArtifactDecl.dataset("output")));

    private final String dataDir;

    SqlTemplateJobType(String dataDir) { this.dataDir = dataDir; }

    @Override public JobTypeDescriptor descriptor() { return DESCRIPTOR; }

    @Override public Job create(JobConfig config) { return new SqlTemplateJob(config, dataDir); }

    /** Static config keys plus every {@code $name} scanned from this config's SQL (R3 — the SQL is the contract). */
    @Override
    public List<ParameterDecl> parameters(JobConfig config) {
        List<ParameterDecl> scanned = SqlParamScanner.scan(config.params().get("sql"));
        boolean incremental = IncrementalSpec.parse(config.name(), config.type(), config.params()) != null;
        if (scanned.isEmpty() && !incremental) return DESCRIPTOR.parameters();
        List<ParameterDecl> all = new ArrayList<>(DESCRIPTOR.parameters());
        all.addAll(scanned);
        // INCREMENTAL-1 (operator, 2026-10-10): the day the lookback ends on; absent = today (UTC).
        if (incremental && scanned.stream().noneMatch(d -> d.name().equals("day")))
            all.add(ParameterDecl.optional("day", ParamType.DATE, null,
                    "Incremental: the day the lookback window ends on (default today, UTC)"));
        return List.copyOf(all);
    }
}
