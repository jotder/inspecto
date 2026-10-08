package com.gamma.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The PURE half of the SLA sweep (plan 8a / MODULE-REORG-1 P7 Workflow &amp; SLA slice 2): every decision the sweep
 * takes over a {@link GovernedItem} - is the clock stopped, which deadlines does the policy stamp, has a response or
 * resolution breach to be recorded, which Escalation Rule fires now and what does the fire-once ledger become. No
 * store, no events, no engine: the host applies the returned decisions through its own writes (and keeps the
 * per-sweep cap, the optimistic-version conflict skip and the event emission). The persisted attribute names are the
 * contract with data already on disk and must not change.
 */
public final class SlaDecisions {

    /** Attribute key holding the resolution deadline as epoch millis (string). */
    public static final String ATTR_DUE_AT = "dueAt";
    /** Attribute key stamped (epoch millis) when a resolution breach has been recorded - makes the sweep idempotent. */
    public static final String ATTR_SLA_BREACHED_AT = "slaBreachedAt";
    /** Attribute key: the response deadline (epoch millis) an {@link SlaPolicy} stamped. */
    public static final String ATTR_RESPONSE_DUE_AT = "responseDueAt";
    /** Attribute key stamped (epoch millis) when a response breach has been recorded. */
    public static final String ATTR_SLA_RESPONSE_BREACHED_AT = "slaResponseBreachedAt";
    /** Attribute key: the priority the policy-stamped deadlines were computed for - a changed priority recomputes them. */
    public static final String ATTR_SLA_PRIORITY = "slaPriority";
    /** Attribute key: the {@link SlaPolicy} kind whose targets set this item's deadlines. */
    public static final String ATTR_SLA_POLICY = "slaPolicy";
    /** Attribute key: the fire-once ledger, comma-separated {@code <rule>@<breach>}. */
    public static final String ATTR_ESCALATIONS = "escalations";

    private SlaDecisions() {}

    /** Evaluates an Escalation Rule's condition tree over one context row (the host passes the engine's Condition Language). */
    @FunctionalInterface
    public interface ItemMatcher {
        /** {@code true} when {@code tree} matches {@code row}. */
        boolean matches(Map<String, Object> tree, Map<String, Object> row);
    }

    /** One Escalation Rule firing: the breach {@code marker} it answers and the ledger the item's attribute becomes. */
    public record Firing(EscalationRule rule, String marker, String ledger) {}

    /**
     * The sweep's stop set: settled, any terminal state of the registered workflow, {@code RESOLVED} (the resolution
     * clock stops) and {@code ARCHIVED} (dismissed, even where not terminal).
     */
    public static boolean stopped(GovernedItem o, Workflow wf) {
        return o.closed() || wf.isTerminal(o.status())
                || "RESOLVED".equalsIgnoreCase(o.status()) || "ARCHIVED".equalsIgnoreCase(o.status());
    }

    /**
     * The deadlines {@code policy} sets for the item's priority, counted in the policy's business calendar from
     * {@code createdAt}; {@code null} when nothing is to be written. An operator-set {@code dueAt} (one no policy
     * stamped) is left alone; policy-stamped ones are recomputed when the priority changes, until a breach has been
     * recorded.
     */
    public static Map<String, String> deadlineStamp(GovernedItem o, SlaPolicy policy) {
        Map<String, String> a = o.attributes();
        boolean operatorSet = a.get(ATTR_DUE_AT) != null && !a.get(ATTR_DUE_AT).isBlank() && a.get(ATTR_SLA_POLICY) == null;
        if (operatorSet || a.containsKey(ATTR_SLA_BREACHED_AT)) return null;
        String priority = o.priority() == null ? "" : o.priority().trim().toUpperCase(Locale.ROOT);
        if (a.get(ATTR_SLA_POLICY) != null && priority.equals(a.get(ATTR_SLA_PRIORITY))) return null;
        SlaPolicy.Target t = policy.targetFor(priority).orElse(null);
        if (t == null) return null;
        Map<String, String> stamp = new LinkedHashMap<>();
        stamp.put(ATTR_SLA_POLICY, policy.objectType().name());
        stamp.put(ATTR_SLA_PRIORITY, priority);
        if (t.resolutionMinutes() != null)
            stamp.put(ATTR_DUE_AT, Long.toString(policy.calendar().addWorkingMinutes(o.createdAt(), t.resolutionMinutes())));
        if (t.responseMinutes() != null && !a.containsKey(ATTR_SLA_RESPONSE_BREACHED_AT))
            stamp.put(ATTR_RESPONSE_DUE_AT, Long.toString(policy.calendar().addWorkingMinutes(o.createdAt(), t.responseMinutes())));
        return stamp;
    }

    /**
     * The response deadline to record a breach for, or {@code 0} for none: the deadline passed, no breach recorded
     * yet, and the item still sits in its workflow's initial state.
     */
    public static long responseBreachDue(GovernedItem o, Workflow wf, long now) {
        long due = parseEpoch(o.attributes().get(ATTR_RESPONSE_DUE_AT));
        if (due <= 0 || due > now || o.attributes().containsKey(ATTR_SLA_RESPONSE_BREACHED_AT)
                || !wf.initialState().equalsIgnoreCase(o.status())) return 0L;
        return due;
    }

    /** The resolution deadline to record a breach for, or {@code 0} for none (not due yet, or already breached). */
    public static long resolutionBreachDue(GovernedItem o, long now) {
        long dueAt = parseEpoch(o.attributes().get(ATTR_DUE_AT));
        return dueAt > 0 && dueAt <= now && !o.attributes().containsKey(ATTR_SLA_BREACHED_AT) ? dueAt : 0L;
    }

    /**
     * The row an Escalation Rule's {@code when} is evaluated over: {@code type}, {@code status} (upper-cased),
     * {@code priority} (trimmed), {@code severity}, {@code category} (the attribute), {@code assignee},
     * {@code ageMinutes}, {@code minutesToDue} (negative once overdue; {@code Integer.MAX_VALUE} with no deadline,
     * because the Condition Language reads a blank cell as 0 and an empty value would look due NOW),
     * {@code resolutionBreached}/{@code responseBreached}/{@code escalated} as 0/1. Absent text is empty.
     */
    public static Map<String, Object> escalationContext(GovernedItem o, long now) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", o.kind());
        row.put("status", o.status() == null ? "" : o.status().trim().toUpperCase(Locale.ROOT));
        row.put("priority", o.priority() == null ? "" : o.priority().trim());
        row.put("severity", o.severity() == null ? "" : o.severity());
        row.put("category", o.attributes().getOrDefault("category", ""));
        row.put("assignee", o.assignee() == null ? "" : o.assignee());
        row.put("ageMinutes", Math.floorDiv(now - o.createdAt(), 60_000L));
        long due = parseEpoch(o.attributes().get(ATTR_DUE_AT));
        row.put("minutesToDue", due > 0 ? Math.floorDiv(due - now, 60_000L) : (long) Integer.MAX_VALUE);
        row.put("resolutionBreached", o.attributes().containsKey(ATTR_SLA_BREACHED_AT) ? 1 : 0);
        row.put("responseBreached", o.attributes().containsKey(ATTR_SLA_RESPONSE_BREACHED_AT) ? 1 : 0);
        row.put("escalated", "true".equals(o.attributes().get("escalated")) ? 1 : 0);
        return row;
    }

    /**
     * The firing of {@code rule} for the item now, or {@code null}: the match tree must match (priority sugar ANDed
     * with {@code when}), the trigger must hold ({@code breach} marker present / {@code age} reached) and the
     * fire-once ledger must not already hold {@code <rule>@<marker>}.
     */
    public static Firing escalationFor(GovernedItem cur, EscalationRule r, long now, ItemMatcher matcher) {
        Map<String, Object> match = r.matchTree();
        if (match != null && !matcher.matches(match, escalationContext(cur, now))) return null;
        String marker = switch (r.on()) {
            case BREACH -> cur.attributes().get("response".equals(r.target()) ? ATTR_SLA_RESPONSE_BREACHED_AT : ATTR_SLA_BREACHED_AT);
            case AGE -> now - cur.createdAt() >= r.afterMinutes() * 60_000L ? "age" : null;
        };
        if (marker == null || marker.isBlank()) return null;
        String entry = r.id() + "@" + marker;
        List<String> fired = new ArrayList<>(csv(cur.attributes().get(ATTR_ESCALATIONS)));
        if (fired.contains(entry)) return null;
        fired.add(entry);
        return new Firing(r, marker, String.join(",", fired));
    }

    /** Epoch millis of an attribute, {@code 0} when absent or not a number. */
    public static long parseEpoch(String s) {
        if (s == null || s.isBlank()) return 0L;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static List<String> csv(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) if (!p.isBlank()) out.add(p.trim());
        return out;
    }
}
