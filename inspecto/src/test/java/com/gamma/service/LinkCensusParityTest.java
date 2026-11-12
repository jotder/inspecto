package com.gamma.service;

import com.gamma.control.ReactorModules;
import com.gamma.pipeline.LinkCensus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The census is the complete list (`SPACE-LINK-CENSUS-1` LC-1): every key a link check still reads by a literal
 * name must be a segment of some {@link LinkCensus} key path, or sit on {@link #NOT_A_LINK} with the written
 * reason it is not a reference. The miss this exists for: a key added to one check's hard-coded copy (the
 * rename rewrite, the {@code metadata_validate} sweep) that the census — and so impact, validate and import —
 * never hears of. Lexical, like {@code ImportLoaderInventoryTest}: it collects the reads {@code .get("k")} and
 * {@code .getOrDefault("k"} in the checks' sources (a rewrite's {@code put} writes a key it has just read).
 */
class LinkCensusParityTest {

    private static final Pattern KEY = Pattern.compile("\\.(?:get|getOrDefault)\\(\\s*\"([A-Za-z_][A-Za-z0-9_]*)\"");

    /** The checks that read link keys, with the marker their link code starts at (null = the whole file). */
    private static final Map<String, String> SOURCES = Map.of(
            "inspecto/src/main/java/com/gamma/control/PipelineRenameRoutes.java", "private int rewriteEnrichTriggers(",
            "inspecto/src/main/java/com/gamma/service/PipelineDependents.java", "",
            "platform/inspecto-engine/src/main/java/com/gamma/pipeline/ComponentIntegrity.java", "",
            "platform/inspecto-engine/src/main/java/com/gamma/job/MetadataValidateTask.java", "");

    /** Literal keys these checks read that are not references, each with why. */
    static final Map<String, String> NOT_A_LINK = new TreeMap<>(Map.of(
            "id", "a Pipeline's own id (the universe a link resolves against), not a reference",
            "name", "a file's or component's own name (its identity), not a reference",
            "targetType", "the KIND a target names (selects toKind), not a reference of its own",
            "job", "the job: section a Job's keys sit in (LinkGraph.jobSection), a container",
            "triggers", "a container (triggers.on_pipeline is the link)",
            "references", "a container (references.<n>.ref / .path are the links)"));

    private static Set<String> censusSegments() {
        Set<String> segs = new HashSet<>();
        for (LinkCensus.Link l : LinkCensus.LINKS)
            for (String s : l.keyPath().split("\\.")) segs.add(s.replace("[]", ""));
        return segs;
    }

    @Test
    void everyKeyALinkCheckReadsIsInTheCensus() throws IOException {
        Path root = ReactorModules.root();
        Set<String> segs = censusSegments();
        Map<String, String> open = new TreeMap<>();
        int seen = 0;
        for (Map.Entry<String, String> src : SOURCES.entrySet()) {
            String text = Files.readString(root.resolve(src.getKey()));
            if (!src.getValue().isEmpty()) {
                int at = text.indexOf(src.getValue());
                assertTrue(at >= 0, "marker '" + src.getValue() + "' gone from " + src.getKey() + " — re-aim the scan");
                text = text.substring(at);
            }
            Matcher m = KEY.matcher(text);
            while (m.find()) {
                seen++;
                String k = m.group(1);
                if (!segs.contains(k) && !NOT_A_LINK.containsKey(k)) open.put(k, src.getKey());
            }
        }
        assertTrue(seen >= 10, "the scan went blind — it saw only " + seen + " key reads");
        assertTrue(open.isEmpty(), () -> "keys a link check reads that LinkCensus does not declare — add the link "
                + "to LinkCensus.LINKS, or a reason to NOT_A_LINK: " + open);
    }

    @Test
    void theRenameKeysAreDeclaredAsRenamed() {
        // The rename path (LC-2 moves it onto the graph) rewrites exactly these; each must say so in the census.
        for (String[] l : List.of(new String[]{"enrichment", "triggers.on_pipeline"}, new String[]{"job", "on_pipeline"},
                new String[]{"expectation", "target"}, new String[]{"decision-rule", "target"},
                new String[]{"dataset", "sourceName"}, new String[]{"dataset", "physicalRef"},
                new String[]{"dataset", "sql"}, new String[]{"alert-rule", "onPipeline"}))
            assertTrue(LinkCensus.of(l[0], l[1]).checks().contains(LinkCensus.Check.RENAME), l[0] + ":" + l[1]);
    }
}
