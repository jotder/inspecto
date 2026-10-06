package com.gamma.inspector;

import com.gamma.acquire.DayManifest;
import com.gamma.acquire.GapTracker;
import com.gamma.acquire.RemoteFile;
import com.gamma.etl.Consignment;
import com.gamma.etl.LineageRow;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.signal.DeliveryAnomalySignal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-DAILY-INGEST-1 T8 (operator 2026-10-06): the per-day manifest written at commit and the windowed {seq} gap check.
 * The gap window ends at the last COMPLETE day, so a missing last day is reported even with an empty inbox, and the
 * manifest stands in for files that have left the inbox.
 */
class DeliveryCheckTest {

    private static final long DAY = 86_400_000L;
    /** 2026-09-08T06:00Z: the last complete day is 2026-09-07. */
    private static final long NOW = Instant.parse("2026-09-08T06:00:00Z").toEpochMilli();

    private final List<Event> seen = new CopyOnWriteArrayList<>();
    private final Consumer<Event> sub = seen::add;

    @AfterEach
    void off() {
        EventLog.current().removeSubscriber(sub);
        GapTracker.shared().reset();
    }

    private PipelineConfig config(Path dir, boolean template) throws Exception {
        Path p = PipelineConfigBatchTest.writePipeline(dir, "");
        if (template)
            Files.writeString(p, Files.readString(p) + "collector:\n  gap_detection:\n    file_template: \"XDR_{yyyyMMdd}_part{seq}*\"\n"
                    + "    seq_scope: PER_BUCKET\n", java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        EventLog.current().addSubscriber(sub);
        return PipelineConfig.load(p.toString());
    }

    private static Consignment.Member member(Path dir, String name, int src) throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, "x");
        return new Consignment.Member(f.toFile(), src, 100L + src, null);
    }

    private static RemoteFile listed(String name) {
        return new RemoteFile(name, name, 1L, Instant.EPOCH, null, null, null);
    }

    @Test
    void aCommitRecordsPartsWithRowsAndARenamedCorrectionIsSignalled(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir, true);
        Consignment.Member a = member(dir, "XDR_20260901_part0001.csv", 1), b = member(dir, "XDR_20260901_part0002.csv", 2);
        List<LineageRow> lineage = List.of(new LineageRow("b1", 1, "a", "o1", "p", 4), new LineageRow("b1", 1, "a", "o2", "p", 3),
                new LineageRow("b1", 2, "b", "o3", "p", 9));
        assertTrue(DeliveryCheck.afterCommit(cfg, "b1", List.of(a, b), lineage, NOW).isEmpty());
        DayManifest m = DayManifest.read(DeliveryCheck.manifestFile(cfg));
        assertEquals(7, m.days().get("20260901").get(0).rows(), "rows per part = its lineage rows summed");
        assertEquals(9, m.days().get("20260901").get(1).rows());

        Consignment.Member fix = member(dir, "XDR_20260901_part0001_v2.csv", 1);
        List<DayManifest.Anomaly> found = DeliveryCheck.afterCommit(cfg, "b2", List.of(fix),
                List.of(new LineageRow("b2", 1, "c", "o4", "p", 7)), NOW);
        assertEquals(DayManifest.Kind.RENAMED_CORRECTION, found.get(0).kind());
        assertTrue(seen.stream().anyMatch(e -> String.valueOf(e.message()).startsWith(DeliveryAnomalySignal.TYPE + " RENAMED_CORRECTION")),
                "the anomaly is signalled: " + seen);
    }

    @Test
    void aMissingLastDayAndAMissingPartAreReportedOverTheExplicitWindowFromInboxPlusManifest(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir, true);
        // days 1..6 committed (and gone from the inbox), day 4 with parts 1 and 3 only; day 7 (the last complete day) never came
        List<Consignment.Member> landed = new ArrayList<>();
        for (int d = 1; d <= 6; d++) {
            landed.add(member(dir, "XDR_2026090" + d + "_part0001.csv", d * 10));
            if (d == 4) landed.add(member(dir, "XDR_20260904_part0003.csv", 43));
        }
        DeliveryCheck.afterCommit(cfg, "b", landed, List.of(), NOW);
        List<String> gaps = DeliveryCheck.detectFileGaps(cfg, List.of(listed("XDR_20260908_part0001.csv")), NOW);
        assertEquals(List.of("20260907", "20260904#2"), gaps, "the empty last complete day and the part hole; today (08) is not judged");
        assertEquals(2, seen.stream().filter(e -> EventType.SEQUENCE_GAP.equals(e.type())).count());
        assertTrue(DeliveryCheck.detectFileGaps(cfg, List.of(), NOW).isEmpty(), "a persistent gap fires once");
    }

    /** Operator, 2026-10-06: a date-only template ({@code {seq}} optional) - one file per day, a missing day is a gap. */
    @Test
    void aDateOnlyTemplateReportsAMissingDay(@TempDir Path dir) throws Exception {
        Path p = PipelineConfigBatchTest.writePipeline(dir, "");
        Files.writeString(p, Files.readString(p) + "collector:\n  gap_detection:\n    file_template: \"XDR_{yyyyMMdd}.csv\"\n",
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        EventLog.current().addSubscriber(sub);
        PipelineConfig cfg = PipelineConfig.load(p.toString());
        List<Consignment.Member> landed = new ArrayList<>();
        for (int d = 1; d <= 7; d++) if (d != 4) landed.add(member(dir, "XDR_2026090" + d + ".csv", d));
        assertTrue(DeliveryCheck.afterCommit(cfg, "b", landed, List.of(), NOW).isEmpty());
        assertEquals(0, DayManifest.read(DeliveryCheck.manifestFile(cfg)).days().get("20260901").get(0).seq());
        assertEquals(List.of("20260904"), DeliveryCheck.detectFileGaps(cfg, List.of(), NOW), "day 4 never came");
        assertEquals(1, seen.stream().filter(e -> EventType.SEQUENCE_GAP.equals(e.type())).count());
    }

    @Test
    void withoutAFileTemplateNothingIsRecordedOrReported(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir, false);
        assertTrue(DeliveryCheck.afterCommit(cfg, "b", List.of(member(dir, "XDR_20260901_part0001.csv", 1)), List.of(), NOW).isEmpty());
        assertTrue(DeliveryCheck.detectFileGaps(cfg, List.of(), NOW).isEmpty());
        assertTrue(Files.notExists(DeliveryCheck.manifestFile(cfg)));
    }
}
