package com.gamma.job;

import com.gamma.query.ResultSetDescriptor;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The merge rule for derived dataset columns (`TYPEFLOW-DATASET-COLUMNS-1` step 4, design §5).
 *
 * <p>⛔ The rule that matters: <b>a human's answer beats the heuristic.</b> A stored role/label/format is
 * never overwritten by a re-derivation — only {@code type} refreshes, because the physical shape genuinely
 * changed. The Studio editor already merges this way client-side; a server-side derivation that did not
 * would clobber human edits on every materialize run, silently, with the job reporting success. That is the
 * exact defect step 2 of this row was filed for, in a new place.
 */
class MaterializeColumnMergeTest {

    private static List<ResultSetDescriptor.Column> derived(String... nameTypeRole) {
        List<ResultSetDescriptor.Column> out = new ArrayList<>();
        for (int i = 0; i < nameTypeRole.length; i += 3)
            out.add(new ResultSetDescriptor.Column(nameTypeRole[i], nameTypeRole[i + 1], nameTypeRole[i + 2], null));
        return out;
    }

    private static Map<String, Object> col(String name, String type, String role, Object... extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type);
        m.put("role", role);
        for (int i = 0; i < extra.length; i += 2) m.put(String.valueOf(extra[i]), extra[i + 1]);
        return m;
    }

    @Test
    void anUnknownDatasetGetsEveryDerivedColumn() {
        List<Map<String, Object>> out = MaterializeTask.mergeColumns(
                null, derived("event_time", "date", "temporal", "amount", "number", "measure"));

        assertEquals(2, out.size());
        assertEquals("event_time", out.get(0).get("name"));
        assertEquals("temporal", out.get(0).get("role"), "the derivation seeds the role when nobody has answered");
    }

    /** ⛔ The load-bearing case: a human's role, label and format survive a re-derivation. */
    @Test
    void storedHumanAnswersAreNeverOverwritten() {
        List<Map<String, Object>> stored = List.of(
                col("amount", "number", "dimension", "label", "Order amount", "format", "currency"));

        List<Map<String, Object>> out = MaterializeTask.mergeColumns(stored, derived("amount", "number", "measure"));

        assertEquals("dimension", out.get(0).get("role"),
                "the heuristic says measure; a human said dimension, and the human wins");
        assertEquals("Order amount", out.get(0).get("label"));
        assertEquals("currency", out.get(0).get("format"));
    }

    /** …but the physical type DOES refresh: the shape genuinely moved, and nobody authored that. */
    @Test
    void theTypeRefreshesEvenWhenTheRoleDoesNot() {
        List<Map<String, Object>> stored = List.of(col("amount", "string", "measure"));

        List<Map<String, Object>> out = MaterializeTask.mergeColumns(stored, derived("amount", "number", "measure"));

        assertEquals("number", out.get(0).get("type"));
        assertEquals("measure", out.get(0).get("role"));
    }

    /**
     * ✅ Q3 (operator): a stored column the derivation no longer produces is MARKED hidden, never dropped —
     * dropping would discard a human's configuration for a column that may come back.
     */
    @Test
    void aColumnThatStoppedBeingDerivedIsHiddenNotDropped() {
        List<Map<String, Object>> stored = List.of(
                col("amount", "number", "measure"),
                col("legacy_code", "string", "dimension", "label", "Legacy code"));

        List<Map<String, Object>> out = MaterializeTask.mergeColumns(stored, derived("amount", "number", "measure"));

        assertEquals(2, out.size(), "the vanished column is KEPT");
        Map<String, Object> gone = out.get(1);
        assertEquals("legacy_code", gone.get("name"));
        assertEquals(Boolean.TRUE, gone.get("hidden"));
        assertEquals("Legacy code", gone.get("label"), "…with its configuration intact, for when it returns");
    }

    /** Derived order is the relation's order; survivors come first, hidden leftovers trail. */
    @Test
    void derivedOrderLeadsAndLeftoversTrail() {
        List<Map<String, Object>> stored = List.of(col("z_old", "string", "dimension"), col("b", "string", "dimension"));

        List<Map<String, Object>> out = MaterializeTask.mergeColumns(
                stored, derived("a", "string", "dimension", "b", "string", "dimension"));

        assertEquals(List.of("a", "b", "z_old"), out.stream().map(c -> c.get("name")).toList());
        assertNull(out.get(1).get("hidden"), "a surviving column is not hidden");
    }
}
