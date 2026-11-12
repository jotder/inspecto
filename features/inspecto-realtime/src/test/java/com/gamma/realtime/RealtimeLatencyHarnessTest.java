package com.gamma.realtime;

import com.gamma.alert.AlertRule;
import com.gamma.alert.AlertService;
import com.gamma.alert.AlertStore;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PA-9d: the latency harness of D-PA39 - "p95 ≤ 5 s from broker to Alert, ≤ 10 s to Incident" - on the PRODUCTION
 * path: a {@code trigger: stream} Pipeline's lane ({@code max_wait: 1s}) drains the {@code faketest} connector, the real
 * ingest commits Parquet as one Consignment per drain ({@code batch.max_files: 64}), the bus hands the commit to this
 * module's {@link RealtimeSession}, the engine's window buffer evaluates a Wangiri rule (10-minute sliding Window, 30
 * distinct B-numbers with duration under 3 s), and the CRITICAL rule raises its Alert and the key's Incident (the
 * {@code inspecto-ops} Object Engine) in the same evaluation.
 *
 * <p>A synthetic producer writes one file a second (one partition drained at {@code max_wait: 1s}) at {@code rate} random
 * events/s, each stamped with its broker time as its event time ({@code EVENT_TS}), and plants a 40-number burst on a
 * fresh key every {@value #BURST_EVERY_S} s. Latency per planted burst = the session's raise time minus the broker time of
 * the newest event in the Window that fired. The Incident is opened inside that same raise, so event → Incident is at
 * most that figure.
 *
 * <p>In CI it runs a CI-sized load ({@code -Drealtime.harness.rate}, default 1,000 events/s, for
 * {@code -Drealtime.harness.seconds}, default 40) and asserts p95 ≤ 5 s with no burst missed. The full-rate run
 * ({@code -Drealtime.harness.rate=5000 -Drealtime.harness.seconds=60}) is recorded in the plan's "PA-9d as built".
 */
class RealtimeLatencyHarnessTest {

    private static final int RATE = Integer.getInteger("realtime.harness.rate", 1000);
    private static final int SECONDS = Integer.getInteger("realtime.harness.seconds", 40);
    private static final int BURST_EVERY_S = 2;
    /** No burst is planted in the first seconds: the JVM, DuckDB and the lane warm up as a booted server already has. */
    private static final int WARM_UP_S = 6;
    private static final int KEYS = 100_000;
    private static final long P95_BUDGET_MS = 5_000;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);

    /** Where the producer's events go, and the pipeline's {@code collector:} block that reads them. */
    interface Feed extends AutoCloseable {
        String collector();

        /** Publish one second's lines. */
        void send(List<String> lines) throws Exception;

        String label();

        /** Bind what the Space needs before its lane opens (a Connection profile). */
        default void bind(CollectorService svc) {}

        @Override
        default void close() throws Exception {}
    }

    @Test
    void plantedBurstsRaiseTheirAlertAndIncidentWithinTheStatedLatency(@TempDir Path tmp) throws Exception {
        Path remote = Files.createDirectories(tmp.resolve("remote"));
        Class<?> fake = Class.forName("com.gamma.service.FakeRemoteConnectorFactory");
        Method reset = fake.getDeclaredMethod("reset", Path.class);
        reset.setAccessible(true);
        reset.invoke(null, remote);
        int[] seq = {0};
        run(tmp, new Feed() {
            public String collector() { return "  connector: faketest" + NL; }
            public void send(List<String> lines) throws Exception {
                Path part = remote.resolve("f" + seq[0] + ".tmp");
                Files.writeString(part, String.join(NL, lines) + NL);
                Files.move(part, remote.resolve("f" + (seq[0]++) + ".csv"));
            }
            public String label() { return "faketest"; }
        });
    }

    /**
     * The one real-broker run (D-PA39, operator Q6): the same harness through the real {@code KafkaConnector} against a
     * broker at {@code -Dkafka.it.bootstrap} (default {@code localhost:9092}; e.g. {@code docker run -p 9092:9092
     * apache/kafka:3.8.0}). Each event is one raw CSV record produced at its event time, so the broker's CreateTime is
     * the event time. SKIPS (never passes vacuously) when no broker answers; the recorded run is in the plan's
     * "PA-9d as built".
     */
    @Test
    void theSameThroughARealBroker(@TempDir Path tmp) throws Exception {
        String bootstrap = System.getProperty("kafka.it.bootstrap", "localhost:9092");
        String[] hp = bootstrap.split(",")[0].trim().split(":");
        boolean answers;
        try (java.net.Socket so = new java.net.Socket()) {
            so.connect(new java.net.InetSocketAddress(hp[0], Integer.parseInt(hp[1])), 500);
            answers = true;
        } catch (Exception e) {
            answers = false;
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(answers, "SKIPPED: no Kafka broker at " + bootstrap
                + " (set -Dkafka.it.bootstrap=host:port, or start one: docker run -p 9092:9092 apache/kafka:3.8.0)");
        String topic = "inspecto-rt-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        java.util.Properties pp = new java.util.Properties();
        pp.put("bootstrap.servers", bootstrap);
        pp.put("linger.ms", "0");
        pp.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        pp.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        org.apache.kafka.clients.producer.KafkaProducer<String, String> producer =
                new org.apache.kafka.clients.producer.KafkaProducer<>(pp);
        // create the topic before the lane opens; an empty value is a line the ingest skips
        producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic, 0, null, "")).get();
        com.gamma.acquire.ConnectionProfile profile = new com.gamma.acquire.ConnectionProfile("KAFKA_RT", "kafka", null, 0,
                null, null, null, null, Map.of("topic", topic, "bootstrap_servers", bootstrap, "payload", "raw",
                "export_ext", "csv", "start", "earliest"), null);
        run(tmp, new Feed() {
                public String collector() { return "  connector: kafka" + NL + "  connection: KAFKA_RT" + NL; }
                public void send(List<String> lines) {
                    for (String l : lines) producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic, 0, null, l));
                    producer.flush();
                }
                public String label() { return "kafka " + bootstrap + " topic " + topic; }
                public void bind(CollectorService svc) { svc.registerConnection(profile); }
                public void close() { producer.close(); }
            });
    }

    private static final String NL = "\n";

    private void run(Path tmp, Feed feed) throws Exception {
        Path base = tmp.resolve("space");
        Path config = Files.createDirectories(base.resolve("config"));
        new ComponentStore(config.resolve("registry")).write("dataset", "voice_cdr",
                new HashMap<>(Map.of("physicalRef", "lane_etl")));
        Path cfg = pipeline(config.resolve("pipelines"), base, feed.collector());

        Map<String, Long> planted = new ConcurrentHashMap<>();   // a_number=W.. -> broker time of its newest event
        Map<String, Long> raised = new ConcurrentHashMap<>();    // a_number=W.. -> raise time
        AtomicBoolean writing = new AtomicBoolean(true);
        try (feed; CollectorService svc = service(cfg, base)) {
            RealtimeSession session = (RealtimeSession) field(svc, "realtime");
            assertNotNull(session, "the inspecto-realtime module is on the classpath: the Space opened its session");
            session.onRaised(r -> {
                if (!r.replay()) raised.putIfAbsent(r.keyId(), r.raisedMs());
            });
            List<long[]> commits = new java.util.concurrent.CopyOnWriteArrayList<>();   // {publish ms, ingest ms}
            svc.eventBus().subscribe(e -> {
                if ("SUCCESS".equals(e.status())) commits.add(new long[]{System.currentTimeMillis(), e.durationMs()});
            });
            feed.bind(svc);
            Object scheduler = field(svc, "pipelineScheduler");
            Method reconcile = scheduler.getClass().getDeclaredMethod("reconcileStreamLanes");
            reconcile.setAccessible(true);
            // the lane copies the caller's MDC: open it under the Space, as the service's own scheduler does
            org.slf4j.MDC.put(com.gamma.event.EventLog.SPACE_MDC_KEY, (String) field(svc, "spaceId"));
            try {
                reconcile.invoke(scheduler);
            } finally {
                org.slf4j.MDC.remove(com.gamma.event.EventLog.SPACE_MDC_KEY);
            }

            Thread writer = Thread.ofVirtual().start(() -> write(feed, planted, writing));
            long until = System.currentTimeMillis() + SECONDS * 1000L + 20_000;
            while (System.currentTimeMillis() < until && (writing.get() || !raised.keySet().containsAll(planted.keySet())))
                Thread.sleep(50);
            writer.join();

            List<Long> latencies = new ArrayList<>();
            int missed = 0;
            for (Map.Entry<String, Long> p : planted.entrySet()) {
                Long at = raised.get(p.getKey());
                if (at == null) missed++;
                else latencies.add(at - p.getValue());
            }
            latencies.sort(null);
            AlertService alerts = svc.alertService().orElseThrow();
            List<AlertStore.Row> open = alerts.openAlerts();
            System.out.printf("PA9D harness " + feed.label() + " rate=%d/s seconds=%d bursts=%d missed=%d | event->Alert(+Incident) p50=%s p95=%s"
                            + " p99=%s max=%s | commits=%d ingest p50=%sms | rule Alerts open=%d with Incident=%s%n",
                    RATE, SECONDS, planted.size(), missed, q(latencies, 0.50), q(latencies, 0.95), q(latencies, 0.99),
                    latencies.isEmpty() ? "-" : latencies.getLast(), commits.size(),
                    commits.stream().mapToLong(c -> c[1]).sorted().skip(commits.size() / 2).findFirst().orElse(-1), open.size(),
                    open.stream().anyMatch(r -> r.incidentId() != null));
            assertTrue(planted.size() >= (SECONDS - WARM_UP_S) / BURST_EVERY_S - 1, "bursts planted: " + planted.size());
            assertEquals(0, missed, "every planted burst raised its Alert: " + planted.keySet() + " vs " + raised.keySet());
            long p95 = latencies.get(Math.min(latencies.size() - 1, (int) Math.ceil(latencies.size() * 0.95) - 1));
            assertTrue(p95 <= P95_BUDGET_MS, "D-PA39: p95 event -> Alert " + p95 + " ms > " + P95_BUDGET_MS + " ms " + latencies);
            assertTrue(open.stream().anyMatch(r -> r.incidentId() != null),
                    "the CRITICAL rule escalated: its Alert links the keys' Incidents " + open);
        }
    }

    private static String q(List<Long> sorted, double p) {
        return sorted.isEmpty() ? "-"
                : sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * p) - 1)) + "ms";
    }

    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    /** A Space-rooted service (every store under {@code base}) over {@code cfg}, armed with the Wangiri rule. */
    private static CollectorService service(Path cfg, Path base) throws Exception {
        Class<?> spaceRoot = Class.forName("com.gamma.service.SpaceRoot");
        Method under = spaceRoot.getDeclaredMethod("under", Path.class);
        under.setAccessible(true);
        Object root = under.invoke(null, base);
        Constructor<CollectorService> c = CollectorService.class.getDeclaredConstructor(List.class, List.class, List.class,
                List.class, List.class, long.class, int.class, com.gamma.etl.StatusStore.class, spaceRoot);
        c.setAccessible(true);
        return c.newInstance(List.of(cfg), List.of(), List.of(), List.of(), List.of(wangiri()), 3600L, 1, null, root);
    }

    private static AlertRule wangiri() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("name", "wangiri_burst");
        raw.put("dataset", "voice_cdr");
        raw.put("measure", "countDistinct(B_NUM)");
        raw.put("by", List.of("A_NUM"));
        raw.put("comparator", "gte");
        raw.put("threshold", 30);
        raw.put("severity", "CRITICAL");
        raw.put("when", Map.of("kind", "group", "op", "AND", "items",
                List.of(Map.of("kind", "condition", "field", "DUR", "operator", "<", "value", "3"))));
        raw.put("window", Map.of("type", "sliding", "size", "10m", "slide", "1m", "time", "EVENT_TS", "lateness", "1m"));
        return AlertRule.fromMap(raw);
    }

    /** One file a second: {@code RATE} random calls, and every few seconds a burst of 40 short calls from a fresh key. */
    private static void write(Feed feed, Map<String, Long> planted, AtomicBoolean writing) {
        Random rnd = new Random(42);
        long end = System.currentTimeMillis() + SECONDS * 1000L;
        int seq = 0;
        try {
            while (System.currentTimeMillis() < end) {
                long tick = System.currentTimeMillis();
                List<String> lines = new ArrayList<>(RATE + 40);
                for (int i = 0; i < RATE; i++) {
                    long now = System.currentTimeMillis();
                    lines.add("A" + rnd.nextInt(KEYS) + ",B" + rnd.nextInt(1_000_000) + "," + (5 + rnd.nextInt(300)) + ","
                            + TS.format(Instant.ofEpochMilli(now)));
                }
                if (seq >= WARM_UP_S && seq % BURST_EVERY_S == 0) {
                    String key = "W" + seq;
                    long last = 0;
                    for (int i = 0; i < 40; i++) {
                        last = System.currentTimeMillis();
                        lines.add(key + ",V" + i + ",1," + TS.format(Instant.ofEpochMilli(last)));
                    }
                    planted.put("A_NUM=" + key, last);
                }
                feed.send(lines);
                seq++;
                long sleep = 1000 - (System.currentTimeMillis() - tick);
                if (sleep > 0) Thread.sleep(sleep);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            writing.set(false);
        }
    }

    private static Path pipeline(Path dir, Path base, String collector) throws Exception {
        Files.createDirectories(dir);
        Path schema = dir.resolve("cdr_schema.toon");
        Files.writeString(schema, """
            raw:
              name: cdr
              format: CSV
              fields[4]{name,selector,type}:
                A_NUM,"0",VARCHAR
                B_NUM,"1",VARCHAR
                DUR,"2",DOUBLE
                EVENT_TS,"3",TIMESTAMP
            mapping:
              canonicalName: cdr
              rawName: cdr
              rules[4]{targetColumn,sourceExpression,transformType}:
                A_NUM,A_NUM,DIRECT
                B_NUM,B_NUM,DIRECT
                DUR,DUR,DIRECT
                EVENT_TS,EVENT_TS,DIRECT
            """);
        String d = base.toString().replace('\\', '/');
        String toon = """
            name: LANE_ETL
            active: true
            version: 1
            dirs:
              poll: %1$s/inbox
              database: %1$s/db
              backup: %1$s/backup
              temp: %1$s/temp
              errors: %1$s/errors
              quarantine: %1$s/quarantine
              markers: %1$s/markers
              status_dir: %1$s/status
              log_dir: %1$s/logs
            collector:
            %3$strigger:
              type: stream
              records: 1000
              max_wait: 1s
            output:
              format: PARQUET
            processing:
              threads: 2
              batch:
                max_files: 64
              file_pattern: "glob:**/*.csv"
              duplicate_check:
                enabled: true
                marker_extension: .processed
              schema_file: "%2$s"
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                skip_junk_lines: 0
                skip_tail_lines: 0
                timestamp_formats[1]: "%%Y-%%m-%%d %%H:%%M:%%S.%%f"
            """.formatted(d, schema.toString().replace("\\", "/"), collector);
        Path p = dir.resolve("lane_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }
}
