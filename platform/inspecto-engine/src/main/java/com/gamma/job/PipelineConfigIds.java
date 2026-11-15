package com.gamma.job;

import com.gamma.config.safety.PathJail;
import com.gamma.util.ToonHelper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Which Pipeline a Job's {@code pipeline_config:} runs — the one rule the save gate's chain-without-Job warning
 * ({@code ConfigRoutes.chainWithoutJobFindings}) and the run-time orphan {@code output_store:} audit
 * ({@code SchedulerAuditTask.orphanOutputStoreFindings}) share, so the two cannot drift
 * (SCHEDULER-AUDIT-PIPELINE-NAME-1). Internal: not part of the {@code @PublicApi} surface.
 */
public final class PipelineConfigIds {

    private PipelineConfigIds() {}

    /**
     * The registered id of the Pipeline file a Job's {@code pipeline_config:} value names, resolved exactly as
     * {@link PipelineJobRunner} resolves it ({@code PathJail.requireJobPathUnderAny} over
     * {@code PathJail.allowedRoots()} from {@code base}, the run's own {@code SpaceConfigRoot.jobPathBase("pipeline_config")}).
     * The id is the file's own identity — {@code id:}, else {@code name:} lower-cased with spaces as {@code _} —
     * never its file name: the shipped samples name {@code orders_pipeline.toon} for a Pipeline called
     * {@code STEP_DEDUP}. A path the runner refuses (outside the jail, or a secrets directory) is never read.
     *
     * @return the id, or {@code null} when the value is blank, refused, missing or unreadable — a Job the runner
     *         could not run either
     */
    public static String registeredIdOf(String pipelineConfig, Path base) {
        if (pipelineConfig == null || pipelineConfig.isBlank()) return null;
        try {
            Path file = PathJail.requireJobPathUnderAny(PathJail.allowedRoots(), base, pipelineConfig.trim(),
                    "job.pipeline_config");
            if (!Files.isRegularFile(file)) return null;
            Map<String, Object> raw = ToonHelper.decode(Files.readString(file));
            Object explicit = raw.get("id");
            String id = explicit == null ? "" : String.valueOf(explicit).trim();
            if (!id.isEmpty()) return id;
            Object nm = raw.get("name");
            String derived = nm == null ? "" : String.valueOf(nm).trim();
            return derived.isEmpty() ? null : derived.toLowerCase().replace(' ', '_');
        } catch (Exception unreadable) {   // a Job the runner could not run either: it runs nothing
            return null;
        }
    }
}
