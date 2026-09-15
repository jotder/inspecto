package com.gamma.inspector;

import com.gamma.acquire.RemoteFile;
import com.gamma.etl.PipelineConfig;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.metrics.MetricRegistry;

import java.util.Map;

/**
 * Observability emitters for the source-acquisition path — the lifecycle {@link Event}s and
 * {@link MetricRegistry} counters/gauges that {@link CollectorProcessor} records as it discovers,
 * gates, fetches, dedups and finalizes inbox files. Each method is a pure leaf: it only emits an
 * event or bumps a metric (labelled by {@code cfg.identity().pipelineName()}), so it carries no
 * acquisition logic.
 *
 * <p>Lifecycle events keep {@code CollectorProcessor}'s class name as their {@code source} so the
 * event stream is byte-identical to before this code moved out of {@code CollectorProcessor}.
 */
final class AcquisitionTelemetry {

    /** Event {@code source} identity, preserved as the original emitter's FQN so events are unchanged. */
    private static final String SOURCE = CollectorProcessor.class.getName();

    private AcquisitionTelemetry() {}

    static void incDuplicatesSkipped(PipelineConfig cfg) {
        MetricRegistry.global().inc("inspecto_duplicates_skipped_total", "Files skipped as duplicates",
                Map.of("pipeline", cfg.identity().pipelineName()));
    }

    static void emitFileChanged(PipelineConfig cfg, RemoteFile f) {
        EventLog.current().emit(Event.builder(EventType.FILE_CHANGED)
                .source(SOURCE)
                .pipeline(cfg.identity().pipelineName())
                .message("File changed: " + f.relativePath())
                .attr("file", f.relativePath()));
    }

    /** Emit the {@link EventType#SEQUENCE_GAP} fact for one missing key in the configured series (Phase D). */
    static void emitSequenceGap(PipelineConfig cfg, String expectedKey, String sequence, String unit) {
        Event.Builder b = Event.builder(EventType.SEQUENCE_GAP)
                .source(SOURCE)
                .pipeline(cfg.identity().pipelineName())
                .message("Missing expected file in sequence: " + expectedKey)
                .attr("expected", expectedKey)
                .attr("sequence", sequence)
                .attr("unit", unit);
        // STALE-TILES-PRECISION-1 (2026-09-15): name the STORES this pipeline produces, so a reader can mark
        // the Datasets on those stores stale without guessing through the pipeline → produces[] hop. A gap
        // is per collector, so it legitimately reaches every store the pipeline writes; a pipeline with
        // one store now says which. Comma-joined, omitted when the graph declares none.
        String stores = producedStores(cfg);
        if (!stores.isEmpty()) b.attr("stores", stores);
        EventLog.current().emit(b);
    }

    /** The {@code store} of every sink the pipeline's graph declares, comma-joined — {@code ""} when none or unliftable. */
    static String producedStores(PipelineConfig cfg) {
        try {
            return String.join(",", com.gamma.pipeline.PipelineStores.produced(com.gamma.pipeline.PipelineLift.lift(cfg)));
        } catch (RuntimeException notLiftable) {
            return "";   // telemetry never fails an acquisition — a graph that will not lift simply names no store
        }
    }

    /** Refresh the per-pipeline gauge of files the readiness gate is currently holding back (Phase B). */
    static void setWaitingGauge(PipelineConfig cfg, int waiting) {
        MetricRegistry.global().setGauge("inspecto_files_waiting_stability",
                "Discovered files held back pending stability",
                Map.of("pipeline", cfg.identity().pipelineName()), waiting);
    }

    /** Emit the {@link EventType#FILE_STABLE} lifecycle fact for a file the gate just released (Phase B). */
    static void emitFileStable(PipelineConfig cfg, RemoteFile f) {
        EventLog.current().emit(Event.builder(EventType.FILE_STABLE)
                .source(SOURCE)
                .pipeline(cfg.identity().pipelineName())
                .message("File stable: " + f.relativePath())
                .attr("file", f.relativePath()));
    }

    /** Emit a remote-acquisition lifecycle fact (DISCOVERED/VALIDATED/FETCH_FAILED) carrying the relative path (Phase E). */
    static void emitFileEvent(PipelineConfig cfg, String type, String message, String file) {
        EventLog.current().emit(Event.builder(type)
                .source(SOURCE)
                .pipeline(cfg.identity().pipelineName())
                .message(message)
                .attr("file", file));
    }

    /** Emit {@link EventType#FILE_FETCHED} with the transferred byte count (Phase E). */
    static void emitFileFetched(PipelineConfig cfg, RemoteFile f, long bytes) {
        EventLog.current().emit(Event.builder(EventType.FILE_FETCHED)
                .source(SOURCE)
                .pipeline(cfg.identity().pipelineName())
                .message("File fetched: " + f.relativePath())
                .attr("file", f.relativePath())
                .attr("bytes", Long.toString(bytes)));
    }

    /** Record per-fetch transfer metrics: total bytes transferred + the fetch-duration histogram (Phase E). */
    static void recordFetch(PipelineConfig cfg, long bytes, double seconds) {
        Map<String, String> labels = Map.of("pipeline", cfg.identity().pipelineName());
        MetricRegistry.global().inc("inspecto_bytes_transferred_total",
                "Bytes retrieved from source connectors", labels, bytes);
        MetricRegistry.global().observe("inspecto_fetch_seconds",
                "Time to fetch one file from a source connector (seconds)", labels, seconds);
    }

    /** Set the gauge of currently-open source-connector sessions for this pipeline (Phase E). */
    static void setActiveConnections(PipelineConfig cfg, int n) {
        MetricRegistry.global().setGauge("inspecto_active_connections",
                "Open source-connector sessions", Map.of("pipeline", cfg.identity().pipelineName()), n);
    }

    /** Count a file listed by a connector's discovery this cycle (Phase F observability). */
    static void incDiscovered(PipelineConfig cfg) {
        MetricRegistry.global().inc("inspecto_files_discovered_total", "Files listed by source discovery",
                Map.of("pipeline", cfg.identity().pipelineName()));
    }

    /** Count a file successfully fetched + integrity-validated this cycle (Phase F observability). */
    static void incDownloaded(PipelineConfig cfg) {
        MetricRegistry.global().inc("inspecto_files_downloaded_total", "Files fetched and validated from a source",
                Map.of("pipeline", cfg.identity().pipelineName()));
    }

    /** Count a fetch or integrity-validation failure this cycle (Phase F observability). */
    static void incDownloadsFailed(PipelineConfig cfg) {
        MetricRegistry.global().inc("inspecto_downloads_failed_total", "Fetch/integrity failures",
                Map.of("pipeline", cfg.identity().pipelineName()));
    }

    /** Count a source-side post-action that failed at runtime (the file is still ingested) (Phase F). */
    static void incPostActionsFailed(PipelineConfig cfg) {
        MetricRegistry.global().inc("inspecto_post_actions_failed_total", "Source post-actions that failed",
                Map.of("pipeline", cfg.identity().pipelineName()));
    }

    /** Emit {@link EventType#FILE_ARCHIVED} when a source-side post-action finalized a processed file (Phase F). */
    static void emitFileArchived(PipelineConfig cfg, RemoteFile f, String action) {
        EventLog.current().emit(Event.builder(EventType.FILE_ARCHIVED)
                .source(SOURCE)
                .pipeline(cfg.identity().pipelineName())
                .message("Source file finalized (" + action + "): " + f.relativePath())
                .attr("file", f.relativePath())
                .attr("action", action));
    }

    /** Emit {@link EventType#SOURCE_CIRCUIT_OPEN} the moment a source's breaker trips OPEN (Phase F). */
    static void emitCircuitOpen(PipelineConfig cfg, String reason) {
        EventLog.current().emit(Event.builder(EventType.SOURCE_CIRCUIT_OPEN)
                .source(SOURCE)
                .pipeline(cfg.identity().pipelineName())
                .message("Source circuit breaker tripped OPEN: " + reason)
                .attr("source", cfg.collector().id()));
    }
}
