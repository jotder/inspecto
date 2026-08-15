package com.gamma.pipeline;

import com.gamma.pipeline.exec.RowShaper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Two sides, one committed vocabulary</b> — the {@code b061ff41} idiom applied to map-node config.
 *
 * <p>{@code PipelineEditable} decides what a map node's config means: {@link PipelineEditable#MAP_AUTHORED}
 * lowers to {@code processing.map}, {@link PipelineEditable#MAP_DERIVED} is dropped, and anything else
 * refuses. {@code RowShaper} decides what a map node's config <b>does</b>. Nothing but this test connects
 * them, and the failure mode when they drift is the exact one AUTHOR-1(a) was filed for: a key the
 * executor honours, that the save silently drops.
 *
 * <p>So this asserts the split covers {@link RowShaper#MAP_NODE_CONFIG_KEYS} exactly — and, because a
 * constant can be updated without updating the code it claims to describe, it also reads RowShaper's own
 * source and asserts the constant matches the {@code node.cfg("…")} reads on the map path.
 */
class MapNodeKeyContractTest {

    private static final String ROW_SHAPER = "inspecto-engine/src/main/java/com/gamma/pipeline/exec/RowShaper.java";

    /**
     * The map-path region of RowShaper: {@code columnsOf} (the projection), {@code csvSettingsOf} and
     * {@code formatList} (the settings it compiles with), and {@code mappingSchemaOf} (the rules). It ends
     * at {@code toSelect}, the first method after them that is about something else. Scoping the scan
     * matters both ways — the whole file reads {@code branches}/{@code keys}/{@code where}/… for OTHER
     * node types, and a method-scoped probe would have missed the sibling readers (PROJECT_NOTES §4).
     */
    private static final String REGION_START = "private static List<?> columnsOf(";
    private static final String REGION_END = "public static Optional<String> toSelect(";

    private static final Pattern CFG_READ = Pattern.compile("node\\.cfg\\(\"([^\"]+)\"\\)");

    @Test
    void theLoweringSplitCoversEveryExecutableMapKey() {
        Set<String> split = new TreeSet<>(PipelineEditable.MAP_AUTHORED);
        split.addAll(PipelineEditable.MAP_DERIVED);

        assertEquals(new TreeSet<>(RowShaper.MAP_NODE_CONFIG_KEYS), split,
                "every key RowShaper executes on a map node must be classified as authored (lowered) or "
                        + "derived (dropped) — an unclassified one is silently discarded on save");
    }

    @Test
    void authoredAndDerivedDoNotOverlap() {
        Set<String> both = new HashSet<>(PipelineEditable.MAP_AUTHORED);
        both.retainAll(PipelineEditable.MAP_DERIVED);
        assertTrue(both.isEmpty(), "a key cannot be both lowered and dropped, got " + both);
    }

    /** The constant is not self-certifying: check it against the reads in RowShaper's map-path methods. */
    @Test
    void theConstantMatchesWhatRowShaperActuallyReads() throws IOException {
        String source = Files.readString(repoFile(ROW_SHAPER));
        int from = source.indexOf(REGION_START);
        int to = source.indexOf(REGION_END);
        assertTrue(from > 0 && to > from,
                "the map-path region moved or was renamed — re-anchor this scan before trusting it");

        Set<String> read = new TreeSet<>();
        Matcher m = CFG_READ.matcher(source.substring(from, to));
        while (m.find()) read.add(m.group(1));

        assertEquals(new TreeSet<>(RowShaper.MAP_NODE_CONFIG_KEYS), read,
                "MAP_NODE_CONFIG_KEYS must list exactly the node config keys the map path reads");
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
