package com.gamma.agent.skill;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.agent.kernel.tool.CredibilityTier;
import com.gamma.agent.kernel.tool.Evidence;
import com.gamma.agent.kernel.tool.NumericGroundingGuard;
import com.gamma.agent.skill.BriefGroundingGuard.Rule;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * One test per rejection class of the Brief grounding contract (BI-INSIGHTS-1 P4 §5.2), each paired with a
 * mutant: with that one rule disabled the same input is accepted (or refused for a different reason), so the
 * test cannot pass without the rule.
 */
class BriefGroundingGuardTest {

    /** Insight 1 rose (delta > 0) in Hamburg; Insight 2 fell (delta < 0) in Berlin. */
    static final List<BriefSlots.Insight> EVIDENCE = List.of(
            BriefSlots.Insight.of(1, Map.ofEntries(
                    Map.entry("insightId", "ins-1"), Map.entry("type", "spike"), Map.entry("measure", "orders"),
                    Map.entry("dimension", "city"), Map.entry("slice", "Hamburg"), Map.entry("period", "2026-11-01"),
                    Map.entry("value", 4.6), Map.entry("expected", 3.0), Map.entry("delta", 1.6),
                    Map.entry("band", "high"), Map.entry("sentence", "orders for city = Hamburg was 4.60 in 2026-11"))),
            BriefSlots.Insight.of(2, Map.ofEntries(
                    Map.entry("insightId", "ins-2"), Map.entry("type", "spike"), Map.entry("measure", "orders"),
                    Map.entry("dimension", "city"), Map.entry("slice", "Berlin"), Map.entry("period", "2026-11-01"),
                    Map.entry("value", 2.0), Map.entry("expected", 6.0), Map.entry("delta", -4.0),
                    Map.entry("band", "elevated"), Map.entry("sentence", "orders for city = Berlin was 2.00 in 2026-11"))));

    private static BriefGroundingGuard without(Rule r) {
        EnumSet<Rule> rules = EnumSet.allOf(Rule.class);
        rules.remove(r);
        return new BriefGroundingGuard(rules);
    }

    private static String reasonOf(BriefGroundingGuard g, String raw) {
        return assertThrows(IllegalArgumentException.class, () -> g.parse(raw, EVIDENCE)).getMessage();
    }

    @Test
    void aGoodSkeletonParses() {
        var line = BriefGroundingGuard.all().parse("[1] {measure} for {slice} rose to {value} against {expected}.", EVIDENCE);
        assertEquals(1, line.n());
        assertEquals("orders for Hamburg rose to 4.60 against 3.00.", BriefSlots.render(line.skeleton(), EVIDENCE.get(0)));
    }

    @Test
    void aLiteralDigitIsRejected() {
        String raw = "[1] {measure} rose to 5 in {period}.";
        assertTrue(reasonOf(BriefGroundingGuard.all(), raw).contains("digit"));
        assertEquals(1, without(Rule.LITERAL_DIGIT).parse(raw, EVIDENCE).n(), "mutant: accepted without the rule");
    }

    @Test
    void fiveIsNotGroundedByFourPointSixEvenThoughTheNumericGuardAllowsIt() {
        // the existing guard's rounded-integer rule lets "5" ground "4.6": the reason the digit rule exists
        var evidence = List.of(new Evidence(Map.of("value", 4.6), CredibilityTier.AUTHORITATIVE, null, "x", 1.0, null));
        assertTrue(new NumericGroundingGuard().check("orders rose to 5", evidence).grounded());
        assertFalse(reasonOf(BriefGroundingGuard.all(), "[1] orders rose to 5").isBlank());
    }

    @Test
    void anotherInsightsSliceCannotAppearInTheLine() {
        String raw = "[1] {measure} in Berlin rose.";   // Berlin is Insight 2's slice
        assertTrue(reasonOf(BriefGroundingGuard.all(), raw).contains("name"));
        assertEquals(1, without(Rule.LITERAL_NAME).parse(raw, EVIDENCE).n(), "mutant");
    }

    @Test
    void slotsResolveFromTheCitedInsightOnly() {
        var line = BriefGroundingGuard.all().parse("[2] {slice} fell to {value}.", EVIDENCE);
        String text = BriefSlots.render(line.skeleton(), EVIDENCE.get(line.n() - 1));
        assertEquals("Berlin fell to 2.00.", text);
        assertFalse(text.contains("Hamburg") || text.contains("4.60"));
    }

    @Test
    void unknownSlotIsRejected() {
        String raw = "[1] {measure} rose by {secret}.";
        assertTrue(reasonOf(BriefGroundingGuard.all(), raw).contains("slot"));
        assertEquals(1, without(Rule.SLOT).parse(raw, EVIDENCE).n(), "mutant");
    }

    @Test
    void wrongDirectionIsRejected() {
        String raw = "[1] {measure} for {slice} fell.";   // Insight 1 rose
        assertTrue(reasonOf(BriefGroundingGuard.all(), raw).contains("'fell'"));
        assertEquals(1, without(Rule.DIRECTION).parse(raw, EVIDENCE).n(), "mutant");
        // and the mirror image: Insight 2 fell
        assertTrue(reasonOf(BriefGroundingGuard.all(), "[2] {measure} climbed.").contains("'climbed'"));
    }

    @Test
    void bothDirectionsInOneLineAreRejectedWithTheirOwnReason() {
        String raw = "[1] {measure} rose and then fell.";
        assertTrue(reasonOf(BriefGroundingGuard.all(), raw).contains("both directions"));
        // DIRECTION alone also refuses it (strict sign), but for the other reason: the message tells the rules apart
        String mutant = reasonOf(without(Rule.BOTH_DIRECTIONS), raw);
        assertFalse(mutant.contains("both directions"), mutant);
    }

    @Test
    void aCauseWordIsRejected() {
        for (String raw : List.of("[1] {measure} rose because of the weather.", "[1] {measure} rose due to a promotion.",
                "[1] {measure} rose, driven by Hamburg.", "[1] {measure} rose as a result of the launch.")) {
            assertTrue(reasonOf(BriefGroundingGuard.all(), raw).contains("cause"), raw);
        }
        assertEquals(1, without(Rule.CAUSE).parse("[1] {measure} rose because of {slice}.", EVIDENCE).n(), "mutant");
    }

    @Test
    void markdownAndHtmlAreRejected() {
        for (String raw : List.of("[1] **{measure}** rose.", "[1] {measure} rose <b>sharply</b>.", "[1] `{measure}` rose.",
                "[1] {measure} rose [see](http://evil.example/x).", "[1] # {measure} rose.", "[1] {measure} rose | x")) {
            assertTrue(reasonOf(BriefGroundingGuard.all(), raw).contains("plain text"), raw);
        }
        assertEquals(1, without(Rule.MARKDOWN).parse("[1] **{measure}** rose.", EVIDENCE).n(), "mutant");
    }

    @Test
    void aLineMustCiteExactlyOneKnownInsight() {
        for (String raw : List.of("{measure} rose.", "[3] {measure} rose.", "[0] {measure} rose.",
                "[1] [2] {measure} rose.", "[1] {measure} rose [2]")) {
            assertFalse(reasonOf(BriefGroundingGuard.all(), raw).isBlank(), raw);
        }
        assertTrue(reasonOf(BriefGroundingGuard.all(), "{measure} rose.").contains("start with"));
        assertTrue(reasonOf(BriefGroundingGuard.all(), "[1] {measure} rose [2]").contains("exactly one"));
        // mutant: a second marker is then left to the markup rule, whose reason is different
        assertFalse(reasonOf(without(Rule.CITATION), "[1] {measure} rose [2]").contains("exactly one"));
    }

    @Test
    void theRenderedParagraphIsCheckedAgainstTheEvidenceAsASecondNet() {
        var g = BriefGroundingGuard.all();
        g.checkRendered("orders for Hamburg rose to 4.60", EVIDENCE);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> g.checkRendered("orders rose to 9999", EVIDENCE))
                .getMessage().contains("ungrounded"));
        without(Rule.BELT).checkRendered("orders rose to 9999", EVIDENCE);   // mutant: nothing checked
    }
}
