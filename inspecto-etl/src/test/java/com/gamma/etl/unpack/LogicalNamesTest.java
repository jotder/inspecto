package com.gamma.etl.unpack;

import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.FieldSpec;
import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extension-insensitive filename identity (unpack-stage plan §2.3) and its allow-list, now the
 * published {@code processing.unpack.data_extensions} key (§6 Q4, operator 2026-08-26).
 *
 * <p>The two halves of §2.3 are ONE mechanism and this class pins both: stripping the data extension
 * is what lets {@code cdr.csv.gz}, {@code cdr.Z} and bare {@code cdr} be one logical file — and the
 * unavoidable dual is that {@code report.csv} and {@code report.json} are one too. You cannot have
 * the first without the second; the escape hatch is emptying the list.
 */
class LogicalNamesTest {

    // ── the operator's actual requirement (§2.3) ───────────────────────────────

    /**
     * 🔴 The load-bearing case. Three spellings of one delivery must land on ONE key — and they only
     * meet at the FULLY-stripped tier, which is exactly why a "safer" scheme that stopped at the
     * compression-stripped form would break the requirement this feature exists for.
     */
    @Test
    void oneDeliveryUnderThreeSpellingsIsOneLogicalName() {
        String expected = "cdr_20260823";
        assertEquals(expected, LogicalNames.logicalName("cdr_20260823.csv.gz"));
        assertEquals(expected, LogicalNames.logicalName("cdr_20260823.Z"));
        assertEquals(expected, LogicalNames.logicalName("cdr_20260823.csv"));
        assertEquals(expected, LogicalNames.logicalName("cdr_20260823"));
    }

    /** Rule 1 strips compression suffixes ITERATIVELY; rule 2 strips at most ONE data extension. */
    @Test
    void ruleOneIsIterativeAndRuleTwoIsAtMostOnce() {
        assertEquals("data", LogicalNames.logicalName("data.csv.gz.Z"));
        // ⛔ never "everything after the first dot" — a dotted stem must survive intact
        assertEquals("feed.2026.08.23", LogicalNames.logicalName("feed.2026.08.23.csv"));
    }

    /** Rule 3: directories stay in the key — extension-insensitive, never path-insensitive. */
    @Test
    void directoriesStayInTheKey() {
        assertEquals("east/cdr", LogicalNames.logicalName("east/cdr.csv.gz"));
        assertNotEquals(LogicalNames.logicalName("east/cdr.csv"),
                LogicalNames.logicalName("west/cdr.csv"));
    }

    // ── the dual, and the escape hatch (§6 Q4) ─────────────────────────────────

    /**
     * 🔴 The COLLISION, stated as a fact rather than left implicit: two data extensions on the list
     * collapse to one key. This is the inescapable dual of the test above — same strip, same rule.
     */
    @Test
    void twoDataExtensionsOnTheListCollapseToOneKey() {
        assertEquals(LogicalNames.logicalName("report.csv"), LogicalNames.logicalName("report.json"));
    }

    /** An EMPTY allow-list is the deployment's opt-out: rule 2 is skipped, names stay verbatim. */
    @Test
    void anEmptyAllowListOptsOutOfRuleTwo() {
        List<String> none = List.of();
        assertEquals("report.csv",
                LogicalNames.logicalName("report.csv", Decompressors.knownSuffixes(), none));
        assertNotEquals(
                LogicalNames.logicalName("report.csv", Decompressors.knownSuffixes(), none),
                LogicalNames.logicalName("report.json", Decompressors.knownSuffixes(), none));
        // ⚠ The cost of opting out: compression spellings no longer unify either.
        assertNotEquals(
                LogicalNames.logicalName("cdr.csv.gz", Decompressors.knownSuffixes(), none),
                LogicalNames.logicalName("cdr.Z", Decompressors.knownSuffixes(), none));
    }

    /** A narrowed list keeps its own extensions unified and leaves the others verbatim. */
    @Test
    void aNarrowedAllowListStripsOnlyWhatItNames() {
        List<String> onlyCsv = List.of(".csv");
        assertEquals("report", LogicalNames.logicalName("report.csv", Decompressors.knownSuffixes(), onlyCsv));
        assertEquals("report.json",
                LogicalNames.logicalName("report.json", Decompressors.knownSuffixes(), onlyCsv),
                "an extension off the list is NOT stripped, so it stays distinct");
    }

    // ── the configured list actually reaches the engine ────────────────────────

    @Test
    void configuredExtensionsAreParsedNormalisedAndHonoured(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = UnpackFixtures.load(dir,
                "  unpack:\n    data_extensions[2]: \"CSV\", \".tsv \"\n");
        assertEquals(List.of(".csv", ".tsv"), cfg.unpack().dataExtensions(),
                "a bare or upper-case or padded entry is normalised to a leading-dot lower-case form");
        assertEquals("report", LogicalNames.logicalName("report.csv", cfg));
        assertEquals("report.json", LogicalNames.logicalName("report.json", cfg),
                "the CONFIGURED list is what the production call sites use");
    }

    @Test
    void anExplicitlyEmptyListIsHonouredNotTreatedAsUnset(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = UnpackFixtures.load(dir, "  unpack:\n    data_extensions[0]:\n");
        assertEquals(List.of(), cfg.unpack().dataExtensions(),
                "empty is a VALID choice — the opt-out — never 'fall back to the default'");
        assertEquals("report.csv", LogicalNames.logicalName("report.csv", cfg));
    }

    @Test
    void anAbsentKeyKeepsTheShippedList(@TempDir Path dir) throws Exception {
        assertEquals(LogicalNames.DEFAULT_DATA_EXTENSIONS,
                UnpackFixtures.load(dir, "").unpack().dataExtensions());
    }

    // ── the one permitted mirror (⛔ do not add a third) ───────────────────────

    /**
     * ⛔ {@code ConfigSpecs} restates this list because {@code inspecto-config} sits BELOW
     * {@code inspecto-etl} and cannot import {@link LogicalNames}. That makes exactly TWO
     * declarations, and this test is what keeps them equal — a drifted published default would tell
     * an operator the engine does something it does not.
     */
    @Test
    void thePublishedDefaultMatchesTheEnginesOwnList() {
        FieldSpec spec = ConfigSpecs.pipeline().fields().stream()
                .filter(f -> "processing.unpack.data_extensions".equals(f.path()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "processing.unpack.data_extensions is not published — every unpack key must be"));
        assertEquals(LogicalNames.DEFAULT_DATA_EXTENSIONS, spec.defaultValue(),
                "the published default and the engine's list have drifted — update BOTH");
    }
}
