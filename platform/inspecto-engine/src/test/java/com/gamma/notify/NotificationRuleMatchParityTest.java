package com.gamma.notify;

import com.gamma.audit.Event;
import com.gamma.audit.EventLevel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Golden corpus for {@link NotificationRule#matches} — the (rule, event) pairs that fire, pinned independently of
 * how they are computed. Written against the hand-coded matcher ({@code equalsIgnoreCase} + {@code atLeast}) and
 * kept green when the rule moved onto the Condition Language tree (Decision Kernel step 4).
 */
class NotificationRuleMatchParityTest {

    private static NotificationRule rule(String type, EventLevel min, boolean enabled) {
        return new NotificationRule("r", type, min, "ops", "{{type}}", "{{message}}", "{{type}}", enabled);
    }

    private static Event ev(String type, EventLevel level) {
        return Event.builder(type).level(level).message("m").build();
    }

    /** One char per event of a fixed corpus: '1' when the rule fires on it. */
    private static String fires(NotificationRule r) {
        List<Event> corpus = new ArrayList<>();
        for (EventLevel l : EventLevel.values()) corpus.add(ev("job.failed", l));
        corpus.add(ev("JOB.FAILED", EventLevel.WARN));
        corpus.add(ev("job.failed.extra", EventLevel.ERROR));
        corpus.add(ev("job_failed", EventLevel.ERROR));          // '_' is not a wildcard
        corpus.add(ev("job.%", EventLevel.ERROR));
        corpus.add(ev("it's;--\"x", EventLevel.ERROR));          // hostile
        StringBuilder sb = new StringBuilder();
        for (Event e : corpus) sb.append(r.matches(e) ? '1' : '0');
        return sb.toString();
    }

    @Test
    void typeIsExactIgnoringCaseAndAnyLevelWhenNoMinimum() {
        assertEquals("1111110000", fires(rule("job.failed", null, true)));
        assertEquals("1111110000", fires(rule("JOB.Failed", null, true)));
    }

    @Test
    void minLevelIsAnInclusiveFloorOnTheSeverityLadder() {
        assertEquals("0011110000", fires(rule("job.failed", EventLevel.INFO, true)));
        assertEquals("0000100000", fires(rule("job.failed", EventLevel.ERROR, true)));
        assertEquals("1111110000", fires(rule("job.failed", EventLevel.TRACE, true)));
    }

    @Test
    void hostileTypesMatchOnlyThemselves() {
        assertEquals("0000000001", fires(rule("it's;--\"x", null, true)));
        assertEquals("0000000010", fires(rule("job.%", null, true)));
        assertEquals("0000000100", fires(rule("job_failed", null, true)));
    }

    @Test
    void disabledAndNullEventNeverFire() {
        assertEquals("0000000000", fires(rule("job.failed", null, false)));
        assertFalse(rule("job.failed", null, true).matches(null));
    }

    /** Deliberate difference, pinned: a blank event type fires nothing (the old matcher fired only on a blank-typed event). */
    @Test
    void aBlankEventTypeFiresNothing() {
        assertEquals("0000000000", fires(rule("", null, true)));
        assertEquals("0000000000", fires(rule("  ", null, true)));
    }

    /** The authoring form still exposes its meaning as a tree the builder can render. */
    @Test
    void treeExpandsTheFlatFields() {
        assertEquals(1, ((List<?>) rule("a", null, true).tree().get("items")).size());
        assertEquals(2, ((List<?>) rule("a", EventLevel.WARN, true).tree().get("items")).size());
    }
}
