package com.gamma.ops.tag;

import com.gamma.ops.OperationalObject;
import com.gamma.workflow.ObjectType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden corpus for {@link TagRule.Filter#matches} — the set of objects each criterion selects, pinned
 * independently of how it is computed. Written against the hand-coded matcher and kept green when the filter
 * moved onto the Condition Language tree (Decision Kernel step 3), so the two evaluators are proven to agree.
 * The one deliberate difference ({@code category} is a case-insensitive prefix on the tree) is pinned apart.
 */
class TagRuleFilterParityTest {

    private static OperationalObject o(ObjectType t, String title, String desc, String status, String priority,
                                       String severity, String category) {
        var b = OperationalObject.builder(t).id(title).title(title).description(desc).status(status)
                .priority(priority).severity(severity);
        if (category != null) b.attr("category", category);
        return b.build();
    }

    private static final List<OperationalObject> CORPUS = List.of(
            o(ObjectType.INCIDENT, "A", "Rejected files spike parse errors", "OPEN", "CRITICAL", "MAJOR", "Pipeline / Ingest / Parse failure"),
            o(ObjectType.INCIDENT, "B", "Latency high api slow", "IDENTIFIED", "LOW", "MINOR", "Application / API / Timeout"),
            o(ObjectType.INCIDENT, "C", "Disk Rejected volume", "CLOSED", null, "CRITICAL", null),
            o(ObjectType.INCIDENT, "D", "Done", "ARCHIVED", "critical", "MINOR", "Pipeline"),
            o(ObjectType.CASE, "E", "rejected batch", "OPEN", "CRITICAL", null, "Pipeline / Ingest"),
            o(ObjectType.TASK, "F", "", "in_progress", "LOW", null, null));

    private static String select(String type, String q, String status, String priority, String severity, String category) {
        var f = new TagRule.Filter(type, q, status, priority, severity, category);
        return CORPUS.stream().filter(f::matches).map(OperationalObject::id).collect(Collectors.joining());
    }

    @Test
    void typeIsCaseInsensitive() {
        assertEquals("ABCD", select("INCIDENT", null, null, null, null, null));
        assertEquals("E", select("case", null, null, null, null, null));
    }

    @Test
    void qIsACaseInsensitiveSubstringOfTitleAndDescriptionTogether() {
        assertEquals("ACE", select(null, "REJECTED", null, null, null, null));
        assertEquals("A", select(null, "files spike", null, null, null, null));
        assertEquals("B", select(null, " high api ", null, null, null, null));   // spans title + " " + description
        assertEquals("", select(null, "nowhere", null, null, null, null));
    }

    @Test
    void incidentStatusesFoldLegacyNamesWhileOtherTypesCompareExactly() {
        assertEquals("AB", select(null, null, "IDENTIFIED", null, null, null));   // the CASE 'OPEN' is not an incident
        assertEquals("ABE", select(null, null, "OPEN", null, null, null));       // ... but a rule saying OPEN still reaches it
        assertEquals("CD", select(null, null, "CLOSED", null, null, null));
        assertEquals("CD", select(null, null, "archived", null, null, null));
        assertEquals("F", select(null, null, "In_Progress", null, null, null));
        assertEquals("", select(null, null, "DIAGNOSING", null, null, null));
        assertEquals("AB", select("INCIDENT", null, "OPEN", null, null, null));
    }

    @Test
    void priorityAndSeverityAreExactIgnoringCaseAndEdgeSpace() {
        assertEquals("ADE", select(null, null, null, "critical", null, null));
        assertEquals("BF", select(null, null, null, "  low ", null, null));
        assertEquals("BD", select(null, null, null, null, "MINOR", null));
        assertEquals("C", select(null, null, null, null, "CRITICAL", null));
    }

    @Test
    void categoryIsAPathPrefix() {
        assertEquals("ADE", select(null, null, null, null, null, "Pipeline"));
        assertEquals("AE", select(null, null, null, null, null, "Pipeline / Ingest"));
        assertEquals("", select(null, null, null, null, null, "Ingest"));
    }

    @Test
    void everySetCriterionMustHold() {
        assertEquals("AD", select("INCIDENT", null, null, "critical", null, "Pipeline"));
        assertEquals("E", select("CASE", "rejected", null, null, null, null));
    }

    /** The one deliberate difference: the tree's {@code startsWith} cannot be case-sensitive. */
    @Test
    void categoryPrefixIgnoresCaseOnTheTree() {
        assertEquals("ADE", select(null, null, null, null, null, "pipeline"));
    }
}
