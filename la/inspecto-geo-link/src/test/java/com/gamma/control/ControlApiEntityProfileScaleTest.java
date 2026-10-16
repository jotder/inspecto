package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.entitystore.LaLimits;
import com.gamma.la.api.InputFingerprintCache;
import com.gamma.la.core.DatasetProvider;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.FingerprintBudget;
import com.gamma.la.core.InputFingerprint;
import com.gamma.pipeline.ComponentStore;
import com.gamma.spi.auth.Authenticators;
import com.gamma.sql.SqlSandboxPolicy;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static com.gamma.control.EntityProfileFixture.OWNER;
import static com.gamma.control.EntityProfileFixture.data;
import static com.gamma.control.EntityProfileFixture.get;
import static com.gamma.control.EntityProfileFixture.noRaw;
import static com.gamma.control.EntityProfileFixture.open;
import static com.gamma.control.EntityProfileFixture.settings;
import static com.gamma.control.EntityProfileFixture.subjects;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Entity Type profile at production scale (IMS showcase plan §6, 2026-10-16: a 132 M-row day ran the profile out of the
 * 1 GB sandbox default, and the failure was cached) over REAL HTTP: the install-wide {@code entity_profile.memory_limit_mb}
 * budget, a read that runs out of memory answered readably and NEVER cached, the relationship read retried over an Entity
 * sample, and the dominant value read from a row sample above {@code shape_sample_rows}. Out of memory is simulated by a
 * delegating {@link DatasetProvider} - the real engine answers every other statement.
 */
class ControlApiEntityProfileScaleTest {

    private static final String OOM = "Out of Memory Error: could not allocate block of size 256.0 KiB (953.3 MiB/953.6 MiB used)";

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
        DatasetProviders.forTest(null);
        InputFingerprintCache.forTest(null, 0);
        LaLimits.installDocPath(null);
    }

    /** Delegates to the real provider; fails any statement {@code fail} matches with an out-of-memory error; records policies. */
    static final class Faulty implements DatasetProvider {
        final DatasetProvider real = DatasetProviders.require();
        volatile Predicate<String> fail = s -> false;
        final List<String> memoryLimits = new ArrayList<>();
        final AtomicLong failures = new AtomicLong();

        private void maybeFail(Request req) throws SQLException {
            if (fail.test(req.sql())) {
                failures.incrementAndGet();
                throw new SQLException(OOM);
            }
        }

        @Override public Optional<Map<String, Object>> dataset(Path w, String id) { return real.dataset(w, id); }
        @Override public List<Entry> datasets(Path w) { return real.datasets(w); }
        @Override public List<Entry> linkAnalysisViews(Path w) { return real.linkAnalysisViews(w); }
        @Override public String relationSql(Map<String, Object> d, Path dr, Path w) { return real.relationSql(d, dr, w); }
        @Override public List<Path> readRoots(Map<String, Object> d, Path dr) { return real.readRoots(d, dr); }
        @Override public java.util.OptionalLong dataAsOf(Map<String, Object> d, String id, Path dr, Path w) { return real.dataAsOf(d, id, dr, w); }
        @Override public InputFingerprint inputFingerprint(Map<String, Object> d, Path dr, Path w) { return real.inputFingerprint(d, dr, w); }
        @Override public InputFingerprint inputFingerprint(Map<String, Object> d, Path dr, Path w, FingerprintBudget b) {
            return real.inputFingerprint(d, dr, w, b);
        }
        @Override public String relationSqlOverFiles(Map<String, Object> d, Path dr, Path w, List<String> files) {
            return real.relationSqlOverFiles(d, dr, w, files);
        }
        @Override public Map<String, String> schemaClassification(Path w, Map<String, Object> d, Predicate<String> masked) {
            return real.schemaClassification(w, d, masked);
        }
        @Override public Result run(Request req) throws SQLException, IOException {
            maybeFail(req);
            return real.run(req);
        }
        @Override public Result run(Request req, SqlSandboxPolicy policy) throws SQLException, IOException {
            maybeFail(req);
            return real.run(req, policy);
        }
        @Override public Result run(Request req, SqlSandboxPolicy policy, ZoneId zone) throws SQLException, IOException {
            synchronized (memoryLimits) {
                memoryLimits.add(policy.memoryLimit());
            }
            maybeFail(req);
            return real.run(req, policy, zone);
        }
        @Override public Result runPlanned(String ds, String rel, SqlSandboxPolicy policy, Planner planner) throws SQLException, IOException {
            return real.runPlanned(ds, rel, policy, planner);
        }
        @Override public BoundFilter predicateBound(Object filter) { return real.predicateBound(filter); }
    }

    private static Faulty install() {
        Faulty f = new Faulty();
        DatasetProviders.forTest(f);
        return f;
    }

    @Test
    void theProfileReadsUnderTheInstallWideMemoryBudgetNotTheSandboxDefault(@TempDir Path cfg, @TempDir Path root, @TempDir Path etc)
            throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            Faulty f = install();
            data(get(c, "/inv/entity-types/msisdn/profile?sections=volume", OWNER));
            assertFalse(f.memoryLimits.isEmpty());
            assertTrue(f.memoryLimits.stream().allMatch("4096MiB"::equals), "the default budget, not 1GB: " + f.memoryLimits);

            Path doc = etc.resolve("la-limits.toon");
            Files.writeString(doc, "entity_profile:\n  memory_limit_mb: 512\n");
            LaLimits.installDocPath(doc);
            f.memoryLimits.clear();
            data(get(c, "/inv/entity-types/msisdn/profile?sections=shapes", OWNER));
            assertTrue(!f.memoryLimits.isEmpty() && f.memoryLimits.stream().allMatch("512MiB"::equals),
                    "a stated install value applies to the next profile: " + f.memoryLimits);
        }
    }

    @Test
    void aReadThatRunsOutOfMemorySaysSoReadablyAndIsNeverCached(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        AtomicLong tick = new AtomicLong();
        InputFingerprintCache.forTest(() -> tick.addAndGet(60_000L), 30_000L);
        String store = "ep_scale_" + System.nanoTime();
        Path dir = Path.of("database").resolve(store);
        boolean hadDatabase = Files.isDirectory(Path.of("database"));
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            new ComponentStore(root.resolve("registry")).write("dataset", "f_ds", Map.of("physicalRef", store, "owner", "analyst-1",
                    "shares", List.of(), "columns", List.of(Map.of("name", "s", "classification", "CELL"))));
            plant(dir.resolve("p1.parquet"), "('CELL-1'),('CELL-2')", 1_700_000_000_000L);
            Faulty f = install();
            f.fail = sql -> sql.contains("APPROX_COUNT_DISTINCT(NULLIF(k");
            String path = "/inv/entity-types/cell/profile?sections=volume,shapes";

            JsonNode first = data(get(c, path, OWNER));
            JsonNode v = first.get("volume");
            assertFalse(v.get("assessed").asBoolean(), v.toString());
            assertTrue(v.get("retryable").asBoolean(), "a failed read, not a section that does not apply");
            assertTrue(v.get("reason").asText().contains("entity_profile.memory_limit_mb"), "readable, names the knob: " + v);
            assertFalse(v.get("reason").asText().contains("953"), "no raw engine message");
            assertTrue(first.at("/shapes/assessed").asBoolean(), "the other section still answers");

            f.fail = sql -> false;
            JsonNode second = data(get(c, path, OWNER));
            assertTrue(second.at("/volume/assessed").asBoolean(), "the failure was not cached: the next read retried it");
            assertEquals(2, second.at("/volume/rows").asLong());
            assertFalse(second.get("cached").asBoolean(), "volume had to be computed");
            assertTrue(data(get(c, path, OWNER)).get("cached").asBoolean(), "now both sections are cached");
        } finally {
            rmTree(dir);
            if (!hadDatabase) Path.of("database").toFile().delete();
        }
    }

    @Test
    void aRelationshipReadThatOutgrowsMemoryIsRetriedOverAnEntitySample(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            Faulty f = install();
            f.fail = sql -> sql.contains("COUNT(DISTINCT o)") && !sql.contains("hash(e)");
            JsonNode r = noRaw(get(c, "/inv/entity-types/msisdn/profile?sections=relationships", OWNER)).at("/relationships/0");
            assertTrue(r.get("assessed").asBoolean(), r.toString());
            assertTrue(r.get("sampled").asBoolean());
            assertEquals(64, r.get("sampleEvery").asInt());
            assertTrue(r.get("approximate").asBoolean());
            assertEquals(0, r.get("entities").asLong() % 64, "counts are scaled up from the sample");

            f.fail = sql -> sql.contains("COUNT(DISTINCT o)");
            JsonNode both = data(get(c, "/inv/entity-types/msisdn/profile?sections=relationships", OWNER)).at("/relationships/0");
            assertFalse(both.get("assessed").asBoolean(), "the sample failing too is a not-assessed section, never a zero");
            assertTrue(both.get("retryable").asBoolean());
            assertFalse(both.has("entities"));
        }
    }

    @Test
    void theDominantValueIsReadFromARowSampleAboveShapeSampleRows(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            JsonNode exact = noRaw(get(c, "/inv/entity-types/msisdn/profile?sections=volume", OWNER)).get("volume");
            assertFalse(exact.has("dominantSampled"), "below the limit the dominant value is exact: " + exact);
            List<String> kinds = new ArrayList<>();
            for (JsonNode s : exact.get("sentinels")) kinds.add(s.get("column").asText() + ":" + s.get("kind").asText());
            assertTrue(kinds.contains("a_msisdn:dominant"), "A holds 3 of 6 values of a_msisdn: " + kinds);

            settings(c, "entity_profile:\n  shape_sample_rows: 4\n");
            JsonNode sampled = noRaw(get(c, "/inv/entity-types/msisdn/profile?sections=volume", OWNER)).get("volume");
            assertTrue(sampled.get("dominantSampled").asBoolean(), sampled.toString());
            assertEquals(4, sampled.get("sampleRows").asLong());
            assertTrue(sampled.get("assessed").asBoolean());
            for (JsonNode s : sampled.get("sentinels"))
                if ("dominant".equals(s.get("kind").asText())) assertTrue(s.get("value").isNull(), "D-EP3 holds under sampling too");
        }
    }

    private static void plant(Path file, String values, long mtime) throws Exception {
        Files.createDirectories(file.getParent());
        DuckDbUtil.loadDriver();
        java.io.File db = DuckDbUtil.tempDbFile("ep_scale_");
        try (java.sql.Connection conn = DuckDbUtil.openConnection(db); java.sql.Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + values + ") AS v(s)) TO '" + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtime));
    }

    private static void rmTree(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
