package com.gamma.alert;

import com.gamma.config.spec.AcceptedConfigKeys;
import com.gamma.config.spec.ConfigSpecs;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The {@code alert} config type's parser census</b> — the second type to get one
 * ({@code DUCKLE-C3-DEAD-PROPERTY-1}), after {@code pipeline}.
 *
 * <p>The dead-property checker refuses a key no component reads. It could only do that for
 * {@code pipeline} because only {@code pipeline} had a census: an accepted set derived from
 * {@code ConfigSpecs} ALONE is unsound, since a hand-written parser reads keys the spec never
 * declared, and refusing those would break configs that run correctly today. That objection is
 * answerable exactly where the parser's reads can be PROVEN from source — and
 * {@code AlertRule.fromMap} is the easiest case in the codebase: one flat block of literal
 * {@code alert.get("…")} calls, no dynamic key access, no nested sub-parsers.
 *
 * <p>This test proves it, the same way {@code PipelineKeyCoverageContractTest} does for the pipeline:
 * scan the source, compare what the parser reads against what the spec declares, and require the
 * difference to be exactly {@link AcceptedConfigKeys#ALERT_PARSER_ONLY}. The checker enforces that
 * same field, so the gate and this ratchet cannot disagree.
 *
 * <p>🔴 <b>{@link #theScanStillSeesTheParser} is not optional.</b> A scan that quietly matches nothing
 * passes every other assertion in this class. That test pins keys {@code fromMap} certainly reads, so
 * a rename or a re-style breaks the scan loudly instead of turning this contract into a no-op.
 */
class AlertKeyCoverageContractTest {

    private static final String PARSER =
            "inspecto-engine/src/main/java/com/gamma/alert/AlertRule.java";

    /** The {@code alert.*} leaves the parser reads, as the spec-relative dotted paths the checker uses. */
    private static Set<String> keysTheParserReads() throws IOException {
        String source = Files.readString(repoFile(PARSER)).replaceAll("//[^\\n]*", "");
        Set<String> keys = new TreeSet<>();
        Matcher m = Pattern.compile("\\balert\\.get\\(\\s*\"([a-zA-Z_0-9]+)\"").matcher(source);
        while (m.find()) keys.add("alert." + m.group(1));
        return keys;
    }

    private static Set<String> declaredBlocks() {
        return AcceptedConfigKeys.declaredBlocks(ConfigSpecs.alert());
    }

    @Test
    void everyKeyTheParserReadsIsDeclaredOrKnownUndeclared() throws IOException {
        Set<String> declared = declaredBlocks();
        Set<String> undeclared = new TreeSet<>();
        for (String key : keysTheParserReads())
            if (!declared.contains(key) && !AcceptedConfigKeys.ALERT_PARSER_ONLY.contains(key))
                undeclared.add(key);

        assertTrue(undeclared.isEmpty(),
                "AlertRule.fromMap reads " + undeclared + ", which ConfigSpecs.alert() does not declare "
                        + "and ALERT_PARSER_ONLY does not list. The dead-property checker would refuse a "
                        + "key the engine actually honours. Declare a FieldSpec for each — or, if the "
                        + "drift is deliberate, add it to ALERT_PARSER_ONLY with the reason.");
    }

    /** The ratchet: a key since declared must leave the list, or the list stops meaning anything. */
    @Test
    void theParserOnlyListHasNoStaleEntries() throws IOException {
        Set<String> declared = declaredBlocks();
        Set<String> read = keysTheParserReads();
        Set<String> stale = new TreeSet<>();
        for (String key : AcceptedConfigKeys.ALERT_PARSER_ONLY)
            if (declared.contains(key) || !read.contains(key)) stale.add(key);

        assertTrue(stale.isEmpty(),
                "ALERT_PARSER_ONLY still lists " + stale + ", which is now declared (or no longer read). "
                        + "Remove each — an entry that is no longer true overstates the drift, and a key "
                        + "left here after the spec declares it is accepted twice for two reasons.");
    }

    /**
     * 🔴 Falsify the scan itself. Without this, a broken regex reports success on both tests above.
     */
    @Test
    void theScanStillSeesTheParser() throws IOException {
        Set<String> read = keysTheParserReads();
        for (String certain : List.of("alert.name", "alert.metric", "alert.comparator",
                                      "alert.threshold", "alert.window", "alert.severity",
                                      "alert.onPipeline", "alert.dataset", "alert.measure", "alert.when"))
            assertTrue(read.contains(certain),
                    "the scan no longer sees '" + certain + "', which AlertRule.fromMap certainly reads "
                            + "— so the scan is broken, not the parser. Re-check the regex before "
                            + "trusting anything else in this class.");
    }

    /** Walk up from the module's CWD to the repo root, so the path works under surefire and an IDE alike. */
    private static Path repoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("could not locate " + relative + " from " + Path.of("").toAbsolutePath());
    }
}
