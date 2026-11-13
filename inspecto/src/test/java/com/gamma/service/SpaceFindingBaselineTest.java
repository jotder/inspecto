package com.gamma.service;

import com.gamma.pipeline.ComponentIntegrity;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.LinkGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-SE10 finding baseline ({@code docs/superpower/2026-11-11-space-evolution-plan.md} §3): every shadow finding the
 * built checks produce on every shipped Space and Space Template, unchanged, computed over a temp copy — the
 * integrity report (broken links, duplicates; changed-outside, renamed and deleted must be empty on a fresh copy and
 * on a recomputation), the column contract (no change on a recomputation), the link census dangling links (what the
 * LC-3 save and delete shadow act on) and the {@code metadata_validate} findings (without the data-root check: no
 * data is shipped). The set must equal {@link #EXPECTED}: a new false positive, or a new defect in a shipped Space,
 * fails here. Record: {@code docs/okf/product/module/config/spec-space-lock.md} §14.
 */
class SpaceFindingBaselineTest {

    /**
     * The recorded baseline: {@code <space or _templates/name> | <check> | <finding>}. Every entry left here is
     * UNKNOWN (genuinely ambiguous, reported, not guessed; a comment beside it says why); a REAL defect is fixed in its Space and a FALSE POSITIVE
     * in its check, so neither stays. Empty = zero unknowns: the clean-shadow clock (D-SE2 / D-LC3) runs.
     */
    static final Set<String> EXPECTED = Set.of();

    @Test
    void everyShippedSpaceHasExactlyTheRecordedFindings(@TempDir Path tmp) throws IOException {
        Set<String> actual = new TreeSet<>();
        for (SpaceCorpusEquivalenceTest.Entry e : SpaceCorpusEquivalenceTest.corpus())
            actual.addAll(findings(e, tmp));
        Set<String> added = new TreeSet<>(actual), gone = new TreeSet<>(EXPECTED);
        added.removeAll(EXPECTED);
        gone.removeAll(actual);
        actual.forEach(f -> System.out.println("[D-SE10] " + f));
        assertTrue(added.isEmpty() && gone.isEmpty(), "the finding baseline moved.\nnew (a defect in the Space, or a "
                + "false positive in the check):\n  " + String.join("\n  ", added)
                + "\nno longer found (drop from EXPECTED):\n  " + String.join("\n  ", gone));
    }

    /** The baseline bites: a planted dangling Widget link and a planted duplicate Connection are found. */
    @Test
    void aPlantedBreakAndDuplicateAreFindings(@TempDir Path tmp) throws IOException {
        SpaceCorpusEquivalenceTest.Entry demo = SpaceCorpusEquivalenceTest.corpus().stream()
                .filter(e -> e.label().equals("demo")).findFirst().orElseThrow();
        Path src = tmp.resolve("src");
        SpaceLoadSnapshot.copyConfig(demo.dir(), src, "planted", false);
        Path config = src.resolve("config");
        java.nio.file.Files.writeString(config.resolve("registry/widgets/planted_widget.toon"),
                "vizType: line\ndatasetId: no_such_dataset\n");
        java.nio.file.Files.copy(config.resolve("connections/demo_local_connection.toon"),
                config.resolve("demo_local_copy_connection.toon"));
        List<String> found = findings(new SpaceCorpusEquivalenceTest.Entry("planted", src, "planted", false),
                tmp.resolve("work"));
        assertTrue(found.contains("planted | integrity.broken | registry/widgets/planted_widget.toon widget "
                + "'planted_widget' datasetId=no_such_dataset -> dataset"), "broken link: " + found);
        assertTrue(found.stream().anyMatch(f -> f.startsWith("planted | metadata_validate | ")
                && f.contains("no_such_dataset")), "metadata_validate: " + found);
        assertTrue(found.stream().anyMatch(f -> f.startsWith("planted | integrity.duplicates | connection:")),
                "duplicate: " + found);
        assertFalse(found.stream().anyMatch(f -> f.contains("integrity.changedOutside") || f.contains("column.change")),
                "an unchanged recomputation reports nothing more: " + found);
    }

    /** Every finding of one corpus entry, as {@code label | check | finding}. */
    static List<String> findings(SpaceCorpusEquivalenceTest.Entry e, Path tmp) throws IOException {
        Path base = tmp.resolve(e.id());
        SpaceLoadSnapshot.copyConfig(e.dir(), base, e.id(), e.template());
        Path config = base.resolve("config").toAbsolutePath().normalize();
        List<String> out = new ArrayList<>();

        // the integrity report, computed twice: the first has no baseline, the second reads the first as one
        SpaceLock.Report first = SpaceLock.compute(e.id(), config, List.of());
        SpaceLock.Report second = SpaceLock.compute(e.id(), config, List.of());
        assertEquals("ready", first.status(), e.label());
        assertEquals("ready", second.status(), e.label());
        assertEquals("lock", second.body().get("baseline"), e.label() + ": the recomputation reads the first lock");

        // broken links: the full census list (the report is capped), and the report agrees on the count
        LinkGraph graph = LinkGraph.of(config, ReservedConfigPaths::reserved);
        for (LinkGraph.Edge d : graph.dangling())
            out.add(line(e, "integrity.broken", (d.file() == null ? "" : rel(config, d.file())) + " " + d.fromKind()
                    + " '" + d.fromName() + "' " + d.key() + "=" + d.value() + " -> " + d.toKind()));
        assertEquals(graph.dangling().size(), total(first, "broken"), e.label() + ": report and census disagree");

        for (Map<String, Object> d : items(first, "duplicates"))
            out.add(line(e, "integrity.duplicates", d.get("claim") + " " + d.get("paths")));
        for (SpaceLock.Report r : List.of(first, second))
            for (String list : List.of("changedOutside", "renamed", "deleted"))
                for (Map<String, Object> f : items(r, list))
                    out.add(line(e, "integrity." + list, f.get("path") + " " + f.get("kind") + ":" + f.get("id")));

        // the column contract: a baseline on the first computation, and no change on an unchanged recomputation
        for (SpaceLock.Report r : List.of(first, second)) {
            @SuppressWarnings("unchecked") Map<String, Object> cc = (Map<String, Object>) r.body().get("columnChanges");
            assertEquals("ready", cc.get("status"), e.label());
            @SuppressWarnings("unchecked") List<Map<String, Object>> changes = (List<Map<String, Object>>) cc.get("items");
            for (Map<String, Object> c : changes)
                out.add(line(e, "column.change", c.get("schema") + " " + c.get("change") + " " + c.get("column")));
        }

        // metadata_validate, as MetadataValidateTask composes it (no data root: the physical-data check is skipped)
        ComponentStore store = new ComponentStore(config.resolve("registry"));
        Map<String, List<ComponentRegistry.Component>> byType = new LinkedHashMap<>();
        for (String type : ComponentStore.WRITABLE_TYPES.stream().sorted().toList()) byType.put(type, store.list(type));
        List<String> mv = new ArrayList<>(ComponentIntegrity.brokenRefs(byType));
        mv.addAll(ComponentIntegrity.duplicates(byType));
        LinkGraph census = LinkGraph.of(config, rel -> {
            String r = rel.replace('\\', '/');
            if (r.equals("registry") || r.startsWith("registry/")) return true;
            for (String seg : r.split("/")) if (seg.startsWith(".")) return true;
            return false;
        });
        if (!census.pipelineIds().isEmpty()) {
            mv.addAll(ComponentIntegrity.brokenPipelineRefs(byType, census.pipelineIds()));
            mv.addAll(ComponentIntegrity.brokenFilePipelineRefs(census));
        }
        mv.addAll(ComponentIntegrity.brokenColumns(census));
        for (String f : mv) out.add(line(e, "metadata_validate", SpaceLoadSnapshot.norm(f, base)));

        // what was looked at, one line per entry (the record's coverage columns)
        Map<LinkGraph.Status, Integer> byStatus = new java.util.EnumMap<>(LinkGraph.Status.class);
        for (LinkGraph.Edge x : graph.edges()) byStatus.merge(x.status(), 1, Integer::sum);
        @SuppressWarnings("unchecked") Map<String, Object> cc = (Map<String, Object>) first.body().get("columnChanges");
        System.out.println("[D-SE10-COVERAGE] " + e.label() + " files=" + first.body().get("files") + " links="
                + graph.edges().size() + " " + byStatus + " schemas=" + cc.get("schemas") + " unknownSchemas="
                + cc.get("unknownSchemas") + " findings=" + out.size());
        return out;
    }

    private static String line(SpaceCorpusEquivalenceTest.Entry e, String check, String finding) {
        return e.label() + " | " + check + " | " + finding;
    }

    private static String rel(Path root, Path p) {
        return root.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static int total(SpaceLock.Report r, String list) {
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) r.body().get(list);
        return ((Number) m.get("total")).intValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(SpaceLock.Report r, String list) {
        Map<String, Object> m = (Map<String, Object>) r.body().get(list);
        assertTrue(((Number) m.get("total")).intValue() <= SpaceLock.CAP, list + " is capped: the baseline would be partial");
        return (List<Map<String, Object>>) m.get("items");
    }
}
