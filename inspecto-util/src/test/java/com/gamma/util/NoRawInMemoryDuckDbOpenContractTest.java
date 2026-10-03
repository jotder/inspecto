package com.gamma.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code DUCKDB-INMEMORY-SCRATCH-UNCAPPED-1} — no production code opens an in-memory DuckDB except through
 * {@link DuckDbUtil#openInMemory}.
 *
 * <p>A raw {@code "jdbc:duckdb:"} open runs on DuckDB's ~80%-of-RAM default and spills to {@code .tmp}
 * relative to the process CWD — outside the Space folder. The row was closed once with every opener routed,
 * and {@code RiskScoreEvaluator} reintroduced a raw open within the day; this test is what keeps it closed.
 *
 * <p>It matches the in-memory URL LITERAL — {@code "jdbc:duckdb:"} not immediately concatenated with a path, or any
 * {@code "jdbc:duckdb::memory:…"} — across every module's {@code src/main/java}. A comment carrying
 * the literal trips it too — the loud direction. {@link #ALLOWED} names each exempt file and why.
 */
class NoRawInMemoryDuckDbOpenContractTest {

    /** The in-memory URL literal: {@code "jdbc:duckdb:"} NOT followed by {@code +} (a {@code "jdbc:duckdb:" + path}
     *  open is file-backed), or any {@code "jdbc:duckdb::memory:…"} — a named in-memory database, even concatenated. */
    static final Pattern IN_MEMORY_LITERAL =
            Pattern.compile("\"jdbc:duckdb:\"(?!\\s*\\+)|\"jdbc:duckdb::memory:[^\"]*\"");

    /** Files that carry the literal without opening a raw connection with it. */
    private static final Map<String, String> ALLOWED = Map.of(
            "DuckDbUtil.java", "the factory itself (openInMemory)",
            "OperationalDbReport.java", "a JDBC scheme allow-list, not an open",
            "JdbcDrivers.java", "a URL-prefix driver dispatch, not an open",
            "EgressGate.java", "a JDBC URL-prefix check (in-process engines dial nothing), not an open");

    @Test
    void noMainSourceOpensARawInMemoryDuckDb() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : mainRoots()) {
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                    if (ALLOWED.containsKey(p.getFileName().toString())) continue;
                    List<String> lines = Files.readAllLines(p);
                    for (int i = 0; i < lines.size(); i++)
                        if (IN_MEMORY_LITERAL.matcher(lines.get(i)).find())
                            offenders.add(repoRoot().relativize(p) + ":" + (i + 1));
                }
            }
        }
        assertTrue(offenders.isEmpty(), "these open an in-memory DuckDB with no memory_limit and a CWD-relative"
                + " spill — use DuckDbUtil.openInMemory(spillDir) instead: " + offenders);
    }

    /** ENGINE-INMEMORY-UNSEALED-1: the file-access opt-in is pinned to the callers that justified it. */
    private static final Map<String, String> FILE_ACCESS_OPT_INS = Map.of(
            "IndexBuilder.java", "the trusted LA relation reads Dataset roots the builder is not given");

    @Test
    void theFileAccessOptInSpreadsOnlyByDecision() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : mainRoots()) {
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                    String name = p.getFileName().toString();
                    if (name.equals("DuckDbUtil.java") || FILE_ACCESS_OPT_INS.containsKey(name)) continue;
                    if (Files.readString(p).contains("openInMemoryWithFileAccess("))
                        offenders.add(repoRoot().relativize(p).toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(), "these take the unsealed file-access opt-in without a recorded reason"
                + " — declare the directories via openInMemory(spill, dirs) instead: " + offenders);
    }

    @Test
    void thePatternCatchesTheRawOpensAndSparesFileBackedOnes() {
        assertTrue(IN_MEMORY_LITERAL.matcher("DriverManager.getConnection(\"jdbc:duckdb:\");").find());
        assertTrue(IN_MEMORY_LITERAL.matcher("String URL = \"jdbc:duckdb:\";").find());
        assertTrue(IN_MEMORY_LITERAL.matcher("getConnection(\"jdbc:duckdb::memory:\")").find());
        // A NAMED in-memory database is in-memory too, concatenated or not.
        assertTrue(IN_MEMORY_LITERAL.matcher("getConnection(\"jdbc:duckdb::memory:scratch\")").find());
        assertTrue(IN_MEMORY_LITERAL.matcher("getConnection(\"jdbc:duckdb::memory:\" + name)").find());
        assertFalse(IN_MEMORY_LITERAL.matcher("return \"jdbc:duckdb:\" + dir.resolve(file);").find());
        assertFalse(IN_MEMORY_LITERAL.matcher("getConnection(\"jdbc:duckdb:\"+path)").find());
    }

    @Test
    void theScanSeesTheModules() {
        List<Path> roots = mainRoots();
        assertTrue(roots.size() >= 10, "only " + roots.size() + " module source roots resolved — the scan is"
                + " measuring almost nothing; fix the root lookup, do not trust the green");
        assertTrue(roots.stream().anyMatch(r -> r.startsWith(repoRoot().resolve("inspecto-engine"))),
                "the scan does not see inspecto-engine, where most scratch opens live");
    }

    /** Every {@code <module>/src/main/java} directly under the checkout root. */
    private static List<Path> mainRoots() {
        try (Stream<Path> modules = Files.list(repoRoot())) {
            return modules.map(m -> m.resolve("src/main/java")).filter(Files::isDirectory).toList();
        } catch (IOException e) {
            throw new AssertionError("cannot list " + repoRoot(), e);
        }
    }

    /** The checkout root — the nearest ancestor of the working directory that holds the reactor pom. */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent())
            if (Files.exists(dir.resolve("pom.xml")) && Files.isDirectory(dir.resolve("inspecto-util"))) return dir;
        throw new AssertionError("cannot locate the checkout root from " + Path.of("").toAbsolutePath());
    }
}
