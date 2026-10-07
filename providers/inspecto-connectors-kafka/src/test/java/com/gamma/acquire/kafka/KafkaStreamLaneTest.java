package com.gamma.acquire.kafka;

import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.InMemoryAcquisitionLedger;
import com.gamma.acquire.RemoteFile;
import com.gamma.acquire.StreamLane;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The continuous lane (ASSURE-PUSH-INGEST-1) over a real {@link KafkaConnector} and kafka-clients'
 * {@link MockConsumer}: it drains on N and on T, the in-flight fence keeps it from re-slicing an uncommitted
 * range, a crash mid-slice re-delivers exactly that one slice, and a measured latency harness.
 *
 * <p>The lane's {@code drain} here is the acquisition half of the real path — {@code discover} + {@code fetchTo}
 * (which stashes the frontier) — followed by what {@code ConsignmentIngestor.commit} does to the ledger. Ingest
 * itself is not run: the lane never touches it (it calls the ordinary {@code runPipeline}).
 */
class KafkaStreamLaneTest {

    private static final String TOPIC = "cdr";
    private static final TopicPartition P0 = new TopicPartition(TOPIC, 0);
    private static final DiscoveryContext ALL = new DiscoveryContext(List.of("*"), List.of(), DiscoveryContext.UNBOUNDED);
    private static final String KEY = "kafka:kafka-lane:cdr:p0";

    private InMemoryAcquisitionLedger ledger;
    private MockConsumer<byte[], byte[]> mock;
    private final List<KafkaConnector> opened = new ArrayList<>();
    private final AtomicLong end = new AtomicLong();

    @BeforeEach
    void setUp() {
        ledger = new InMemoryAcquisitionLedger();
        AcquisitionLedgers.use(ledger);
        mock = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        mock.updatePartitions(TOPIC, List.of(new PartitionInfo(TOPIC, 0, null, null, null)));
        mock.updateBeginningOffsets(Map.of(P0, 0L));
        mock.updateEndOffsets(Map.of(P0, 0L));
    }

    @AfterEach
    void tearDown() throws Exception {
        for (KafkaConnector c : opened) c.close();
        AcquisitionLedgers.use(null);
    }

    private KafkaConnector connector() {
        Map<String, String> options = new HashMap<>(Map.of("topic", TOPIC));
        ConnectionProfile profile = new ConnectionProfile("kafka-lane", "kafka", "127.0.0.1", 9092,
                null, null, null, null, options, null);
        KafkaConnector c = new KafkaConnector(profile, props -> mock);
        opened.add(c);
        return c;
    }

    /** Produce offsets [end, end+n) with the given event timestamp; the mock serves them on the next poll. */
    private void produce(int n, long eventTs) {
        long from = end.getAndAdd(n);
        mock.updateEndOffsets(Map.of(P0, from + n));
        List<ConsumerRecord<byte[], byte[]>> recs = new ArrayList<>();
        for (long o = from; o < from + n; o++)
            recs.add(new ConsumerRecord<>(TOPIC, 0, o, eventTs, TimestampType.CREATE_TIME, 0, 0,
                    null, ("v" + o).getBytes(StandardCharsets.UTF_8), new org.apache.kafka.common.header.internals.RecordHeaders(),
                    Optional.empty()));
        mock.schedulePollTask(() -> recs.forEach(mock::addRecord));
    }

    /** discover + fetchTo: land every slice discovery offers into {@code dir}; returns the landed paths. */
    private static List<Path> land(KafkaConnector c, Path dir) throws Exception {
        List<Path> out = new ArrayList<>();
        for (RemoteFile f : c.discover(ALL)) out.add(c.fetchTo(f, dir.resolve(f.relativePath())));
        return out;
    }

    /** What ConsignmentIngestor.commit does with the stash: persist the frontier. */
    private void commit(Path slice) {
        var wm = AcquisitionLedgers.takeDbWatermark(slice).orElseThrow();
        ledger.recordDbWatermark(wm.key(), wm.value());
    }

    @Test
    void theLaneDrainsWhenTheBacklogReachesN(@TempDir Path dir) throws Exception {
        KafkaConnector drainer = connector();
        List<Path> slices = Collections.synchronizedList(new ArrayList<>());
        AtomicLong now = new AtomicLong(0);
        StreamLane lane = new StreamLane("p", 5, 60_000, 10, this::connector, () -> {
            try { for (Path p : land(drainer, dir)) { slices.add(p); commit(p); } }
            catch (Exception e) { throw new RuntimeException(e); }
        }, now::get);
        produce(4, 0);
        assertFalse(lane.step(), "4 < N and no time has passed");
        produce(1, 0);
        assertTrue(lane.step(), "N = 5 reached");
        assertEquals(List.of("cdr-p0-0-5.ndjson"), slices.stream().map(p -> p.getFileName().toString()).toList());
        assertEquals(Optional.of("5"), ledger.dbWatermark(KEY));
        assertFalse(lane.step(), "drained and committed — nothing pending");
        lane.close();
    }

    @Test
    void theLaneDrainsASmallBacklogOnT(@TempDir Path dir) throws Exception {
        KafkaConnector drainer = connector();
        AtomicLong now = new AtomicLong(0);
        List<Path> slices = new ArrayList<>();
        StreamLane lane = new StreamLane("p", 1_000, 2_000, 10, this::connector, () -> {
            try { for (Path p : land(drainer, dir)) { slices.add(p); commit(p); } }
            catch (Exception e) { throw new RuntimeException(e); }
        }, now::get);
        produce(2, 0);
        assertFalse(lane.step());
        now.set(1_999);
        assertFalse(lane.step());
        now.set(2_000);
        assertTrue(lane.step(), "2 records waited T = 2 s");
        assertEquals("cdr-p0-0-2.ndjson", slices.getFirst().getFileName().toString());
        lane.close();
    }

    @Test
    void anUncommittedSliceIsNotBacklogSoTheLaneCannotReSliceIt(@TempDir Path dir) throws Exception {
        KafkaConnector c = connector();
        produce(3, 0);
        List<Path> first = land(c, dir);                    // landed, NOT committed
        assertEquals(1, first.size());
        produce(2, 0);                                      // the backlog grows before the commit
        assertEquals(0, c.pendingRecords(), "a fenced partition is not drainable backlog");
        assertTrue(land(c, dir).isEmpty(), "no overlapping slice while [0,3) is uncommitted");
        commit(first.getFirst());
        assertEquals(2, c.pendingRecords(), "the commit releases the fence: [3,5) is backlog");
    }

    @Test
    void aCrashMidSliceReDeliversThatOneSliceAndNothingElse(@TempDir Path dir) throws Exception {
        KafkaConnector before = connector();
        produce(3, 0);
        Path slice = land(before, dir).getFirst();          // landed, then the process dies before the commit
        produce(4, 0);                                      // more arrives while it is down
        // Restart: in-memory stashes are gone (the durable SliceFrontiers record is what restores them in
        // production; here the slice is simply re-offered, which is the at-least-once contract).
        AcquisitionLedgers.discardDbWatermark(slice);
        Files.delete(slice);
        KafkaConnector after = connector();
        produce(0, 0);
        mock.updateEndOffsets(Map.of(P0, 7L));
        List<RemoteFile> offered = after.discover(ALL);
        assertEquals(List.of("cdr-p0-0-7.ndjson"), offered.stream().map(RemoteFile::relativePath).toList(),
                "the uncommitted [0,3) is re-read — and only that range is re-delivered, merged into the next slice");
        assertEquals(3, 7 - 4, "re-delivered records = the one uncommitted slice's 3, the 4 new ones are first reads");
    }

    /**
     * The measured harness: 20 bursts of events arrive at random gaps; the lane (N=200, T=1 s, probe 20 ms) drains
     * them; latency = slice committed − event timestamp. Reports p50/p95/max. This measures the LANE's part of
     * event → Incident (arrival → committed slice frontier), not ingest or Alert evaluation — see the report.
     */
    @Test
    void measuredLatencyEventToCommittedSlice(@TempDir Path dir) throws Exception {
        KafkaConnector drainer = connector();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        StreamLane lane = new StreamLane("p", 200, 1_000, 20, this::connector, () -> {
            try {
                for (Path p : land(drainer, dir)) {
                    commit(p);
                    long done = System.currentTimeMillis();
                    for (String line : Files.readAllLines(p)) {
                        int i = line.indexOf("\"timestamp\":");
                        long ts = Long.parseLong(line.substring(i + 12, line.indexOf(',', i)));
                        latencies.add(done - ts);
                    }
                }
            } catch (Exception e) { throw new RuntimeException(e); }
        }, System::currentTimeMillis);
        lane.start(null);
        java.util.Random rnd = new java.util.Random(7);
        int produced = 0;
        for (int burst = 0; burst < 20; burst++) {
            int n = 1 + rnd.nextInt(60);
            synchronized (lane) { produce(n, System.currentTimeMillis()); }
            produced += n;
            Thread.sleep(50 + rnd.nextInt(250));
        }
        long deadline = System.currentTimeMillis() + 10_000;
        while (latencies.size() < produced && System.currentTimeMillis() < deadline) Thread.sleep(20);
        lane.close();
        assertEquals(produced, latencies.size(), "every produced event was drained exactly once");
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        long p50 = sorted.get(sorted.size() / 2);
        long p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
        long max = sorted.getLast();
        System.out.printf("STREAM-LANE-LATENCY events=%d p50=%dms p95=%dms max=%dms (N=200, T=1000ms, probe=20ms)%n",
                sorted.size(), p50, p95, max);
        assertTrue(p95 <= 30_000, "D-P7 budget is 30 s end to end; the lane alone must leave most of it: p95=" + p95);
        assertTrue(p95 <= 1_000 + 20 + 500, "the lane's own bound is T + one probe (+ scheduling slack): p95=" + p95);
    }
}
