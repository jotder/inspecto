package com.gamma.workflow;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * An Escalation Rule (ASSURE-WORKFLOW-SLA-1, GLOSSARY §9; the {@code escalation-rule} component kind,
 * {@code registry/escalation-rules/<id>.toon}): when an object of {@link #objectType} breaches an SLA target, or
 * reaches an age, the SLA sweep escalates it — reassign, notify, raise priority. Each rule fires at most ONCE per
 * breach (per object, per rule); the sweep records the firing on the object and audits it as an
 * {@code OBJECT_ESCALATED} event.
 *
 * <pre>
 * objectType: INCIDENT
 * on: breach            # breach | age
 * target: resolution    # breach only: resolution | response
 * afterMinutes: 120     # age only: wall-clock minutes since the object was opened
 * priority: CRITICAL    # optional: only objects of this priority (sugar: ANDed into {@link #matchTree()})
 * when:                 # optional: a condition group (the Condition Language) over the object's context -
 *   kind: group         #   type status priority severity category assignee ageMinutes minutesToDue
 *   op: AND             #   resolutionBreached responseBreached (0/1) escalated (0/1); ANDed with priority
 *   items[1]:
 *     - {kind: condition, field: ageMinutes, operator: ">=", value: "30"}
 * reassign: duty-manager
 * notify: true
 * raisePriority: true   # one step up the ladder LOW → MINOR → MAJOR → CRITICAL, never past CRITICAL
 * </pre>
 */
public record EscalationRule(String id, ObjectType objectType, Trigger on, String target, Long afterMinutes,
                             String priority, String reassign, boolean notifies, boolean raisePriority,
                             Map<String, Object> when) {

    public enum Trigger { BREACH, AGE }

    /** The Incident priority ladder, lowest first ({@code mail-model.ts INCIDENT_PRIORITIES}, reversed). */
    public static final List<String> PRIORITY_LADDER = List.of("LOW", "MINOR", "MAJOR", "CRITICAL");

    private static final Pattern SAFE_USER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._@-]{0,127}");

    /** The priority one step above {@code current}; {@code current} itself at the top or off the ladder. */
    public static String raised(String current) {
        int i = current == null ? -1 : PRIORITY_LADDER.indexOf(current.trim().toUpperCase(Locale.ROOT));
        if (i < 0) return current;
        return PRIORITY_LADDER.get(Math.min(i + 1, PRIORITY_LADDER.size() - 1));
    }

    /** Parse + validate an {@code escalation-rule} component; fail closed on anything malformed. */
    public static EscalationRule fromComponent(String id, Map<String, Object> c) {
        if (c == null) throw new IllegalArgumentException("escalation-rule content is required");
        ObjectType type = ObjectType.of(str(c.get("objectType")));
        if (type == null) throw new IllegalArgumentException("escalation-rule.objectType is required");
        String on = str(c.get("on"));
        Trigger trigger;
        try {
            trigger = Trigger.valueOf(on == null ? "" : on.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("escalation-rule.on must be 'breach' or 'age'");
        }
        String target = null;
        Long after = null;
        if (trigger == Trigger.BREACH) {
            target = str(c.get("target"));
            target = target == null ? "resolution" : target.toLowerCase(Locale.ROOT);
            if (!target.equals("resolution") && !target.equals("response"))
                throw new IllegalArgumentException("escalation-rule.target must be 'resolution' or 'response'");
            if (c.get("afterMinutes") != null) throw new IllegalArgumentException("afterMinutes applies to on: age only");
        } else {
            Object raw = c.get("afterMinutes");
            try {
                after = raw instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("escalation-rule on: age needs afterMinutes (whole minutes)");
            }
            if (after < 1 || after > SlaPolicy.MAX_MINUTES)
                throw new IllegalArgumentException("afterMinutes must be between 1 and " + SlaPolicy.MAX_MINUTES);
            if (c.get("target") != null) throw new IllegalArgumentException("target applies to on: breach only");
        }
        String priority = str(c.get("priority"));
        String reassign = str(c.get("reassign"));
        if (reassign != null && !SAFE_USER.matcher(reassign).matches())
            throw new IllegalArgumentException("escalation-rule.reassign must be a user id");
        boolean notify = bool(c.get("notify"), "notify");
        boolean raise = bool(c.get("raisePriority"), "raisePriority");
        // Opaque here (this module must not depend on the engine, which owns the Condition Language): the shape is
        // validated with ConditionTree wherever the rule is written and wherever it is loaded.
        Map<String, Object> when = null;
        if (c.get("when") != null) {
            if (!(c.get("when") instanceof Map<?, ?> w))
                throw new IllegalArgumentException("escalation-rule.when must be a condition group object");
            if (!w.isEmpty()) {
                when = new java.util.LinkedHashMap<>();
                for (Map.Entry<?, ?> e : w.entrySet()) when.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        if (reassign == null && !notify && !raise)
            throw new IllegalArgumentException("an Escalation Rule must do something: reassign, notify or raisePriority");
        return new EscalationRule(id, type, trigger, target, after,
                priority == null ? null : priority.toUpperCase(Locale.ROOT), reassign, notify, raise, when);
    }

    /**
     * The match as ONE condition group, or {@code null} when the rule narrows nothing: the {@code priority} sugar
     * (a case-insensitive {@code =} leaf, so a null or blank priority never matches) ANDed with {@link #when}.
     * Evaluated by the SLA sweep over the object's context row; the trigger ({@code on}) and the fire-once ledger
     * stay the sweep's own.
     */
    public Map<String, Object> matchTree() {
        if (priority == null && when == null) return null;
        List<Object> items = new java.util.ArrayList<>();
        if (priority != null) {
            Map<String, Object> leaf = new java.util.LinkedHashMap<>();
            leaf.put("kind", "condition");
            leaf.put("field", "priority");
            leaf.put("operator", "=");
            leaf.put("value", priority);
            leaf.put("ignoreCase", true);
            items.add(leaf);
        }
        if (when != null) items.add(when);
        Map<String, Object> group = new java.util.LinkedHashMap<>();
        group.put("kind", "group");
        group.put("op", "AND");
        group.put("items", items);
        return group;
    }

    private static boolean bool(Object o, String key) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        String s = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
        if (s.equals("true")) return true;
        if (s.equals("false")) return false;
        throw new IllegalArgumentException("escalation-rule." + key + " must be true or false");
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
