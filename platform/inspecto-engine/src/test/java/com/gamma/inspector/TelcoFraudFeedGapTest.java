package com.gamma.inspector;

import com.gamma.acquire.GapTracker;
import com.gamma.acquire.RemoteFile;
import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.etl.Consignment;
import com.gamma.etl.PipelineConfig;
import com.gamma.event.EventLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * 2026-10-14 demo rehearsal: a missing CDR day (0702 between 0701 and 0703) raised no sequence gap on the polled inbox.
 * Cause was config, not code: the telco-fraud Collectors declared only the legacy {@code gap_detection.sequence}, never
 * the {@code file_template} that arms the day manifest and the windowed gap check. Loads the SHIPPED pipelines.
 */
class TelcoFraudFeedGapTest {

    private static final long NOW = Instant.parse("2026-07-06T06:00:00Z").toEpochMilli();   // last complete day = 0705
    private final List<Event> seen = new CopyOnWriteArrayList<>();
    private final Consumer<Event> sub = seen::add;

    @BeforeEach
    void on() {
        GapTracker.shared().reset();
        EventLog.current().addSubscriber(sub);
    }

    @AfterEach
    void off() {
        EventLog.current().removeSubscriber(sub);
        GapTracker.shared().reset();
    }

    private static PipelineConfig shipped(Path dir, String feed) throws Exception {
        Path cfgDir = Path.of("..", "..", "spaces", "_templates", "telco-fraud", "config", feed).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(cfgDir), "template missing: " + cfgDir);
        String base = dir.toAbsolutePath().toString().replace('\\', '/');
        String toon = Files.readString(cfgDir.resolve(feed + "_pipeline.toon"))
                .replace("data/", base + "/data/")
                .replace("schema_file: " + feed + "_schema.toon",
                        "schema_file: \"" + cfgDir.resolve(feed + "_schema.toon").toString().replace('\\', '/') + "\"");
        Path p = Files.writeString(dir.resolve(feed + "_pipeline.toon"), toon);
        return PipelineConfig.load(p.toString());
    }

    private static Consignment.Member member(Path dir, String name, int src) throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, "x");
        return new Consignment.Member(f.toFile(), src, 100L + src, null);
    }

    private long gaps() {
        return seen.stream().filter(e -> EventType.SEQUENCE_GAP.equals(e.type())).count();
    }

    @Test
    void aMissingDayOnEachShippedFeedIsAGap(@TempDir Path dir) throws Exception {
        for (String[] f : new String[][]{{"cdr", "CDR"}, {"payments", "PAYMENTS"}, {"subscriber_events", "SUBSCRIBER_EVENTS"}}) {
            Path d = Files.createDirectory(dir.resolve(f[0]));
            PipelineConfig cfg = shipped(d, f[0]);
            List<Consignment.Member> landed = new ArrayList<>();
            int src = 1;
            for (String day : new String[]{"20260701", "20260703", "20260704", "20260705"})
                landed.add(member(d, f[1] + "_" + day + ".csv", src++));
            assertTrue(DeliveryCheck.afterCommit(cfg, "b", landed, List.of(), NOW).isEmpty(), f[0] + ": no manifest anomaly");
            assertEquals(List.of("20260702"), DeliveryCheck.detectFileGaps(cfg, List.of(), NOW), f[0] + ": 0702 never came");
        }
        assertEquals(3, gaps());
    }

    @Test
    void aContiguousRunAndTheFirstDayRaiseNothing(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = shipped(dir, "cdr");
        // first delivery day only (the sample): the window starts at the earliest day known, nothing before it is a gap
        DeliveryCheck.afterCommit(cfg, "b", List.of(member(dir, "CDR_20260701.csv", 1)), List.of(), NOW);
        assertTrue(DeliveryCheck.detectFileGaps(cfg, List.of(), Instant.parse("2026-07-02T06:00:00Z").toEpochMilli()).isEmpty());
        // contiguous 0701..0705; the in-progress day (0706) is not judged
        List<Consignment.Member> more = new ArrayList<>();
        for (int d = 2; d <= 5; d++) more.add(member(dir, "CDR_2026070" + d + ".csv", d));
        DeliveryCheck.afterCommit(cfg, "b2", more, List.of(), NOW);
        List<RemoteFile> today = List.of(new RemoteFile("CDR_20260706.csv", "CDR_20260706.csv", 1L, Instant.EPOCH, null, null, null));
        assertTrue(DeliveryCheck.detectFileGaps(cfg, today, NOW).isEmpty());
        assertEquals(0, gaps());
    }
}
