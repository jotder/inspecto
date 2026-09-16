package com.gamma.config.spec;

import com.gamma.config.io.ConfigCodec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dead-property census (`DUCKLE-C3-DEAD-PROPERTY-1`): a block no component reads must produce a
 * coded ERROR, a near name must be suggested when there is one, and — the half that is easy to get
 * wrong — <b>nothing must be suggested when nothing is close</b>.
 */
class AcceptedConfigKeysTest {

    private static Map<String, Object> pipelineDraft() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "orders");
        raw.put("dirs", new LinkedHashMap<>(Map.of("poll", "in", "database", "db")));
        raw.put("processing", new LinkedHashMap<>(Map.of("threads", 4)));
        return raw;
    }

    private static List<Finding> check(Map<String, Object> raw) {
        return AcceptedConfigKeys.unknownKeyFindings("pipeline", raw, Severity.ERROR);
    }

    @Test
    void aCleanDraftIsNotFlagged() {
        assertTrue(check(pipelineDraft()).isEmpty(), "a draft of declared blocks must produce no finding");
    }

    @Test
    void everyParserOnlyBlockIsAccepted() {
        // The engine-honoured, spec-undeclared blocks are read by SOMETHING, so none is dead.
        Map<String, Object> raw = pipelineDraft();
        raw.put("active", true);
        raw.put("steps", List.of());
        raw.put("trigger", new LinkedHashMap<>(Map.of("every", "30s")));
        @SuppressWarnings("unchecked")
        Map<String, Object> proc = (Map<String, Object>) raw.get("processing");
        proc.put("summarize", new LinkedHashMap<>(Map.of("group_by", List.of("region"))));
        proc.put("disabled_steps", List.of("dedup"));
        assertTrue(check(raw).isEmpty(), "a parser-only block is read by the engine and must not be flagged");
    }

    @Test
    void aDeadTopLevelBlockIsAnErrorWithAStableCode() {
        Map<String, Object> raw = pipelineDraft();
        raw.put("banana", "yellow");
        List<Finding> findings = check(raw);
        assertEquals(1, findings.size(), "exactly one finding for one dead block: " + findings);
        Finding f = findings.getFirst();
        assertEquals(Severity.ERROR, f.severity());
        assertEquals("banana", f.fieldPath());
        assertEquals(FindingCodes.ERR_UNKNOWN_CONFIG_KEY, f.code());
    }

    @Test
    void suggestsTheNearName() {
        Map<String, Object> raw = pipelineDraft();
        raw.put("procesing", new LinkedHashMap<>());   // one deletion away from `processing`
        Finding f = check(raw).stream().filter(x -> x.fieldPath().equals("procesing")).findFirst().orElseThrow();
        assertTrue(f.guidance().contains("did you mean 'processing'"),
                "a one-edit typo must name the key the author meant; got: " + f.guidance());
    }

    /**
     * 🔴 The row's explicit requirement. A message that volunteers an unrelated name for a word that is
     * not a typo of anything sends the author to the wrong key — worse than saying nothing.
     */
    @Test
    void offersNoSuggestionWhenNothingIsClose() {
        Map<String, Object> raw = pipelineDraft();
        raw.put("banana", "yellow");
        Finding f = check(raw).getFirst();
        assertFalse(f.guidance().contains("did you mean"),
                "nothing accepted is close to 'banana', so no name may be guessed; got: " + f.guidance());
        assertTrue(f.guidance().contains("no accepted key is close"));
        assertTrue(AcceptedConfigKeys.nearestName("banana", AcceptedConfigKeys.acceptedBlocks("pipeline")).isEmpty());
    }

    @Test
    void suggestionsStayAtTheSameDepth() {
        // `procesing` must never be told to try `processing.dedup`: that is not a legal top-level key.
        Finding f = check(new LinkedHashMap<>(Map.of("procesing", Map.of()))).getFirst();
        assertFalse(f.guidance().contains("processing.dedup"), f.guidance());
    }

    @Test
    void aDeadSubBlockUnderAnAcceptedBlockIsFlagged() {
        Map<String, Object> raw = pipelineDraft();
        @SuppressWarnings("unchecked")
        Map<String, Object> proc = (Map<String, Object>) raw.get("processing");
        proc.put("dedupe", Map.of());   // the block is `processing.dedup`
        Finding f = check(raw).stream().filter(x -> x.fieldPath().equals("processing.dedupe"))
                .findFirst().orElseThrow();
        assertTrue(f.guidance().contains("did you mean 'processing.dedup'"),
                "a nested suggestion must be written as a full path the author can use; got: " + f.guidance());
    }

    /**
     * 🔴 `dirs` is accepted WHOLE even though it HAS declared leaves. Its other leaves
     * ({@code errors}, {@code quarantine}, {@code markers}, {@code log_dir}) are engine-read and carry
     * no {@code FieldSpec}, so a checker that descended on "this block has a declared sub-block" would
     * refuse four keys that work today. Only {@code processing} is censused at leaf level.
     */
    @Test
    void aBlockThatIsNotCensusedAtLeafLevelIsNotDescendedInto() {
        Map<String, Object> raw = pipelineDraft();
        @SuppressWarnings("unchecked")
        Map<String, Object> dirs = (Map<String, Object>) raw.get("dirs");
        dirs.put("quarantine", "q");
        dirs.put("markers", "m");
        raw.put("collector", new LinkedHashMap<>(Map.of("etag_header", "x-amz-meta-etag")));
        assertTrue(check(raw).isEmpty(),
                "engine-read leaves inside a whole-accepted block must not be flagged: " + check(raw));
    }

    @Test
    void xPrefixedKeysAreAccepted() {
        Map<String, Object> raw = pipelineDraft();
        raw.put("x-owner", "platform-team");
        @SuppressWarnings("unchecked")
        Map<String, Object> proc = (Map<String, Object>) raw.get("processing");
        proc.put("x-note", "tuned 2026-09");
        assertTrue(check(raw).isEmpty(), "an x- block is the author's own annotation and is never dead");
    }

    /** An `x-` key is only a safe escape hatch if the codec actually carries it through a save. */
    @Test
    void xPrefixedKeysRoundTripThroughTheCodecUntouched() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "orders");
        raw.put("x-owner", "platform-team");
        Map<String, Object> back = ConfigCodec.toMap(ConfigCodec.toToon(raw));
        assertEquals("platform-team", back.get("x-owner"), "x- keys must survive encode/decode verbatim");
    }

    @Test
    void aTypeWithNoCensusIsCheckedNotAtAll() {
        // Fail-open BY OMISSION and stated: `alert` has no parser census, so nothing is known to be dead.
        assertFalse(AcceptedConfigKeys.hasCensus("alert"));
        assertTrue(AcceptedConfigKeys.unknownKeyFindings("alert",
                new LinkedHashMap<>(Map.of("banana", "yellow")), Severity.ERROR).isEmpty());
    }

    @Test
    void theWarningSeverityCarriesTheWarnCode() {
        Map<String, Object> raw = pipelineDraft();
        raw.put("banana", "yellow");
        Finding f = AcceptedConfigKeys.unknownKeyFindings("pipeline", raw, Severity.WARNING).getFirst();
        assertEquals(Severity.WARNING, f.severity());
        assertEquals(FindingCodes.WARN_UNKNOWN_CONFIG_KEY, f.code());
    }
}
