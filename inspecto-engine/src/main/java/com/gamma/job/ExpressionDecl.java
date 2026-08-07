package com.gamma.job;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One declared Expression — a {@code $}-token resolved at fire time (job-parameter-contract §4.1).
 * Doubles as the catalog entry a UI renders in its token picker, so the vocabulary is discoverable
 * instead of hidden in a switch.
 *
 * @param token       the declared surface: {@code $today} (LITERAL), {@code $signal.} (PREFIX) or
 *                    {@code $day(n)} (FUNCTION)
 * @param form        which of the three shapes {@code token} is (§2)
 * @param yields      the {@link ParamType} the token resolves to, so a UI can offer only tokens valid
 *                    for a field
 * @param description picker text
 * @param example     the worked sample a picker shows. For a shaped token (PREFIX/FUNCTION) that is an
 *                    instance an author can actually type (`$day(-1)`); for a LITERAL, whose typeable form
 *                    is the token itself, it is the value the token resolves to (`cron`). Both answer the
 *                    picker's one question — "what does this look like in practice"
 * @param availableIn the Trigger kinds the token is meaningful for ({@code $signal.*} means nothing on
 *                    a cron fire)
 * @param contextFree whether it resolves from fire time alone ({@code $today}) rather than needing a
 *                    firing context ({@code $signal.*}, {@code $upstream(…)}) — drives the catalog's
 *                    live preview
 */
public record ExpressionDecl(String token, Form form, ParamType yields, String description,
                             String example, Set<TriggerKind> availableIn, boolean contextFree) {

    /** The three token shapes (§2). {@link #PREFIX} matches {@code token + <rest>}; {@link #FUNCTION}
     *  matches on the {@code $name(} head, its argument parsed by the provider. */
    public enum Form { LITERAL, PREFIX, FUNCTION }

    /** A Job's Trigger kinds ({@code JobConfig}): {@code cron:} · {@code onPipeline:} · {@code onSignal:}
     *  · manual {@code POST}. */
    public enum TriggerKind { CRON, ON_PIPELINE, ON_SIGNAL, MANUAL }

    /** Meaningful on every Trigger kind — what a fire-time-only token declares. */
    public static final Set<TriggerKind> ANY_TRIGGER = Set.of(TriggerKind.values());

    /** A context-free token available on any Trigger — the common case (ten of today's fifteen). */
    public static ExpressionDecl literal(String token, ParamType yields, String description, String example) {
        return new ExpressionDecl(token, Form.LITERAL, yields, description, example, ANY_TRIGGER, true);
    }

    /** The head this token is matched by: the token itself for a prefix, {@code $name(} for a function. */
    String matchKey() {
        return switch (form) {
            case LITERAL, PREFIX -> token;
            case FUNCTION -> token.substring(0, token.indexOf('(') + 1);
        };
    }

    /** A concrete expression the registry can actually evaluate for the catalog's live preview (§4.3):
     *  a literal's own token, or the shaped forms' typeable {@link #example} — {@code $day(n)} is a shape,
     *  {@code $day(-1)} is what resolves. */
    String sampleExpression() {
        return form == Form.LITERAL ? token : example;
    }

    /** JSON view for the catalog route, mirroring {@link JobTypeDescriptor#toMap()}'s style. {@code preview}
     *  is supplied by the registry, which is the only thing that may evaluate (§4.3). */
    Map<String, Object> toMap(String preview) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", token);
        m.put("form", form.name());
        m.put("yields", yields.name());
        m.put("description", description);
        m.put("example", example);
        m.put("availableIn", availableIn.stream().map(t -> t.name().toLowerCase(Locale.ROOT)).sorted().toList());
        m.put("contextFree", contextFree);
        m.put("preview", preview);
        return m;
    }
}
