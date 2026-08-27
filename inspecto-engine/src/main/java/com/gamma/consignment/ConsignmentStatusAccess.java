package com.gamma.consignment;

import com.gamma.api.PublicApi;
import com.gamma.etl.BatchManifest;
import com.gamma.etl.ManifestStore;
import com.gamma.etl.PipelineConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Platform Service {@code consignment-status} (platform-services S1-6): read-only answers to "what
 * happened to this Consignment" — its manifest (pipeline, schema identity, member files and their
 * per-file status, produced outputs), the output-file registry rows, and a file's stage progression.
 * Granted to a Run via a Job Type's {@code requires: [consignment-status]} declaration.
 *
 * <h3>What backs each answer</h3>
 * <ul>
 *   <li>{@link #consignment} / {@link #latestFor} — the JSON {@link BatchManifest} under a pipeline's
 *       {@code manifestsDir}, which is authoritative for a Consignment's existence and carries
 *       per-member status, so no separate status-store read is needed.</li>
 *   <li>{@link #outputs} — {@link DbConsignmentOutputStore} via the per-space
 *       {@link ConsignmentOutputStores} registry, which is <b>default-off</b>: an empty list means
 *       "no registry configured" as much as "no outputs", and the manifest stays authoritative.</li>
 *   <li>{@link #fileStages} — {@link FileStages}, likewise default-off and empty when absent.</li>
 * </ul>
 *
 * <h3>Dry-run contract (plan §3.4)</h3>
 * Read-only — unaffected by a dry run; the real service is handed through unchanged.
 *
 * @since 5.1.0
 */
@PublicApi(since = "4.0.0")
public interface ConsignmentStatusAccess {

    /** The manifest of this Consignment, searched across the loaded pipelines, or empty when unknown. */
    Optional<BatchManifest> consignment(String consignmentId);

    /** The newest Consignment manifest of {@code pipeline} (by {@code createdAt}), or empty when it has none. */
    Optional<BatchManifest> latestFor(String pipeline);

    /** The registry's output-file rows for this Consignment, newest-first; empty when the registry is off. */
    List<ConsignmentOutput> outputs(String consignmentId);

    /** One acquired file's stage progression, oldest-first; empty when the stage store is off. */
    List<FileStageRecord> fileStages(String sourceId, String relativePath);

    /** The production implementation over a live view of the loaded pipelines — the supplier is invoked
     *  per call, so a pipeline registered after boot is searched too. */
    static ConsignmentStatusAccess over(Supplier<List<PipelineConfig>> pipelines) {
        return new ConsignmentStatusAccess() {

            @Override public Optional<BatchManifest> consignment(String consignmentId) {
                if (consignmentId == null || consignmentId.isBlank()) return Optional.empty();
                for (String dir : manifestDirs(null)) {
                    if (Files.exists(Path.of(dir, consignmentId + ".json"))) return read(dir, consignmentId);
                }
                return Optional.empty();
            }

            @Override public Optional<BatchManifest> latestFor(String pipeline) {
                if (pipeline == null || pipeline.isBlank()) return Optional.empty();
                List<BatchManifest> found = new ArrayList<>();
                for (String dir : manifestDirs(pipeline)) {
                    try (Stream<Path> files = Files.list(Path.of(dir))) {
                        files.map(Path::getFileName).map(Path::toString)
                                .filter(f -> f.endsWith(".json"))
                                .map(f -> f.substring(0, f.length() - ".json".length()))
                                .forEach(id -> read(dir, id).ifPresent(found::add));
                    } catch (IOException | RuntimeException unreadable) {
                        // an unreadable manifests dir contributes nothing — never fails the lookup
                    }
                }
                // createdAt is the DuckDbUtil timestamp format, so lexical order is chronological.
                return found.stream().max(Comparator.comparing(m -> m.createdAt == null ? "" : m.createdAt));
            }

            @Override public List<ConsignmentOutput> outputs(String consignmentId) {
                DbConsignmentOutputStore store = ConsignmentOutputStores.shared();
                if (store == null || consignmentId == null || consignmentId.isBlank()) return List.of();
                return store.outputs(consignmentId);
            }

            @Override public List<FileStageRecord> fileStages(String sourceId, String relativePath) {
                return FileStages.stages(sourceId, relativePath);
            }

            /** The manifests dirs of the loaded pipelines (all, or only {@code pipeline}'s), skipping the
             *  ones with status disabled — {@code manifestsDir} is null there. */
            private List<String> manifestDirs(String pipeline) {
                List<String> dirs = new ArrayList<>();
                for (PipelineConfig cfg : pipelines.get()) {
                    if (pipeline != null && !pipeline.equals(cfg.identity().pipelineName())
                            && !pipeline.equals(cfg.identity().name())) continue;
                    String dir = cfg.dirs().manifestsDir();
                    if (dir != null && !dir.isBlank() && Files.isDirectory(Path.of(dir))) dirs.add(dir);
                }
                return dirs;
            }

            private Optional<BatchManifest> read(String dir, String consignmentId) {
                try {
                    return Optional.of(ManifestStore.read(dir, consignmentId));
                } catch (IOException | RuntimeException unreadable) {
                    return Optional.empty();   // a missing/corrupt manifest reads as absent, never throws
                }
            }
        };
    }
}
