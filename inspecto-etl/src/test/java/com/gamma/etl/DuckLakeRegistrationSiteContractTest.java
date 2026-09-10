package com.gamma.etl;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code DUCKLAKE-COMMIT-COUNT-1} — the catalog registration happens in exactly ONE place, so a batch's
 * files are registered exactly once.
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
    private static final String CALL = "DuckLakeRegistrar.register(";

    /**
     * The one production call site today: the flat ingest lane's per-batch finalisation. ⛔ The graph lane
     * registers nothing at all — that asymmetry is deliberate and is itself part of §5.4's scope.
     */
    private static final List<String> EXPECTED_SITES =
            List.of("inspecto-engine/src/main/java/com/gamma/inspector/ConsignmentIngestor.java");

    /** Module main-source roots that could hold a call site. */
    private static final List<String> MAIN_ROOTS = List.of(
            "inspecto/src/main/java", "inspecto-engine/src/main/java", "inspecto-etl/src/main/java",
            "inspecto-acquire/src/main/java", "inspecto-ops/src/main/java", "inspecto-event/src/main/java",
            "inspecto-config/src/main/java", "inspecto-util/src/main/java", "inspecto-sql/src/main/java",
            "inspecto-connectors/src/main/java", "inspecto-processor/src/main/java");

    @Test
    void theCatalogRegistrationHasExactlyOneProductionCallSite() throws IOException {
        // The scan must be able to SEE the known site, or a passing run proves nothing (a mistyped root, a
        // renamed file, or a moved module would otherwise read as "zero extra call sites" — a false green).
        Path known = repoFile(EXPECTED_SITES.get(0));
        assertTrue(Files.readString(known).contains(CALL),
                "the known call site no longer contains " + CALL
                        + " — re-anchor this scan before trusting it, and check whether the registration moved");

        List<String> found = new ArrayList<>();
        for (String root : MAIN_ROOTS) {
            Path dir = repoDirOrNull(root);
            if (dir == null) continue;   // a module absent from this checkout's profile
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                    if (Files.readString(p).contains(CALL)) found.add(relative(p));
                }
            }
        }

        assertEquals(EXPECTED_SITES.size(), found.size(),
                "the catalog registration must have exactly one production call site; found " + found
                        + ". A SECOND site is the §5.4 double-registration hazard: a per-file commit added"
                        + " inside the reveal path while the per-batch call stays would register the flat"
                        + " lane's files twice. Remove one, or update EXPECTED_SITES deliberately.");
        assertTrue(found.get(0).endsWith("ConsignmentIngestor.java"),
                "the single call site moved to " + found.get(0) + " — confirm that is intended, then update"
                        + " EXPECTED_SITES");
    }

    /** At least one scanned root must exist, or the walk proves nothing about the repository. */
    @Test
    void theScannedRootsResolve() {
        long present = MAIN_ROOTS.stream().filter(r -> repoDirOrNull(r) != null).count();
        assertTrue(present >= 3,
                "only " + present + " of the " + MAIN_ROOTS.size() + " scanned source roots resolved —"
                        + " the scan is measuring almost nothing; fix the paths, do not trust the green");
    }

    private static String relative(Path p) {
        return p.toString().replace('\\', '/');
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
