package com.gamma.etl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * <b>Phase 4 S4c — a parked Consignment's pending commit state.</b> When a batch PARKS at a disabled
 * route-branch sink, its <em>enabled</em> branches have already written their partition files and
 * recorded a {@code BRANCH} row in the {@link com.gamma.etl.CommitLog}'s branch-aware cousin — but a
 * parked batch runs no commit tail, so the {@link PartitionOutput}s, {@link LineageRow}s and
 * event-time bounds those writes produced exist <b>only in the ingest JVM's memory</b>. The branch
 * commit log records branch <em>ids</em>, nothing more, so a drain in a later process could never
 * re-run {@code finalizeSource} (DuckLake register, {@code manifest.outputs}, the §11.3 output
 * registry) for them.
 *
 * <p>So park writes them here, as a sidecar beside the park tables, and the drain consumes and
 * deletes it. One file per parked batch: {@code <parkHome>/<batchId>__pending.json}.
 *
 * <p>{@code bounds} mirrors {@code com.gamma.consignment.EventTimeBounds} rather than referencing it:
 * that type lives in the engine, above this module. The mirror is three fields wide and pinned by
 * {@code ParkedCommitTest}; extend both together.
 *
 * @param batchId  the parked batch
 * @param outputs  every partition file the already-committed branches wrote
 * @param lineage  {@code LineageCollector}'s count matrix for {@code outputs}
 * @param bounds   output file → its event-time bounds (§3.1); empty when no {@code __event_time}
 */
public record ParkedCommit(String batchId, List<PartitionOutput> outputs, List<LineageRow> lineage,
                           Map<String, Bounds> bounds) {

    /** The wire form of an event-time range — see the class note on why this is a mirror. */
    public record Bounds(String min, String max, long spreadMs) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** {@code <parkHome>/<batchId>__pending.json} — the one place this sidecar's location is spelled. */
    public static Path pathFor(Path parkHome, String batchId) {
        return parkHome.resolve(batchId + "__pending.json");
    }

    public static void write(Path parkHome, ParkedCommit pending) throws IOException {
        Files.createDirectories(parkHome);
        Files.writeString(pathFor(parkHome, pending.batchId()), GSON.toJson(pending), StandardCharsets.UTF_8);
    }

    /** Read the sidecar for {@code batchId}. Throws if missing — a drain without it cannot finalise. */
    public static ParkedCommit read(Path parkHome, String batchId) throws IOException {
        Path file = pathFor(parkHome, batchId);
        if (!Files.exists(file))
            throw new IOException("Parked commit state not found for batch " + batchId + ": " + file);
        return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ParkedCommit.class);
    }

    public static void delete(Path parkHome, String batchId) throws IOException {
        Files.deleteIfExists(pathFor(parkHome, batchId));
    }
}
