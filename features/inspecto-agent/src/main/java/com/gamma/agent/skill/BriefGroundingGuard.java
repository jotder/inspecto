package com.gamma.agent.skill;

import com.gamma.agent.kernel.tool.CredibilityTier;
import com.gamma.agent.kernel.tool.Evidence;
import com.gamma.agent.kernel.tool.GroundingGuard;
import com.gamma.agent.kernel.tool.NumericGroundingGuard;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rejection rules for a model-written Brief line (BI-INSIGHTS-1 P4 §5.2, D-BI18). The existing
 * {@link NumericGroundingGuard} lets "5" ground "4.6" and attributes one slice's figure to another's
 * sentence, so these rules are the first net and that guard is only the second ({@link Rule#BELT}).
 *
 * <p>Each {@link Rule} can be switched off in a package-private constructor <em>for the whole-feature
 * mutant tests only</em>; production always uses {@link #all()}.
 */
final class BriefGroundingGuard {

    enum Rule { LITERAL_DIGIT, LITERAL_NAME, SLOT, CITATION, MARKDOWN, BOTH_DIRECTIONS, DIRECTION, CAUSE, BELT }

    /** A validated line: the cited Insight's 1-based number and the skeleton (still with its slots). */
    record Line(int n, String skeleton) {}

    private static final Pattern CITE = Pattern.compile("^\\s*\\[(\\d+)\\]\\s*(.*)$", Pattern.DOTALL);
    private static final Pattern ANY_CITE = Pattern.compile("\\[\\d+\\]");
    private static final Pattern MARKUP = Pattern.compile("[*_`~<>#|\\\\\\[\\]]|://|^\\s*[-+>]");

    private static final List<String> UP = List.of("up", "rose", "rise", "rises", "risen", "rising", "climbed", "climb",
            "climbing", "climbs", "grew", "grow", "grown", "growing", "growth", "higher", "increase", "increased",
            "increases", "increasing", "surged", "surge", "jumped", "soared", "gained", "spiked");
    private static final List<String> DOWN = List.of("down", "fell", "fall", "falls", "fallen", "falling", "dropped", "drop",
            "drops", "dropping", "slid", "slide", "sliding", "lower", "decrease", "decreased", "decreases", "decreasing",
            "declined", "decline", "dipped", "dip", "shrank", "shrunk", "plunged", "slumped");
    private static final List<String> CAUSE = List.of("because", "due to", "caused", "causes", "causing", "driven by",
            "as a result", "thanks to", "reason", "owing to", "led to", "leading to", "result of", "resulted", "therefore",
            "hence", "attributed to", "explained by");

    private final Set<Rule> enabled;

    BriefGroundingGuard(Set<Rule> enabled) { this.enabled = EnumSet.copyOf(enabled); }

    static BriefGroundingGuard all() { return new BriefGroundingGuard(EnumSet.allOf(Rule.class)); }

    /** Accepts one model line or throws {@link IllegalArgumentException} with the reason fed back to the model. */
    Line parse(String raw, List<BriefSlots.Insight> insights) {
        Matcher c = CITE.matcher(raw == null ? "" : raw);
        if (!c.matches()) throw new IllegalArgumentException("a line must start with [n], the number of the one Insight it is about");
        int n;
        try { n = Integer.parseInt(c.group(1)); } catch (NumberFormatException e) { n = -1; }
        if (n < 1 || n > insights.size())
            throw new IllegalArgumentException("[" + c.group(1) + "] cites no Insight (use 1 to " + insights.size() + ")");
        BriefSlots.Insight cited = insights.get(n - 1);
        String body = c.group(2).trim();
        if (body.isEmpty()) throw new IllegalArgumentException("the line has no text after [" + n + "]");

        if (on(Rule.CITATION) && ANY_CITE.matcher(body).find())
            throw new IllegalArgumentException("a line cites exactly one Insight: remove the second [n]");
        if (on(Rule.MARKDOWN) && MARKUP.matcher(body).find())
            throw new IllegalArgumentException("plain text only: no markdown, HTML, links or special characters");
        if (on(Rule.LITERAL_DIGIT) && body.codePoints().anyMatch(Character::isDigit))
            throw new IllegalArgumentException("no digits: write {value}, {expected}, {delta}, {pct} or {period} and the server fills the figure");
        if (on(Rule.SLOT)) {
            for (String s : BriefSlots.slotsIn(body))
                if (!BriefSlots.SLOTS.contains(s))
                    throw new IllegalArgumentException("unknown slot {" + s + "}: use only " + BriefSlots.SLOTS);
            if (body.replaceAll("\\{[a-z_]+\\}", "").matches(".*[{}].*"))
                throw new IllegalArgumentException("stray brace: a slot is written {name}");
        }
        String norm = " " + body.toLowerCase().replaceAll("[^a-z ]+", " ").replaceAll(" +", " ") + " ";
        if (on(Rule.CAUSE)) {
            for (String w : CAUSE)
                if (norm.contains(" " + w + " "))
                    throw new IllegalArgumentException("'" + w + "' states a cause: an Insight says what moved, never why");
        }
        if (on(Rule.LITERAL_NAME)) {
            for (BriefSlots.Insight i : insights) {
                String name = i.slice().toLowerCase();
                if (name.length() >= 3 && body.toLowerCase().contains(name))
                    throw new IllegalArgumentException("do not write the name '" + i.slice() + "': write {slice}");
            }
        }
        String up = first(norm, UP), down = first(norm, DOWN);
        if (on(Rule.BOTH_DIRECTIONS) && up != null && down != null)
            throw new IllegalArgumentException("both directions in one line ('" + up + "' and '" + down + "'): pick the one the Insight has");
        if (on(Rule.DIRECTION)) {
            if (up != null && cited.delta() <= 0)
                throw new IllegalArgumentException("direction word '" + up + "' says up, but Insight [" + n + "] did not go up");
            if (down != null && cited.delta() >= 0)
                throw new IllegalArgumentException("direction word '" + down + "' says down, but Insight [" + n + "] did not go down");
        }
        return new Line(n, body);
    }

    /** The second net: the rendered paragraph's numbers must all be ones the cited Insights hold. */
    void checkRendered(String paragraph, List<BriefSlots.Insight> insights) {
        if (!on(Rule.BELT)) return;
        List<Evidence> allowed = new ArrayList<>();
        for (BriefSlots.Insight i : insights)
            allowed.add(new Evidence(i.groundedNumbers(), CredibilityTier.AUTHORITATIVE, null, i.id(), 1.0, null));
        GroundingGuard.Verdict v = new NumericGroundingGuard().check(paragraph, allowed);
        if (!v.grounded()) throw new IllegalArgumentException("ungrounded figure(s): " + String.join(", ", v.ungrounded()));
    }

    private boolean on(Rule r) { return enabled.contains(r); }

    private static String first(String norm, List<String> words) {
        for (String w : words) if (norm.contains(" " + w + " ")) return w;
        return null;
    }
}
