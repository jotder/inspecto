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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            "inspecto/src/main/java/com/gamma/control/PipelineRenameRoutes.java", "private int rewriteDependents(",
            "inspecto/src/main/java/com/gamma/service/PipelineDependents.java", "",
            "platform/inspecto-engine/src/main/java/com/gamma/pipeline/ComponentIntegrity.java", "",
            "platform/inspecto-engine/src/main/java/com/gamma/job/MetadataValidateTask.java", "",
            "platform/inspecto-engine/src/main/java/com/gamma/pipeline/ColumnSets.java", "");

    /** Literal keys these checks read that are not references, each with why. */
    static final Map<String, String> NOT_A_LINK = new TreeMap<>(Map.ofEntries(
            Map.entry("id", "a Pipeline's own id (the universe a link resolves against), not a reference"),
            Map.entry("name", "a file's or component's own name (its identity), not a reference"),
            Map.entry("targetType", "the KIND a target names (selects toKind), not a reference of its own"),
            Map.entry("job", "the job: section a Job's keys sit in (LinkGraph.jobSection), a container"),
            Map.entry("triggers", "a container (triggers.on_pipeline is the link)"),
            Map.entry("references", "a container (references.<n>.ref / .path are the links)"),
            // LC-5 (ColumnSets): the column sets column links resolve against - a Schema's or Dataset's own
            // declaration, never a reference to something defined elsewhere
            Map.entry("dirs", "a Pipeline's own directories; dirs.database names the store it publishes its columns under"),
            Map.entry("mapping", "a Schema's own mapping section, a container of its column declaration"),
            Map.entry("fields", "mapping.fields / processing.map.fields: the columns a Pipeline publishes, the universe"),
            Map.entry("map", "processing.map, a container of the projection that outranks mapping.fields"),
            Map.entry("schemas", "processing.schemas (plural): only read to mark the column set unknown"),
            Map.entry("mapping_file", "processing.mapping_file: only read to mark the column set unknown"),
            Map.entry("columns", "a Dataset's own declared columns, the universe"),
            Map.entry("columnMap", "a Reconciliation side's column renames: only read to mark that side unknown")));

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
    void theColumnLinksArePinnedAndReportOnly() {
        // LC-5: the column links LinkGraph judges against Schema / Dataset column sets. Report-only: none is
        // rewritten by a rename, none is read by the bundle import pre-check.
        Set<String> cols = new TreeSet<>();
        for (LinkCensus.Link l : LinkCensus.columnLinks()) {
            cols.add(l.fromKind() + ":" + l.keyPath());
            assertEquals("column", l.toKind(), l.fromKind() + ":" + l.keyPath());
            assertEquals(LinkCensus.OnRename.REPORT, l.onRename(), l.fromKind() + ":" + l.keyPath());
            assertTrue(!l.checks().contains(LinkCensus.Check.IMPORT) && !l.checks().contains(LinkCensus.Check.RENAME),
                    l.fromKind() + ":" + l.keyPath() + " must stay report-only");
        }
        assertEquals(new TreeSet<>(List.of("enrichment:transform", "expectation:column", "expectation:refColumn",
                "expectation:when.**.field", "decision-rule:when.**.field", "dataset:sql", "dataset:dateField",
                "dataset:calculated[].expr", "widget:controls.*.field", "widget:controls.*[].field",
                "reconciliation:keyColumns[]", "reconciliation:compareColumns[].column", "reconciliation:impact.column",
                "query:text")), cols);
    }

    @Test
    void theRenameRewritesEveryCensusLinkToAPipelineOrItsStore() {
        // LC-2: PipelineRenameRoutes rewrites LinkCensus.renamedWithPipeline() and nothing else. Pin that set, and
        // that the RENAME check says exactly which links the rename reads.
        Set<String> renamed = new TreeSet<>();
        for (LinkCensus.Link l : LinkCensus.renamedWithPipeline()) {
            renamed.add(l.fromKind() + ":" + l.keyPath());
            assertTrue(l.checks().contains(LinkCensus.Check.RENAME), l.fromKind() + ":" + l.keyPath() + " lacks RENAME");
            // the rewriter walks plain keys and `*` only
            for (String seg : l.keyPath().split("[.]"))
                assertTrue(seg.equals("*") || seg.matches("[A-Za-z_][A-Za-z0-9_]*"),
                        l.fromKind() + ":" + l.keyPath() + " has a segment the rename cannot walk: " + seg);
        }
        assertEquals(new TreeSet<>(List.of("enrichment:triggers.on_pipeline", "enrichment:references.*.ref",
                "job:on_pipeline", "expectation:target", "decision-rule:target", "dataset:sourceName",
                "dataset:physicalRef", "dataset:sql", "alert-rule:onPipeline")), renamed);
        Set<String> declared = new TreeSet<>();
        for (LinkCensus.Link l : LinkCensus.LINKS)
            if (l.checks().contains(LinkCensus.Check.RENAME)) declared.add(l.fromKind() + ":" + l.keyPath());
        assertEquals(renamed, declared, "a link marked RENAME that the rename does not rewrite, or the reverse");
    }
}
