package com.gamma.inspector;

import com.gamma.acquire.DayManifest;
import com.gamma.acquire.FileSequenceGaps;
import com.gamma.acquire.GapTracker;
import com.gamma.acquire.RemoteFile;
import com.gamma.etl.Consignment;
import com.gamma.etl.LineageRow;
import com.gamma.etl.PipelineConfig;
import com.gamma.metrics.MetricRegistry;
import com.gamma.signal.DeliveryAnomalySignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LA-DAILY-INGEST-1 T8 (operator 2026-10-06): the per-day delivery manifest and the windowed file-gap check of a feed whose
 * Collector declares {@code gap_detection.file_template} + {@code seq_scope} (e.g. {@code XDR_{yyyyMMdd}_part{seq}*}).
 *
 * <ul>
 *   <li>{@link #afterCommit} - after a batch is durable, record each member's day, part number and row count in the
 *       manifest ({@link DayManifest}, kept beside the markers) and signal every re-delivery, renamed correction and
 *       changed part count as {@link DeliveryAnomalySignal}.</li>
 *   <li>{@link #detectFileGaps} - enumerate the EXPLICIT window [first known day, last complete day] with
 *       {@link FileSequenceGaps}, over the inbox listing PLUS the manifest (a processed file has left the inbox), so a
 *       missing last day and a missing part are both reported as {@code SEQUENCE_GAP}.</li>
 * </ul>
 * Both are best effort: a failure is logged and never fails a commit or a poll cycle.
 */
final class DeliveryCheck {

    private static final Logger log = LoggerFactory.getLogger(DeliveryCheck.class);

    /** The manifest's file name inside the pipeline's markers directory. */
    static final String FILE = "day-manifest.tsv";

    private DeliveryCheck() {}

    static Path manifestFile(PipelineConfig cfg) {
        String markers = cfg.dirs().markers();
        return markers == null || markers.isBlank() ? null : Paths.get(markers).toAbsolutePath().resolve(FILE);
    }

    static boolean applies(PipelineConfig cfg) {
        return cfg.collector().gapDetection().hasFileTemplate() && manifestFile(cfg) != null;
    }

    /** Records the committed members of {@code batch}; returns the anomalies found (also signalled). */
    static List<DayManifest.Anomaly> afterCommit(PipelineConfig cfg, String batchId, List<Consignment.Member> members,
                                                 List<LineageRow> lineage, long nowMillis) {
        if (!applies(cfg)) return List.of();
        String template = cfg.collector().gapDetection().fileTemplate();
        try {
            List<DayManifest.Part> parts = new ArrayList<>();
            for (Consignment.Member m : members) {
                String name = m.file().getName();
                if (!DayManifest.recordable(name)) continue;
                var key = FileSequenceGaps.match(template, name);
                if (key.isEmpty()) continue;                                   // not part of the feed's series
                long rows = 0;
                if (lineage != null) for (LineageRow r : lineage) if (r.srcId() == m.srcId()) rows += r.rowCount();
                parts.add(new DayManifest.Part(key.get().bucket(), key.get().seq(), name, rows, m.bytes(), nowMillis));
            }
            if (parts.isEmpty()) return List.of();
            List<DayManifest.Anomaly> found = DayManifest.record(manifestFile(cfg), parts);
            String pipeline = cfg.identity().pipelineName();
            for (DayManifest.Anomaly a : found) {
                log.warn("Delivery anomaly for {}: {} day {} - {}", pipeline, a.kind(), a.day(), a.detail());
                DeliveryAnomalySignal.emit(pipeline, batchId, a.kind().name(), a.day(),
                        com.gamma.etl.FileNames.safe(cfg, a.name()),
                        a.kind() == DayManifest.Kind.RENAMED_CORRECTION ? com.gamma.etl.FileNames.safe(cfg, a.previous()) : null,
                        a.detail());
            }
            if (!found.isEmpty())
                MetricRegistry.global().inc("inspecto_delivery_anomalies_total", "Re-delivered, renamed or changed parts of a daily feed",
                        Map.of("pipeline", pipeline), found.size());
            return found;
        } catch (Exception e) {
            log.warn("Day manifest not updated for {}: {}", cfg.identity().pipelineName(), e.getMessage());
            return List.of();
        }
    }

    /**
     * The windowed gap check. The window starts at the earliest day known (inbox or manifest) and ends at the last
     * COMPLETE bucket before {@code nowMillis} (UTC), so the bucket still being delivered is never a gap and a missing
     * last day is. Missing keys are a whole empty bucket ({@code 20260905}) or a part hole ({@code 20260905#3}).
     */
    static List<String> detectFileGaps(PipelineConfig cfg, List<RemoteFile> discovered, long nowMillis) {
        if (!applies(cfg)) return List.of();
        PipelineConfig.GapDetection gd = cfg.collector().gapDetection();
        try {
            Set<String> names = new LinkedHashSet<>();
            for (RemoteFile f : discovered) names.add(f.name());
            names.addAll(DayManifest.read(manifestFile(cfg)).names());
            LocalDateTime first = null;
            for (String n : names) {
                var k = FileSequenceGaps.match(gd.fileTemplate(), n);
                if (k.isPresent() && (first == null || k.get().start().isBefore(first))) first = k.get().start();
            }
            if (first == null) return List.of();                              // nothing known yet: no window to judge
            ChronoUnit unit = FileSequenceGaps.unit(gd.fileTemplate());
            LocalDateTime now = LocalDateTime.ofEpochSecond(nowMillis / 1000, 0, ZoneOffset.UTC);
            LocalDateTime current = unit == ChronoUnit.MONTHS ? now.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1)
                    : unit == ChronoUnit.YEARS ? now.truncatedTo(ChronoUnit.DAYS).withDayOfYear(1) : now.truncatedTo(unit);
            LocalDateTime lastComplete = current.minus(1, unit);
            if (lastComplete.isBefore(first)) return List.of();
            FileSequenceGaps.Report r = FileSequenceGaps.analyze(gd.fileTemplate(), names, first, lastComplete,
                    FileSequenceGaps.SeqScope.valueOf(gd.seqScope()));
            List<String> missing = new ArrayList<>(r.emptyBuckets());
            for (FileSequenceGaps.Bucket b : r.buckets())
                for (Long s : b.missing()) missing.add(b.key() + "#" + s);
            List<String> fresh = GapTracker.shared().newGaps(cfg.collector().id() + "#file_template", missing);
            if (fresh.isEmpty()) return List.of();
            MetricRegistry.global().inc("inspecto_sequence_gaps_total", "Missing files detected in a configured sequence",
                    Map.of("pipeline", cfg.identity().pipelineName()), fresh.size());
            for (String key : fresh) AcquisitionTelemetry.emitSequenceGap(cfg, key, gd.fileTemplate(), unit.name());
            log.warn("File gap(s) for {}: {} missing in '{}' - {}", cfg.identity().pipelineName(), fresh.size(),
                    gd.fileTemplate(), fresh);
            return fresh;
        } catch (Exception e) {
            log.warn("File gap check skipped for {}: {}", cfg.identity().pipelineName(), e.getMessage());
            return List.of();
        }
    }
}
