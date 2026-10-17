package com.gamma.job;

import com.gamma.analytics.MedianMode;
import com.gamma.analytics.QuantileMode;
import com.gamma.analytics.Stats;
import com.gamma.analytics.time.TimeGrain;
import com.gamma.query.AnalyticsAccess;
import com.gamma.query.DatasetAccess;
import com.gamma.util.JdbcDrivers;
import com.gamma.util.RunLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Platform Service {@code analytics} (ANALYTICS-CORE-1 S7): the Analytics Kernel granted through the seam's own rules —
 * declared {@code requires:} validated at registration, invisible when undeclared, handed through a dry run unchanged —
 * and a sample Job Type that profiles a Dataset it reads through its {@code datasets} grant.
 */
class AnalyticsServiceTest {

    private static final RunLog QUIET = new RunLog() {
        @Override public void info(String m, Object... kv) {}
        @Override public void warn(String m, Object... kv) {}
        @Override public void error(String m, Throwable t, Object... kv) {}
    };

    private static JobTypeProvider type(String id, List<String> requires, java.util.function.Function<JobContext, JobResult> body) {
        return JobTypeProvider.of(new JobTypeDescriptor(id, id, "analytics fixture", List.of(), List.of(), List.of(), requires),
                cfg -> new Job() {
                    @Override public String name() { return cfg.name(); }
                    @Override public String type() { return id; }
                    @Override public JobResult run() { throw new UnsupportedOperationException(); }
                    @Override public JobResult run(JobContext ctx) { return body.apply(ctx); }
                });
    }

    private static PlatformServiceRegistry hostLike() {
        PlatformServiceRegistry platform = new PlatformServiceRegistry();
        platform.register("analytics", AnalyticsAccess.class, AnalyticsAccess.kernel());
        platform.register("noop", Runnable.class, () -> {});
        return platform;
    }

    // ── grants ────────────────────────────────────────────────────────────────

    @Test
    void aDeclaredAnalyticsGrantRegistersAndAnUnknownOrAbsentIdIsRefusedAtRegistration() {
        JobTypeRegistry registry = new JobTypeRegistry(hostLike());
        registry.register(type("acme.stats", List.of("analytics"), ctx -> null), "acme-pack");
        assertEquals(List.of("analytics"), registry.descriptor("acme.stats").orElseThrow().requires());

        IllegalStateException typo = assertThrows(IllegalStateException.class,
                () -> registry.register(type("acme.typo", List.of("analytic")), "acme-pack"));
        assertTrue(typo.getMessage().contains("analytic") && typo.getMessage().contains("acme.typo"), typo.getMessage());
        assertFalse(registry.has("acme.typo"));

        PlatformServiceRegistry without = new PlatformServiceRegistry();
        without.register("noop", Runnable.class, () -> {});
        IllegalStateException absent = assertThrows(IllegalStateException.class,
                () -> new JobTypeRegistry(without).register(type("acme.stats", List.of("analytics"), ctx -> null), "acme-pack"));
        assertTrue(absent.getMessage().contains("analytics"), absent.getMessage());
    }

    private static JobTypeProvider type(String id, List<String> requires) {
        return type(id, requires, ctx -> null);
    }

    @Test
    void anUndeclaredConsumerCannotReachTheServiceEvenThoughTheHostBindsIt() {
        PlatformServiceRegistry platform = hostLike();
        PlatformServices other = platform.grant(Set.of("noop"));
        assertTrue(other.find(AnalyticsAccess.class).isEmpty());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> other.get(AnalyticsAccess.class));
        assertTrue(e.getMessage().contains("AnalyticsAccess"), e.getMessage());

        PlatformServices mine = platform.grant(Set.of("analytics"));
        assertSame(AnalyticsAccess.kernel(), mine.get(AnalyticsAccess.class));
        platform.disable("analytics");
        assertThrows(IllegalStateException.class, () -> platform.grant(Set.of("analytics")));
    }

    @Test
    void aDryRunHandsTheReadOnlyServiceThroughUnchangedAndWidensNothing() {
        PlatformServiceRegistry platform = hostLike();
        PlatformServices dry = DryRunServices.wrap(platform.grant(Set.of("analytics")), QUIET);
        assertSame(AnalyticsAccess.kernel(), dry.get(AnalyticsAccess.class), "read-only: the real service, no stand-in");
        assertEquals(3.0, dry.get(AnalyticsAccess.class).median(new double[] {1, 3, 9}, MedianMode.STANDARD));

        PlatformServices dryOther = DryRunServices.wrap(platform.grant(Set.of("noop")), QUIET);
        assertTrue(dryOther.find(AnalyticsAccess.class).isEmpty(), "a dry run never widens visibility");
    }

    // ── the surface delegates to the kernel ───────────────────────────────────

    @Test
    void everyStatisticIsTheKernelsDefinition() {
        AnalyticsAccess a = AnalyticsAccess.kernel();
        double[] v = {2, 4, 4, 4, 5, 5, 7, 9};
        assertEquals(Stats.mean(v), a.mean(v));
        assertEquals(4.0, a.variance(v, AnalyticsAccess.Spread.POPULATION));
        assertEquals(Stats.varianceSample(v), a.variance(v, AnalyticsAccess.Spread.SAMPLE));
        assertEquals(2.0, a.stddev(v, AnalyticsAccess.Spread.POPULATION));
        assertEquals(Stats.stddevSample(v), a.stddev(v, AnalyticsAccess.Spread.SAMPLE));
        assertEquals(4.5, a.median(v, MedianMode.STANDARD));
        assertEquals(4.0, a.median(v, MedianMode.LOWER));
        assertEquals(Stats.quantile(v, 0.9), a.quantile(v, 0.9, QuantileMode.CONTINUOUS));
        assertEquals(Stats.quantile(v, 0.9, QuantileMode.DISCRETE), a.quantile(v, 0.9, QuantileMode.DISCRETE));
        assertEquals(Stats.mad(v), a.mad(v));
        assertEquals(Stats.scaledMad(v), a.scaledMad(v));
        assertEquals(2.0, a.zScore(9, 5, 2));
        assertEquals(Stats.robustZ(9, v, 1), a.robustZ(9, v, 1));
        assertEquals(Stats.iqr(v), a.iqr(v));
        assertTrue(a.ratio(1, 0).isEmpty());
        assertEquals(0.25, a.rate(1, 4).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> a.mean(new double[0]), "the kernel's input rules hold");
        assertThrows(IllegalArgumentException.class, () -> a.stddev(new double[] {1}, AnalyticsAccess.Spread.SAMPLE));
    }

    @Test
    void timeGrainTruncationIsTheKernelsWithAnExplicitZone() {
        AnalyticsAccess a = AnalyticsAccess.kernel();
        // 2026-10-17 is a Saturday; the week starts on Monday the 12th.
        assertEquals(LocalDateTime.parse("2026-10-12T00:00"), a.truncate(LocalDateTime.parse("2026-10-17T13:45"), TimeGrain.WEEK));
        ZoneId kolkata = ZoneId.of("Asia/Kolkata");
        Instant t = Instant.parse("2026-10-17T20:00:00Z");   // already the 18th in Kolkata
        assertEquals(Instant.parse("2026-10-17T18:30:00Z"), a.truncate(t, TimeGrain.DAY, kolkata));
        assertEquals(TimeGrain.DAY.truncateSql("ts", kolkata), a.truncateSql("ts", TimeGrain.DAY, kolkata));
    }

    // ── a sample Job Type: datasets + analytics ───────────────────────────────

    /** Reads its Dataset through {@code datasets}, profiles it and judges its largest value through {@code analytics}. */
    private static JobTypeProvider profiler() {
        return type("acme.profile", List.of("datasets", "analytics"), ctx -> {
            DatasetAccess.Read read = ctx.services().get(DatasetAccess.class).read(ctx.spaceId(), "orders");
            AnalyticsAccess a = ctx.services().get(AnalyticsAccess.class);
            try (Connection c = JdbcDrivers.connect("jdbc:duckdb:"); Statement st = c.createStatement()) {
                long rows;
                double mean;
                double p50;
                try (ResultSet rs = st.executeQuery(a.profileSql(read, List.of("amount"), 0.5))) {
                    assertTrue(rs.next());
                    rows = rs.getLong("row_count");
                    mean = rs.getDouble("mean");
                    p50 = rs.getDouble("p50");
                }
                List<Double> vs = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("SELECT amount FROM (" + read.relationSql() + ") AS r WHERE amount IS NOT NULL")) {
                    while (rs.next()) vs.add(rs.getDouble(1));
                }
                double[] v = vs.stream().mapToDouble(Double::doubleValue).toArray();
                double z = a.robustZ(95, v, 1);
                ctx.log().info("profiled", "rows", rows, "mean", String.format(java.util.Locale.ROOT, "%.9f", mean), "p50", p50,
                        "median", a.median(v, MedianMode.STANDARD), "robustZ", z);
                return JobResult.ok(z > 3 ? "outlier" : "normal", 0L);
            } catch (Exception e) {
                return JobResult.failed(e.toString(), 0L);
            }
        });
    }

    @Test
    void aSampleJobProfilesADatasetItReadsThroughItsGrantsAndRunsTheSameDry(@TempDir Path root) throws Exception {
        Path dir = Files.createDirectories(root.resolve("orders"));
        try (Connection c = JdbcDrivers.connect("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES (1,10.0),(2,12.0),(3,11.0),(4,13.0),(5,9.0),(6,10.0),(7,11.0),"
                    + "(8,12.0),(9,NULL),(10,10.0),(11,11.0),(12,95.0)) AS t(id, amount)) TO '"
                    + dir.resolve("part-0.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        DatasetAccess datasets = DatasetAccess.over("default",
                id -> "orders".equals(id) ? Optional.of(Map.<String, Object>of("physicalRef", "orders")) : Optional.empty(),
                () -> root, () -> null);
        PackTestHarness h = PackTestHarness.create().service("datasets", DatasetAccess.class, datasets).load(profiler());

        double robustZ = 84 / Stats.MAD_SCALE;   // median 11, MAD 1, scaled 1.4826 above the floor of 1
        for (PackTestHarness.Outcome run : List.of(h.run("acme.profile", Map.of()), h.dryRun("acme.profile", Map.of()))) {
            assertEquals("SUCCESS", run.status(), run.message());
            assertEquals("outlier", run.message());
            assertTrue(run.granted().containsAll(Set.of(DatasetAccess.class, AnalyticsAccess.class)), run.granted().toString());
            assertTrue(run.logged("rows=12 mean=18.545454545 p50=11.0 median=11.0 robustZ=" + robustZ), run.log().toString());
        }
    }

    @Test
    void aJobThatDidNotDeclareAnalyticsCannotProfile() {
        PackTestHarness h = PackTestHarness.create().load(type("acme.sneaky", List.of(), ctx ->
                JobResult.ok(ctx.services().find(AnalyticsAccess.class).isPresent() ? "reached" : "invisible", 0L)));
        assertEquals("invisible", h.run("acme.sneaky", Map.of()).message());
    }
}
