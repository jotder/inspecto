package com.gamma.control;

import com.gamma.service.SpaceLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SE-2a opt-in rewrite (D-SE3): a hand rename of a Pipeline in a Space whose {@code space.toon} says
 * {@code rename_rewrite: true} has its dependents rewritten by the Pipeline rename route's own census rewrite
 * ({@link PipelineRenameRoutes#rewriteHandRename}, installed as the route registers); without the opt-in, or under
 * {@code -Dspace.rename.rewrite=off}, nothing is written.
 */
class SpaceLockRenameRewriteTest {

    @BeforeEach
    void install() {
        SpaceLock.installRewriter(PipelineRenameRoutes::rewriteHandRename);
    }

    @AfterEach
    void restore() {
        SpaceLock.installRewriter(null);
        System.clearProperty(SpaceLock.RENAME_REWRITE_PROPERTY);
    }

    private static Path space(Path tmp, boolean optIn) throws IOException {
        Path config = Files.createDirectories(tmp.resolve("alpha/config"));
        write(config.resolve("orders/orders_pipeline.toon"), "id: orders\nname: Orders\n");
        write(config.resolve("orders/orders_daily_enrich.toon"), "name: ORDERS_DAILY\ntriggers:\n  on_pipeline: orders\n");
        write(config.resolve("jobs/nightly_job.toon"), "job:\n  name: nightly\n  type: noop\n  on_pipeline: \"orders, other\"\n");
        write(tmp.resolve("alpha/space.toon"), "display_name: Alpha\n" + (optIn ? "rename_rewrite: true\n" : ""));
        return config;
    }

    private static void write(Path p, String text) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(SpaceLock.Report r, String list) {
        return (List<Map<String, Object>>) ((Map<String, Object>) r.body().get(list)).get("items");
    }

    private static SpaceLock.Report await(Path config, Predicate<SpaceLock.Report> ok) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            SpaceLock.Report r = SpaceLock.report("alpha", config);
            if ("ready".equals(r.status()) && !Boolean.TRUE.equals(r.body().get("recomputing")) && ok.test(r)) return r;
            Thread.sleep(100);
        }
        throw new AssertionError("not reached in time: " + SpaceLock.report("alpha", config).body());
    }

    /** Hand-rename, then let the background computation run (as after a boot). */
    private static void handRename(Path config) throws Exception {
        SpaceLock.compute("alpha", config, List.of());   // the baseline lock
        write(config.resolve("orders/orders_pipeline.toon"), "id: orders_v2\nname: Orders\n");
        SpaceLock.afterBoot("alpha", config);
    }

    @Test
    void anOptedInSpaceHasItsDependentsRewrittenThroughTheCensusRewrite(@TempDir Path tmp) throws Exception {
        Path config = space(tmp, true);
        try {
            handRename(config);
            SpaceLock.Report r = await(config, rep -> {
                try {
                    return Files.readString(config.resolve("orders/orders_daily_enrich.toon")).contains("orders_v2")
                            && items(rep, "renamed").isEmpty();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            String job = Files.readString(config.resolve("jobs/nightly_job.toon"));
            assertTrue(job.contains("orders_v2, other"), "a comma list keeps its other member: " + job);
            for (Map<String, Object> o : items(r, "changedOutside"))
                assertEquals("orders/orders_pipeline.toon", o.get("path"),
                        "the rewrite is a server write, not an outside edit: " + o);
        } finally {
            SpaceLock.forget(config);
        }
    }

    @Test
    void withoutTheOptInNothingIsRewritten(@TempDir Path tmp) throws Exception {
        Path config = space(tmp, false);
        assertNothingRewritten(config, "report");
    }

    @Test
    void theKillSwitchStopsAnOptedInRewrite(@TempDir Path tmp) throws Exception {
        System.setProperty(SpaceLock.RENAME_REWRITE_PROPERTY, "off");
        Path config = space(tmp, true);
        assertNothingRewritten(config, "disabled");
    }

    private static void assertNothingRewritten(Path config, String mode) throws Exception {
        String enrich = Files.readString(config.resolve("orders/orders_daily_enrich.toon"));
        String job = Files.readString(config.resolve("jobs/nightly_job.toon"));
        try {
            handRename(config);
            SpaceLock.Report r = await(config, rep -> items(rep, "renamed").size() == 1);
            assertEquals(mode, r.body().get("renameRewrite"));
            Thread.sleep(2_500);   // longer than the 1.5 s debounce: a rewrite would have scheduled and run by now
            assertEquals(enrich, Files.readString(config.resolve("orders/orders_daily_enrich.toon")));
            assertEquals(job, Files.readString(config.resolve("jobs/nightly_job.toon")));
        } finally {
            SpaceLock.forget(config);
        }
    }

    /** A dependent the Space's approval policy holds stops the whole rewrite: nothing is written. */
    @Test
    void aGovernedDependentStopsTheRewrite(@TempDir Path tmp) throws Exception {
        Path config = space(tmp, true);
        write(config.resolve("approval.toon"), "approval:\n  enrichment:\n    required: true\n");
        SpaceLock.RewriteResult res = PipelineRenameRoutes.rewriteHandRename(config, "orders", "orders_v2");
        assertEquals(1, res.held().size(), res.toString());
        assertEquals(0, res.rewritten());
        assertTrue(Files.readString(config.resolve("jobs/nightly_job.toon")).contains("orders, other"),
                "an ungoverned dependent is not rewritten either");
        assertTrue(Files.readString(config.resolve("orders/orders_daily_enrich.toon")).contains("on_pipeline: orders\n"));
    }
}
