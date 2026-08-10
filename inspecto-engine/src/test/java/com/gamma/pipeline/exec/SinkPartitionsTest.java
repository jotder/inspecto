package com.gamma.pipeline.exec;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SinkPartitions}: the rule {@link PartitionSinkWriter} acts on and {@link ComponentPreview} predicts.
 * Asserted here directly, because a rule that only exists inside its two callers is how the two drifted before.
 */
class SinkPartitionsTest {

    private static Map<String, Object> entry(String column, String source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("column", column);
        if (source != null) m.put("source", source);
        return m;
    }

    // ── columns ────────────────────────────────────────────────────────────────

    @Test
    void columnsAcceptsBareNamesAndMapEntries() {
        assertEquals(List.of("year", "day"),
                SinkPartitions.columns(List.of("year", entry("day", "TXN_DATE"))));
    }

    @Test
    void columnsIgnoresBlankBareNamesAndNonListDeclarations() {
        assertEquals(List.of("year"), SinkPartitions.columns(List.of("year", "   ")));
        assertEquals(List.of(), SinkPartitions.columns(null));
        assertEquals(List.of(), SinkPartitions.columns("year,day"));   // JToon mis-syntax → not a list
    }

    @Test
    void aMapEntryContributesItsColumnAndNeverItselfAsOne() {
        // it used to stringify the whole map, yielding a directory literally named "{source=TXN_DATE}"
        Object noColumn = List.of(Map.of("source", "TXN_DATE"));
        assertEquals(List.of(), SinkPartitions.columns(noColumn));
        assertEquals(List.of("{source=TXN_DATE}"), SinkPartitions.entriesWithoutColumn(noColumn));
    }

    @Test
    void aBlankColumnIsNoColumn() {
        Object blank = List.of(entry("   ", "TXN_DATE"));
        assertEquals(List.of(), SinkPartitions.columns(blank));
        assertEquals(1, SinkPartitions.entriesWithoutColumn(blank).size());
    }

    @Test
    void aBareStringEntryIsNeverReportedAsMissingAColumn() {
        // long-standing behaviour: a blank bare string is skipped, not an error
        assertEquals(List.of(), SinkPartitions.entriesWithoutColumn(List.of("year", "   ")));
    }

    // ── event time ─────────────────────────────────────────────────────────────

    @Test
    void eventTimeSourceIsTheOneEveryEntryAgreesOn() {
        assertEquals("TXN_DATE", SinkPartitions.eventTimeSource(
                List.of(entry("year", "TXN_DATE"), entry("month", "TXN_DATE"))));
    }

    @Test
    void eventTimeSourceIsNullForEveryDeclarationTheWriterRefuses() {
        // none declared — bare strings and map entries without a source
        assertNull(SinkPartitions.eventTimeSource(List.of("year", entry("day", null))));
        // two sources identify no one event time
        assertNull(SinkPartitions.eventTimeSource(
                List.of(entry("year", "TXN_DATE"), entry("day", "LOAD_DATE"))));
        // present but blank
        assertNull(SinkPartitions.eventTimeSource(List.of(entry("day", "   "))));
        // not a plain identifier — never embedded in min()/max() SQL
        assertNull(SinkPartitions.eventTimeSource(List.of(entry("day", "TXN DATE"))));
        assertNull(SinkPartitions.eventTimeSource(List.of(entry("day", "a\"; DROP TABLE t --"))));
    }

    @Test
    void declaredSourcesKeepsABlankSourceVisibleToThePreview() {
        // "" must survive: it voids the bounds at the writer, so the preview has to be able to name it
        assertEquals(List.of(""), SinkPartitions.declaredSources(List.of(entry("day", " "))));
        assertEquals(List.of(), SinkPartitions.declaredSources(List.of(entry("day", null))));
    }
}
