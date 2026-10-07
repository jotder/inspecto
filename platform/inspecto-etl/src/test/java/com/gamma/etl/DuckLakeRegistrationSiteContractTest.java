package com.gamma.etl;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code DUCKLAKE-COMMIT-COUNT-1} — the catalog registration happens ONLY in the places listed here, so a
 * batch's files are registered exactly once.
 *
 * <p>⚠ It was one place until 2026-09-14 and is now TWO ({@code DUCKLAKE-GRAPH-LANE-1}): the ingest tail,
 * which serves both ingest lanes, and the at-rest pipeline-job lane, which has no tail of its own. The
 * invariant is unchanged — <b>no set of files is registered by more than one site</b> — and the list is the
 * mechanism, not the count.
 *
 * <p><b>Why a source-scanning contract test rather than a behavioural one.</b> The hazard this pins is an
 * implementation-ORDER one, described in {@code superpower/enterprise-scale-out-plan.md} §5.4: that work
 * moves visibility onto the catalog commit, and the natural way to write it is to commit from inside
 * {@code PartitionWriter.reveal} — which runs <b>per file, on both lanes</b>. Doing that while leaving the
 * existing batch-level call in place would register the flat lane's files <b>twice</b>. Nothing in the suite
 * would have failed: {@code DuckLakeRegistrarTest} covers only the no-op, disabled and no-flag branches with
 * {@code assertDoesNotThrow} and asserts no call count at all.
 *
 * <p>The behavioural alternative — write Parquet, register, and assert the catalog table holds N rows rather
 * than 2N — needs the {@code ducklake} DuckDB extension, which is downloaded on first use. That makes it
 * environment-dependent, so it would have to be opt-in behind a system property like this repo's other
 * opt-in suites, and an opt-in test gives <b>no CI protection</b> — which is exactly what this hazard needs.
 * A source invariant runs everywhere, every time, and names the offending file when it breaks.
 *
 * <p>⚠ <b>What this test does NOT prove:</b> that one call is made once per batch <em>at runtime</em>. It
 * proves there is one place from which a registration can be made. If §5.4 introduces a second, legitimate
 * commit site, the fix is to update {@link #EXPECTED_SITES} <em>deliberately</em>, in the same change that
 * removes the old one — not to relax this test.
 *
 * <p>⚠ It matches raw TEXT, so writing {@code DuckLakeRegistrar.register(} inside a comment also trips it.
 * That direction is deliberate: a spurious failure is loud and takes one line to fix, whereas the dangerous
 * direction — a real second call site the scan cannot see — is what text matching cannot miss. Both
 * assertions in this class exist to keep a green run meaningful: the first re-reads the known site so a
 * mistyped root cannot read as "no extra call sites", and {@link #theScannedRootsResolve()} fails when the
 * walk is measuring almost nothing.
 */
class DuckLakeRegistrationSiteContractTest {

    /** The registration entry point every commit site must go through. */
    // ⛔ BOTH entry points. `register(` alone would MISS `registerInto(` — the config-agnostic overload the
    // pipeline-job lane uses — so the guard would have kept passing while silently covering only half the
    // registrations it exists to bound. The trailing paren keeps each match a real call, not a mention.
    private static final List<String> CALLS =
            List.of("DuckLakeRegistrar.register(", "DuckLakeRegistrar.registerInto(");

    private static boolean callsRegistrar(String src) {
        return CALLS.stream().anyMatch(src::contains);
    }

    /**
     * The production call sites, WIDENED FROM ONE TO TWO on 2026-09-14 ({@code DUCKLAKE-GRAPH-LANE-1}).
     *
     * <ol>
     *   <li>{@code ConsignmentIngestor.finalizeSource} — the ingest path's per-batch finalisation. ⚠ This
     *       serves <b>both</b> ingest lanes: {@code writeAndTrace} forks to flat/graph and both return the
     *       same {@code Written} into this one tail. 🔴 A previous version of this comment said "the graph
     *       lane registers nothing at all — that asymmetry is deliberate". <b>That was wrong</b>, and it is
     *       corrected rather than deleted because the same false claim reached four other places.</li>
     *   <li>{@code PipelineJobRunner.registerInLakehouse} — the at-rest pipeline-job lane, which has no
     *       shared tail: it drives {@code PipelineExecutor} directly with a no-op finalizer, so until this
     *       date its Parquet reached no catalog and no other node could see it.</li>
     * </ol>
     *
     * <p>⛔ <b>Two sites is the CEILING, not a licence to add more.</b> The hazard this guard exists for is
     * unchanged: a per-file commit added inside the reveal path while a per-batch call remains would
     * register the same files twice. Adding a third site means proving it cannot overlap the other two.
     */
    private static final List<String> EXPECTED_SITES = List.of(
            "inspecto-engine/src/main/java/com/gamma/inspector/ConsignmentIngestor.java",
            "inspecto-engine/src/main/java/com/gamma/job/PipelineJobRunner.java");

    /** Module main-source roots that could hold a call site. */
    private static final List<String> MAIN_ROOTS = List.of(
            "inspecto/src/main/java", "inspecto-engine/src/main/java", "inspecto-etl/src/main/java",
            "inspecto-acquire/src/main/java", "inspecto-ops/src/main/java", "inspecto-event/src/main/java",
            "inspecto-config/src/main/java", "inspecto-util/src/main/java", "inspecto-sql/src/main/java",
            "inspecto-connectors/src/main/java", "inspecto-processor/src/main/java");

    @Test
    void theCatalogRegistrationHasExactlyItsDeclaredProductionCallSites() throws IOException {
        // The scan must be able to SEE the known site, or a passing run proves nothing (a mistyped root, a
        // renamed file, or a moved module would otherwise read as "zero extra call sites" — a false green).
        for (String site : EXPECTED_SITES) {
            assertTrue(callsRegistrar(Files.readString(repoFile(site))),
                    "the known call site " + site + " no longer calls the registrar — re-anchor this scan"
                            + " before trusting it, and check whether the registration moved. A scan that"
                            + " cannot see a site it knows about reports every OTHER site as absent too.");
        }

        List<String> found = new ArrayList<>();
        for (String root : MAIN_ROOTS) {
            Path dir = repoDirOrNull(root);
            if (dir == null) continue;   // a module absent from this checkout's profile
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                    if (callsRegistrar(Files.readString(p))) found.add(relative(p));
                }
            }
        }

        // ⛔ Set comparison, not size-then-index. Files.walk order across MAIN_ROOTS is not guaranteed, so
        // the old found.get(0) check would have been order-dependent the moment a second site existed.
        assertEquals(Set.copyOf(EXPECTED_SITES), Set.copyOf(found),
                "the catalog registration call sites changed; found " + found + ". An UNEXPECTED site is"
                        + " the §5.4 double-registration hazard: a per-file commit added inside the reveal"
                        + " path while a per-batch call stays would register the same files twice. A MISSING"
                        + " one means a lane stopped registering and its output is now invisible to every"
                        + " other node. Remove it, or update EXPECTED_SITES deliberately.");
    }

    /** At least one scanned root must exist, or the walk proves nothing about the repository. */
    @Test
    void theScannedRootsResolve() {
        long present = MAIN_ROOTS.stream().filter(r -> repoDirOrNull(r) != null).count();
        assertTrue(present >= 3,
                "only " + present + " of the " + MAIN_ROOTS.size() + " scanned source roots resolved —"
                        + " the scan is measuring almost nothing; fix the paths, do not trust the green");
    }

    /**
     * The path as this test declares it — repo-relative, forward slashes.
     *
     * <p>🔴 This used to only swap separators and return an ABSOLUTE path, despite its name. Nothing
     * noticed, because the single-site assertion compared a size and an {@code endsWith}, both of which a
     * full path satisfies. Comparing the found SET against {@link #EXPECTED_SITES} is what exposed it —
     * a stricter assertion finding a weaker helper.
     */
    private static String relative(Path p) {
        String full = p.toString().replace('\\', '/');
        Path root = repoRoot();
        if (root != null) {
            String prefix = root.toString().replace('\\', '/') + "/";
            if (full.startsWith(prefix)) return full.substring(prefix.length());
        }
        return full;
    }

    /** The checkout root — the nearest ancestor of the working directory that holds the reactor pom. */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent())
            if (Files.exists(dir.resolve("pom.xml")) && Files.isDirectory(dir.resolve("inspecto-etl"))) return dir;
        return null;
    }

    private static Path repoDirOrNull(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.isDirectory(candidate)) return candidate;
        }
        return null;
    }

    private static Path repoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new AssertionError("cannot locate " + relative + " from " + Path.of("").toAbsolutePath());
    }
}
