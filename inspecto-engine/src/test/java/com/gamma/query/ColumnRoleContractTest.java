package com.gamma.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The cross-language contract for a column's ANALYTIC ROLE — temporal / measure / dimension — pinning
 * {@link ResultSetDescriptor#roleFor} against the same committed JSON the client's `roleFor`
 * (`inspecto/viz/result-set.ts`) is pinned to (`TYPEFLOW-DATASET-COLUMNS-1` step 1).
 *
 * <p><b>Why it is needed.</b> The rule existed in THREE unpinned copies — this one, plus two in
 * TypeScript (`result-set.ts` and the Studio's `dataset-types.ts`, byte-identical down to the
 * {@code (^|_)id$} regex). Nothing compared them, so any could drift silently. The two client copies
 * were collapsed into one when this contract was added; what remains is one per language, pinned here.
 *
 * <p><b>Why it matters that they agree.</b> A stored Dataset's roles and a live result set's roles are
 * consumed by the SAME Show-Me scoring in the widget builder, which buckets fields by role to
 * recommend charts and to decide which field is legal on which channel. If the two sides disagree, the
 * same column is a measure in one surface and a dimension in the other — and the chart still renders,
 * so nothing fails loudly.
 *
 * <p><b>Why a committed file rather than generation</b> — the same reasoning as
 * {@link MeasureGrammarContractTest}: both sides compare to ONE checked-in artifact, so a divergence
 * is a reviewable diff on the contract rather than something a generator absorbs.
 */
class ColumnRoleContractTest {

    private static final String CONTRACT = "inspecto-ui/src/app/inspecto/contracts/column-role.contract.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Walk up from the module's CWD to the repo root, so the path works under surefire and an IDE alike. */
    private static Path contractPath() {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve(CONTRACT);
            if (Files.exists(candidate)) return candidate;
        }
        throw new AssertionError("cannot locate " + CONTRACT + " from " + Path.of("").toAbsolutePath());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> contract() throws IOException {
        return JSON.readValue(contractPath().toFile(), Map.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    void everyContractCaseGetsTheRoleThisEngineAssigns() throws IOException {
        List<Map<String, String>> cases = (List<Map<String, String>>) contract().get("cases");
        assertNotNull(cases, "contract file has no `cases` key");
        // Pinned so a silently-emptied contract cannot turn this test into a vacuous pass — the same
        // guard the measure-grammar spec puts on its aggregate list.
        assertTrue(cases.size() >= 12, "the contract lost cases (" + cases.size() + ") — a shrinking "
                + "case table weakens both sides of this pin at once");

        for (Map<String, String> c : cases) {
            String name = c.get("name"), type = c.get("type"), expected = c.get("role");
            assertEquals(expected, ResultSetDescriptor.roleFor(name, type),
                    "role for {name=" + name + ", type=" + type + "} disagrees with " + CONTRACT
                            + " — decide which is right, then update both sides");
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void theRoleVocabularyMatchesWhatThisEngineCanActuallyProduce() throws IOException {
        List<String> published = (List<String>) contract().get("roles");
        assertNotNull(published, "contract file has no `roles` key");
        // Every published role must be reachable, and roleFor must never invent one outside the list.
        List<Map<String, String>> cases = (List<Map<String, String>>) contract().get("cases");
        for (Map<String, String> c : cases)
            assertTrue(published.contains(ResultSetDescriptor.roleFor(c.get("name"), c.get("type"))),
                    "roleFor produced a role outside the published vocabulary for " + c.get("name"));
        for (String role : published)
            assertTrue(cases.stream().anyMatch(c -> role.equals(c.get("role"))),
                    "published role '" + role + "' is exercised by no case — an unexercised role is an "
                            + "unpinned one");
    }

    /**
     * 🔴 The id test is anchored {@code (^|_)id$}, NOT a bare {@code id$} suffix. {@code paid} ends in
     * the letters "id" and must still be a measure; loosening the anchor would silently reclassify it
     * — and a column that stops being a measure simply disappears from a chart's measure picker.
     */
    @Test
    void theIdHeuristicIsAnchoredAndDoesNotSwallowWordsEndingInId() throws IOException {
        assertEquals("(^|_)id$", contract().get("idColumn"), "the published id pattern moved");
        assertEquals("measure", ResultSetDescriptor.roleFor("paid", "number"));
        assertEquals("measure", ResultSetDescriptor.roleFor("valid", "number"));
        assertEquals("dimension", ResultSetDescriptor.roleFor("id", "number"));
        assertEquals("dimension", ResultSetDescriptor.roleFor("order_id", "number"));
    }

    /** An id-named DATE is still temporal: the id test guards the number branch only. */
    @Test
    void theIdHeuristicAppliesOnlyToTheNumberBranch() {
        assertEquals("temporal", ResultSetDescriptor.roleFor("order_id", "date"));
        assertEquals("dimension", ResultSetDescriptor.roleFor("order_id", "string"));
    }
}
