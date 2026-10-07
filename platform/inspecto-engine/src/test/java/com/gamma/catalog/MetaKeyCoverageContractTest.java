package com.gamma.catalog;

import com.gamma.config.spec.AcceptedConfigKeys;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The {@code meta} config type's parser census</b> — the third type to get one
 * ({@code DUCKLE-C3-DEAD-PROPERTY-1}), after {@code pipeline} and {@code alert}.
 *
 * <p>The dead-property checker refuses a key no component reads, and it may only do that for a type
 * whose reads can be PROVEN from source: an accepted set derived from {@code ConfigSpecs} alone is
 * unsound, because a hand-written parser reads keys the spec never declared and refusing those would
 * break configs that run correctly today. {@code SemanticModel.load} answers that objection at the
 * granularity the checker actually uses — the TOP-LEVEL block — with five literal
 * {@code raw.get("…")} reads and no dynamic access over {@code raw}.
 *
 * <p>🔴 <b>The nesting is where {@code meta} differs from {@code alert}, and it is why
 * {@link #theParserDoesNotIterateTheTopLevelDynamically} matters.</b> {@code SemanticModel.load} DOES
 * call {@code entrySet()} — three times — but one level down, over {@code tables}, {@code kpis} and
 * {@code reports}, whose keys are names the AUTHOR invents. That is exactly why
 * {@code censusedParents("meta")} is empty: the census stops at the top level, and a scan that only
 * asked "does this file contain entrySet?" would have refused the type for the wrong reason.
 *
 * <p>🔴 <b>{@link #theScanStillSeesTheParser} is not optional.</b> A scan that quietly matches nothing
 * passes every other assertion in this class.
 */
class MetaKeyCoverageContractTest {

    private static final String PARSER =
            "inspecto-engine/src/main/java/com/gamma/catalog/SemanticModel.java";

    /** The top-level keys {@code SemanticModel.load} reads off the loaded config map. */
    private static Set<String> keysTheParserReads() throws IOException {
        String source = Files.readString(repoFile(PARSER)).replaceAll("//[^\\n]*", "");
        Set<String> keys = new TreeSet<>();
        Matcher m = Pattern.compile("\\braw\\.get(?:OrDefault)?\\(\\s*\"([a-zA-Z_0-9]+)\"").matcher(source);
        while (m.find()) keys.add(m.group(1));
        return keys;
    }

    @Test
    void everyKeyTheParserReadsIsDeclared() throws IOException {
        Set<String> declared = AcceptedConfigKeys.declaredBlocks(ConfigSpecs.meta());
        Set<String> undeclared = new TreeSet<>();
        for (String key : keysTheParserReads()) if (!declared.contains(key)) undeclared.add(key);

        assertTrue(undeclared.isEmpty(),
                "SemanticModel.load reads " + undeclared + ", which ConfigSpecs.meta() does not declare. "
                        + "The dead-property checker would refuse a key the engine actually honours — "
                        + "`meta` has NO parser-only list precisely because this set has always been "
                        + "empty. Declare a FieldSpec for each, or give the type one.");
    }

    /**
     * 🔴 The soundness condition for censusing the TOP LEVEL: nothing reads the root map by iteration.
     * The three {@code entrySet()} calls in this parser are all one level down and are what
     * {@code censusedParents("meta")} deliberately leaves out.
     */
    @Test
    void theParserDoesNotIterateTheTopLevelDynamically() throws IOException {
        String source = Files.readString(repoFile(PARSER)).replaceAll("//[^\\n]*", "");
        Matcher m = Pattern.compile("\\braw\\.(keySet|entrySet|forEach|values)\\b").matcher(source);
        assertTrue(!m.find(),
                "SemanticModel.load now iterates the top-level config map dynamically, so its reads are "
                        + "no longer enumerable and the `meta` census is unsound. Remove the `meta` case "
                        + "from AcceptedConfigKeys.acceptedBlocks, or keep the top level literal.");
    }

    /** 🔴 Falsify the scan itself — without this, a broken regex reports success above. */
    @Test
    void theScanStillSeesTheParser() throws IOException {
        Set<String> read = keysTheParserReads();
        for (String certain : List.of("name", "tables", "kpis", "reports", "domain"))
            assertTrue(read.contains(certain),
                    "the scan no longer sees '" + certain + "', which SemanticModel.load certainly reads "
                            + "— so the scan is broken, not the parser.");
    }

    /** The BREAKING half: no committed {@code *_meta.toon} may start failing under the new refusal. */
    @Test
    void everyCommittedMetaConfigSurvivesTheCensus() throws IOException {
        for (String file : List.of("spaces/default/config/events/events_meta.toon",
                                   "spaces/demo/config/orders/orders_meta.toon")) {
            Map<String, Object> raw = ToonHelper.load(repoFile(file).toString());
            List<Finding> findings = AcceptedConfigKeys.unknownKeyFindings("meta", raw, Severity.ERROR);
            assertTrue(findings.isEmpty(), file + " would now be refused by its own census: " + findings);
        }
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
