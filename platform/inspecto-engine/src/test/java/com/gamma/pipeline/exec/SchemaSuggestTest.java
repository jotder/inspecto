package com.gamma.pipeline.exec;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S4 of {@code consignment-chain-plan.md} (G1): TRY_CAST voting over a parsed sample yields a DRAFT
 * type per column — the most specific type every non-blank value accepts. The vote must stay a
 * draft-maker: it never touches ingest, where {@code auto_detect=false} stays pinned.
 */
class SchemaSuggestTest {

    private static Map<String, Object> row(String id, String amt, String day, String ts,
                                           String flag, String mixed, String blank) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ID", id);
        r.put("AMT", amt);
        r.put("EVENT_DAY", day);
        r.put("EVENT_TS", ts);
        r.put("ACTIVE", flag);
        r.put("NOTE", mixed);
        r.put("EMPTY", blank);
        return r;
    }

    @Test
    void votesTheMostSpecificTypePerColumn() throws Exception {
        List<SchemaSuggest.Field> fields = SchemaSuggest.infer(List.of(
                row("1001", "1.5", "2026-01-01", "2026-01-01 10:30:00", "true", "3", ""),
                row("1002", "2",   "2026-02-03", "2026-01-02 00:00:00", "false", "x", "")));

        List<String> types = new ArrayList<>();
        for (SchemaSuggest.Field f : fields) types.add(f.name() + ":" + f.type());
        assertEquals(List.of(
                "ID:BIGINT",            // integral strings
                "AMT:DOUBLE",           // '1.5' fails BIGINT, every value casts DOUBLE
                "EVENT_DAY:DATE",       // all values midnight ⇒ TIMESTAMP demotes to DATE
                "EVENT_TS:TIMESTAMP",   // one value carries a time part ⇒ stays TIMESTAMP
                "ACTIVE:BOOLEAN",       // true/false — and only AFTER the numeric candidates
                "NOTE:VARCHAR",         // 'x' fails everything ⇒ the fallback
                "EMPTY:VARCHAR"),       // all-blank ⇒ unknown is not evidence
                types);
    }

    /** A 0/1 column stays numeric — BIGINT is checked before BOOLEAN deliberately. */
    @Test
    void zeroOneColumnsAreNumbersNotBooleans() throws Exception {
        List<SchemaSuggest.Field> fields = SchemaSuggest.infer(List.of(
                Map.of("FLAG", "0"), Map.of("FLAG", "1")));
        assertEquals("BIGINT", fields.get(0).type());
    }

    /** Blanks don't poison a vote: the non-blank values decide, blanks abstain. */
    @Test
    void blanksAbstainFromTheVote() throws Exception {
        List<SchemaSuggest.Field> fields = SchemaSuggest.infer(List.of(
                Map.of("N", "42"), Map.of("N", "")));
        assertEquals("BIGINT", fields.get(0).type());
    }

    @Test
    void anEmptySampleIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SchemaSuggest.infer(List.of()));
    }

    // ── drift (B3, definition-surface unification P4) ────────────────────────────

    /** A draft field map in the shape the route receives. */
    private static Map<String, Object> field(String name, String selector, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        if (selector != null) m.put("selector", selector);
        m.put("type", type);
        return m;
    }

    @SafeVarargs
    private static Map<String, Object> draftOf(Map<String, Object>... fields) {
        return Map.of("raw", Map.of("fields", List.of(fields)));
    }

    private static List<SchemaSuggest.Field> inferredOf(SchemaSuggest.Field... f) {
        return List.of(f);
    }

    @Test
    void aDraftMatchingTheSampleHasNotDrifted() {
        SchemaSuggest.Drift d = SchemaSuggest.drift(
                draftOf(field("id", "ID", "BIGINT"), field("amt", "AMT", "DOUBLE")),
                inferredOf(new SchemaSuggest.Field("ID", "BIGINT"), new SchemaSuggest.Field("AMT", "DOUBLE")));
        assertTrue(d.isEmpty());
    }

    @Test
    void aNewSampleColumnIsAddedAndAVanishedOneIsMissing() {
        SchemaSuggest.Drift d = SchemaSuggest.drift(
                draftOf(field("id", "ID", "BIGINT"), field("gone", "GONE", "VARCHAR")),
                inferredOf(new SchemaSuggest.Field("ID", "BIGINT"), new SchemaSuggest.Field("EXTRA", "VARCHAR")));

        assertEquals(List.of("EXTRA"), d.added().stream().map(SchemaSuggest.Field::name).toList());
        assertEquals(List.of("gone"), d.missing().stream().map(SchemaSuggest.Field::name).toList(),
                "missing is keyed by the DRAFT's field name, the anchor a grid marks");
        assertFalse(d.isEmpty());
    }

    @Test
    void aChangedVoteIsReportedWithBothTypes() {
        SchemaSuggest.Drift d = SchemaSuggest.drift(
                draftOf(field("amt", "AMT", "BIGINT")),
                inferredOf(new SchemaSuggest.Field("AMT", "DOUBLE")));

        assertEquals(1, d.typeChanged().size());
        assertEquals("amt", d.typeChanged().get(0).name());
        assertEquals("BIGINT", d.typeChanged().get(0).declared());
        assertEquals("DOUBLE", d.typeChanged().get(0).suggested());
    }

    /**
     * The join key is the SELECTOR, so renaming the output column — which an author does deliberately —
     * is not drift. Keying on name would light the indicator on every intentional rename.
     */
    @Test
    void renamingTheOutputColumnIsNotDrift() {
        SchemaSuggest.Drift d = SchemaSuggest.drift(
                draftOf(field("account_number", "A_NUMBER", "VARCHAR")),
                inferredOf(new SchemaSuggest.Field("A_NUMBER", "VARCHAR")));
        assertTrue(d.isEmpty());
    }

    /** ⛔ A renamed SOURCE column is not derivable — it surfaces as the remove+add pair, by design. */
    @Test
    void aRenamedSourceColumnSurfacesAsMissingPlusAdded() {
        SchemaSuggest.Drift d = SchemaSuggest.drift(
                draftOf(field("ts", "EVENT_TS", "TIMESTAMP")),
                inferredOf(new SchemaSuggest.Field("EVENT_TIME", "TIMESTAMP")));

        assertEquals(List.of("ts"), d.missing().stream().map(SchemaSuggest.Field::name).toList());
        assertEquals(List.of("EVENT_TIME"), d.added().stream().map(SchemaSuggest.Field::name).toList());
    }

    @Test
    void aFieldWithNoDeclaredTypeHasNothingToHaveChangedFrom() {
        SchemaSuggest.Drift d = SchemaSuggest.drift(
                draftOf(field("amt", "AMT", "")),
                inferredOf(new SchemaSuggest.Field("AMT", "DOUBLE")));
        assertTrue(d.typeChanged().isEmpty());
        assertTrue(d.isEmpty());
    }

    /** A draft field with no selector falls back to its own name, the shape `suggest` itself emits. */
    @Test
    void aSelectorlessDraftFieldJoinsOnItsName() {
        SchemaSuggest.Drift d = SchemaSuggest.drift(
                draftOf(field("AMT", null, "DOUBLE")),
                inferredOf(new SchemaSuggest.Field("AMT", "DOUBLE")));
        assertTrue(d.isEmpty());
    }

    @Test
    void typesCompareCaseInsensitively() {
        SchemaSuggest.Drift d = SchemaSuggest.drift(
                draftOf(field("amt", "AMT", "double")),
                inferredOf(new SchemaSuggest.Field("AMT", "DOUBLE")));
        assertTrue(d.isEmpty());
    }
}
