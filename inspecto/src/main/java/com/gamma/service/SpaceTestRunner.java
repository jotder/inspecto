package com.gamma.service;

import com.gamma.config.safety.PathJail;
import com.gamma.etl.PartitionOutput;
import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.PipelineTestRun;
import com.gamma.sql.SqlViews;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.ToonHelper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * <b>SE-6a — test cases that travel with a Space.</b> A case is a folder {@code <space>/tests/<case>/} holding a
 * {@code case.toon} and the synthetic sample files it feeds to one Pipeline. Spec:
 * {@code docs/okf/product/module/config/spec-space-tests.md}.
 *
 * <p>A case runs through {@link PipelineTestRun#run} — the run-to-here engine, not a second one — into a fresh
 * scratch root in the JVM temp dir, which is deleted afterwards. That path re-roots every destination, skips
 * commit/audit/provenance and contains ambient Signals, so a case never writes under the Space's {@code data/},
 * {@code audit/} or {@code duckdb/}, and never reads the inbox.
 *
 * <p>Lives beside {@link ImportPaths}, whose {@link ImportPaths#segmentRefusal segment rule} jails every name.
 */
public final class SpaceTestRunner {

    private SpaceTestRunner() {}

    /** The folder, beside {@code config/}, that holds the cases. No loader walks it. */
    public static final String TESTS_DIR = "tests";
    public static final String CASE_FILE = "case.toon";
    /** Bound on the cases one listing or run reads. */
    public static final int MAX_CASES = 200;

    private static final Set<String> CHECKS = Set.of("not_null", "distinct", "min", "max");

    public record ColumnCheck(String column, String check, String value) {}

    public record Case(String name, Path dir, String pipeline, List<Path> inputs, Long rows, Long rejects,
                       String checksum, List<ColumnCheck> columns) {}

    /** One expectation's verdict. */
    public record Line(String name, String expected, String actual, boolean passed) {}

    public record CaseResult(String name, String pipeline, boolean passed, String error, List<Line> lines) {}

    /** A case name that is not a plain name. */
    public static final class BadName extends RuntimeException {
        BadName(String m) { super(m); }
    }

    /** No such case. */
    public static final class NoCase extends RuntimeException {
        NoCase(String m) { super(m); }
    }

    /** A case file that cannot be read as a case (including an input outside the case folder). */
    public static final class Malformed extends RuntimeException {
        Malformed(String m) { super(m); }
    }

    /** {@code <space>/tests} for a Space whose config root is {@code configRoot}. */
    public static Path testsDir(Path configRoot) {
        Path space = PathJail.spaceDirOf(configRoot);
        if (space == null) space = configRoot.toAbsolutePath().normalize().getParent();
        return space.resolve(TESTS_DIR);
    }

    /** Case names under {@code testsDir}, sorted: every direct sub-folder holding a {@code case.toon}. */
    public static List<String> list(Path testsDir) throws IOException {
        if (!Files.isDirectory(testsDir)) return List.of();
        try (Stream<Path> s = Files.list(testsDir)) {
            return s.filter(p -> Files.isRegularFile(p.resolve(CASE_FILE)))
                    .map(p -> p.getFileName().toString())
                    .filter(n -> ImportPaths.segmentRefusal(n) == null)
                    .sorted().limit(MAX_CASES).toList();
        }
    }

    /** Read and validate one case. Throws {@link BadName}, {@link NoCase} or {@link Malformed}. */
    public static Case load(Path testsDir, String name) {
        String why = name == null || name.contains("/") || name.contains("\\")
                ? "a case name is one plain name" : ImportPaths.segmentRefusal(name);
        if (why != null) throw new BadName("case '" + name + "' refused: " + why);
        Path dir = testsDir.resolve(name).toAbsolutePath().normalize();
        Path file = dir.resolve(CASE_FILE);
        if (!PathJail.contains(testsDir, dir) || !Files.isRegularFile(file))
            throw new NoCase("no test case '" + name + "'");
        Map<String, Object> t;
        try {
            t = ToonHelper.load(file.toString());
        } catch (IOException | RuntimeException e) {
            throw new Malformed("case '" + name + "': " + CASE_FILE + " does not parse: " + e.getMessage());
        }
        String pipeline = text(t.get("pipeline"));
        if (pipeline == null) throw new Malformed("case '" + name + "': 'pipeline' is required");
        if (!(t.get("inputs") instanceof List<?> raw) || raw.isEmpty())
            throw new Malformed("case '" + name + "': 'inputs' must be a non-empty list of file names");
        List<Path> inputs = new ArrayList<>();
        for (Object o : raw) {
            String f = text(o);
            String bad = f == null ? "a blank entry" : ImportPaths.segmentRefusal(f);
            Path p = f == null ? null : dir.resolve(f).normalize();
            if (bad == null && !PathJail.contains(dir, p)) bad = "it resolves outside the case folder";
            if (bad != null) throw new Malformed("case '" + name + "': input '" + f + "' refused: " + bad);
            if (CASE_FILE.equals(f) || !Files.isRegularFile(p))
                throw new Malformed("case '" + name + "': input '" + f + "' is not a file in the case folder");
            inputs.add(p);
        }
        if (!(t.get("expect") instanceof Map<?, ?> expect))
            throw new Malformed("case '" + name + "': 'expect' is required");
        Long rows = count(name, "rows", expect.get("rows"));
        Long rejects = count(name, "rejects", expect.get("rejects"));
        String checksum = text(expect.get("checksum"));
        List<ColumnCheck> columns = new ArrayList<>();
        if (expect.get("columns") != null) {
            if (!(expect.get("columns") instanceof List<?> cl))
                throw new Malformed("case '" + name + "': 'expect.columns' must be a list");
            for (Object o : cl) {
                if (!(o instanceof Map<?, ?> c)) throw new Malformed("case '" + name + "': a column check is not a row");
                String col = text(c.get("column")), check = text(c.get("check"));
                String value = c.get("value") == null ? "" : String.valueOf(c.get("value"));
                if (col == null || check == null || !CHECKS.contains(check.toLowerCase(Locale.ROOT)))
                    throw new Malformed("case '" + name + "': a column check needs a column and one of " + CHECKS);
                columns.add(new ColumnCheck(col, check.toLowerCase(Locale.ROOT), value));
            }
        }
        if (rows == null && rejects == null && checksum == null && columns.isEmpty())
            throw new Malformed("case '" + name + "': 'expect' names no expectation");
        return new Case(name, dir, pipeline, List.copyOf(inputs), rows, rejects, checksum, List.copyOf(columns));
    }

    /** Run one case. Never throws: a case that cannot run fails with the reason. */
    public static CaseResult run(Case c, Function<String, Optional<PipelineConfig>> pipelines) {
        PipelineConfig cfg;
        try {
            cfg = pipelines.apply(c.pipeline()).orElse(null);
        } catch (RuntimeException e) {
            cfg = null;
        }
        if (cfg == null) return failed(c, "no Pipeline '" + c.pipeline() + "' in this Space");
        if (cfg.collector().hasDataset())
            return failed(c, "Pipeline '" + c.pipeline() + "' is fed by a Dataset; a case feeds files");
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("spacetest_");
            PipelineTestRun.Result r = PipelineTestRun.run(cfg, c.inputs(), scratch);
            if ("FAILED".equals(r.status()))
                return failed(c, "the batch FAILED: " + r.error());
            List<Line> lines = new ArrayList<>();
            if (c.rows() != null) lines.add(line("rows", c.rows(), r.rowsWritten()));
            if (c.rejects() != null)
                lines.add(line("rejects", c.rejects(),
                        r.files().stream().mapToLong(PipelineTestRun.FileResult::errorRows).sum()));
            if (c.checksum() != null || !c.columns().isEmpty())
                lines.addAll(outputChecks(c, r.outputs(), String.valueOf(cfg.output().format())));
            boolean ok = lines.stream().allMatch(Line::passed);
            return new CaseResult(c.name(), c.pipeline(), ok, null, List.copyOf(lines));
        } catch (Exception e) {
            return failed(c, "the case could not run: " + e.getMessage());
        } finally {
            PipelineTestRun.deleteScratch(scratch);
        }
    }

    /**
     * The Pipelines of a Space, read from its own {@code *_pipeline.toon} files with
     * {@link PipelineConfig#loadForValidation} (no write). For callers with no running service (the Job).
     */
    public static Function<String, Optional<PipelineConfig>> pipelinesUnder(Path configRoot) {
        return id -> {
            String want = id.toLowerCase(Locale.ROOT);
            try (Stream<Path> s = Files.walk(configRoot, 6)) {
                for (Path p : s.filter(f -> f.getFileName().toString().endsWith("_pipeline.toon")).toList()) {
                    try {
                        PipelineConfig cfg = PipelineConfig.loadForValidation(p.toString());
                        if (want.equals(cfg.identity().pipelineName().toLowerCase(Locale.ROOT))
                                || want.equals(cfg.identity().name().toLowerCase(Locale.ROOT)))
                            return Optional.of(cfg);
                    } catch (IOException | RuntimeException skipped) {
                        // one unreadable sibling must not hide the rest
                    }
                }
            } catch (IOException e) {
                return Optional.empty();
            }
            return Optional.empty();
        };
    }

    private static List<Line> outputChecks(Case c, List<PartitionOutput> outputs, String format) throws Exception {
        List<Line> lines = new ArrayList<>();
        if (outputs.isEmpty()) {
            if (c.checksum() != null) lines.add(new Line("checksum", c.checksum(), "(no rows landed)", false));
            for (ColumnCheck k : c.columns())
                lines.add(new Line(k.column() + " " + k.check(), k.value(), "(no rows landed)", false));
            return lines;
        }
        String reader = SqlViews.reader(format, outputs.stream().map(PartitionOutput::outputFile).toList(), false);
        File db = DuckDbUtil.tempDbFile("spacetest_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            if (c.checksum() != null) {
                String sum = scalar(st, "SELECT md5(coalesce(string_agg(r, chr(10) ORDER BY r), '')) FROM "
                        + "(SELECT CAST(t AS VARCHAR) AS r FROM " + reader + " t)");
                lines.add(new Line("checksum", c.checksum(), sum, c.checksum().equalsIgnoreCase(sum)));
            }
            for (ColumnCheck k : c.columns()) {
                String q = com.gamma.util.SqlIdent.q(k.column());
                String label = k.column() + " " + k.check();
                try {
                    switch (k.check()) {
                        case "not_null" -> {
                            String nulls = scalar(st, "SELECT count(*) FROM " + reader + " WHERE " + q + " IS NULL");
                            lines.add(new Line(label, "0 nulls", nulls + " nulls", "0".equals(nulls)));
                        }
                        case "distinct" -> {
                            String n = scalar(st, "SELECT count(DISTINCT " + q + ") FROM " + reader);
                            lines.add(new Line(label, k.value(), n, k.value().trim().equals(n)));
                        }
                        default -> {
                            String v = scalar(st, "SELECT CAST(" + k.check() + "(" + q + ") AS VARCHAR) FROM " + reader);
                            lines.add(new Line(label, k.value(), v, k.value().equals(v)));
                        }
                    }
                } catch (java.sql.SQLException e) {
                    lines.add(new Line(label, k.value(), "error: " + e.getMessage().lines().findFirst().orElse(""), false));
                }
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        return lines;
    }

    private static String scalar(Statement st, String sql) throws java.sql.SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? String.valueOf(rs.getString(1)) : "";
        }
    }

    private static Line line(String name, long expected, long actual) {
        return new Line(name, Long.toString(expected), Long.toString(actual), expected == actual);
    }

    private static CaseResult failed(Case c, String why) {
        return new CaseResult(c.name(), c.pipeline(), false, why, List.of());
    }

    private static Long count(String name, String key, Object v) {
        if (v == null) return null;
        try {
            long n = Long.parseLong(String.valueOf(v).trim());
            if (n < 0) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new Malformed("case '" + name + "': 'expect." + key + "' must be a whole number ≥ 0");
        }
    }

    private static String text(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
