package com.gamma.enrich;

import com.gamma.config.spec.AcceptedConfigKeys;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The {@code enrichment} config type's parser census</b> — the fourth type to get one
 * ({@code DUCKLE-C3-DEAD-PROPERTY-1}), after {@code pipeline}, {@code alert} and {@code meta}.
 *
 * <p>The dead-property checker refuses a key no component reads, and it may only do that for a type
 * whose reads can be PROVEN from source: an accepted set derived from {@code ConfigSpecs} alone is
 * unsound, because a hand-written parser reads keys the spec never declared and refusing those would
 * break configs that run correctly today. {@code EnrichmentConfig} answers that objection at the two
 * granularities the checker uses — the TOP LEVEL, and one level down inside {@code input},
 * {@code output} and {@code triggers} — with literal key reads and no dynamic access over either.
 *
 * <p>🔴 <b>{@code references} is where enrichment looks like {@code meta}.</b> {@code fromMap} DOES
 * call {@code entrySet()}, over the {@code references} map, whose keys are view names the AUTHOR
 * invents. That is exactly why {@code references} is accepted WHOLE and is not a censused parent:
 * descending would refuse every reference anyone names. ⛔ A scan that only asked "does this file
 * contain {@code entrySet}?" would have refused the type for the wrong reason.
 *
 * <p>🔴 <b>{@link #theScanStillSeesTheParser} and {@link #theScanStillSeesTheNestedReads} are not
 * optional.</b> A scan that quietly matches nothing passes every other assertion in this class.
 */
class EnrichmentKeyCoverageContractTest {

    private static final String PARSER =
            "inspecto-engine/src/main/java/com/gamma/enrich/EnrichmentConfig.java";

    /** The parser locals that hold the three censused sub-blocks, in {@code EnrichmentConfig.fromMap}. */
    private static final Map<String, String> NESTED_LOCALS =
            Map.of("input", "in", "output", "out", "triggers", "tr");

    private static String parserSource() throws IOException {
        return Files.readString(repoFile(PARSER)).replaceAll("//[^\\n]*", "");
    }

    /**
     * The TOP-LEVEL keys the parser reads off the decoded config map — both spellings it uses:
     * {@code raw.get("…")} and {@code ToonHelper.requireSection(raw, "…")}.
     */
    private static Set<String> keysTheParserReads() throws IOException {
        String source = parserSource();
        Set<String> keys = new TreeSet<>();
        Matcher direct = Pattern.compile("\\braw\\.get(?:OrDefault)?\\(\\s*\"([a-zA-Z_0-9]+)\"").matcher(source);
        while (direct.find()) keys.add(direct.group(1));
        Matcher section = Pattern.compile("requireSection\\(\\s*raw\\s*,\\s*\"([a-zA-Z_0-9]+)\"").matcher(source);
        while (section.find()) keys.add(section.group(1));
        return keys;
    }

    /**
     * The leaves the parser reads off one censused sub-block, via that block's local — both spellings
     * it uses: {@code local.get("…")}/{@code getOrDefault} and the {@code req(local, "…", …)} helper.
     *
     * <p>⚠ The {@code req(…)} half is not decoration: {@code input.database} and {@code output.database}
     * are read ONLY through it, and a scan that saw just {@code local.get(…)} missed them — which
     * {@link #theScanStillSeesTheNestedReads} caught, and is why that test exists.
     */
    private static Set<String> leavesTheParserReads(String local) throws IOException {
        String source = parserSource();
        Set<String> keys = new TreeSet<>();
        Matcher direct = Pattern.compile("\\b" + local + "\\.get(?:OrDefault)?\\(\\s*\"([a-zA-Z_0-9]+)\"")
                .matcher(source);
        while (direct.find()) keys.add(direct.group(1));
        Matcher helper = Pattern.compile("\\breq\\(\\s*" + local + "\\s*,\\s*\"([a-zA-Z_0-9]+)\"")
                .matcher(source);
        while (helper.find()) keys.add(helper.group(1));
        return keys;
    }

    // ── the ratchet ──────────────────────────────────────────────────────────────

    @Test
    void everyTopLevelKeyTheParserReadsIsDeclaredOrListed() throws IOException {
        Set<String> declared = AcceptedConfigKeys.declaredBlocks(ConfigSpecs.enrichment());
        Set<String> unaccounted = new TreeSet<>();
        for (String key : keysTheParserReads())
            if (!declared.contains(key) && !AcceptedConfigKeys.ENRICHMENT_PARSER_ONLY.contains(key))
                unaccounted.add(key);

        assertTrue(unaccounted.isEmpty(),
                "EnrichmentConfig reads top-level " + unaccounted + ", which neither "
                        + "ConfigSpecs.enrichment() declares nor ENRICHMENT_PARSER_ONLY lists. The "
                        + "dead-property checker would refuse a key the engine actually honours — "
                        + "declare a FieldSpec for it, or add it to ENRICHMENT_PARSER_ONLY with the reason.");
    }

    /** ⚠ {@code ENRICHMENT_PARSER_ONLY} may only ever SHRINK: a block on it must still be undeclared. */
    @Test
    void theParserOnlyListHasNoStaleEntries() throws IOException {
        Set<String> declared = AcceptedConfigKeys.declaredBlocks(ConfigSpecs.enrichment());
        Set<String> read = keysTheParserReads();
        Set<String> stale = new TreeSet<>();
        for (String key : AcceptedConfigKeys.ENRICHMENT_PARSER_ONLY)
            if (declared.contains(key) || !read.contains(key)) stale.add(key);

        assertTrue(stale.isEmpty(),
                "ENRICHMENT_PARSER_ONLY still lists " + stale + ", which is now declared (or no longer "
                        + "read). Remove it — the list is the remaining spec gap, not a permanent allowance.");
    }

    /**
     * The soundness condition for DESCENDING into {@code input}/{@code output}/{@code triggers}: every
     * leaf the parser reads there is spec-declared, so the descent cannot refuse a key the engine reads.
     */
    @Test
    void everyNestedLeafTheParserReadsIsDeclared() throws IOException {
        Set<String> accepted = AcceptedConfigKeys.acceptedBlocks("enrichment");
        Set<String> undeclared = new TreeSet<>();
        for (Map.Entry<String, String> e : NESTED_LOCALS.entrySet())
            for (String leaf : leavesTheParserReads(e.getValue()))
                if (!accepted.contains(e.getKey() + "." + leaf)) undeclared.add(e.getKey() + "." + leaf);

        assertTrue(undeclared.isEmpty(),
                "EnrichmentConfig.fromMap reads " + undeclared + " inside a CENSUSED parent, and "
                        + "ConfigSpecs.enrichment() does not declare it — so the checker would refuse a "
                        + "leaf the engine honours. Declare it, or drop that parent from "
                        + "AcceptedConfigKeys.censusedParents(\"enrichment\").");
    }

    /**
     * 🔴 The soundness condition for censusing the TOP LEVEL: nothing reads the root map by iteration.
     * The one {@code entrySet()} in this parser is over {@code references}, one level down and over
     * author-invented view names — which is exactly why {@code references} is accepted whole.
     */
    @Test
    void theParserDoesNotIterateTheTopLevelDynamically() throws IOException {
        Matcher m = Pattern.compile("\\braw\\.(keySet|entrySet|forEach|values)\\b").matcher(parserSource());
        assertFalse(m.find(),
                "EnrichmentConfig now iterates the top-level config map dynamically, so its reads are no "
                        + "longer enumerable and the `enrichment` census is unsound. Remove the "
                        + "`enrichment` case from AcceptedConfigKeys.acceptedBlocks, or keep the top "
                        + "level literal.");
    }

    /** The same condition for each censused parent: a dynamic read there makes the DESCENT unsound. */
    @Test
    void theParserDoesNotIterateACensusedSubBlockDynamically() throws IOException {
        String source = parserSource();
        for (String local : NESTED_LOCALS.values()) {
            Matcher m = Pattern.compile("\\b" + local + "\\.(keySet|entrySet|forEach|values)\\b").matcher(source);
            assertFalse(m.find(),
                    "EnrichmentConfig.fromMap now iterates `" + local + "` dynamically, so that block's "
                            + "leaves are no longer enumerable. Drop the block from "
                            + "AcceptedConfigKeys.censusedParents(\"enrichment\").");
        }
    }

    // ── falsify the scan ─────────────────────────────────────────────────────────

    /** 🔴 Falsify the scan itself — without this, a broken regex reports success above. */
    @Test
    void theScanStillSeesTheParser() throws IOException {
        Set<String> read = keysTheParserReads();
        for (String certain : List.of("name", "transform", "transform_file", "references", "triggers",
                                      "input", "output"))
            assertTrue(read.contains(certain),
                    "the scan no longer sees '" + certain + "', which EnrichmentConfig certainly reads "
                            + "off the root map — so the scan is broken, not the parser.");
    }

    /** The nested scan needs the same proof: a local that stops matching would report "no leaves". */
    @Test
    void theScanStillSeesTheNestedReads() throws IOException {
        assertTrue(leavesTheParserReads("in").containsAll(Set.of("database", "format", "partitions")),
                "the scan no longer sees the input.* reads — it is broken, not the parser.");
        assertTrue(leavesTheParserReads("out").containsAll(Set.of("database", "format", "compression", "partitions")),
                "the scan no longer sees the output.* reads — it is broken, not the parser.");
        assertTrue(leavesTheParserReads("tr").containsAll(Set.of("on_pipeline", "schedule_seconds")),
                "the scan no longer sees the triggers.* reads — it is broken, not the parser.");
    }

    // ── the checker's behaviour on this type ─────────────────────────────────────

    @Test
    void theTypeHasACensusAndReferencesIsAcceptedWhole() {
        assertTrue(AcceptedConfigKeys.hasCensus("enrichment"));
        // An author-named reference view must not be refused: `references` is accepted whole.
        Map<String, Object> raw = draft();
        assertTrue(AcceptedConfigKeys.unknownKeyFindings("enrichment", raw, Severity.ERROR).isEmpty(),
                "a well-formed enrichment draft was refused: "
                        + AcceptedConfigKeys.unknownKeyFindings("enrichment", raw, Severity.ERROR));
    }

    @Test
    void aTopLevelKeyNoComponentReadsIsRefusedWithASuggestion() {
        Map<String, Object> raw = draft();
        raw.put("transfrom", "SELECT 1");                 // typo of `transform`
        List<Finding> findings = AcceptedConfigKeys.unknownKeyFindings("enrichment", raw, Severity.ERROR);
        assertEquals(1, findings.size(), "expected exactly one finding, got " + findings);
        assertEquals("transfrom", findings.getFirst().fieldPath());
        assertTrue(findings.getFirst().guidance().contains("'transform'"),
                "expected a near-name suggestion, got: " + findings.getFirst().guidance());
    }

    /** The descent is the half that matters: an enrichment's real settings live under these blocks. */
    @Test
    void aLeafNoComponentReadsInsideACensusedBlockIsRefused() {
        Map<String, Object> raw = draft();
        nested(raw, "input").put("databse", "x");          // typo of `input.database`
        nested(raw, "triggers").put("on_pipelines", "p");  // typo of `triggers.on_pipeline`
        Set<String> paths = new TreeSet<>();
        for (Finding f : AcceptedConfigKeys.unknownKeyFindings("enrichment", raw, Severity.ERROR))
            paths.add(f.fieldPath());
        assertEquals(Set.of("input.databse", "triggers.on_pipelines"), paths);
    }

    /** An `x-` block is the author's own annotation and passes through untouched, at both levels. */
    @Test
    void anExtensionBlockIsAcceptedAtBothLevels() {
        Map<String, Object> raw = draft();
        raw.put("x-owner", "analytics");
        nested(raw, "output").put("x-note", "keep");
        assertTrue(AcceptedConfigKeys.unknownKeyFindings("enrichment", raw, Severity.ERROR).isEmpty(),
                "an x- block was refused: "
                        + AcceptedConfigKeys.unknownKeyFindings("enrichment", raw, Severity.ERROR));
    }

    /** The BREAKING half: no committed {@code *_enrich.toon} may start failing under the new refusal. */
    @Test
    void everyCommittedEnrichmentConfigSurvivesTheCensus() throws IOException {
        for (String file : List.of("spaces/demo/config/orders/orders_daily_enrich.toon")) {
            Map<String, Object> raw = ToonHelper.load(repoFile(file).toString());
            List<Finding> findings = AcceptedConfigKeys.unknownKeyFindings("enrichment", raw, Severity.ERROR);
            assertTrue(findings.isEmpty(), file + " would now be refused by its own census: " + findings);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** The shape of the one committed enrichment, built by hand so the unit cases do not need the file. */
    private static Map<String, Object> draft() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "ORDERS_DAILY");
        raw.put("input", new LinkedHashMap<>(Map.of("database", "db", "format", "PARQUET",
                "partitions", List.of("year"))));
        raw.put("references", new LinkedHashMap<>(Map.of(
                "region_dim", new LinkedHashMap<>(Map.of("path", "ref/region_dim.csv", "format", "CSV")))));
        raw.put("output", new LinkedHashMap<>(Map.of("database", "out", "format", "PARQUET",
                "compression", "snappy", "partitions", List.of("year"))));
        raw.put("triggers", new LinkedHashMap<>(Map.of("on_pipeline", "orders", "schedule_seconds", 3600)));
        raw.put("transform", "SELECT 1");
        return raw;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> raw, String key) {
        return (Map<String, Object>) raw.get(key);
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
