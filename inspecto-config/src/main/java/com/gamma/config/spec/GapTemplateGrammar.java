package com.gamma.config.spec;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a Collector's {@code gap_detection.file_template} / {@code seq_scope} pair may say (completeness KPI
 * K2, operator 2026-10-06). The one grammar behind two gates that must never disagree: the engine's
 * fail-closed refusal at config load ({@code PipelineConfigParser}) and the {@code CrossFieldRule} in
 * {@link ConfigSpecs#pipeline()} that turns the same refusal into a 422 at authoring time.
 *
 * <p>⚠ Structural only: a literal prefix, exactly one {@code {datePattern}} token, a {@code {seq}} token,
 * an optional trailing {@code *}. Whether the date pattern itself parses is checked where it is used
 * ({@code FileSequenceGaps}), which lives in a module this one cannot import.
 *
 * <p>⛔ It is separate from {@code gap_detection.sequence}, which keeps {@code GapDetector}'s one-token
 * grammar and its running detector untouched.
 */
public final class GapTemplateGrammar {

    private GapTemplateGrammar() {}

    /** The two {@code seq_scope} values — {@code FileSequenceGaps.SeqScope}'s constants, spelled here. */
    public static final List<String> SCOPES = List.of("PER_BUCKET", "CONTINUOUS");

    private static final Pattern TOKEN = Pattern.compile("\\{([^{}]*)}");

    /**
     * Why the pair is not acceptable, or {@code null} if it is. Both absent is acceptable (no file template).
     * ⛔ {@code seq_scope} is never defaulted: a wrong default invents a gap at every bucket boundary or
     * hides every real one, so a template without it is refused.
     */
    public static String refusal(String template, String scope) {
        boolean hasTemplate = template != null && !template.isBlank();
        boolean hasScope = scope != null && !scope.isBlank();
        if (!hasTemplate)
            return hasScope ? "collector.gap_detection.seq_scope is set but file_template is not — a scope "
                    + "describes a {seq} template; add collector.gap_detection.file_template or remove seq_scope"
                    : null;
        int seq = 0, date = 0;
        Matcher m = TOKEN.matcher(template);
        while (m.find()) {
            if ("seq".equals(m.group(1))) seq++;
            else if (!m.group(1).isBlank()) date++;
            else return "collector.gap_detection.file_template has an empty {} token: " + template;
        }
        if (seq != 1 || date != 1)
            return "collector.gap_detection.file_template must hold exactly one {datePattern} and one {seq} "
                    + "token, e.g. \"CDR_{yyyyMMddHH}_{seq}_*\" — got: " + template;
        if (!hasScope)
            return "collector.gap_detection.file_template needs collector.gap_detection.seq_scope ("
                    + String.join(" | ", SCOPES) + "): whether {seq} restarts per time bucket is a fact "
                    + "about the feed and is never guessed";
        if (!SCOPES.contains(scope.trim().toUpperCase()))
            return "collector.gap_detection.seq_scope must be one of " + SCOPES + " — got: " + scope;
        return null;
    }
}
