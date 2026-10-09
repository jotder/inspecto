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
        assertTrue(roots.stream().anyMatch(r -> r.toString().replace('\\', '/').contains("/inspecto-engine/")),
                "the scan does not see inspecto-engine, where most scratch opens live");
    }

    /** Every {@code <module>/src/main/java} under the checkout root, wherever the module sits (repo root or a group directory). */
    private static List<Path> mainRoots() {
        List<Path> roots = new ArrayList<>();
        try {
            Files.walkFileTree(repoRoot(), new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes a) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (name.equals("target") || name.equals("node_modules") || name.startsWith(".") || name.equals("inspecto-ui")
                            || name.equals("spaces") || name.equals("data"))
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    if (name.equals("java") && dir.endsWith(Path.of("src", "main", "java"))) {
                        roots.add(dir);
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new AssertionError("cannot list " + repoRoot(), e);
        }
        return roots;
    }

    /**
     * The checkout root - the nearest ancestor holding .git (a directory in a plain checkout, a file in a git worktree) - OUTERMOST alone resolved to the MAIN checkout when the build ran in a worktree under .claude/worktrees/, so the guard scanned another tree; else the OUTERMOST ancestor whose pom.xml declares the reactor modules.
     */
    private static Path repoRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent())
            if (Files.exists(dir.resolve(".git")) && Files.isRegularFile(dir.resolve("pom.xml"))) return dir;
        Path found = null;
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path pom = dir.resolve("pom.xml");
            try {
                if (Files.isRegularFile(pom) && Files.readString(pom).contains("<modules>")) found = dir;
            } catch (IOException e) {
                throw new AssertionError("cannot read " + pom, e);
            }
        }
        if (found == null) throw new AssertionError("cannot locate the checkout root from " + Path.of("").toAbsolutePath());
        return found;
    }
}
