package com.gamma.screening;

import com.gamma.etl.ConsignmentEventBus;
import com.gamma.job.JobConfig;
import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.gamma.pipeline.ComponentStore;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The {@code screening.run} Job over a real Parquet Dataset (SCR-D7, SCR-D8, SCR-D11): it raises one open hit per
 * new match, never a second hit for the same (list, entry, subject) whatever its state, and refuses a subset.
 */
class ScreeningJobTest {

    @AfterEach
    void clear() {
        System.clearProperty("assist.write.root");
        System.clearProperty("data.dir");
    }

    private static void plant(Path cfg, Path data) throws Exception {
        ScreeningLists.unmasked(cfg);
        ScreeningLists.create(cfg, "sanctions", "block", "subscriber", "default");
        ScreeningLists.add(cfg, "sanctions", "default", List.of("Vladimir Putin", "Osama bin Laden"));
        ScreeningLists.create(cfg, "deny", "block", "msisdn", "e164");
        ScreeningLists.add(cfg, "deny", "e164", List.of("+447700900123"));
        new ComponentStore(cfg.resolve("registry")).write("dataset", "customers", Map.of("physicalRef", "customers"));
        Path dir = data.resolve("customers");
        Files.createDirectories(dir);
        String file = dir.resolve("data.parquet").toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES "
                    + "('c1', 'Putin, Vladimir', NULL), "
                    + "('c2', '0sama bin Laden', NULL), "
                    + "('c3', 'Jane Doe', '+44 7700 900123'), "
                    + "('c4', 'Alice Johnson', '+447700900999')"
                    + ") AS t(customer_id, full_name, msisdn)) TO '" + file + "' (FORMAT PARQUET)");
        }
    }

    private static JobRun await(Supplier<JobRun> s, String previousRunId) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        JobRun r = null;
        while (System.nanoTime() < deadline) {
            r = s.get();
            if (r != null && !"RUNNING".equals(r.status()) && !r.runId().equals(previousRunId)) return r;
            Thread.sleep(50);
        }
        fail("expected a finished job run within 20s, last " + r);
        return null;
    }

    private static JobConfig job(Map<String, String> extra) {
        Map<String, String> p = new HashMap<>(Map.of("dataset", "customers", "keyField", "customer_id",
                "nameField", "full_name", "idField", "msisdn", "lists", "sanctions, deny"));
        p.putAll(extra);
        return new JobConfig("nightly", "screening.run", null, null, true, false, p, null, null);
    }

    private static Map<String, Map<String, Object>> hitsBySubject(Path cfg) throws Exception {
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (Map<String, Object> h : ScreeningHits.list(cfg)) out.put(String.valueOf(h.get("subjectKey")), h);
        return out;
    }

    @Test
    void raisesOneHitPerNewMatchAndNeverRaisesTheSameHitTwice(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        plant(cfg, data);
        System.setProperty("assist.write.root", cfg.toString());
        System.setProperty("data.dir", data.toString());
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job(Map.of())), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString(), null, null, data.toString())) {
            js.start();
            assertTrue(js.triggerRun("nightly", null).isPresent());
            JobRun first = await(() -> js.lastRunOf("nightly").orElse(null), "");
            assertEquals("SUCCESS", first.status(), "run failed: " + first.message());
            assertTrue(first.message().contains("3 new hit(s)"), first.message());

            Map<String, Map<String, Object>> hits = hitsBySubject(cfg);
            assertEquals(3, hits.size(), hits.keySet().toString());
            assertEquals("vladimir putin", hits.get("c1").get("entry"));
            assertEquals("name", hits.get("c1").get("method"));
            assertEquals("Putin, Vladimir", hits.get("c1").get("subjectName"));
            assertEquals(1.0, ((Number) hits.get("c2").get("score")).doubleValue(), "a look-alike spelling");
            assertEquals("identifier", hits.get("c3").get("method"));
            assertEquals("+447700900123", hits.get("c3").get("entry"));
            assertFalse(hits.containsKey("c4"), "no match, no hit");
            for (Map<String, Object> h : hits.values()) {
                assertEquals("open", h.get("state"));
                assertFalse(ScreeningHits.invalid(h), h.toString());
                assertEquals("nightly", ((Map<?, ?>) h.get("source")).get("job"));
                assertEquals(first.runId(), ((Map<?, ?>) h.get("source")).get("runId"));
            }

            // A reviewer dismisses c1 as a false positive; the next run raises nothing new.
            Map<String, Object> c1 = ScreeningHits.read(cfg, String.valueOf(hits.get("c1").get("id")));
            ScreeningHits.transition(c1, "dismissed", "analyst-1", "different person");
            ScreeningHits.save(cfg, c1);
            assertTrue(js.triggerRun("nightly", null).isPresent());
            JobRun second = await(() -> js.lastRunOf("nightly").orElse(null), first.runId());
            assertEquals("SUCCESS", second.status(), "run failed: " + second.message());
            assertTrue(second.message().contains("0 new hit(s)"), second.message());
            assertEquals(3, ScreeningHits.list(cfg).size());
            assertEquals("dismissed", ScreeningHits.read(cfg, String.valueOf(c1.get("id"))).get("state"),
                    "a dismissed hit stays dismissed");
        }
    }

    @Test
    void refusesToScreenASubsetAndRefusesAnUnknownList(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        plant(cfg, data);
        System.setProperty("assist.write.root", cfg.toString());
        System.setProperty("data.dir", data.toString());
        JobConfig tooMany = new JobConfig("capped", "screening.run", null, null, true, false,
                Map.of("dataset", "customers", "keyField", "customer_id", "nameField", "full_name",
                        "lists", "sanctions", "maxRows", "3"), null, null);
        JobConfig unknown = new JobConfig("unknown", "screening.run", null, null, true, false,
                Map.of("dataset", "customers", "keyField", "customer_id", "nameField", "full_name",
                        "lists", "sanctions,nope"), null, null);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(tooMany, unknown), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString(), null, null, data.toString())) {
            js.start();
            assertTrue(js.triggerRun("capped", null).isPresent());
            JobRun capped = await(() -> js.lastRunOf("capped").orElse(null), "");
            assertEquals("FAILED", capped.status(), capped.message());
            assertTrue(capped.message().contains("refusing to screen a subset"), capped.message());

            assertTrue(js.triggerRun("unknown", null).isPresent());
            JobRun bad = await(() -> js.lastRunOf("unknown").orElse(null), "");
            assertEquals("FAILED", bad.status(), bad.message());
            assertTrue(bad.message().contains("nope"), bad.message());
            assertEquals(List.of(), ScreeningHits.list(cfg), "a failed run raises nothing");
        }
    }

    @Test
    void paramsAreValidated() {
        Map<String, String> ok = Map.of("dataset", "d", "keyField", "k", "nameField", "n", "lists", "a,b");
        ScreeningJobType.Params p = ScreeningJobType.Params.of(ok);
        assertEquals(List.of("a", "b"), p.lists());
        assertEquals(Screener.DEFAULT_THRESHOLD, p.threshold());
        assertEquals(ScreeningJobType.DEFAULT_MAX_ROWS, p.maxRows());
        assertEquals(List.of("k", "n"), p.columns());
        for (Map<String, String> bad : List.of(
                Map.of("dataset", "d", "keyField", "k", "lists", "a"),                                  // no name/id field
                Map.of("dataset", "d", "keyField", "k; DROP", "nameField", "n", "lists", "a"),          // not a column name
                Map.of("dataset", "d", "keyField", "k", "nameField", "n", "lists", "A B"),              // not a list id
                Map.of("dataset", "d", "keyField", "k", "nameField", "n", "lists", "a", "threshold", "0.2"),
                Map.of("dataset", "d", "keyField", "k", "nameField", "n", "lists", "a", "maxRows", "0"))) {
            try {
                ScreeningJobType.Params.of(bad);
                fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().startsWith("screening.run"), expected.getMessage());
            }
        }
    }
}
