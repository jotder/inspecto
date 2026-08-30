package com.gamma.etl;

import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.FieldSpec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * The blocks the parser reads that the spec does not declare, as of 2026-08-31. ⚠ This list may only
     * ever SHRINK. Each entry is a key the engine honours and no generated form can show.
     */
    private static final Set<String> UNDECLARED_BLOCKS = Set.of(
            // ── top-level ────────────────────────────────────────────────────────
            "active",              // the arming switch itself — authored on every runnable pipeline
            "collector",           // the acquisition block (GLOSSARY: Collector)
            "output_store",        // the Stage-2 arming condition (gap 8)
            "route",               // gap 9's block — the branch-aware ingest lane
            "sinks",               // the plural destination block
            "steps",               // the ordered Stage-2 chain (gap 11)
            "template",            // template: true ⇒ never registered, so never runnable
            "trigger",             // schedule / on:dataset
            // ── processing.* ─────────────────────────────────────────────────────
            "processing.dedup",
            "processing.disabled_steps",
            "processing.duplicate_check",
            "processing.ingester_config",
            "processing.join",
            "processing.map",
            "processing.mapping_file",
            "processing.schemas",
            "processing.segments",
            "processing.summarize");

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

    /** Blocks {@code ConfigSpecs.pipeline()} declares: a leaf {@code a.b.c} declares {@code a} and {@code a.b}. */
    private static Set<String> declaredBlocks() {
        Set<String> blocks = new TreeSet<>();
        for (FieldSpec f : ConfigSpecs.pipeline().fields()) {
            String[] parts = f.path().split("\\.");
            blocks.add(parts[0]);
            if (parts.length > 1) blocks.add(parts[0] + "." + parts[1]);
        }
        return blocks;
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
