package com.gamma.notify;

import com.gamma.audit.Event;
import com.gamma.audit.EventLevel;
import com.gamma.query.ConditionTree;
import com.gamma.util.Values;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a triggering {@link Event} to an in-app {@link Notification}. The rule decouples <em>what</em> to
 * notify about (event type + minimum severity) from the <em>copy</em> ({@link NotificationTemplate}
 * title/body) and the de-duplication key — so wording can move to config without touching code. Built-in
 * rules ({@link NotificationRules#defaults()}) use a synthetic {@code id}; authored rules are persisted as
 * a {@code notification-rule} component via the {@code /notifications/rules*} admin CRUD (mirrors
 * {@link ChannelConfig}) and checked ahead of the built-ins by {@link NotificationRules#forEvent}.
 *
 * @param id                unique identifier (storage key for authored rules; a synthetic slug for built-ins)
 * @param eventType         the {@link com.gamma.audit.EventType} this rule fires on (case-insensitive)
 * @param minLevel          minimum severity, or {@code null} for any
 * @param category          notification category (also the preference key gating delivery)
 * @param titleTemplate     {@code {{var}}} template for the headline
 * @param bodyTemplate      {@code {{var}}} template for the detail line
 * @param dedupeKeyTemplate {@code {{var}}} template for the collapse key (repeat suppression)
 * @param enabled           {@code false} disables the rule without deleting it
 * @since 4.0.0
 */
public record NotificationRule(String id, String eventType, EventLevel minLevel, String category,
                               String titleTemplate, String bodyTemplate, String dedupeKeyTemplate,
                               boolean enabled) {

    /** {@code true} when {@code e} should fire this rule — {@link #tree()} evaluated over {@link #matchContext}.
     *  A blank {@code eventType} fires nothing (an empty tree leaf would otherwise read as "no constraint"). */
    public boolean matches(Event e) {
        return enabled && e != null && eventType != null && !eventType.isBlank()
                && ConditionTree.matched(tree(), List.of(matchContext(e))) == 1;
    }

    /**
     * The match as a condition tree (Decision Kernel step 4) — {@code eventType} / {@code minLevel} stay the
     * authoring form, this is what they mean: {@code type} equals the event type ignoring case, and
     * {@code levelRank} is at least the minimum's rank. Evaluated over {@link #matchContext}.
     */
    public Map<String, Object> tree() {
        List<Object> all = new ArrayList<>();
        all.add(leaf("type", "=", eventType, true));
        if (minLevel != null) all.add(leaf("levelRank", ">=", String.valueOf(minLevel.ordinal()), false));
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("kind", "group");
        g.put("op", "AND");
        g.put("items", all);
        return g;
    }

    /** The row the match tree is evaluated over: {@code type} and {@code levelRank} (the {@link EventLevel}
     *  ordinal, so a minimum is a numeric floor). */
    static Map<String, Object> matchContext(Event e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", e.type());
        row.put("levelRank", e.level().ordinal());
        return row;
    }

    private static Map<String, Object> leaf(String field, String operator, String value, boolean ignoreCase) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("kind", "condition");
        c.put("field", field);
        c.put("operator", operator);
        c.put("value", value);
        if (ignoreCase) c.put("ignoreCase", true);
        return c;
    }

    /**
     * Parse + validate an authored rule from a request/stored map. {@code id}/{@code eventType}/{@code
     * category} are required (blank → {@link IllegalArgumentException} → 422); {@code titleTemplate}/
     * {@code bodyTemplate}/{@code dedupeKeyTemplate} default to {@code {{message}}}/{@code {{type}}} when
     * absent; {@code minLevel} is optional ({@code null}/blank ⇒ any severity; an unknown name → 422);
     * {@code enabled} defaults true.
     */
    public static NotificationRule fromMap(Map<String, Object> m) {
        String id = require(m, "id");
        String eventType = require(m, "eventType");
        String category = require(m, "category");
        EventLevel minLevel = level(Values.blankToNull(m.get("minLevel")));
        String title = m.get("titleTemplate") == null ? "{{type}}" : String.valueOf(m.get("titleTemplate"));
        String body = m.get("bodyTemplate") == null ? "{{message}}" : String.valueOf(m.get("bodyTemplate"));
        String dedupe = m.get("dedupeKeyTemplate") == null ? "{{type}}:{{correlationId}}"
                : String.valueOf(m.get("dedupeKeyTemplate"));
        boolean enabled = !(m.get("enabled") instanceof Boolean b) || b;
        return new NotificationRule(id, eventType, minLevel, category, title, body, dedupe, enabled);
    }

    /** The wire shape the UI's rule editor consumes. */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("eventType", eventType);
        out.put("minLevel", minLevel == null ? null : minLevel.name());
        out.put("category", category);
        out.put("titleTemplate", titleTemplate);
        out.put("bodyTemplate", bodyTemplate);
        out.put("dedupeKeyTemplate", dedupeKeyTemplate);
        out.put("enabled", enabled);
        return out;
    }

    private static EventLevel level(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return EventLevel.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown minLevel '" + name + "'");
        }
    }

    private static String require(Map<String, Object> m, String key) {
        String v = Values.blankToNull(m.get(key));
        if (v == null) throw new IllegalArgumentException("id, eventType and category are required");
        return v;
    }

    /** Render a fresh UNREAD notification for {@code e} from this rule's templates. */
    public Notification render(Event e) {
        Map<String, Object> ctx = context(e);
        return Notification.create(category, e.type(), e.correlationId(),
                NotificationTemplate.render(titleTemplate, ctx),
                NotificationTemplate.render(bodyTemplate, ctx),
                NotificationTemplate.render(dedupeKeyTemplate, ctx),
                e.attributes().get(Notification.RECIPIENT_ATTR));
    }

    /** The interpolation context for an event: top-level fields, {@code time} (event-signal-backbone-plan
     *  §4.4's {@code {{time}}}), the legacy flat {@code attributes} and the structured {@code payload}
     *  (S0's {@link Event#payload()}) side by side, and the recipient. */
    static Map<String, Object> context(Event e) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("eventId", e.eventId());
        ctx.put("type", e.type());
        ctx.put("level", e.level().name());
        ctx.put("source", e.source());
        ctx.put("pipeline", e.pipeline());
        ctx.put("correlationId", e.correlationId());
        ctx.put("message", e.message());
        ctx.put("ts", e.ts());
        ctx.put("time", e.timestamp());
        ctx.put("attributes", e.attributes());
        ctx.put("payload", e.payload());
        // An addressed event (an owned Alert Rule's, DUCKLE-C1 residual 2) names its recipient, a Subject id;
        // anything else is a broadcast and keeps the auth-free core's placeholder, appUser.
        String recipient = e.attributes().get(Notification.RECIPIENT_ATTR);
        if (recipient == null || recipient.isBlank()) recipient = "appUser";
        ctx.put("recipient", Map.of("first_name", recipient, "name", recipient));
        return ctx;
    }
}
