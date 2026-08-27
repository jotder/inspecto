package com.gamma.service;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.config.io.ConfigCodec;
import com.gamma.etl.PipelineConfig;
import com.gamma.job.JobConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Resolves a data source (a pipeline) to its cohesive {@link DataSourceBundle} — the pipeline config plus
 * everything it references: the connection profile it binds to, the schema / grammar files it read at parse
 * time, any job that targets it, and the registry components bound to it (Decision Rules targeting it,
 * Datasets reading its store — see {@link #findComponentsFor}).
 *
 * <p>Scoped to one space: parsed configs come from that space's {@link ReadModel}; the connection / job
 * files are found by scanning that space's {@code config/} tree, because they are addressed by their in-file
 * id ({@code connection.id} / {@code on_pipeline}), not by their filename. Bad / unreadable connection or job
 * files are warned-and-skipped (mirroring boot discovery) so one malformed file never breaks resolution.
 */
public final class DataSourceBundleResolver {

    private static final Logger log = LoggerFactory.getLogger(DataSourceBundleResolver.class);

    private final ReadModel service;
    private final Path configDir;

    public DataSourceBundleResolver(ReadModel service, Path configDir) {
        this.service   = Objects.requireNonNull(service, "service");
        this.configDir = Objects.requireNonNull(configDir, "configDir");
    }

    /** The data-source ids (pipeline names) that resolve to a bundle in this space, sorted. */
    public List<String> dataSourceIds() {
        return service.pipelines().stream()
                .map(PipelineView::name)
                .sorted()
                .toList();
    }

    /**
     * Resolve the bundle for one data source.
     *
     * @param dataSourceId the pipeline name (as listed by {@link #dataSourceIds()})
     * @throws NoSuchElementException if no pipeline with that name exists in this space
     */
    public DataSourceBundle resolve(String dataSourceId) {
        PipelineConfig cfg = service.configFor(dataSourceId)
                .orElseThrow(() -> new NoSuchElementException("no pipeline '" + dataSourceId + "' in this space"));
        Path pipelineFile = service.pathFor(dataSourceId)
                .orElseThrow(() -> new NoSuchElementException("no config file for pipeline '" + dataSourceId + "'"));

        Path connection = cfg.collector().hasConnection()
                ? findConnectionFile(cfg.collector().connection())
                : null;

        List<Path> jobs = findJobsFor(dataSourceId);
        return new DataSourceBundle(
                dataSourceId, pipelineFile, connection,
                cfg.referencedFiles(), jobs, findComponentsFor(dataSourceId, jobs));
    }

    /** The {@code *_connection.toon} whose in-file {@code id} matches {@code connId}, or {@code null} if none. */
    private Path findConnectionFile(String connId) {
        for (Path p : scan("_connection.toon")) {
            try {
                if (connId.equals(ConnectionProfile.load(p).id())) return p;
            } catch (Exception e) {
                log.warn("skipping unreadable connection file {}: {}", p, e.toString());
            }
        }
        log.warn("connection '{}' is referenced by a pipeline but no matching *_connection.toon was found under {}",
                connId, configDir);
        return null;
    }

    /**
     * Every {@code *_job.toon} whose {@code on_pipeline} targets {@code pipelineName}. Matched
     * case-insensitively because the engine lowercases pipeline names on the event bus (a job's
     * {@code on_pipeline} is conventionally the lowercased pipeline name).
     */
    private List<Path> findJobsFor(String pipelineName) {
        List<Path> jobs = new ArrayList<>();
        for (Path p : scan("_job.toon")) {
            try {
                String target = JobConfig.load(p.toString()).onPipeline();
                if (target != null && target.equalsIgnoreCase(pipelineName)) jobs.add(p);
            } catch (Exception e) {
                log.warn("skipping unreadable job file {}: {}", p, e.toString());
            }
        }
        return jobs;
    }

    /**
     * The registry components bound to this data source (W3, 2026-08-01): the **Decision Rules** that
     * target it and the **Datasets** that read its store. Both live in the component registry
     * ({@code config/registry/<type-dir>/<id>.toon}, discovered by <em>directory</em> —
     * {@code ComponentRegistry.TYPE_BY_DIR} — never by a filename suffix), which is why the suffix scans
     * above could never have found them. They are inside {@code config/}, so once they are in the bundle
     * the import side already handles them: {@code BundleImporter.writeConfig} unpacks anything
     * config-relative, and a fresh {@link SpaceBootstrap} boot re-scans the registry.
     *
     * <p>Both are <b>reverse</b> references — the component names the pipeline, not the other way round —
     * exactly like {@link #findJobsFor}. Matching is deliberately narrow:
     * <ul>
     *   <li><b>Decision Rule</b> — {@code target} matched case-insensitively (as
     *       {@code DecisionRules.forTarget} does) against this pipeline for the default
     *       {@code targetType: pipeline}, or against a job <em>this bundle already carries</em> for
     *       {@code targetType: job}. A {@code job}-targeted rule whose job stays behind must stay behind
     *       too, or the target gets a rule pointing at nothing.</li>
     *   <li><b>Dataset</b> — the first path segment of {@code physicalRef} matched against the pipeline
     *       name. That is the established store-name convention, not a guess:
     *       {@code DbBrowserRoutes.pipelineDatabaseDir} resolves a store name by calling
     *       {@code configFor(storeName)} directly, and a deeper ref ({@code orders/database}) still names
     *       the same store in its first segment.</li>
     * </ul>
     *
     * <p><b>Deliberately NOT matched</b>, so the omissions are known rather than accidental:
     * a {@code view}-backed Dataset (its {@code view} resolves through a {@code ViewStore} living outside
     * {@code config/}, so the thing it depends on cannot ride in a config bundle at all); a Dataset over a
     * bundled <em>job's</em> output store ({@code orders_rollup_dataset}'s {@code physicalRef: rollup} —
     * resolving a job's output store means following job → flow → sink, which nothing here reads today);
     * and Alert Rules / Expectations, which are the same shape and the same one-line addition, but were
     * not in this change's scope.
     *
     * <p>A <b>disabled</b> Decision Rule still travels. {@code DecisionRules} filters {@code enabled} at
     * <em>evaluation</em> time; an export is a promotion of the config as authored, and silently dropping
     * a rule someone turned off would lose their intent.
     */
    private List<Path> findComponentsFor(String pipelineName, List<Path> jobs) {
        List<String> jobNames = jobs.stream().map(this::jobNameOf).filter(Objects::nonNull).toList();
        List<Path> out = new ArrayList<>();
        out.addAll(componentFiles("decision-rules", c -> ruleTargets(c, pipelineName, jobNames)));
        out.addAll(componentFiles("datasets", c -> datasetReadsStore(c, pipelineName)));
        return out;
    }

    /** Whether a Decision Rule's {@code targetType}/{@code target} names this pipeline or a bundled job. */
    private static boolean ruleTargets(Map<String, Object> rule, String pipelineName, List<String> jobNames) {
        String target = rule.get("target") == null ? "" : String.valueOf(rule.get("target")).trim();
        if (target.isEmpty()) return false;
        String type = String.valueOf(rule.getOrDefault("targetType", "pipeline"));
        if ("pipeline".equalsIgnoreCase(type)) return target.equalsIgnoreCase(pipelineName);
        if ("job".equalsIgnoreCase(type)) return jobNames.stream().anyMatch(target::equalsIgnoreCase);
        return false;   // an unknown targetType is not this pipeline's business
    }

    /** Whether a Dataset's {@code physicalRef} reads this pipeline's store (first path segment). */
    private static boolean datasetReadsStore(Map<String, Object> dataset, String pipelineName) {
        Object ref = dataset.get("physicalRef");
        String s = ref == null ? "" : String.valueOf(ref).trim();
        if (s.isEmpty() || "null".equals(s)) return false;
        int slash = s.indexOf('/');
        return (slash < 0 ? s : s.substring(0, slash)).equalsIgnoreCase(pipelineName);
    }

    /**
     * The {@code *.toon} files directly under {@code config/registry/<typeDir>/} whose parsed content the
     * predicate accepts, sorted. Unreadable files are warned-and-skipped, matching boot discovery and the
     * connection/job scans above — one malformed component must never fail a whole export.
     */
    private List<Path> componentFiles(String typeDir, Predicate<Map<String, Object>> matches) {
        Path dir = configDir.resolve("registry").resolve(typeDir);
        if (!Files.isDirectory(dir)) return List.of();
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            // Files.list, not walk: ComponentRegistry reads only the files DIRECTLY in the type dir, so
            // its `.history/` snapshots are not components and must not be exported as if they were.
            for (Path p : s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".toon")).sorted().toList()) {
                try {
                    if (matches.test(ConfigCodec.toMap(Files.readString(p)))) out.add(p);
                } catch (RuntimeException | IOException bad) {
                    log.warn("skipping unreadable {} component {}: {}", typeDir, p, bad.toString());
                }
            }
        } catch (IOException e) {
            log.warn("failed to list {}: {}", dir, e.toString());
        }
        return out;
    }

    /** A job's in-file {@code name}, or {@code null} if the file will not load. */
    private String jobNameOf(Path job) {
        try {
            return JobConfig.load(job.toString()).name();
        } catch (Exception e) {
            log.warn("skipping unreadable job file {}: {}", job, e.toString());
            return null;
        }
    }

    /** All regular files under the space's config tree whose name ends with {@code suffix}, sorted. */
    private List<Path> scan(String suffix) {
        if (!Files.isDirectory(configDir)) return List.of();
        try (Stream<Path> s = Files.walk(configDir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("failed to scan {} for {}: {}", configDir, suffix, e.toString());
            return List.of();
        }
    }
}
