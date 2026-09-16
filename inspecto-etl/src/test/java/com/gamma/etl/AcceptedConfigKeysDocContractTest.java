package com.gamma.etl;

import com.gamma.config.spec.AcceptedConfigKeys;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The accepted-names doc is <b>generated from the same map the checker enforces</b>, so it cannot
 * drift (`DUCKLE-C3-DEAD-PROPERTY-1`). This test renders the table from {@link AcceptedConfigKeys} and
 * compares it to the block committed in {@code pipeline-config-keys.md}; when they disagree it fails
 * with the exact expected text, so fixing the doc is a copy, never a re-derivation.
 *
 * <p>⚠ Rendered and compared rather than written at build time — the same reasoning
 * {@code NodeAttributes} gives for not generating its contract JSON: an artifact regenerated in place
 * silently absorbs a change on whichever side ran the generator, which is the drift, not the fix.
 */
class AcceptedConfigKeysDocContractTest {

    private static final String DOC = "docs/okf/backend/pipeline-graph/pipeline-config-keys.md";
    private static final String OPEN = "<!-- generated:accepted-config-keys -->";
    private static final String CLOSE = "<!-- /generated:accepted-config-keys -->";

    @Test
    void theAcceptedNamesTableMatchesTheMapTheCheckerEnforces() throws IOException {
        String doc = Files.readString(repoFile(DOC));
        int from = doc.indexOf(OPEN);
        int to = doc.indexOf(CLOSE);
        assertTrue(from >= 0 && to > from,
                DOC + " has lost its " + OPEN + " … " + CLOSE + " block. It is generated from "
                        + "AcceptedConfigKeys and is the published answer to 'which keys may I write?' "
                        + "— restore it rather than deleting it.");

        String committed = doc.substring(from + OPEN.length(), to).strip();
        String rendered = render().strip();
        assertEquals(rendered, committed,
                "the accepted-names table in " + DOC + " no longer matches AcceptedConfigKeys. Replace "
                        + "the generated block with the expected text above — do not hand-edit it, and do "
                        + "not adjust the map to match the doc.");
    }

    /** The table, exactly as it is committed: one row per accepted block, alphabetical. */
    private static String render() {
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add("| Accepted block | Declared by |");
        lines.add("|---|---|");
        for (String block : new TreeSet<>(AcceptedConfigKeys.acceptedBlocks("pipeline")))
            lines.add("| `" + block + "` | "
                    + (AcceptedConfigKeys.PARSER_ONLY.contains(block) ? "parser-only" : "spec") + " |");
        lines.add("");
        return String.join("\n", lines);
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
