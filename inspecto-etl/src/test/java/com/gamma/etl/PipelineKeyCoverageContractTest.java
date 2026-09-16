package com.gamma.etl;

import com.gamma.config.spec.AcceptedConfigKeys;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Two authorities read the pipeline config, and they disagree</b> — the sixth contract test
 * (pipeline spec §12 Wave 1, gap 10, "the largest structural problem in the current design").
 *
 * <p>{@code PipelineConfigParser} is what the <b>engine</b> reads. {@code ConfigSpecs.pipeline()} is
 * what the product <b>declares</b> — the thing a UI generates a form from, an LLM authors against, and
 * {@code ConfigLoader.validate} judges a draft by. Nothing connected the two, so the parser grew keys
 * the spec never heard of, and the spec published a key the parser had already deprecated. Every
 * consequence of that drift is silent: an author sees no field for a key the engine honours, and a
 * generated form cannot round-trip a config whose shape it did not know.
 *
 * <p><b>This test does not fix the drift — it stops it growing.</b> {@link #UNDECLARED_BLOCKS} is the
 * drift that existed the day it landed, written out one entry at a time. A NEW undeclared block fails
 * immediately; a newly declared one must be <em>removed</em> from the list, or
 * {@link #theAllowListHasNoStaleEntries} fails. The list only ever shrinks, and its size is the honest
 * measure of how much of gap 10 is left.
 *
 * <p><b>The granularity is the BLOCK, deliberately.</b> Reconstructing every dotted leaf path from
 * source would be a heuristic over a dozen nested sub-parsers, and a heuristic census over-reports —
 * a failure mode this project has been bitten by. A block ({@code route:}, {@code processing.dedup}) is
 * a read this test can actually prove from the source, and block level is exactly the granularity gap
 * 10 describes.
 *
 * <p>🔴 <b>Two traps caught while this scan was being written, both of which made it lie quietly.</b>
 * They are pinned by {@link #theScanStillSeesTheParser} and
 * {@link #theOnlyShadowedRawLocalsAreTheKnownTwo} — do not delete those tests, because without them a
 * broken scan reports success:
 * <ol>
 *   <li><b>Comment stripping ate the file.</b> A general non-greedy block-comment strip loses 31k of
 *       the parser's 114k, because a glob string literal contains a slash-star that opens a comment
 *       running to the next star-slash. Eight keys vanished silently. This scan therefore strips only
 *       block comments whose opener starts a line, which is this file's style.</li>
 *   <li><b>A shadowing local named {@code raw}.</b> {@code columnNamesOf} and
 *       {@code requireZoneForTimestampTz} bind {@code raw} to a <em>schema's</em> {@code raw:} block,
 *       so the {@code fields} they read is not a pipeline-root key at all.</li>
 * </ol>
 */
class PipelineKeyCoverageContractTest {

    private static final String PARSER = "inspecto-etl/src/main/java/com/gamma/etl/PipelineConfigParser.java";

    /**
     * The blocks the parser reads that the spec does not declare. ⚠ This list may only ever SHRINK.
     * Each entry is a key the engine honours and no generated form can show.
     *
     * <p>18 when this landed (2026-08-31); <b>17</b> after gap 8 declared {@code output_store} the same
     * day — the ratchet caught that one for real, not in a probe; <b>16</b> after CONSIGNMENT-HOME-1
     * declared {@code collector.consignment.max_files} (2026-09-02).
     *
     * <p>🔴 <b>Moved into production code 2026-09-16 (`DUCKLE-C3-DEAD-PROPERTY-1`)</b> as
     * {@link AcceptedConfigKeys#PARSER_ONLY}, and this test now ratchets THAT field rather than a copy
     * of it. The dead-property checker enforces the same list and the generated accepted-names doc is
     * rendered from it, so the ratchet, the gate and the doc cannot disagree — a second copy here is
     * exactly the drift this class exists to catch.
     */
    private static final Set<String> UNDECLARED_BLOCKS = AcceptedConfigKeys.PARSER_ONLY;

    /**
     * The methods that bind a local {@code raw} to something that is NOT the pipeline root. Pinned by
     * count so a third one fails this test rather than silently widening the scan's idea of a root read.
     */
    private static final List<String> SHADOWED_RAW_SITES =
            List.of("columnNamesOf", "requireZoneForTimestampTz");

    /** Helper shapes the parser reads a section through, e.g. {@code castMapAt(raw, "reference")}. */
    private static final String HELPERS =
            "opt|require|requireSection|str|castMapAt|mapAt|section|strList|trimToNull|first|toInt";

    @Test
    void everyBlockTheParserReadsIsDeclaredOrKnownUndeclared() throws IOException {
        Set<String> declared = declaredBlocks();
        Set<String> undeclared = new TreeSet<>();
        for (String block : blocksTheParserReads())
            if (!declared.contains(block) && !UNDECLARED_BLOCKS.contains(block)) undeclared.add(block);

        assertTrue(undeclared.isEmpty(),
                "PipelineConfigParser reads " + undeclared + ", which ConfigSpecs.pipeline() does not "
                        + "declare. The engine would honour these keys while no form, no draft validation "
                        + "and no LLM author can see them. Declare a FieldSpec for each — or, if the drift "
                        + "is deliberate and understood, add it to UNDECLARED_BLOCKS with the reason.");
    }

    /** The ratchet: a block since declared must leave the allow-list, or the list stops meaning anything. */
    @Test
    void theAllowListHasNoStaleEntries() throws IOException {
        Set<String> declared = declaredBlocks();
        Set<String> read = blocksTheParserReads();
        Set<String> stale = new TreeSet<>();
        for (String block : UNDECLARED_BLOCKS)
            if (declared.contains(block) || !read.contains(block)) stale.add(block);

        assertTrue(stale.isEmpty(),
                "UNDECLARED_BLOCKS still lists " + stale + ", which is now declared (or no longer read). "
                        + "Remove each — the list is the remaining gap-10 debt, and an entry that is no "
                        + "longer true overstates it.");
    }

    /**
     * 🔴 Falsify the scan itself. A scan that silently matches nothing passes every assertion above. This
     * pins blocks that are unambiguously read, so a rename, a re-style, or the comment-strip trap breaks
     * THIS test loudly instead of turning the whole contract into a no-op.
     */
    @Test
    void theScanStillSeesTheParser() throws IOException {
        Set<String> read = blocksTheParserReads();
        for (String certain : List.of("processing", "dirs", "parsing", "reference",
                                      "processing.csv_settings", "processing.schema_file"))
            assertTrue(read.contains(certain),
                    "the scan no longer sees '" + certain + "', which PipelineConfigParser certainly "
                            + "reads — so the scan is broken, not the parser. Re-check the helper shapes "
                            + "and the comment strip before trusting anything else in this class.");
        assertTrue(read.size() >= 35,
                "the scan found only " + read.size() + " blocks; it saw 42 when written. A large drop "
                        + "means the scan stopped matching, not that the parser shrank.");
    }

    /** Guards the over-report: {@code raw} must mean the pipeline root everywhere except the known sites. */
    @Test
    void theOnlyShadowedRawLocalsAreTheKnownTwo() throws IOException {
        String source = Files.readString(repoFile(PARSER));
        Matcher m = Pattern.compile("instanceof\\s+Map<\\?,\\s*\\?>\\s+raw\\b").matcher(source);
        int found = 0;
        while (m.find()) found++;
        assertEquals(SHADOWED_RAW_SITES.size(), found,
                "a local named 'raw' that is not the pipeline root was added or removed. The scan treats "
                        + "every raw.get(\"…\") as a root read, so a new shadow makes it report a key the "
                        + "pipeline config does not have. Re-scope the scan, then update SHADOWED_RAW_SITES.");
    }

    // ── the BREAKING half: no committed pipeline may regress ─────────────────────

    /** The committed sample trees. Both — scoping to one of them is how this was undercounted by a third. */
    private static final List<String> SAMPLE_TREES = List.of("spaces", "inspecto/examples");

    /**
     * 🔴 <b>No committed {@code *_pipeline.toon} may be refused by the census that judges it.</b>
     *
     * <p>This test did not exist when the dead-property checker shipped ({@code 2c310d1c}), and the
     * omission cost exactly what it was worth: all 36 committed samples carried a top-level
     * {@code version:} that {@code ConfigSpecs.pipeline()} has never declared and no component reads,
     * so re-saving ANY shipped sample through {@code POST /config/write} answered 422
     * {@code ERR_UNKNOWN_CONFIG_KEY} from that commit until {@code PIPELINE-SAMPLES-CARRY-DEAD-VERSION-1}
     * stripped the key. {@code meta} escaped the same fate only because {@code ConfigSpecs.meta()}
     * happens to declare {@code version}. A key added to the census, a key dropped from a spec, or a new
     * sample authored against an older convention all land here now instead of in a 422 nobody drives.
     *
     * <p>⚠ The sweep WALKS the trees rather than naming files: a list would go stale the first time a
     * sample was added, which is the half of the risk a hand-written list cannot cover.
     */
    @Test
    void everyCommittedPipelineConfigSurvivesTheCensus() throws IOException {
        List<Path> samples = committedSamples();
        List<String> refused = new ArrayList<>();
        for (Path sample : samples) {
            Map<String, Object> raw = ToonHelper.load(sample.toString());
            List<Finding> findings = AcceptedConfigKeys.unknownKeyFindings("pipeline", raw, Severity.ERROR);
            if (!findings.isEmpty()) refused.add(sample + " -> " + findings);
        }
        assertTrue(refused.isEmpty(),
                "a committed sample pipeline would be refused by its own census — re-saving it through "
                        + "/config/write answers 422. Either the key is dead and belongs out of the "
                        + "sample, or something reads it and ConfigSpecs.pipeline() must declare it:\n  "
                        + String.join("\n  ", refused));
    }

    /**
     * 🔴 Falsify the sweep. A walk that finds nothing passes the test above while gating nothing — this
     * repo has shipped at least three probes that reported "absent" because they could not return a hit.
     */
    @Test
    void theSweepStillSeesTheCommittedSamples() throws IOException {
        List<Path> samples = committedSamples();
        assertTrue(samples.size() >= 36,
                "the sweep found only " + samples.size() + " committed *_pipeline.toon files; there were "
                        + "36 when this landed (24 under spaces/, 12 under inspecto/examples/). A large "
                        + "drop means the walk stopped finding the trees, not that the corpus shrank.");
        for (String tree : SAMPLE_TREES)
            assertTrue(samples.stream().anyMatch(p -> p.toString().replace('\\', '/').contains("/" + tree + "/")),
                    "the sweep found no sample under '" + tree + "' — that tree is not being gated.");
    }

    /**
     * Every committed {@code *_pipeline.toon} under {@link #SAMPLE_TREES}.
     *
     * <p>⚠ {@code spaces/uat} and {@code spaces/_shared} are skipped: both are gitignored runtime state
     * ({@code tools/seed-uat.ps1} clones the former from {@code spaces/demo}), so in a working tree that
     * has been seeded they would make this gate red for something nobody committed. Same exclusion, same
     * reason, as {@code RepoSpacesConfigValidationTest}.
     */
    private static List<Path> committedSamples() throws IOException {
        List<Path> out = new ArrayList<>();
        for (String tree : SAMPLE_TREES) {
            Path root = repoFile(tree);
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("_pipeline.toon"))
                    .filter(p -> { String s = root.relativize(p).toString().replace('\\', '/');
                                   return !s.startsWith("uat/") && !s.startsWith("_shared/"); })
                    .sorted()
                    .forEach(out::add);
            }
        }
        return out;
    }

    // ── the scan ─────────────────────────────────────────────────────────────────

    /** Every top-level and {@code processing.*} block the parser reads. */
    private static Set<String> blocksTheParserReads() throws IOException {
        String source = stripComments(Files.readString(repoFile(PARSER)));
        Set<String> out = new LinkedHashSet<>();
        for (String key : keysReadFrom(source, "raw"))
            if (!isSchemaRawKey(key)) out.add(key);
        for (String key : keysReadFrom(source, "proc")) out.add("processing." + key);
        return out;
    }

    /**
     * {@code fields} is the schema's {@code raw.fields}, reached through the shadowing locals pinned by
     * {@link #theOnlyShadowedRawLocalsAreTheKnownTwo} — never a pipeline-root key.
     */
    private static boolean isSchemaRawKey(String key) {
        return "fields".equals(key);
    }

    private static Set<String> keysReadFrom(String source, String var) {
        Set<String> keys = new TreeSet<>();
        for (String regex : List.of(
                "\\b" + var + "\\.(?:get|getOrDefault|containsKey)\\(\\s*\"([a-z_0-9]+)\"",
                "(?:" + HELPERS + ")\\(\\s*" + var + "\\s*,\\s*\"([a-z_0-9]+)\"")) {
            Matcher m = Pattern.compile(regex).matcher(source);
            while (m.find()) keys.add(m.group(1));
        }
        return keys;
    }

    /**
     * ⚠ Strips ONLY block comments whose opener starts a line, plus line comments. A general block
     * strip is wrong here — see trap 1 on the class.
     */
    private static String stripComments(String source) {
        String out = source.replaceAll("(?ms)^[ \\t]*/\\*.*?\\*/[ \\t]*\\r?\\n?", "");
        return out.replaceAll("//[^\\n]*", "");
    }

    /** Blocks {@code ConfigSpecs.pipeline()} declares: a leaf {@code a.b.c} declares {@code a} and {@code a.b}.
     *  The derivation moved to {@link AcceptedConfigKeys#declaredBlocks} 2026-09-16 so the checker and
     *  this ratchet judge "declared" identically. */
    private static Set<String> declaredBlocks() {
        return AcceptedConfigKeys.declaredBlocks(ConfigSpecs.pipeline());
    }

    /** Walk up from the module's CWD to the repo root, so the path works under surefire and an IDE alike. */
    private static Path repoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new AssertionError("cannot locate " + relative + " from " + Path.of("").toAbsolutePath());
    }
}
