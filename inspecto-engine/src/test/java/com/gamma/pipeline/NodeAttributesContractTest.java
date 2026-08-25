package com.gamma.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The §3.1 cross-language contract: the node cfg vocabulary this server publishes must equal the table the
 * Angular client falls back to offline.
 *
 * <p><b>Why a committed file rather than build-time generation.</b> Both sides compare to ONE checked-in
 * artifact — this test compares the Java table to it, and {@code node-attributes.spec.ts} compares the TS
 * table to the same file. A generated artifact would silently absorb whichever side ran the generator,
 * which is the drift it is supposed to catch. Because it is committed, a real divergence shows up as a
 * reviewable diff on the contract file itself.
 *
 * <p>Regenerate deliberately (never as a reflex) with {@code -Dnode.attributes.write=true}: that rewrites
 * the file from the Java table and the TS suite then tells you whether the client agrees. If it does not,
 * ONE of the two tables is wrong — decide which before committing, because the JSON is the contract, not a
 * scratch pad.
 */
class NodeAttributesContractTest {

    /** Shared with the TS side + the mock's `/pipelines/node-types`, so the offline preview cannot drift either. */
    private static final String CONTRACT = "inspecto-ui/src/app/inspecto/mock/node-attributes.contract.json";

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** Walk up from the module's CWD to the repo root, so the path works under surefire and an IDE alike. */
    private static Path contractPath() {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve(CONTRACT);
            if (Files.exists(candidate)) return candidate;
        }
        throw new AssertionError("cannot locate " + CONTRACT + " from " + Path.of("").toAbsolutePath());
    }

    @Test
    void theServedVocabularyMatchesTheCommittedContract() throws IOException {
        Path path = contractPath();
        String actual = JSON.writeValueAsString(NodeAttributes.wireMap()).replace("\r\n", "\n").trim();

        if (Boolean.getBoolean("node.attributes.write")) {
            Files.writeString(path, actual + "\n");
            return;
        }

        String expected = Files.readString(path).replace("\r\n", "\n").trim();
        assertEquals(expected, actual,
                "the published node attributes and " + CONTRACT + " disagree. If the Java table is right, "
                        + "regenerate with -Dnode.attributes.write=true and check node-attributes.spec.ts "
                        + "still passes; if the CLIENT is right, fix NodeAttributes.java instead.");
    }

    /**
     * The 2026-08-04 fold, asserted on the published side: the acquisition node advertises the WHOLE
     * collector block, {@code duplicate__*} included. D9 had split those keys onto a
     * {@code transform.dedup.fingerprint} node; that node was removed because file dedup executes in
     * the {@code CollectorProcessor} poll cycle ({@code ledgerFilter}) — it never was a transform, so
     * the split told the operator the check happens somewhere it does not.
     *
     * <p>P5-a folded MARKER dedup onto the same node for the same reason, so the published list is now
     * {@code COLLECTOR + MARKER_DEDUP}. ⚠ The two lists stay separate deliberately: {@code COLLECTOR}
     * IS the {@code collector:} block and Onboarding's Collection stage renders it whole, while the
     * marker keys live in {@code processing:}/{@code dirs:} and are only borrowed by this node.
     */
    @Test
    void acquisitionPublishesTheWholeCollectorBlockIncludingBothFileDedups() {
        List<String> acq = NodeAttributes.forType("acquisition").stream().map(NodeAttribute::key).toList();
        assertTrue(acq.contains("duplicate__mode"));        // fingerprint policy (2026-08-04 fold)
        assertTrue(acq.contains("duplicate__on_change"));
        assertTrue(acq.contains("duplicate_check"));        // marker dedup (P5-a fold)
        assertTrue(acq.contains("markers_dir"));
        assertEquals(
                Stream.concat(Stream.concat(NodeAttributes.COLLECTOR.stream(), NodeAttributes.MARKER_DEDUP.stream()),
                                NodeAttributes.TRIGGER.stream())
                        .map(NodeAttribute::key).toList(),
                acq);
        // …and the marker + trigger keys are NOT in the collector-block table itself — folding them in
        // would give Onboarding's Collection stage fields it would write to a block nothing reads them in.
        assertTrue(NodeAttributes.COLLECTOR.stream().map(NodeAttribute::key)
                .noneMatch(k -> k.equals("duplicate_check") || k.equals("markers_dir")
                        || k.startsWith("trigger__")));

        // Neither removed node publishes anything — transform.dedup.marker is read-compat only now.
        assertTrue(NodeAttributes.forType("transform.dedup.fingerprint").isEmpty());
        assertTrue(NodeAttributes.forType("transform.dedup.marker").isEmpty());
    }

    /** The catalog every client reads must actually carry the specs — the whole point of §3.1. */
    @Test
    void theNodeTypeCatalogPublishesAttributes() {
        Map<String, Object> byType = new java.util.LinkedHashMap<>();
        for (Map<String, Object> t : PipelineProjection.catalog()) byType.put((String) t.get("type"), t.get("attributes"));

        assertTrue(byType.containsKey("acquisition"), "catalog lost the acquisition type");
        for (String type : NodeAttributes.speccedTypes()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> attrs = (List<Map<String, Object>>) byType.get(type);
            assertNotNull(attrs, type + " is specced but the catalog published no attributes for it");
            assertFalse(attrs.isEmpty(), type + " published an empty attribute list");
            assertEquals(NodeAttributes.forType(type).size(), attrs.size(), type + " lost attributes in the catalog");
        }
        // An unspecced type must publish an empty list, not be absent — the client tells them apart.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mapAttrs = (List<Map<String, Object>>) byType.get("transform.map");
        assertNotNull(mapAttrs);
        assertTrue(mapAttrs.isEmpty(), "transform.map is deliberately unspecced (free-form fallback)");
    }

    /**
     * The control vocabulary must stay single-sourced. {@link NodeAttribute} delegates to
     * {@link com.gamma.ops.findings.FindingsSpec}, so widening one widens both — this pins that, because
     * two independently-declared unions is how the renderer ends up asked to draw a type it cannot.
     */
    @Test
    void theControlVocabularyIsSharedWithTheOtherSpecSurface() {
        assertSame(com.gamma.ops.findings.FindingsSpec.TYPES, NodeAttribute.TYPES);
        assertSame(com.gamma.ops.findings.FindingsSpec.TIERS, NodeAttribute.TIERS);
    }
}
