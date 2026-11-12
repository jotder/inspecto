package com.gamma.service;

import com.gamma.event.EventLog;
import com.gamma.job.CapturingJobContext;
import com.gamma.job.JobConfig;
import com.gamma.job.JobResult;
import com.gamma.pipeline.SpaceConfigRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SE-6a — the case format, its path jail, the run over the SHIPPED demo cases (read in place, nothing written
 * into the repo), and the {@code space.test} Job.
 */
class SpaceTestRunnerTest {

    private static final Path DEMO = Path.of("..", "spaces", "demo").toAbsolutePath().normalize();

    // ── the shipped demo cases ───────────────────────────────────────────────────

    @Test
    void theDemoCasesPassAndWriteNothingIntoTheSpace() throws Exception {
        Path config = DEMO.resolve("config");
        Path tests = SpaceTestRunner.testsDir(config);
        assertEquals(DEMO.resolve("tests"), tests);
        List<String> before = tree(DEMO);

        List<String> names = SpaceTestRunner.list(tests);
        assertTrue(names.containsAll(List.of("orders_ingest", "payments_ingest")), names.toString());
        var pipelines = SpaceTestRunner.pipelinesUnder(config);
        for (String n : names) {
            SpaceTestRunner.CaseResult r = SpaceTestRunner.run(SpaceTestRunner.load(tests, n), pipelines);
            assertTrue(r.passed(), n + ": " + r);
        }
        assertEquals(before, tree(DEMO), "a case run must not create, move or delete a file in the Space");
    }

    @Test
    void theOrdersCaseChecksTheDocumentedLoop() throws Exception {
        SpaceTestRunner.Case c = SpaceTestRunner.load(DEMO.resolve("tests"), "orders_ingest");
        SpaceTestRunner.CaseResult r = SpaceTestRunner.run(c, SpaceTestRunner.pipelinesUnder(DEMO.resolve("config")));
        assertEquals("34", line(r, "rows").actual(), r.toString());
        assertEquals("2", line(r, "rejects").actual(), r.toString());
    }

    @Test
    void aWrongExpectationFailsWithAReadableDiff(@TempDir Path dir) throws Exception {
        Path tests = copyCase(dir, "orders_ingest");
        Files.writeString(tests.resolve("orders_ingest/case.toon"), """
                pipeline: orders
                inputs[1]: ORDERS_20260703.csv
                expect:
                  rows: 99
                  rejects: 2
                  checksum: "deadbeef"
                  columns[2]{column,check,value}:
                    REGION,max,ZZZ
                    NO_SUCH_COLUMN,not_null,""
                """);
        SpaceTestRunner.CaseResult r = SpaceTestRunner.run(SpaceTestRunner.load(tests, "orders_ingest"),
                SpaceTestRunner.pipelinesUnder(DEMO.resolve("config")));
        assertFalse(r.passed());
        assertEquals(new SpaceTestRunner.Line("rows", "99", "10", false), line(r, "rows"));
        assertTrue(line(r, "rejects").passed(), r.toString());
        assertFalse(line(r, "checksum").passed());
        assertEquals(32, line(r, "checksum").actual().length(), "an md5 is reported: " + r);
        assertEquals("WEST", line(r, "REGION max").actual());
        assertTrue(line(r, "NO_SUCH_COLUMN not_null").actual().startsWith("error:"), r.toString());
    }

    @Test
    void theChecksumIsStableAcrossRuns(@TempDir Path dir) throws Exception {
        Path tests = copyCase(dir, "orders_ingest");
        Files.writeString(tests.resolve("orders_ingest/case.toon"), """
                pipeline: orders
                inputs[1]: ORDERS_20260701.csv
                expect:
                  checksum: "x"
                """);
        var pipelines = SpaceTestRunner.pipelinesUnder(DEMO.resolve("config"));
        String a = line(SpaceTestRunner.run(SpaceTestRunner.load(tests, "orders_ingest"), pipelines), "checksum").actual();
        String b = line(SpaceTestRunner.run(SpaceTestRunner.load(tests, "orders_ingest"), pipelines), "checksum").actual();
        assertEquals(a, b);
    }

    @Test
    void anUnknownPipelineFailsTheCaseRatherThanTheRun(@TempDir Path dir) throws Exception {
        Path tests = copyCase(dir, "orders_ingest");
        Files.writeString(tests.resolve("orders_ingest/case.toon"),
                "pipeline: ghost\ninputs[1]: ORDERS_20260701.csv\nexpect:\n  rows: 1\n");
        SpaceTestRunner.CaseResult r = SpaceTestRunner.run(SpaceTestRunner.load(tests, "orders_ingest"),
                SpaceTestRunner.pipelinesUnder(DEMO.resolve("config")));
        assertFalse(r.passed());
        assertTrue(r.error().contains("ghost"), r.error());
    }

    // ── the jail and the format ──────────────────────────────────────────────────

    @Test
    void aCaseNameIsOnePlainName(@TempDir Path dir) {
        for (String bad : List.of("..", "../x", "a/b", "a\\b", ".hidden", "CON", ""))
            assertThrows(SpaceTestRunner.BadName.class, () -> SpaceTestRunner.load(dir, bad), bad);
    }

    @Test
    void anUnknownCaseIsNoCase(@TempDir Path dir) {
        assertThrows(SpaceTestRunner.NoCase.class, () -> SpaceTestRunner.load(dir, "missing"));
    }

    @Test
    void anInputCannotLeaveTheCaseFolder(@TempDir Path dir) throws Exception {
        Path tests = dir.resolve("tests");
        Files.createDirectories(tests.resolve("c"));
        Files.writeString(dir.resolve("secret.csv"), "x\n");
        for (String input : List.of("../../secret.csv", "\"../c/case.toon\"", "case.toon", "missing.csv")) {
            Files.writeString(tests.resolve("c/case.toon"),
                    "pipeline: orders\ninputs[1]: " + input + "\nexpect:\n  rows: 1\n");
            assertThrows(SpaceTestRunner.Malformed.class, () -> SpaceTestRunner.load(tests, "c"), input);
        }
    }

    @Test
    void aMalformedCaseFileIsRefused(@TempDir Path dir) throws Exception {
        Path tests = dir.resolve("tests");
        Files.createDirectories(tests.resolve("c"));
        Files.writeString(tests.resolve("c/in.csv"), "x\n");
        for (String body : List.of(
                "inputs[1]: in.csv\nexpect:\n  rows: 1\n",                                  // no pipeline
                "pipeline: orders\nexpect:\n  rows: 1\n",                                    // no inputs
                "pipeline: orders\ninputs[1]: in.csv\n",                                     // no expect
                "pipeline: orders\ninputs[1]: in.csv\nexpect:\n  rows: many\n",               // not a number
                "pipeline: orders\ninputs[1]: in.csv\nexpect:\n  note: x\n",                  // no expectation
                "pipeline: orders\ninputs[1]: in.csv\nexpect:\n  columns[1]{column,check,value}:\n    A,median,1\n")) {
            Files.writeString(tests.resolve("c/case.toon"), body);
            assertThrows(SpaceTestRunner.Malformed.class, () -> SpaceTestRunner.load(tests, "c"), body);
        }
    }

    @Test
    void theTestsFolderIsTheSiblingOfConfig(@TempDir Path dir) {
        assertEquals(dir.resolve("sp/tests").toAbsolutePath().normalize(),
                SpaceTestRunner.testsDir(dir.resolve("sp/config")));
    }

    // ── the Job ──────────────────────────────────────────────────────────────────

    @Test
    void theJobRunsTheCasesAndSignalsAFailure(@TempDir Path dir) throws Exception {
        Path space = dir.resolve("sp");
        copyTree(DEMO.resolve("config/orders"), space.resolve("config/orders"));
        Path tests = copyCase(space, "orders_ingest");
        copyCase(space, "orders_ingest", "orders_broken");
        Files.writeString(tests.resolve("orders_broken/case.toon"),
                "pipeline: orders\ninputs[1]: ORDERS_20260701.csv\nexpect:\n  rows: 1\n");
        // The Job resolves SpaceConfigRoot.requireCurrent(), where a registered default-Space root (left behind by any
        // earlier DecisionRules/SpaceBootstrap use in this JVM) beats -Dassist.write.root: register ours explicitly.
        SpaceConfigRoot.register(EventLog.DEFAULT_SPACE_ID, space.resolve("config"));
        try {
            var job = new SpaceTestJobType().create(new JobConfig("t", SpaceTestJobType.TYPE, null, null, true, false,
                    Map.of(), null, null));

            CapturingJobContext ok = new CapturingJobContext(Map.of("cases", "orders_ingest"), false);
            JobResult r = job.run(ok);
            assertTrue(r.message().contains("all passed"), r.message());
            assertTrue(ok.signals.stream().noneMatch(s -> SpaceTestJobType.FAILED.equals(s.get("__type"))));

            CapturingJobContext all = new CapturingJobContext(Map.of(), false);
            r = job.run(all);
            assertTrue(r.message().contains("1 of 2 failed - orders_broken"), r.message());
            assertTrue(all.signals.stream().anyMatch(s -> SpaceTestJobType.FAILED.equals(s.get("__type"))
                    && List.of("orders_broken").equals(s.get("cases"))), all.signals.toString());
            assertTrue(all.warned("orders_broken"), all.warnings.toString());
        } finally {
            SpaceConfigRoot.forget(EventLog.DEFAULT_SPACE_ID);
        }
        assertFalse(Files.exists(space.resolve("data")), "the Job must not create the Space's data folder");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static SpaceTestRunner.Line line(SpaceTestRunner.CaseResult r, String name) {
        return r.lines().stream().filter(l -> l.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no line '" + name + "' in " + r));
    }

    private static Path copyCase(Path space, String name) throws IOException {
        return copyCase(space, name, name);
    }

    private static Path copyCase(Path space, String name, String as) throws IOException {
        Path tests = space.resolve("tests");
        copyTree(DEMO.resolve("tests").resolve(name), tests.resolve(as));
        return tests;
    }

    private static void copyTree(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (Stream<Path> s = Files.list(from)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) Files.copy(f, to.resolve(f.getFileName()));
        }
    }

    private static List<String> tree(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.map(p -> root.relativize(p) + "|" + (Files.isRegularFile(p) ? p.toFile().length() : -1)).sorted().toList();
        }
    }
}
