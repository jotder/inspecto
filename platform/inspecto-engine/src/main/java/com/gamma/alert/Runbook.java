package com.gamma.alert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A <b>Runbook</b> (the {@code runbook} component kind, operator 2026-10-10): linked guidance for the person who
 * works an Incident or Case. It automates nothing. An Alert Rule names one with {@code runbook: <id>}, and the
 * Incident or Case that rule raises shows it on its page.
 *
 * <p>Content shape: {@code title} (required), optional {@code summary}, {@code steps} (an ordered, non-empty list of
 * {@code {text, link?}}, where {@code link} is {@code {kind, id}} and {@code kind} is one of {@link #LINK_KINDS}),
 * optional {@code ownerRole} (the role that owns the guidance; not {@code owner}, which is the R3 author stamp) and optional {@code tags}. {@code name} is the id the store
 * stamps. A key outside these is refused unless it is an author's {@code x-} annotation. {@link #fromMap} is the one
 * structural validator; every write door calls it. A step link is checked for shape only, not for existence.
 */
public record Runbook(String name, String title, String summary, List<Step> steps, String ownerRole, List<String> tags) {

    /** What a step may link to. */
    public static final Set<String> LINK_KINDS = Set.of("dataset", "query", "dashboard", "case");
    static final Set<String> MODELLED = Set.of("name", "title", "summary", "steps", "ownerRole", "tags",
            "owner", "shares");   // owner + shares: the R3 envelope ComponentAccess stamps at create
    private static final Set<String> STEP_KEYS = Set.of("text", "link");
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** One ordered step: its text, and an optional link to a Dataset, Query, Dashboard or Case. */
    public record Step(String text, String linkKind, String linkId) {}

    /** Parse and validate a stored {@code runbook} component's content. */
    public static Runbook fromMap(String id, Map<String, Object> m) {
        require(m != null, "runbook content is missing");
        for (String k : m.keySet())
            require(MODELLED.contains(k) || k.startsWith(AlertRule.AUTHOR_PREFIX),
                    "runbook key '" + k + "' is not part of a Runbook; remove it, or prefix an annotation with 'x-'");
        String title = text(m.get("title"));
        require(title != null, "runbook.title is required");
        require(m.get("steps") instanceof List<?> l && !l.isEmpty(), "runbook.steps must be a non-empty list");
        List<Step> steps = new ArrayList<>();
        int i = 0;
        for (Object o : (List<?>) m.get("steps")) {
            String at = "runbook.steps[" + i++ + "]";
            require(o instanceof Map<?, ?>, at + " must be an object with a 'text'");
            Map<?, ?> s = (Map<?, ?>) o;
            for (Object k : s.keySet()) require(STEP_KEYS.contains(String.valueOf(k)), at + " key '" + k + "' is not part of a step");
            String stepText = text(s.get("text"));
            require(stepText != null, at + ".text is required");
            String kind = null, linkId = null;
            if (s.get("link") != null) {
                require(s.get("link") instanceof Map<?, ?>, at + ".link must be {kind, id}");
                Map<?, ?> link = (Map<?, ?>) s.get("link");
                kind = text(link.get("kind"));
                linkId = text(link.get("id"));
                require(kind != null && LINK_KINDS.contains(kind), at + ".link.kind must be one of " + LINK_KINDS.stream().sorted().toList());
                require(linkId != null && ID.matcher(linkId).matches(), at + ".link.id must be an id");
            }
            steps.add(new Step(stepText, kind, linkId));
        }
        List<String> tags = new ArrayList<>();
        if (m.get("tags") != null) {
            require(m.get("tags") instanceof List<?>, "runbook.tags must be a list");
            for (Object t : (List<?>) m.get("tags")) {
                String tag = text(t);
                require(tag != null, "runbook.tags may not hold a blank tag");
                tags.add(tag);
            }
        }
        return new Runbook(id, title, text(m.get("summary")), List.copyOf(steps), text(m.get("ownerRole")), List.copyOf(tags));
    }

    private static String text(Object o) {
        if (o == null || o instanceof Map<?, ?> || o instanceof List<?>) return null;   // an empty TOON key arrives as {}
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }
}
