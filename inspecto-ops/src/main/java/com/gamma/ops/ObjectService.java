package com.gamma.ops;

import com.gamma.objects.AnnotationKinds;
import com.gamma.objects.ObjectType;

import com.gamma.event.Event;
import com.gamma.event.EventLevel;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.ops.link.InMemoryLinkStore;
import com.gamma.ops.link.LinkRelationship;
import com.gamma.ops.link.LinkStore;
import com.gamma.ops.link.ObjectLink;
import com.gamma.ops.note.InMemoryNoteStore;
import com.gamma.ops.note.NoteKind;
import com.gamma.ops.note.NoteService;
import com.gamma.ops.note.NoteStore;
import com.gamma.ops.note.ObjectNote;
import com.gamma.objects.RcaTemplate;
import com.gamma.ops.tag.CaseRule;
import com.gamma.ops.tag.Tag;
import com.gamma.ops.tag.TagRule;
import com.gamma.ops.workflow.Workflow;
import com.gamma.util.JsonAttributes;
import com.gamma.util.Values;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Object Engine + Workflow Engine, wired together — the orchestrator behind the Alert Center
 * (Phase 2). It opens {@link OperationalObject}s in their workflow's initial state and walks them
 * through lifecycle transitions, persisting each change via an {@link ObjectStore} and recording it as
 * a Phase-1 {@link Event} ({@link EventType#OBJECT_OPENED} / {@link EventType#OBJECT_ACTIVITY}) on
 * {@link EventLog#global()} — so an investigator sees the object's history inline in the Event Viewer.
 *
 * <p>Lifecycle rules come from a {@link Workflow} per {@link ObjectType}: the built-in
 * {@link Workflow#defaultFor} set, optionally overridden (e.g. from {@code *_workflow.toon}). Illegal
 * moves throw {@link IllegalStateException}; an unknown id throws {@link NoSuchElementException} — the
 * Control API maps these to 422 / 404.
 *
 * @since 4.0.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public final class ObjectService {

    private static final String SOURCE = ObjectService.class.getName();

    /** Attribute key holding an incident's SLA deadline as epoch millis (string) — set at creation (Phase 3). */
    public static final String ATTR_DUE_AT = "dueAt";
    /** Attribute key stamped (epoch millis) when an SLA breach has been emitted — makes {@link #sweepIncidentSla} idempotent. */
    public static final String ATTR_SLA_BREACHED_AT = "slaBreachedAt";
    /**
     * Epoch-millis of the most recent transition into {@code RESOLVED} ({@code INCIDENT-KPI-MTTR-1},
     * 2026-09-11) — the only record of WHEN an object was resolved, and therefore the whole basis of MTTR.
     *
     * <p>🔴 It exists because {@link OperationalObject#closedAt()} does not answer this. {@code closedAt}
     * is stamped on the TERMINAL state, and for an Incident the only terminal state is {@code ARCHIVED}
     * ({@code Workflow.defaultFor}: {@code IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED}). So an Incident
     * resolved in two hours and archived a month later has a {@code closedAt} a month out — the existing
     * {@code cycleTime} over it is time-to-ARCHIVE, not time-to-resolve, and reporting it as MTTR would
     * overstate the number by however long the operator took to tidy up.
     *
     * <p>⚠ <b>Most recent resolution wins</b>, deliberately. A reopened object's first resolution did not
     * hold, so measuring to it would report a fix that was not a fix; the clock runs to the resolution
     * that stuck. (Contrast {@code firstSeenAt} on a reconciliation Break, where FIRST is the meaningful
     * end — there the question is "how long has this been wrong", here it is "how long until it was
     * right".) Both choices are stated wherever the number is published, per this row's own rule that a
     * KPI must be defined rather than implied.
     */
    public static final String ATTR_RESOLVED_AT = "resolvedAt";
    /**
     * Attribute key holding WHEN THE UNDERLYING CONDITION OCCURRED (epoch ms) — the numerator MTTD needs
     * and nothing recorded until 2026-09-15 (`INCIDENT-KPI-MTTD-1`). Stamped at promotion by whoever has the
     * occurrence time in hand: {@code EventObjectBridge} copies the triggering {@code Event.ts()}. ⚠ It is
     * the event's OWN time, not the earliest Signal at the causation root — that fuller anchor needs an
     * event-store read on the analytics path, which is a seam nobody has built and this row refuses to
     * fake. An object without the stamp is simply excluded from the MTTD mean, never counted as zero.
     */
    public static final String ATTR_OCCURRED_AT = "occurredAt";
    /** Attribute key holding an object's comma-separated watcher list (INC-4). */
    public static final String ATTR_WATCHERS = "watchers";
    /** Attribute key holding an object's comma-separated tag list (GLOSSARY §9 — Tag / Tag Rule). */
    public static final String ATTR_TAGS = "tags";
    /** Attribute key stamped on an absorbed case: the surviving case it was merged into (GLOSSARY §9). */
    public static final String ATTR_MERGED_INTO = "mergedInto";
    /** Attribute key stamped on a rule-raised case: the {@link CaseRule} name that opened it (GLOSSARY §9, C5). */
    public static final String ATTR_RAISED_BY_RULE = "raisedByRule";
    /** Attribute key placing an object under legal hold — see {@link #hasLegalHold} (MNT-14). */
    public static final String ATTR_LEGAL_HOLD = "legalHold";
    /**
     * Attribute keys stamped on an Incident minted from a Link Analysis Entity (LA-CASE-CREATE-IN-PLACE-1):
     * the Entity's node id ({@code entity:[<entityType>:]<normalised key>}, D-S4) and the Dataset it was
     * projected from. Together they are the Entity's IDENTITY — minting the same pair again reuses the object.
     */
    public static final String ATTR_ENTITY_KEY = "entityKey";
    public static final String ATTR_ENTITY_DATASET = "entityDataset";

    /**
     * {@code true} when {@code o} is under legal hold ({@link #ATTR_LEGAL_HOLD}) and must therefore
     * <b>never</b> be purged by the MNT-14 retention sweep, however long its retention window has been
     * expired. Absence of the key means no hold.
     *
     * <p><b>Deliberately fail-safe on anything unrecognised.</b> Only an explicit falsey spelling
     * ({@code false}/{@code 0}/{@code no}/{@code off}, case-insensitively) clears the hold; every other
     * non-blank value holds. A typo in a hold value must keep records, not delete them — the two failure
     * modes are not symmetric, and this one is irreversible.
     *
     * <p>The sweep must call this at <b>purge time</b>, not only when building its preview, or a hold
     * applied between the preview and the run is ignored.
     */
    public static boolean hasLegalHold(OperationalObject o) {
        String v = o == null ? null : o.attributes().get(ATTR_LEGAL_HOLD);
        if (v == null || v.isBlank()) return false;
        return switch (v.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "false", "0", "no", "off" -> false;
            default -> true;
        };
    }

    private final ObjectStore store;
    private final LinkStore links;
    private final NoteStore notes;
    /** D7: the truth for object tags. {@link #ATTR_TAGS} is a projection of this, never the reverse. */
    private final com.gamma.ops.tag.TagAssignmentStore tagAssignments;
    private final NoteService noteService;                          // D10: kind-agnostic note path
    private final Map<ObjectType, Workflow> workflows = new EnumMap<>(ObjectType.class);
    private final Map<String, Tag> tags = new ConcurrentHashMap<>();          // user-created tag registry
    private final Map<String, TagRule> tagRules = new ConcurrentHashMap<>();  // Gmail-filter Tag Rules, by name
    private final Map<String, CaseRule> caseRules = new ConcurrentHashMap<>(); // rule-raised-case rules, by name (C5)

    /** Build with the built-in default workflows and in-memory link + note stores. */
    public ObjectService(ObjectStore store) {
        this(store, Map.of());
    }

    /** Build with workflow {@code overrides}; in-memory link + note stores. */
    public ObjectService(ObjectStore store, Map<ObjectType, Workflow> overrides) {
        this(store, overrides, new InMemoryLinkStore(), new InMemoryNoteStore());
    }

    /** Build with workflow {@code overrides} and an explicit {@link LinkStore}; in-memory note store. */
    public ObjectService(ObjectStore store, Map<ObjectType, Workflow> overrides, LinkStore links) {
        this(store, overrides, links, new InMemoryNoteStore());
    }

    /**
     * Build with workflow {@code overrides} and explicit {@link LinkStore} (Phase 4) + {@link NoteStore}
     * (Phase 4 follow-up) — the deployment supplies durable {@code Db*} stores or the lean in-memory ones,
     * mirroring the object store backend.
     */
    public ObjectService(ObjectStore store, Map<ObjectType, Workflow> overrides, LinkStore links, NoteStore notes) {
        this(store, overrides, links, notes, new com.gamma.ops.tag.InMemoryTagAssignmentStore());
    }

    /**
     * Build with an explicit {@link com.gamma.ops.tag.TagAssignmentStore} (BACKLOG D7 phase 2) — the
     * cross-entity tag graph this service keeps in step for the {@code object} family.
     *
     * <p><b>The assignment store is the source of truth for an object's tags; the {@link #ATTR_TAGS} CSV
     * attribute is a projection of it.</b> The CSV is still written on every change because it is part of
     * the object's JSON and the Incidents UI reads it, but nothing treats it as authoritative — every tag
     * mutation goes store-first and then re-projects. That is what lets a cross-kind rename reach objects,
     * which the pre-D7 CSV-only shape could not do.
     */
    public ObjectService(ObjectStore store, Map<ObjectType, Workflow> overrides, LinkStore links, NoteStore notes,
                         com.gamma.ops.tag.TagAssignmentStore tagAssignments) {
        this.store = store;
        this.links = links;
        this.notes = notes;
        this.tagAssignments = tagAssignments;
        // D10: the object family's plug-in to the kind-agnostic note path — resolve the object (this is
        // what used to be require(objectId)) and hand back its correlation id; null ⇒ "no such target".
        this.noteService = new NoteService(notes, (targetKind, targetId) -> {
            if (!AnnotationKinds.OBJECT.equals(targetKind)) return null;   // fail closed: not our family
            OperationalObject o = store.get(targetId).orElse(null);
            if (o == null) return null;
            return o.correlationId() == null ? "" : o.correlationId();
        });
        for (ObjectType t : ObjectType.values()) {
            Workflow wf = overrides == null ? null : overrides.get(t);
            workflows.put(t, wf != null ? wf : Workflow.defaultFor(t));
        }
    }

    /** The effective workflow for {@code type}. */
    public Workflow workflow(ObjectType type) {
        return workflows.get(type);
    }

    /**
     * Install (create or replace) the workflow for an {@link ObjectType} — the boot-time seam for
     * {@code *_workflow.toon} overrides ({@link com.gamma.service.ServiceBootstrap} scans them and calls
     * this after construction, the same post-construction pattern as {@link #registerTag}/
     * {@link #registerCaseRule}). Replaces the built-in {@link Workflow#defaultFor default} baked in by
     * the constructor; last registration for a given type wins.
     */
    public void registerWorkflow(Workflow workflow) {
        workflows.put(workflow.objectType(), workflow);
    }

    /**
     * Open a new object in its workflow's initial state, persist it, and emit an
     * {@link EventType#OBJECT_OPENED} event. Convenience overload with no ownership/priority (used by
     * the auto-promoting {@link com.gamma.alert.AlertService}).
     */
    public OperationalObject open(ObjectType type, String title, String description, String severity,
                                  String correlationId, Map<String, String> attributes) {
        return open(type, title, description, severity, null, null, null, correlationId, attributes);
    }

    /**
     * Open a new object in its workflow's initial state, persist it, and emit an
     * {@link EventType#OBJECT_OPENED} event. The fuller form carries {@code priority}/{@code owner}/
     * {@code assignee} — the operator-set fields an incident is created with (Phase 3's {@code POST /objects}).
     */
    public OperationalObject open(ObjectType type, String title, String description, String severity,
                                  String priority, String owner, String assignee,
                                  String correlationId, Map<String, String> attributes) {
        long now = System.currentTimeMillis();
        OperationalObject obj = OperationalObject.builder(type)
                .title(title)
                .description(description)
                .severity(severity)
                .priority(priority)
                .owner(owner)
                .assignee(assignee)
                .status(workflow(type).initialState())
                .correlationId(correlationId)
                .attributes(attributes)
                .createdAt(now)
                .updatedAt(now)
                .build();
        obj = autoApplyTagRules(obj, now);   // Tag Rules tag incoming objects (GLOSSARY §9, Gmail-filter semantics)
        OperationalObject stored = store.create(obj);
        // D7: the CSV the builder and Tag Rules just produced is authored input; adopt it into the
        // assignment store so the object is immediately visible to GET /tags/{name}/targets.
        adoptTags(stored);
        EventLog.current().emit(Event.builder(EventType.OBJECT_OPENED)
                .level(EventLevel.INFO)
                .source(SOURCE)
                .correlationId(correlationId)
                .message(type + " opened: " + stored.title() + " [" + stored.id() + "]")
                .attr("objectId", stored.id())
                .attr("objectType", type.name())
                .attr("status", stored.status())
                .attr("severity", severity));
        return stored;
    }

    /**
     * Apply a named {@code action} to the object's current state (e.g. {@code ack}, {@code resolve}),
     * persist, and emit an {@link EventType#OBJECT_ACTIVITY} event.
     *
     * @throws NoSuchElementException if no object has this id
     * @throws IllegalStateException  if the action is not legal from the current state
     */
    public OperationalObject transition(String id, String action, String actor) {
        OperationalObject obj = require(id);
        Workflow wf = workflow(obj.objectType());
        String target = wf.apply(obj.status(), action).orElseThrow(() -> new IllegalStateException(
                "illegal transition: '" + action + "' from " + obj.status()
                        + " (" + obj.objectType() + ")"));
        return commit(obj, wf, target, action, actor);
    }

    /**
     * Move the object directly to {@code targetState} (must be a legal neighbour of the current state),
     * persist, and emit an {@link EventType#OBJECT_ACTIVITY} event.
     */
    public OperationalObject transitionTo(String id, String targetState, String actor) {
        OperationalObject obj = require(id);
        Workflow wf = workflow(obj.objectType());
        if (!wf.allows(obj.status(), targetState))
            throw new IllegalStateException("illegal transition: " + obj.status() + " -> " + targetState
                    + " (" + obj.objectType() + ")");
        return commit(obj, wf, targetState, "transition", actor);
    }

    /**
     * Patch the operator-mutable fields ({@code PATCH /objects/{id}}): any non-null argument is applied —
     * {@code priority}/{@code severity}/{@code assignee} replace, {@code attributes} merge over the stored
     * bag (updates win, existing keys survive). No workflow involvement; a no-op patch returns the object
     * unchanged without a write.
     *
     * @throws NoSuchElementException if no object has this id
     */
    public OperationalObject patch(String id, String priority, String severity, String assignee,
                                   Map<String, String> attributes) {
        OperationalObject obj = require(id);
        long now = System.currentTimeMillis();
        OperationalObject next = obj;
        if (priority != null) next = next.withPriority(priority, now);
        if (severity != null) next = next.withSeverity(severity, now);
        if (assignee != null) next = next.withAssignee(assignee, now);
        if (attributes != null && !attributes.isEmpty()) next = next.withAttributes(attributes, now);
        return next == obj ? obj : store.update(next);
    }

    /**
     * Save a Case's Findings values ({@code PUT /objects/{id}/findings}, operator 2026-09-25): merge
     * {@code attributes} — the {@code findings} blob and its flat copies, assembled and validated at the edge —
     * over the stored bag, and audit it as an {@link EventType#OBJECT_ACTIVITY} {@code findings} event naming
     * the {@code actor}. Unlike {@link #patch} it always writes and always audits: the save is a collaboration
     * act anyone who can see the object may perform, so the record of who did it is the point.
     *
     * @throws NoSuchElementException if no object has this id
     */
    public OperationalObject saveFindings(String id, Map<String, String> attributes, String actor) {
        return saveAttributes(id, attributes, actor, "findings");
    }

    /**
     * The narrow-write seam {@link #saveFindings} and the {@code PUT /objects/{id}/postmortem|category} routes
     * share (operator 2026-09-26): merge {@code attributes} over the stored bag, always write, and audit an
     * {@link EventType#OBJECT_ACTIVITY} event whose {@code action} is {@code what}, naming the {@code actor}.
     *
     * @throws NoSuchElementException if no object has this id
     */
    public OperationalObject saveAttributes(String id, Map<String, String> attributes, String actor, String what) {
        OperationalObject obj = require(id);
        OperationalObject updated = store.update(obj.withAttributes(attributes, System.currentTimeMillis()));
        EventLog.current().emit(Event.builder(EventType.OBJECT_ACTIVITY)
                .level(EventLevel.INFO)
                .source(SOURCE)
                .correlationId(obj.correlationId())
                .message(obj.objectType() + " " + id + ": " + what + " saved" + (actor == null ? "" : " by " + actor))
                .attr("objectId", id)
                .attr("objectType", obj.objectType().name())
                .attr("action", what)
                .attr("actor", actor));
        return updated;
    }

    /**
     * Replace an Incident's or Case's typed financial {@link Impact} ({@code PUT /objects/{id}/impact}, WS-10) and
     * audit it as an {@link EventType#OBJECT_ACTIVITY} {@code impact} event carrying the stored value
     * {@code before} and {@code after} — a ledger change must say what it changed, not only that it happened.
     * An empty {@code impact} clears it.
     *
     * @throws NoSuchElementException   if no object has this id
     * @throws IllegalArgumentException if the object is not an Incident or a Case
     * @throws IllegalStateException    if the object is in its terminal state (ARCHIVED / CLOSED) — its books
     *                                  are closed; reopen it first
     */
    public OperationalObject saveImpact(String id, Impact impact, String actor) {
        OperationalObject obj = require(id);
        if (!Impact.TYPES.contains(obj.objectType()))
            throw new IllegalArgumentException("impact is recorded on an Incident or a Case, not a " + obj.objectType());
        if (workflow(obj.objectType()).isTerminal(obj.status()))
            throw new IllegalStateException(obj.objectType() + " " + id + " is " + obj.status()
                    + " — its impact is closed; reopen it to change the impact");
        String before = obj.attributes().getOrDefault(Impact.ATTR, "");
        String after = impact.toJson();
        OperationalObject updated = store.update(
                obj.withAttributes(Map.of(Impact.ATTR, after), System.currentTimeMillis()));
        EventLog.current().emit(Event.builder(EventType.OBJECT_ACTIVITY)
                .level(EventLevel.INFO)
                .source(SOURCE)
                .correlationId(obj.correlationId())
                .message(obj.objectType() + " " + id + ": impact saved" + (actor == null ? "" : " by " + actor))
                .attr("objectId", id)
                .attr("objectType", obj.objectType().name())
                .attr("action", Impact.ATTR)
                .attr("before", before)
                .attr("after", after)
                .attr("actor", actor));
        return updated;
    }

    /** Convenience: acknowledge an object (the {@code ack} action). */
    public OperationalObject ack(String id, String actor) {
        return transition(id, "ack", actor);
    }

    /** Convenience: resolve an object (the {@code resolve} action). */
    public OperationalObject resolve(String id, String actor) {
        return transition(id, "resolve", actor);
    }

    public Optional<OperationalObject> get(String id) {
        return store.get(id);
    }

    public List<OperationalObject> query(ObjectQuery query) {
        return store.query(query);
    }

    /**
     * The not-yet-terminal objects of {@code type} for a {@code correlationId} — used to avoid opening a
     * duplicate object while one is still being handled (e.g. an alert that keeps breaching).
     */
    public List<OperationalObject> active(ObjectType type, String correlationId) {
        Workflow wf = workflow(type);
        return store.query(ObjectQuery.builder()
                        .objectType(type).correlationId(correlationId).limit(ObjectQuery.MAX_LIMIT).build())
                .stream().filter(o -> !wf.isTerminal(o.status())).toList();
    }

    // ── analytics (GLOSSARY §9, C4) ───────────────────────────────────────────────────

    /**
     * A rollup over all objects of {@code type} (C4 — the business-lens numbers): totals, backlog
     * (non-terminal count), breakdowns by status / L1-category / priority, cycle-time stats over the
     * terminal objects ({@code closedAt − createdAt}), <b>MTTR</b> over the resolved ones
     * ({@link #ATTR_RESOLVED_AT}{@code  − createdAt} — a different number, see that constant), and the
     * typed {@link Impact} (WS-10) summed <b>per currency</b> — amounts in different currencies are never
     * added together — with {@code outstanding} derived per object, plus {@code recordsAffected} summed from
     * the Findings' flat copy. Shaped as a JSON-ready map so the UI renders it directly and the
     * {@code objects.analytics} Job can sample the same surface.
     */
    public Map<String, Object> analytics(ObjectType type) {
        Workflow wf = workflow(type);
        List<OperationalObject> all = store.query(ObjectQuery.builder()
                .objectType(type).limit(ObjectQuery.MAX_LIMIT).build());
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        Map<String, Integer> byPriority = new LinkedHashMap<>();
        int backlog = 0;
        long cycleSum = 0;
        int cycleCount = 0;
        long mttrSum = 0;
        int mttrCount = 0;
        long mttdSum = 0;
        int mttdCount = 0;
        Map<String, Map<String, Object>> impactByCurrency = new java.util.TreeMap<>();
        long recordsAffected = 0;
        for (OperationalObject o : all) {
            bump(byStatus, o.status() == null ? "UNKNOWN" : o.status().toUpperCase(java.util.Locale.ROOT));
            bump(byCategory, categoryL1(o.attributes().get("category")));
            bump(byPriority, o.priority() == null || o.priority().isBlank() ? "NONE" : o.priority().toUpperCase(java.util.Locale.ROOT));
            if (!wf.isTerminal(o.status())) backlog++;
            if (o.closedAt() > 0 && o.closedAt() >= o.createdAt()) {
                cycleSum += o.closedAt() - o.createdAt();
                cycleCount++;
            }
            long resolvedAt = parseEpoch(o.attributes().get(ATTR_RESOLVED_AT));
            if (resolvedAt > 0 && resolvedAt >= o.createdAt()) {
                mttrSum += resolvedAt - o.createdAt();
                mttrCount++;
            }
            long occurredAt = parseEpoch(o.attributes().get(ATTR_OCCURRED_AT));
            if (occurredAt > 0 && o.createdAt() >= occurredAt) {
                mttdSum += o.createdAt() - occurredAt;
                mttdCount++;
            }
            Impact.of(o).filter(i -> i.currency() != null).ifPresent(i -> addImpact(impactByCurrency, i));
            recordsAffected += parseEpoch(o.attributes().get("recordsAffected")); // long-or-0 parse
        }
        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("count", cycleCount);
        cycle.put("avgMs", cycleCount == 0 ? 0 : cycleSum / cycleCount);
        // ⚠ Named so nobody reads it as MTTR: this is time to the TERMINAL state, which for an Incident
        // is ARCHIVED, not RESOLVED.
        cycle.put("definition", "created \u2192 closed (the terminal state; for an Incident that is ARCHIVED, not RESOLVED)");
        // INCIDENT-KPI-MTTR-1. `count` is the honest denominator: objects with no recorded resolution are
        // EXCLUDED, never counted as zero. Every object resolved before 2026-09-11 has no stamp, so a
        // freshly upgraded deployment reports count 0 rather than a number built from nothing.
        Map<String, Object> mttr = new LinkedHashMap<>();
        mttr.put("count", mttrCount);
        mttr.put("avgMs", mttrCount == 0 ? 0 : mttrSum / mttrCount);
        mttr.put("definition", "created \u2192 most recent RESOLVED transition; a reopened object measures "
                + "to the resolution that stuck. Objects with no recorded resolution are excluded from the mean");
        // INCIDENT-KPI-MTTD-1. Same honesty rule as MTTR: `count` is the denominator, objects with no
        // recorded occurrence time are EXCLUDED. Today only the event bridge stamps one (gap and
        // conservation-imbalance promotions), so a deployment whose Incidents were all opened by hand or
        // by an Alert Rule reports count 0 — a true statement, not a KPI built from nothing.
        Map<String, Object> mttd = new LinkedHashMap<>();
        mttd.put("count", mttdCount);
        mttd.put("avgMs", mttdCount == 0 ? 0 : mttdSum / mttdCount);
        mttd.put("definition", "occurredAt (the triggering event's own time) \u2192 created (the object was opened). "
                + "Objects with no recorded occurrence time are excluded from the mean");
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("byCurrency", impactByCurrency);
        impact.put("recordsAffected", recordsAffected);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type.name());
        out.put("total", all.size());
        out.put("backlog", backlog);
        out.put("byStatus", byStatus);
        out.put("byCategory", byCategory);
        out.put("byPriority", byPriority);
        out.put("cycleTime", cycle);
        out.put("mttr", mttr);
        out.put("mttd", mttd);
        out.put("impact", impact);
        return out;
    }

    /** Add one object's impact to its currency's totals ({@code count} + the four amounts + outstanding). */
    private static void addImpact(Map<String, Map<String, Object>> byCurrency, Impact i) {
        Map<String, Object> t = byCurrency.computeIfAbsent(i.currency(), c -> {
            Map<String, Object> z = new LinkedHashMap<>();
            z.put("count", 0);
            for (String a : Impact.AMOUNTS) z.put(a, java.math.BigDecimal.ZERO);
            z.put("outstanding", java.math.BigDecimal.ZERO);
            return z;
        });
        t.merge("count", 1, (a, b) -> (Integer) a + (Integer) b);
        for (String a : Impact.AMOUNTS) addAmount(t, a, i.amount(a));
        addAmount(t, "outstanding", i.outstanding());
    }

    private static void addAmount(Map<String, Object> totals, String key, java.math.BigDecimal v) {
        if (v != null) totals.put(key, ((java.math.BigDecimal) totals.get(key)).add(v));
    }

    private static void bump(Map<String, Integer> m, String key) {
        m.merge(key, 1, Integer::sum);
    }

    /** The first level of a "L1 / L2 / L3" category path, or "UNCATEGORIZED". */
    private static String categoryL1(String category) {
        if (category == null || category.isBlank()) return "UNCATEGORIZED";
        int slash = category.indexOf('/');
        return (slash < 0 ? category : category.substring(0, slash)).trim();
    }

    // ── assignment, watchers ─────────────────────────────────────────────────────────

    // ── tags & Tag Rules (GLOSSARY §9) ────────────────────────────────────────────────

    /** Register (create or replace) a {@link Tag}; loaded from {@code *_tag.toon} at boot or {@code POST /tags}. */
    public Tag registerTag(Tag tag) {
        tags.put(tag.name(), tag);
        return tag;
    }

    /** The registered tag with this name, or empty. */
    public Optional<Tag> tag(String name) {
        return Optional.ofNullable(name == null ? null : tags.get(name.trim()));
    }

    /** Every registered tag, sorted by name. */
    public List<Tag> tags() {
        return tags.values().stream().sorted(Comparator.comparing(Tag::name)).toList();
    }

    /**
     * Register (create or replace) a {@link TagRule}; loaded from {@code *_tagrule.toon} at boot or
     * {@code POST /tags/rules}. Saving a rule implicitly registers its tag (Gmail creates the label
     * with the filter).
     */
    public TagRule registerTagRule(TagRule rule) {
        tags.computeIfAbsent(rule.tag(), n -> new Tag(n, System.currentTimeMillis()));
        tagRules.put(rule.name(), rule);
        return rule;
    }

    /** The Tag Rule with this name, or empty. */
    public Optional<TagRule> tagRule(String name) {
        return Optional.ofNullable(name == null ? null : tagRules.get(name.trim()));
    }

    /** Every registered Tag Rule, sorted by name. */
    public List<TagRule> tagRules() {
        return tagRules.values().stream().sorted(Comparator.comparing(TagRule::name)).toList();
    }

    /** Remove a Tag Rule; {@code false} when no rule had that name. */
    public boolean removeTagRule(String name) {
        return name != null && tagRules.remove(name.trim()) != null;
    }

    /** Bulk-apply outcome: how many objects matched the rule, and how many were newly tagged. */
    public record TagRuleApplication(int matched, int updated) {}

    /** This service's view of the cross-entity tag graph (D7) — the truth behind the {@link #ATTR_TAGS} CSV. */
    /**
     * This service seen through the core {@link com.gamma.objects.ObjectAccess} seam (EDG-01 cell 7).
     *
     * <p>Cached, because {@code IncidentAccess.over(...)} resolves its supplier per call and a fresh
     * adapter per open would allocate for nothing. Core holds the returned interface and never names this
     * class, which is what lets {@code com.gamma.ops} become an optional edition module.
     */
    public com.gamma.objects.ObjectAccess access() {
        com.gamma.objects.ObjectAccess a = this.access;
        if (a == null) this.access = a = new ObjectServiceAccess(this);
        return a;
    }

    private com.gamma.objects.ObjectAccess access;

    public com.gamma.ops.tag.TagAssignmentStore tagAssignments() {
        return tagAssignments;
    }

    /** An object's tags, alphabetical, read from the assignment store (never from the CSV projection). */
    public List<String> tagsOf(String objectId) {
        return tagAssignments.tagsOf(com.gamma.objects.AnnotationKinds.OBJECT, objectId);
    }

    /** Apply one tag to an object and re-project the CSV. Idempotent; returns the updated object. */
    public OperationalObject applyTag(String objectId, String tag, String actor) {
        OperationalObject o = require(objectId);
        tagAssignments.add(com.gamma.objects.TagAssignment.of(
                tag, com.gamma.objects.AnnotationKinds.OBJECT, objectId, actor));
        return projectTags(o, System.currentTimeMillis());
    }

    /** Remove one tag from an object and re-project the CSV. Idempotent; returns the updated object. */
    public OperationalObject removeTag(String objectId, String tag) {
        OperationalObject o = require(objectId);
        tagAssignments.remove(tag, com.gamma.objects.AnnotationKinds.OBJECT, objectId);
        return projectTags(o, System.currentTimeMillis());
    }

    /**
     * One-time D7 migration: adopt every tag that exists only in an object's {@link #ATTR_TAGS} CSV into
     * the assignment store. Idempotent (the store's {@code add} is), so re-running is harmless.
     *
     * <p>Run once at Space startup rather than lazily on read, deliberately: a lazy "if the store has
     * nothing, fall back to the CSV" adoption would make removing an object's LAST tag resurrect all of
     * them on the next read, because "no assignments" and "not yet migrated" would be the same state.
     *
     * @return how many assignments were created
     */
    public int backfillTagAssignments() {
        int created = 0;
        for (OperationalObject o : store.query(ObjectQuery.builder().limit(ObjectQuery.MAX_LIMIT).build())) {
            List<String> csv = csvTags(o.attributes().get(ATTR_TAGS));
            if (csv.isEmpty()) continue;
            List<String> known = tagsOf(o.id());
            for (String tag : csv) {
                if (known.contains(tag)) continue;
                tagAssignments.add(com.gamma.objects.TagAssignment.of(
                        tag, com.gamma.objects.AnnotationKinds.OBJECT, o.id(), "migration"));
                created++;
            }
        }
        return created;
    }

    /**
     * What a vocabulary-level tag change touched: assignment edges rewritten, object CSVs re-projected,
     * and the Tag Rules that followed the rename (their persisted files need rewriting by the caller).
     */
    public record TagVocabularyChange(int assignments, int objects, List<String> rules) {}

    /**
     * Rename a tag <em>everywhere</em> — registry, assignment edges, the {@link #ATTR_TAGS} projection of
     * every affected object, and any Tag Rule that applies it. This is the operation the central
     * assignment store exists for: under the pre-D7 per-entity CSV shape a rename could not propagate.
     *
     * <p>Renaming onto an existing tag <b>merges</b> them, which the store's composite key already handles
     * correctly; the source tag then ceases to exist. Tag Rules follow the rename, otherwise the next rule
     * run would resurrect the old name.
     *
     * @throws NoSuchElementException   if no tag has this name
     * @throws IllegalArgumentException if the new name is not a valid tag name
     */
    public TagVocabularyChange renameTag(String from, String to) {
        Tag existing = tag(from).orElseThrow(() -> new NoSuchElementException("no tag named '" + from + "'"));
        Tag renamed = new Tag(to, existing.createdAt());   // validates the new name before anything mutates
        if (renamed.name().equals(existing.name())) return new TagVocabularyChange(0, 0, List.of());

        // Collect the affected objects BEFORE the rename — afterwards the old name has no edges left.
        List<String> affected = objectTargetsOf(existing.name());
        int edges = tagAssignments.rename(existing.name(), renamed.name());
        tags.remove(existing.name());
        tags.put(renamed.name(), renamed);

        List<String> followed = new java.util.ArrayList<>();
        for (TagRule rule : tagRules()) {
            if (!rule.tag().equals(existing.name())) continue;
            tagRules.put(rule.name(), new TagRule(rule.name(), renamed.name(), rule.filter(), rule.createdAt()));
            followed.add(rule.name());
        }
        return new TagVocabularyChange(edges, reprojectAll(affected), List.copyOf(followed));
    }

    /**
     * Delete a tag from the registry and remove every assignment it has, re-projecting each affected
     * object's CSV. Without this, retiring a tag would leave its edges orphaned in the store.
     *
     * @throws NoSuchElementException if no tag has this name
     * @throws IllegalStateException  if a Tag Rule still applies it — the rule would immediately
     *                                re-create the tag, so the rule must be dealt with first
     */
    public TagVocabularyChange deleteTag(String name) {
        Tag existing = tag(name).orElseThrow(() -> new NoSuchElementException("no tag named '" + name + "'"));
        List<String> appliedBy = tagRules().stream().filter(r -> r.tag().equals(existing.name()))
                .map(TagRule::name).toList();
        if (!appliedBy.isEmpty())
            throw new IllegalStateException("tag '" + existing.name() + "' is still applied by tag rule(s) "
                    + appliedBy + " — delete or repoint them first");

        List<String> affected = objectTargetsOf(existing.name());
        int edges = tagAssignments.removeTag(existing.name());
        tags.remove(existing.name());
        return new TagVocabularyChange(edges, reprojectAll(affected), List.of());
    }

    /** The ids of the objects currently carrying {@code tag} (component targets have no CSV to project). */
    private List<String> objectTargetsOf(String tag) {
        return tagAssignments.forTag(tag).stream()
                .filter(a -> com.gamma.objects.AnnotationKinds.OBJECT.equals(a.targetKind()))
                .map(com.gamma.objects.TagAssignment::targetId)
                .distinct()
                .toList();
    }

    /**
     * Re-project the CSV of each object id. A store-level rename alone leaves every projection stale —
     * this is the step that makes the vocabulary change visible in the object JSON the UI reads. Ids with
     * no object behind them are skipped: assignments are not cascade-deleted, so a stale edge is normal.
     */
    private int reprojectAll(List<String> objectIds) {
        long now = System.currentTimeMillis();
        int done = 0;
        for (String id : objectIds) {
            OperationalObject o = store.get(id).orElse(null);
            if (o == null) continue;
            projectTags(o, now);
            done++;
        }
        return done;
    }

    /** Rewrite an object's CSV attribute from the assignment store — the projection, never the reverse. */
    private OperationalObject projectTags(OperationalObject o, long now) {
        return store.update(o.withAttributes(
                Map.of(ATTR_TAGS, String.join(",", tagsOf(o.id()))), now));
    }

    /** Mirror a freshly-created object's authored/rule-applied CSV tags into the assignment store. */
    private void adoptTags(OperationalObject stored) {
        for (String tag : csvTags(stored.attributes().get(ATTR_TAGS)))
            tagAssignments.add(com.gamma.objects.TagAssignment.of(
                    tag, com.gamma.objects.AnnotationKinds.OBJECT, stored.id(), "system"));
    }

    /**
     * Apply a saved Tag Rule to every existing match ({@code POST /tags/rules/{name}/apply}) — the
     * Gmail "also apply to existing" semantics. Idempotent: objects already carrying the tag count as
     * matched but are not rewritten.
     *
     * @throws NoSuchElementException if no rule has this name
     */
    public TagRuleApplication applyTagRule(String name) {
        TagRule rule = tagRule(name).orElseThrow(() -> new NoSuchElementException("no tag rule named '" + name + "'"));
        int matched = 0;
        int updated = 0;
        for (OperationalObject o : store.query(ObjectQuery.builder().limit(ObjectQuery.MAX_LIMIT).build())) {
            if (!rule.matches(o)) continue;
            matched++;
            if (tagsOf(o.id()).contains(rule.tag())) continue;
            applyTag(o.id(), rule.tag(), "tag-rule:" + name);
            updated++;
        }
        return new TagRuleApplication(matched, updated);
    }

    /** Merge every matching Tag Rule's tag into a not-yet-stored object's {@link #ATTR_TAGS} CSV. */
    private OperationalObject autoApplyTagRules(OperationalObject obj, long now) {
        if (tagRules.isEmpty()) return obj;
        List<String> merged = csvTags(obj.attributes().get(ATTR_TAGS));
        boolean changed = false;
        for (TagRule rule : tagRules.values()) {
            if (!merged.contains(rule.tag()) && rule.matches(obj)) {
                merged.add(rule.tag());
                changed = true;
            }
        }
        return changed ? obj.withAttributes(Map.of(ATTR_TAGS, String.join(",", merged)), now) : obj;
    }

    /** The comma-separated tag attribute parsed into a mutable, trimmed, non-empty list. */
    private static List<String> csvTags(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    // ── rule-raised cases (GLOSSARY §9, C5) ───────────────────────────────────────────

    /** Register (create or replace) a {@link CaseRule}; loaded from {@code *_caserule.toon} at boot or {@code POST /cases/rules}. */
    public CaseRule registerCaseRule(CaseRule rule) {
        caseRules.put(rule.name(), rule);
        return rule;
    }

    /** The Case Rule with this name, or empty. */
    public Optional<CaseRule> caseRule(String name) {
        return Optional.ofNullable(name == null ? null : caseRules.get(name.trim()));
    }

    /** Every registered Case Rule, sorted by name. */
    public List<CaseRule> caseRules() {
        return caseRules.values().stream().sorted(Comparator.comparing(CaseRule::name)).toList();
    }

    /** Remove a Case Rule; {@code false} when no rule had that name. */
    public boolean removeCaseRule(String name) {
        return name != null && caseRules.remove(name.trim()) != null;
    }

    /** Evaluate outcome: matching in-window incidents, how many were newly grouped, and the target case. */
    public record CaseRuleEvaluation(int matched, int grouped, String caseId, boolean opened) {}

    /**
     * Evaluate a Case Rule ({@code POST /cases/rules/{name}/evaluate}) — the auto-grouping step of the
     * Alert → Incident → Case chain (C5). Finds Incidents that match the rule's {@link TagRule.Filter},
     * were created within {@link CaseRule#windowMinutes} of {@code now}, and are not already a member of
     * any Case. If a still-open Case previously raised by this rule exists, the matches are attached to
     * it; otherwise, once at least {@link CaseRule#threshold} matches accrue, a new Case is opened
     * (inheriting the rule's title / category / tags) and the matches are grouped under it. Idempotent:
     * already-grouped incidents are skipped, so re-evaluation only attaches new ones.
     *
     * @throws NoSuchElementException if no rule has this name
     */
    public CaseRuleEvaluation evaluateCaseRule(String name) {
        CaseRule rule = caseRule(name).orElseThrow(() -> new NoSuchElementException("no case rule named '" + name + "'"));
        long now = System.currentTimeMillis();
        long cutoff = rule.windowMinutes() <= 0 ? 0 : now - rule.windowMinutes() * 60_000L;
        List<OperationalObject> matches = store.query(ObjectQuery.builder()
                        .objectType(ObjectType.INCIDENT).limit(ObjectQuery.MAX_LIMIT).build())
                .stream()
                .filter(o -> o.createdAt() >= cutoff)
                .filter(rule::matches)
                .filter(o -> !isCaseMember(o.id()))   // not already grouped under any case
                .toList();
        if (matches.isEmpty()) return new CaseRuleEvaluation(0, 0, null, false);

        String existing = openCaseRaisedBy(name);
        boolean opened = false;
        String caseId = existing;
        if (caseId == null) {
            if (matches.size() < rule.threshold())
                return new CaseRuleEvaluation(matches.size(), 0, null, false);  // below threshold, no case yet
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put(ATTR_RAISED_BY_RULE, name);
            if (rule.category() != null) attrs.put("category", rule.category());
            if (rule.tags() != null) attrs.put(ATTR_TAGS, rule.tags());
            caseId = open(ObjectType.CASE, rule.title(), "Auto-raised by case rule '" + name + "'",
                    null, null, null, null, matches.get(0).correlationId(), attrs).id();
            opened = true;
        }
        for (OperationalObject inc : matches) link(caseId, inc.id(), LinkRelationship.CONTAINS, "case-rule:" + name);
        return new CaseRuleEvaluation(matches.size(), matches.size(), caseId, opened);
    }

    /** Whether {@code incidentId} is already a {@code CONTAINS} member of some case. */
    private boolean isCaseMember(String incidentId) {
        return links.incident(incidentId).stream()
                .anyMatch(l -> l.toId().equals(incidentId) && LinkRelationship.CONTAINS.equalsIgnoreCase(l.relationship()));
    }

    /** The id of a still-open case previously raised by {@code ruleName}, or null. */
    private String openCaseRaisedBy(String ruleName) {
        return store.query(ObjectQuery.builder().objectType(ObjectType.CASE).limit(ObjectQuery.MAX_LIMIT).build())
                .stream()
                .filter(c -> ruleName.equals(c.attributes().get(ATTR_RAISED_BY_RULE)))
                .filter(c -> !workflow(ObjectType.CASE).isTerminal(c.status()))
                .map(OperationalObject::id)
                .findFirst().orElse(null);
    }


    /**
     * Assign an object to a person. Sets the assignee, records an {@link EventType#OBJECT_ASSIGNED}
     * event (the assignment history), and — when the current state has a legal {@code assign} action
     * (e.g. INCIDENT {@code OPEN → ASSIGNED}) — also advances the workflow.
     *
     * @throws NoSuchElementException   unknown object id
     * @throws IllegalArgumentException no assignee was supplied
     */
    public OperationalObject assign(String id, String assignee, String actor) {
        OperationalObject obj = require(id);
        if (assignee == null || assignee.isBlank()) throw new IllegalArgumentException("assign needs an 'assignee'");
        String target = assignee.trim();
        long now = System.currentTimeMillis();
        String from = obj.assignee();
        OperationalObject updated = store.update(obj.withAssignee(target, now));
        EventLog.current().emit(Event.builder(EventType.OBJECT_ASSIGNED)
                .level(EventLevel.INFO)
                .source(SOURCE)
                .correlationId(obj.correlationId())
                .message(obj.objectType() + " " + id + " assigned to " + target
                        + (from == null || from.isBlank() ? "" : " (was " + from + ")")
                        + (actor == null ? "" : " by " + actor))
                .attr("objectId", id)
                .attr("objectType", obj.objectType().name())
                .attr("from", from)
                .attr("to", target)
                .attr("actor", actor));
        // Unify assignment with the workflow: if the current state legally accepts an `assign` action
        // (INCIDENT OPEN → ASSIGNED), advance it too so status tracks reality. Absent such a transition
        // (already ASSIGNED, or a type without one) the assignee change alone stands.
        Workflow wf = workflow(obj.objectType());
        if (wf.apply(updated.status(), "assign").isPresent())
            return commit(updated, wf, wf.apply(updated.status(), "assign").get(), "assign", actor);
        return updated;
    }

    /**
     * Add {@code user} to an object's watcher list (idempotent); returns the updated object. Unknown id → 404.
     *
     * <p>⚠ RETIRE-HALVES-1 deleted the {@code /objects/{id}/watch|unwatch|watchers} routes, so this and
     * {@link #unwatch} have no production caller. They are RETAINED deliberately: the {@code watchers}
     * attribute is still live — {@code mergeCases} unions it, reached by {@code POST /objects/{id}/merge} —
     * and this is the only seam that can seed a watcher to test that union. ⛔ Do not delete as dead code.
     */
    public OperationalObject watch(String id, String user) {
        return mutateWatchers(id, user, true);
    }

    /** Remove {@code user} from an object's watcher list (idempotent); returns the updated object. Unknown id → 404. */
    public OperationalObject unwatch(String id, String user) {
        return mutateWatchers(id, user, false);
    }

    private OperationalObject mutateWatchers(String id, String user, boolean add) {
        if (user == null || user.isBlank()) throw new IllegalArgumentException("watch needs a 'user'");
        OperationalObject obj = require(id);
        String u = user.trim();
        List<String> current = new ArrayList<>(obj.watchers());
        boolean changed = add ? (!current.contains(u) && current.add(u)) : current.remove(u);
        if (!changed) return obj;   // idempotent — no write, no event
        return store.update(obj.withAttributes(
                Map.of(ATTR_WATCHERS, String.join(",", current)), System.currentTimeMillis()));
    }

    /**
     * SLA sweep (Phase 3): breach every {@link ObjectType#INCIDENT} that has passed its {@link #ATTR_DUE_AT}
     * deadline while still being worked. An incident qualifies when it carries a {@code dueAt} attribute at
     * or before {@code now}, is not yet {@code RESOLVED} and not terminal ({@code CLOSED}), and has not
     * already breached. Each new breach stamps a {@link #ATTR_SLA_BREACHED_AT} marker (so repeated sweeps
     * never re-fire) and emits an {@link EventType#OBJECT_SLA_BREACH} event onto {@link EventLog#global()},
     * so the breach surfaces in the Event Viewer next to the incident's {@code OBJECT_ACTIVITY} history.
     *
     * <p>Intended to be driven by {@link com.gamma.util.Scheduler} (see {@code CollectorService}); {@code now}
     * is injected so the schedule and tests evaluate against the same clock. Safe to call with no incidents.
     *
     * @param now the wall-clock instant (epoch millis) to evaluate deadlines against
     * @return the number of incidents newly breached by this sweep
     */
    public int sweepIncidentSla(long now) {
        List<OperationalObject> incidents = store.query(ObjectQuery.builder()
                .objectType(ObjectType.INCIDENT).limit(ObjectQuery.MAX_LIMIT).build());
        int breached = 0;
        for (OperationalObject o : incidents) {
            if (o.isClosed()) continue;                                      // terminal (CLOSED) — settled
            if ("RESOLVED".equalsIgnoreCase(o.status())) continue;           // fixed — SLA clock stopped
            if (o.attributes().containsKey(ATTR_SLA_BREACHED_AT)) continue;  // already breached — idempotent
            long dueAt = parseEpoch(o.attributes().get(ATTR_DUE_AT));
            if (dueAt <= 0 || dueAt > now) continue;                         // no SLA set, or not yet due
            OperationalObject marked = store.update(
                    o.withAttributes(Map.of(ATTR_SLA_BREACHED_AT, Long.toString(now)), now));
            EventLog.current().emit(Event.builder(EventType.OBJECT_SLA_BREACH)
                    .level(EventLevel.WARN)
                    .source(SOURCE)
                    .correlationId(marked.correlationId())
                    .message("INCIDENT " + marked.id() + " breached SLA: due " + dueAt
                            + ", overdue " + (now - dueAt) + "ms")
                    .attr("objectId", marked.id())
                    .attr("objectType", marked.objectType().name())
                    .attr("status", marked.status())
                    .attr("severity", marked.severity())
                    .attr("assignee", marked.assignee())
                    .attr("dueAt", dueAt)
                    .attr("overdueMs", now - dueAt));
            breached++;
        }
        return breached;
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ObjectService.class);

    // ── correlation graph (Phase 4) ──────────────────────────────────────────────────

    /**
     * Persist a directed correlation {@link ObjectLink} {@code from --relationship--> to} (e.g. a CASE
     * {@code CONTAINS} an INCIDENT) and emit an {@link EventType#OBJECT_LINKED} event so the correlation
     * shows in the Event Viewer. Both endpoints must exist (else {@link NoSuchElementException} → 404).
     * Idempotent: an identical edge (same {@code from}/{@code to}/{@code relationship}) is returned as-is
     * rather than duplicated. A {@code null} {@code relationship} defaults to {@link LinkRelationship#RELATED_TO}.
     */
    public ObjectLink link(String fromId, String toId, String relationship, String actor) {
        OperationalObject from = require(fromId);
        OperationalObject to = require(toId);
        String rel = (relationship == null || relationship.isBlank()) ? LinkRelationship.RELATED_TO : relationship;
        for (ObjectLink existing : links.incident(fromId)) {
            if (existing.fromId().equals(fromId) && existing.toId().equals(toId)
                    && existing.relationship().equalsIgnoreCase(rel))
                return existing;   // already linked — idempotent
        }
        ObjectLink created = links.add(ObjectLink.of(fromId, from.objectType(), toId, to.objectType(), rel));
        EventLog.current().emit(Event.builder(EventType.OBJECT_LINKED)
                .level(EventLevel.INFO)
                .source(SOURCE)
                .correlationId(from.correlationId())
                .message(from.objectType() + " " + fromId + " " + created.relationship() + " "
                        + to.objectType() + " " + toId + (actor == null ? "" : " (by " + actor + ")"))
                .attr("objectId", fromId)
                .attr("from", fromId)
                .attr("fromType", from.objectType().name())
                .attr("to", toId)
                .attr("toType", to.objectType().name())
                .attr("relationship", created.relationship())
                .attr("actor", actor));
        return created;
    }

    /** Every link touching {@code id} at either end, newest-first (the object's correlations). */
    public List<ObjectLink> linksOf(String id) {
        return links.incident(id);
    }

    /**
     * Remove the edge {@code fromId → toId} via {@code relationship} (case group management: e.g.
     * removing a member incident from a Case). The removal is audited as an {@link EventType#OBJECT_ACTIVITY}
     * event; {@code false} when no such edge exists. Unknown {@code fromId} → {@link NoSuchElementException}.
     */
    public boolean unlink(String fromId, String toId, String relationship, String actor) {
        OperationalObject from = require(fromId);
        String rel = (relationship == null || relationship.isBlank()) ? LinkRelationship.RELATED_TO : relationship;
        boolean removed = links.remove(fromId, toId, rel);
        if (removed) {
            EventLog.current().emit(Event.builder(EventType.OBJECT_ACTIVITY)
                    .level(EventLevel.INFO)
                    .source(SOURCE)
                    .correlationId(from.correlationId())
                    .message(from.objectType() + " " + fromId + " unlinked " + rel.toUpperCase() + " " + toId
                            + (actor == null ? "" : " (by " + actor + ")"))
                    .attr("objectId", fromId)
                    .attr("from", fromId)
                    .attr("to", toId)
                    .attr("relationship", rel.toUpperCase())
                    .attr("action", "unlink")
                    .attr("actor", actor));
        }
        return removed;
    }

    // ── case group management: Split & Merge (GLOSSARY §9) ───────────────────────────

    /** Merge outcome: the updated survivor, the absorbed case ids, and how many member links moved. */
    public record MergeResult(OperationalObject survivor, List<String> merged, int membersMoved) {}

    /** Split outcome: the newly opened case and how many member links moved to it. */
    public record SplitResult(OperationalObject part, int membersMoved) {}

    /**
     * <b>Merge</b> {@code sources} into the surviving case {@code survivorId} — likely-similar cases
     * become one group managed as one. Per source: its {@code CONTAINS} members are re-pointed to the
     * survivor (idempotent), tags + watchers union onto the survivor, a {@code MERGED_INTO} trace link
     * + {@link #ATTR_MERGED_INTO} marker + comments record the merge, and the source is closed
     * (direct terminal move — an administrative action, deliberately outside the workflow). History,
     * comments and attachments stay on the source, reachable via the trace link.
     *
     * @throws NoSuchElementException   unknown survivor/source id
     * @throws IllegalArgumentException empty {@code sources}
     * @throws IllegalStateException    a non-CASE participant, self-merge, or an already-closed/merged source
     */
    public MergeResult mergeCases(String survivorId, List<String> sources, String actor) {
        if (sources == null || sources.isEmpty())
            throw new IllegalArgumentException("merge needs at least one source case");
        OperationalObject survivor = requireActiveCase(survivorId, "merge survivor");
        List<OperationalObject> absorbed = new ArrayList<>();
        for (String id : new LinkedHashSet<>(sources)) {
            if (id.equals(survivorId)) throw new IllegalStateException("a case cannot be merged into itself");
            absorbed.add(requireActiveCase(id, "merge source"));
        }
        long now = System.currentTimeMillis();
        int moved = 0;
        Set<String> tags = new LinkedHashSet<>(tagsOf(survivor.id()));
        Set<String> watchers = new LinkedHashSet<>(survivor.watchers());
        for (OperationalObject src : absorbed) {
            moved += movePart(src.id(), survivorId, null);
            tags.addAll(tagsOf(src.id()));
            watchers.addAll(src.watchers());
            link(src.id(), survivorId, LinkRelationship.MERGED_INTO, actor);
            store.update(src.withAttributes(Map.of(ATTR_MERGED_INTO, survivorId), now)
                    .withStatus("CLOSED", now, true));
            comment(src.id(), actor, "Merged into " + survivorId + (actor == null ? "" : " by " + actor) + ".");
            comment(survivorId, actor, "Absorbed " + src.id() + " (\"" + src.title() + "\")"
                    + (actor == null ? "" : " by " + actor) + ".");
            EventLog.current().emit(Event.builder(EventType.OBJECT_ACTIVITY)
                    .level(EventLevel.INFO)
                    .source(SOURCE)
                    .correlationId(src.correlationId())
                    .message("CASE " + src.id() + " merged into " + survivorId
                            + (actor == null ? "" : " by " + actor))
                    .attr("objectId", src.id())
                    .attr("action", "merge")
                    .attr("survivor", survivorId)
                    .attr("actor", actor));
        }
        // D7: the survivor absorbs the union in the assignment store; the CSV below is its projection.
        for (String tag : tags)
            tagAssignments.add(com.gamma.objects.TagAssignment.of(
                    tag, com.gamma.objects.AnnotationKinds.OBJECT, survivorId, actor));
        Map<String, String> union = new LinkedHashMap<>();
        if (!tags.isEmpty()) union.put(ATTR_TAGS, String.join(",", tagsOf(survivorId)));
        if (!watchers.isEmpty()) union.put(ATTR_WATCHERS, String.join(",", watchers));
        OperationalObject updated = union.isEmpty() ? require(survivorId)
                : store.update(require(survivorId).withAttributes(union, now));
        return new MergeResult(updated, absorbed.stream().map(OperationalObject::id).toList(), moved);
    }

    /**
     * <b>Split</b> the listed {@code members} out of case {@code caseId} into a newly opened case
     * titled {@code title}, managed individually from here on. The new part inherits the original's
     * category + tags, the members' {@code CONTAINS} links move over, a {@code SPLIT_FROM} trace link
     * + comments record the split, and the original stays active with its remaining members. An
     * optional {@code assignee} routes the new part.
     *
     * @throws NoSuchElementException   unknown case id
     * @throws IllegalArgumentException blank title / empty members
     * @throws IllegalStateException    a non-CASE or closed case, or a member the case does not contain
     */
    public SplitResult splitCase(String caseId, String title, List<String> members,
                                 String assignee, String actor) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("split needs a 'title' for the new case");
        if (members == null || members.isEmpty()) throw new IllegalArgumentException("split needs at least one member");
        OperationalObject original = requireActiveCase(caseId, "split");
        Set<String> contained = new LinkedHashSet<>();
        for (ObjectLink l : links.incident(caseId)) {
            if (l.fromId().equals(caseId) && LinkRelationship.CONTAINS.equalsIgnoreCase(l.relationship()))
                contained.add(l.toId());
        }
        for (String m : members) {
            if (!contained.contains(m))
                throw new IllegalStateException("case " + caseId + " does not contain member '" + m + "'");
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        String category = original.attributes().get("category");
        List<String> tags = tagsOf(caseId);
        if (category != null) attrs.put("category", category);
        // The new part is created through open(), which adopts this CSV into the assignment store.
        if (!tags.isEmpty()) attrs.put(ATTR_TAGS, String.join(",", tags));
        OperationalObject part = open(ObjectType.CASE, title, "Split from " + caseId + ": " + original.title(),
                original.severity(), original.priority(), original.owner(), null, original.correlationId(), attrs);
        int moved = movePart(caseId, part.id(), new LinkedHashSet<>(members));
        link(part.id(), caseId, LinkRelationship.SPLIT_FROM, actor);
        comment(caseId, actor, "Split " + moved + " member(s) out into " + part.id() + " (\"" + title + "\")"
                + (actor == null ? "" : " by " + actor) + ".");
        comment(part.id(), actor, "Split from " + caseId + (actor == null ? "" : " by " + actor) + ".");
        if (assignee != null && !assignee.isBlank()) assign(part.id(), assignee, actor);
        EventLog.current().emit(Event.builder(EventType.OBJECT_ACTIVITY)
                .level(EventLevel.INFO)
                .source(SOURCE)
                .correlationId(original.correlationId())
                .message("CASE " + part.id() + " split from " + caseId + " (" + moved + " member(s))"
                        + (actor == null ? "" : " by " + actor))
                .attr("objectId", part.id())
                .attr("action", "split")
                .attr("original", caseId)
                .attr("membersMoved", moved)
                .attr("actor", actor));
        return new SplitResult(require(part.id()), moved);
    }

    /**
     * Re-point {@code CONTAINS} member edges from {@code fromCase} to {@code toCase} — all of them, or
     * only {@code onlyMembers} when non-null. Idempotent per member; returns how many edges moved.
     */
    private int movePart(String fromCase, String toCase, Set<String> onlyMembers) {
        int moved = 0;
        for (ObjectLink l : links.incident(fromCase)) {
            if (!l.fromId().equals(fromCase) || !LinkRelationship.CONTAINS.equalsIgnoreCase(l.relationship())) continue;
            if (onlyMembers != null && !onlyMembers.contains(l.toId())) continue;
            links.remove(fromCase, l.toId(), LinkRelationship.CONTAINS);
            link(toCase, l.toId(), LinkRelationship.CONTAINS, null);   // idempotent add + OBJECT_LINKED audit
            moved++;
        }
        return moved;
    }

    /** One Link Analysis Entity to become a member of a new Case: its node id, source Dataset and label. */
    public record EntityMember(String entityKey, String dataset, String label) {
        public EntityMember {
            if (entityKey == null || !entityKey.startsWith("entity:") || entityKey.length() <= "entity:".length())
                throw new IllegalArgumentException("an entity needs its node id ('entity:…'), got '" + entityKey + "'");
            if (dataset == null || dataset.isBlank())
                throw new IllegalArgumentException("entity '" + entityKey + "' needs the Dataset it was projected from");
            label = label == null || label.isBlank() ? entityKey : label.trim();
        }
    }

    /** What {@link #openCaseFromEntities} did: the Case, every member it now CONTAINS, and which were new. */
    public record EntityCase(OperationalObject caseObject, List<OperationalObject> members, Set<String> minted) {}

    /**
     * Open a Case whose first members are minted from Link Analysis Entities (LA-CASE-CREATE-IN-PLACE-1,
     * operator decision 2026-09-23: <i>mint from the node</i>). Each Entity becomes an INCIDENT keyed by
     * {@link #ATTR_ENTITY_KEY} + {@link #ATTR_ENTITY_DATASET}; one that already exists — and that the caller
     * can see — is REUSED, never duplicated. {@code existingMembers} are Incidents the graph already named
     * (a node projected from an {@code incidentId} column). The 2026-07-22 rule stands: a Case is never born
     * empty, so at least one member is required.
     *
     * <p><b>Fails closed, in two layers.</b> Everything that can be refused is checked BEFORE the first write
     * (members exist, are visible, are Incidents). The writes then run under compensation: if any of them
     * throws, every object this call created — the Case and each minted Incident — is removed with its
     * links, notes and tag edges, and the error propagates. Reused objects are never touched. So a failure
     * leaves no orphan Case and no half-minted members; the OBJECT_OPENED events already emitted stay in the
     * append-only log, followed by a {@code rollback} activity naming each discarded id.
     *
     * <p>{@code synchronized} so two concurrent mints of one Entity cannot both miss the lookup and open two.
     *
     * @param visible the caller's data-scope predicate — an object it cannot see is neither reused nor linked
     * @throws IllegalArgumentException blank title, no members, an existing member that is not an INCIDENT
     * @throws NoSuchElementException   an existing member that is absent or not visible (existence-hiding)
     */
    public synchronized EntityCase openCaseFromEntities(String title, String description, List<EntityMember> entities,
                                                        List<String> existingMembers,
                                                        java.util.function.Predicate<OperationalObject> visible,
                                                        String actor) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("a Case needs a 'title'");
        if (entities.isEmpty() && existingMembers.isEmpty())
            throw new IllegalArgumentException("a Case CONTAINS its members — name at least one entity");
        // ── resolve (no writes) ──
        Map<String, OperationalObject> members = new LinkedHashMap<>();
        for (String id : existingMembers) {
            OperationalObject o = store.get(id).filter(visible).orElseThrow(
                    () -> new NoSuchElementException("no object with id '" + id + "'"));
            if (o.objectType() != ObjectType.INCIDENT)
                throw new IllegalArgumentException("a Case member must be an INCIDENT, but " + id + " is a " + o.objectType());
            members.put(o.id(), o);
        }
        Map<List<String>, EntityMember> toMint = new LinkedHashMap<>();
        Map<List<String>, OperationalObject> reused = new LinkedHashMap<>();
        for (EntityMember e : entities) {
            List<String> identity = List.of(e.entityKey(), e.dataset());
            if (toMint.containsKey(identity) || reused.containsKey(identity)) continue;
            Optional<OperationalObject> existing = store.findByAttributes(ObjectType.INCIDENT,
                    Map.of(ATTR_ENTITY_KEY, e.entityKey(), ATTR_ENTITY_DATASET, e.dataset()), ObjectQuery.MAX_LIMIT)
                    .stream().filter(visible).findFirst();
            if (existing.isPresent()) reused.put(identity, existing.get());
            else toMint.put(identity, e);
        }
        // ── write, under compensation ──
        List<String> created = new ArrayList<>();
        try {
            for (OperationalObject o : reused.values()) members.putIfAbsent(o.id(), o);
            for (EntityMember e : toMint.values()) {
                Map<String, String> attrs = new LinkedHashMap<>();
                attrs.put(ATTR_ENTITY_KEY, e.entityKey());
                attrs.put(ATTR_ENTITY_DATASET, e.dataset());
                OperationalObject minted = open(ObjectType.INCIDENT, e.label(),
                        "Raised from Link Analysis: Entity " + e.entityKey() + " in Dataset " + e.dataset() + ".",
                        null, null, null, null, null, attrs);
                created.add(minted.id());
                members.put(minted.id(), minted);
            }
            OperationalObject kase = open(ObjectType.CASE, title.trim(), description, null, null, null, null, null, Map.of());
            created.add(kase.id());
            for (String member : members.keySet()) link(kase.id(), member, LinkRelationship.CONTAINS, actor);
            return new EntityCase(require(kase.id()), List.copyOf(members.values()),
                    Set.copyOf(created.subList(0, created.size() - 1)));
        } catch (RuntimeException failure) {
            for (String id : created.reversed()) discard(id, actor, failure);
            throw failure;
        }
    }

    /** Compensation for {@link #openCaseFromEntities}: remove one object it created, cascade first (see {@link #purge}). */
    private void discard(String objectId, String actor, RuntimeException cause) {
        try {
            notes.deleteForTarget(AnnotationKinds.OBJECT, objectId);
            links.removeAllIncident(objectId);
            tagAssignments.removeAllForTarget(AnnotationKinds.OBJECT, objectId);
            if (store.get(objectId).isPresent()) store.delete(objectId);
            EventLog.current().emit(Event.builder(EventType.OBJECT_ACTIVITY)
                    .level(EventLevel.WARN)
                    .source(SOURCE)
                    .message("object " + objectId + " discarded: opening a Case from entities failed ("
                            + cause.getMessage() + ")")
                    .attr("objectId", objectId)
                    .attr("action", "rollback")
                    .attr("actor", actor));
        } catch (RuntimeException secondary) {
            cause.addSuppressed(secondary);
        }
    }

    /** The object must exist, be a CASE, and not be closed/merged — the precondition for group operations. */
    private OperationalObject requireActiveCase(String id, String what) {
        OperationalObject o = require(id);
        if (o.objectType() != ObjectType.CASE)
            throw new IllegalStateException(what + " must be a CASE, but " + id + " is a " + o.objectType());
        if (o.isClosed() || o.attributes().containsKey(ATTR_MERGED_INTO))
            throw new IllegalStateException(what + " " + id + " is closed"
                    + (o.attributes().containsKey(ATTR_MERGED_INTO) ? " (already merged)" : ""));
        return o;
    }

    /**
     * A correlation subgraph around {@code rootId} out to {@code depth} hops (BFS over links in both
     * directions): {@code {root, depth, nodes:[{id,objectType,title,status,severity}], edges:[link maps]}}.
     * {@code nodes} carries a light summary of each reachable object (skipping any whose row no longer
     * exists), so the UI can render the graph without extra lookups. Unknown root → {@link NoSuchElementException}.
     */
    public Map<String, Object> graph(String rootId, int depth) {
        require(rootId);
        int maxDepth = Math.max(1, depth);
        Set<String> seen = new LinkedHashSet<>();
        Set<ObjectLink> edges = new LinkedHashSet<>();
        Deque<String> frontier = new ArrayDeque<>();
        seen.add(rootId);
        frontier.add(rootId);
        for (int hop = 0; hop < maxDepth && !frontier.isEmpty(); hop++) {
            for (int i = frontier.size(); i > 0; i--) {
                String cur = frontier.poll();
                for (ObjectLink l : links.incident(cur)) {
                    edges.add(l);
                    String other = l.other(cur);
                    if (other != null && seen.add(other)) frontier.add(other);
                }
            }
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String oid : seen) store.get(oid).ifPresent(o -> nodes.add(nodeSummary(o)));
        List<Map<String, Object>> edgeMaps = new ArrayList<>();
        for (ObjectLink l : edges) edgeMaps.add(l.toMap());
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("root", rootId);
        g.put("depth", maxDepth);
        g.put("nodes", nodes);
        g.put("edges", edgeMaps);
        return g;
    }

    private static Map<String, Object> nodeSummary(OperationalObject o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.id());
        m.put("objectType", o.objectType().name());
        m.put("title", o.title());
        m.put("status", o.status());
        m.put("severity", o.severity());
        return m;
    }

    // ── evidence: comments / attachments / RCA (Phase 4 follow-up) ───────────────────

    /**
     * The engine-side note store, for a caller that needs the kind-agnostic D10 path (a
     * {@link NoteService} over a wider {@link NoteService.TargetResolver}). Object-targeted notes should
     * keep using {@link #comment}/{@link #attach} here.
     */
    public NoteStore noteStore() {
        return notes;
    }

    /** Add a free-text comment to an object (unknown id → {@link NoSuchElementException}); emits OBJECT_NOTE. */
    public ObjectNote comment(String objectId, String author, String body) {
        return noteService.comment(AnnotationKinds.OBJECT, objectId, author, body);
    }

    /**
     * Attach a reference to external evidence (file/URL <em>metadata only</em> — the bytes stay out of the
     * lean core) to an object; emits an {@link EventType#OBJECT_NOTE} event. Unknown id → {@link NoSuchElementException}.
     */
    public ObjectNote attach(String objectId, String author, String name, String contentType,
                             String uri, String caption) {
        return noteService.attach(AnnotationKinds.OBJECT, objectId, author, name, contentType, uri, caption);
    }

    /** An object's notes, newest-first; {@code kind} {@code null} returns comments and attachments alike.
     *  Unlike the writes this stays permissive on an unknown id (an absent object simply has no notes) —
     *  the shipped {@code GET /objects/{id}/comments} contract. */
    public List<ObjectNote> notesOf(String objectId, NoteKind kind) {
        return notes.forObject(objectId, kind);
    }

    /**
     * Apply an {@link RcaTemplate} to an object (typically a CASE): seed one {@link NoteKind#COMMENT} per
     * template section, giving the investigator a structured skeleton to complete. Unknown id →
     * {@link NoSuchElementException}. Returns the seeded notes in section order.
     */
    public List<ObjectNote> applyRca(String objectId, RcaTemplate template, String actor) {
        String correlationId = noteService.require(AnnotationKinds.OBJECT, objectId);
        List<ObjectNote> seeded = new ArrayList<>();
        for (String section : template.sections())
            seeded.add(noteService.append(ObjectNote.comment(objectId, actor, "## " + section), actor, correlationId));
        return seeded;
    }

    // ── internals ──────────────────────────────────────────────────────────────────

    // ── retention purge (MNT-14) ─────────────────────────────────────────────────────

    /** What one {@link #purge} removed: the object, plus the dependent rows that went with it. */
    public record PurgeOutcome(String objectId, int notes, int links, int tagEdges) {

        /** Dependent rows removed alongside the object — the cascade's size, excluding the object itself. */
        public int dependents() {
            return notes + links + tagEdges;
        }
    }

    /**
     * Objects whose retention window has expired: {@code objectType == type}, {@code status} matches, and
     * the object was closed (archived) strictly before {@code closedBefore} — at most {@code limit} of
     * them, longest-expired first. Delegates to {@link ObjectQuery#purgeEligible} so the in-memory and DB
     * backends cannot disagree about eligibility.
     *
     * <p>⚠ <b>Legal hold is not applied here</b> and cannot be: a hold lives in the attribute bag, not a
     * column, so no store can filter on it. Every caller must exclude {@link #hasLegalHold} itself —
     * {@link #purge} refuses a held object as a backstop, but a dry-run preview that forgets the check
     * would over-report. The separation is deliberate, not an oversight.
     */
    public List<OperationalObject> purgeEligible(ObjectType type, String status, long closedBefore, int limit) {
        return store.query(ObjectQuery.purgeEligible(type, status, closedBefore, limit));
    }

    /**
     * Physically remove an object and every dependent row that references it — the MNT-14 retention
     * cascade. Returns what was removed; unknown id ⇒ {@link NoSuchElementException}.
     *
     * <p>The cascade lives here, and not in the calling task, because this service is the one place that
     * holds all four stores; {@link ObjectStore#delete} explicitly does not cascade and requires its
     * caller to.
     *
     * <p><b>Dependents are removed before the object, deliberately.</b> If a later step fails, the object
     * still exists and the next sweep retries it. Deleting the object first and then failing would leave
     * notes and edges pointing at an id that can no longer be resolved — invisible orphans, the failure
     * mode this ordering exists to prevent.
     *
     * <p>⚠ <b>A purge is not "all trace removed" (MNT-14 G3).</b> {@link com.gamma.event.EventStore} is append-only by
     * contract, so the object's {@link EventType#OBJECT_ACTIVITY} history — including the purge itself,
     * emitted below — outlives it permanently. That is the intended behaviour: the audit log is not the
     * record being retention-managed. Anyone answering a legal/DPA erasure question needs to know this.
     *
     * @throws IllegalStateException if the object is under {@link #hasLegalHold legal hold}
     */
    public PurgeOutcome purge(String objectId, String actor) {
        OperationalObject o = require(objectId);
        // Checked here rather than only in the caller's preview: a hold applied between preview and run
        // must still be honoured, and this is the only chokepoint every purge passes through.
        if (hasLegalHold(o))
            throw new IllegalStateException("object '" + objectId + "' is under legal hold — not purgeable");
        String correlationId = o.correlationId();          // read before the row is gone
        int notesRemoved = notes.deleteForTarget(AnnotationKinds.OBJECT, objectId);
        int linksRemoved = links.removeAllIncident(objectId);
        int tagsRemoved = tagAssignments.removeAllForTarget(AnnotationKinds.OBJECT, objectId);
        store.delete(objectId);
        EventLog.current().emit(Event.builder(EventType.OBJECT_ACTIVITY)
                .level(EventLevel.INFO)
                .source(SOURCE)
                .correlationId(correlationId)
                .message(o.objectType() + " " + objectId + " purged after retention expiry"
                        + (actor == null ? "" : " by " + actor))
                .attr("objectId", objectId)
                .attr("objectType", o.objectType().name())
                .attr("action", "purge")
                .attr("notesRemoved", String.valueOf(notesRemoved))
                .attr("linksRemoved", String.valueOf(linksRemoved))
                .attr("tagEdgesRemoved", String.valueOf(tagsRemoved))
                .attr("actor", actor));
        return new PurgeOutcome(objectId, notesRemoved, linksRemoved, tagsRemoved);
    }

    private static long parseEpoch(String s) {
        if (s == null || s.isBlank()) return 0L;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private OperationalObject require(String id) {
        return store.get(id).orElseThrow(() -> new NoSuchElementException("no object with id '" + id + "'"));
    }

    private OperationalObject commit(OperationalObject obj, Workflow wf, String target,
                                     String action, String actor) {
        if (obj.objectType() == ObjectType.INCIDENT && "RESOLVED".equalsIgnoreCase(target)) {
            List<String> gaps = incidentResolutionGaps(obj);
            if (!gaps.isEmpty())
                throw new IllegalStateException(
                        "incident resolution blocked — missing: " + String.join(", ", gaps));
        }
        long now = System.currentTimeMillis();
        OperationalObject next = obj.withStatus(target, now, wf.isTerminal(target));
        // INCIDENT-KPI-MTTR-1: commit() is the single place every status change lands, so stamping here
        // cannot be bypassed by transition / transitionTo / resolve. Overwrites on a re-resolve on
        // purpose — see ATTR_RESOLVED_AT.
        if ("RESOLVED".equalsIgnoreCase(target)) next = next.withAttributes(Map.of(ATTR_RESOLVED_AT, Long.toString(now)), now);
        OperationalObject updated = store.update(next);
        EventLog.current().emit(Event.builder(EventType.OBJECT_ACTIVITY)
                .level(EventLevel.INFO)
                .source(SOURCE)
                .correlationId(obj.correlationId())
                .message(obj.objectType() + " " + obj.id() + ": " + obj.status() + " -> " + target
                        + " (" + action + (actor == null ? "" : " by " + actor) + ")")
                .attr("objectId", obj.id())
                .attr("objectType", obj.objectType().name())
                .attr("from", obj.status())
                .attr("to", target)
                .attr("action", action)
                .attr("actor", actor));
        return updated;
    }

    /**
     * I1 — the mandatory resolution pattern (`case-management-design.md` §2b): which of the four
     * required postmortem sections an incident's {@code attributes.postmortem} JSON blob still lacks.
     * Empty = complete. Server-side mirror of the UI's {@code postmortemGaps} soft-warn (`mail-model.ts`)
     * — this is the hard gate the doc calls out as the follow-up, enforced in {@link #commit} so the
     * API can't bypass it the way a UI-only check could.
     */
    static List<String> incidentResolutionGaps(OperationalObject obj) {
        Map<String, Object> pm = JsonAttributes.fromPayloadJson(obj.attributes().get("postmortem"));
        List<String> gaps = new ArrayList<>();
        if (!anyEntryNonBlank(Values.listAt(pm, "timeline"), "time", "text")) gaps.add("timeline");
        if (!anyStringNonBlank(Values.listAt(pm, "causeAnalysis"))) gaps.add("cause analysis");
        if (!anyEntryNonBlank(Values.listAt(pm, "actions"), "text")) gaps.add("corrective actions");
        String dueAt = obj.attributes().get(ATTR_DUE_AT);
        if (dueAt == null || dueAt.isBlank()) gaps.add("SLA");
        return gaps;
    }

    /** True if any element of {@code list} (maps of {@code String -> Object}) has a non-blank value at any of {@code keys}. */
    private static boolean anyEntryNonBlank(List<Object> list, String... keys) {
        if (list == null) return false;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) continue;
            for (String key : keys) {
                Object v = row.get(key);
                if (v != null && !v.toString().isBlank()) return true;
            }
        }
        return false;
    }

    /** True if any element of {@code list} (plain strings) is non-blank. */
    private static boolean anyStringNonBlank(List<Object> list) {
        if (list == null) return false;
        for (Object item : list) {
            if (item != null && !item.toString().isBlank()) return true;
        }
        return false;
    }
}
