package com.gamma.ops;

import com.gamma.objects.AnnotationKinds;
import com.gamma.workflow.ObjectType;

import com.gamma.audit.Event;
import com.gamma.audit.EventLevel;
import com.gamma.event.EventLog;
import com.gamma.audit.EventType;
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
import com.gamma.ops.tag.Tag;
import com.gamma.ops.tag.TagRule;
import com.gamma.workflow.EscalationRule;
import com.gamma.workflow.SlaPolicy;
import com.gamma.workflow.Workflow;
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
    /** Attribute key: the response deadline (epoch millis) an {@link SlaPolicy} stamped — met by leaving the initial state. */
    public static final String ATTR_RESPONSE_DUE_AT = "responseDueAt";
    /** Attribute key stamped (epoch millis) when a response breach has been emitted. */
    public static final String ATTR_SLA_RESPONSE_BREACHED_AT = "slaResponseBreachedAt";
    /** Attribute key: the priority the policy-stamped deadlines were computed for — a changed priority recomputes them. */
    public static final String ATTR_SLA_PRIORITY = "slaPriority";
    /** Attribute key: the {@link SlaPolicy} object type whose targets set this object's deadlines. */
    public static final String ATTR_SLA_POLICY = "slaPolicy";
    /**
     * Attribute key: every Escalation Rule firing on this object, comma-separated {@code <rule>@<breach>} — the
     * idempotency record. A rule fires for a breach only when its entry is absent, so a later sweep never repeats it.
     */
    public static final String ATTR_ESCALATIONS = "escalations";
    /** Most escalations one sweep performs; the rest wait for the next sweep. */
    static final int MAX_ESCALATIONS_PER_SWEEP = 500;
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
     * occurrence time in hand: a producer such as the retired Event bridge copies the triggering {@code Event.ts()}. ⚠ It is
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
    /** Attribute key stamped on a rule-raised case: the {@code CaseRule} name that opened it (GLOSSARY §9, C5). */
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

    /** An Incident's Disposition (WS-10), one of {@link com.gamma.objects.FindingsSpec#DISPOSITIONS} — set with the
     *  resolve that needs it. A Case's Disposition is instead a value of its Findings blob. */
    public static final String ATTR_DISPOSITION = "disposition";

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
    private volatile GovernanceRegistry governance;                  // authored, hot-reloaded (ASSURE-WORKFLOW-SLA-1)
    private final Map<String, Tag> tags = new ConcurrentHashMap<>();          // user-created tag registry
    private final Map<String, TagRule> tagRules = new ConcurrentHashMap<>();  // Gmail-filter Tag Rules, by name

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
            if (o.isInert()) throw new InertObjectException(targetId, o.typeName());   // a note is a write
            return o.correlationId() == null ? "" : o.correlationId();
        });
        for (ObjectType t : ObjectType.values()) {
            Workflow wf = overrides == null ? null : overrides.get(t);
            workflows.put(t, wf != null ? wf : Workflow.defaultFor(t));
        }
    }

    /**
     * The effective workflow for {@code type}: the Space's authored {@code workflow} component when one is installed
     * and valid ({@link #useGovernance}, re-read on change), else a {@code *_workflow.toon} override, else the built-in.
     */
    public Workflow workflow(ObjectType type) {
        GovernanceRegistry g = governance;
        Workflow authored = g == null ? null : g.snapshot().workflows().get(type);
        return authored != null ? authored : workflows.get(type);
    }

    /**
     * Read this Space's authored Workflows, SLA policies and Escalation Rules from {@code registryRoot}
     * ({@code <config>/registry}), re-reading them whenever they change (ASSURE-WORKFLOW-SLA-1). {@code null} = none.
     */
    public void useGovernance(java.nio.file.Path registryRoot) {
        this.governance = registryRoot == null ? null : new GovernanceRegistry(registryRoot);
    }

    private GovernanceRegistry.Snapshot governance() {
        GovernanceRegistry g = governance;
        return g == null ? GovernanceRegistry.Snapshot.EMPTY : g.snapshot();
    }

    /**
     * Install (create or replace) the workflow for an {@link ObjectType} — the boot-time seam for
     * {@code *_workflow.toon} overrides ({@link com.gamma.service.ServiceBootstrap} scans them and calls
     * this after construction, the same post-construction pattern as {@link #registerTag}/
     * {@code registerCaseRule}). Replaces the built-in {@link Workflow#defaultFor default} baked in by
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
        return transition(id, action, actor, null);
    }

    /**
     * As {@link #transition(String, String, String)}, recording {@code disposition} with the move: an Incident
     * resolves only with a Disposition from {@link com.gamma.objects.FindingsSpec#DISPOSITIONS} (WS-10) — the one
     * given here, else one already on {@code attributes.disposition}. {@code null} supplies none.
     *
     * @throws IllegalArgumentException if a disposition is given off the ladder, or for anything but an Incident
     *                                  moving to {@code RESOLVED}
     */
    public OperationalObject transition(String id, String action, String actor, String disposition) {
        return retrying(() -> {
            OperationalObject obj = require(id);
            Workflow wf = workflow(obj.objectType());
            String target = wf.apply(obj.status(), action).orElseThrow(() -> new IllegalStateException(
                    "illegal transition: '" + action + "' from " + obj.status()
                            + " (" + obj.objectType() + ")"));
            return commit(obj, wf, target, action, actor, disposition);
        });
    }

    /**
     * Move the object directly to {@code targetState} (must be a legal neighbour of the current state),
     * persist, and emit an {@link EventType#OBJECT_ACTIVITY} event.
     */
    public OperationalObject transitionTo(String id, String targetState, String actor) {
        return transitionTo(id, targetState, actor, null);
    }

    /** As {@link #transitionTo(String, String, String)} with a {@code disposition} — see {@link #transition(String, String, String, String)}. */
    public OperationalObject transitionTo(String id, String targetState, String actor, String disposition) {
        return retrying(() -> {
            OperationalObject obj = require(id);
            Workflow wf = workflow(obj.objectType());
            if (!wf.allows(obj.status(), targetState))
                throw new IllegalStateException("illegal transition: " + obj.status() + " -> " + targetState
                        + " (" + obj.objectType() + ")");
            return commit(obj, wf, targetState, "transition", actor, disposition);
        });
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
        return patch(id, null, priority, severity, assignee, attributes);
    }

    /**
     * {@link #patch(String, String, String, String, Map)} with an optional client-held {@code expectedVersion}
     * (the {@code version} a GET returned): when non-null and the stored object is at any other version, nothing
     * is written and {@link ObjectVersionConflictException} is thrown — a client's stale edit is refused rather
     * than silently re-applied over a newer one. {@code null} keeps the last-write-wins merge.
     */
    public OperationalObject patch(String id, Long expectedVersion, String priority, String severity,
                                   String assignee, Map<String, String> attributes) {
        return retrying(() -> {
            OperationalObject obj = require(id);
            if (expectedVersion != null && obj.version() != expectedVersion)
                throw new ObjectVersionConflictException(id, expectedVersion);
            long now = System.currentTimeMillis();
            OperationalObject next = obj;
            if (priority != null) next = next.withPriority(priority, now);
            if (severity != null) next = next.withSeverity(severity, now);
            if (assignee != null) next = next.withAssignee(assignee, now);
            if (attributes != null && !attributes.isEmpty()) next = next.withAttributes(attributes, now);
            return next == obj ? obj : store.update(next);
        });
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
        return retrying(() -> {
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
        });
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
        return retrying(() -> {
            OperationalObject obj = require(id);
            if (!Impact.TYPES.contains(obj.objectType()))
                throw new IllegalArgumentException("impact is recorded on an Incident or a Case, not a " + obj.objectType());
            boolean incident = obj.objectType() == ObjectType.INCIDENT;
            if (incident && "ARCHIVED".equalsIgnoreCase(obj.status()))
                throw new IllegalStateException(obj.objectType() + " " + id + " is ARCHIVED — its impact is closed; "
                        + "reopen it to change the impact");
            // Late recoveries (operator, 2026-09-26): money recovered or prevented after the outcome is decided is
            // still recorded on a RESOLVED Incident or a CLOSED (terminal) Case — but nothing else on it changes.
            boolean booksClosed = incident ? "RESOLVED".equalsIgnoreCase(obj.status())
                    : workflow(obj.objectType()).isTerminal(obj.status());
            if (booksClosed) {
                List<String> changed = Impact.fromAttribute(obj.attributes().get(Impact.ATTR)).changedFieldsOtherThan(
                        impact, Impact.LATE_FIELDS);
                if (!changed.isEmpty())
                    throw new IllegalStateException(obj.objectType() + " " + id + " is " + obj.status()
                            + " — only " + Impact.LATE_FIELDS + " may still change (late recoveries), not " + changed
                            + "; reopen it to change the rest");
            }
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
        });
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
     * EVERY object matching {@code filter}'s constraints — its {@code limit}/{@code offset}/ordering are
     * ignored — lazily, oldest-first, holding one {@link ObjectQuery#MAX_LIMIT} page in memory at a time. The read
     * behind a rollup that must count the whole corpus: a single {@link #query} stops at {@code MAX_LIMIT}
     * rows, silently (`ASSURE-IMPACT-LEDGER-RESIDUALS-1`).
     *
     * <p>⚠ Offset paging, not a snapshot: an object deleted mid-walk (only the MNT-14 retention sweep deletes)
     * can shift a later one across a page boundary and be missed, and one created mid-walk may or may not be
     * seen. Fine for a sampled rollup; not a transactional read. An object inserted mid-walk with a
     * {@code createdAt} behind the cursor (an import keeping timestamps, clock skew) shifts a seen one into the
     * next page, so each walk remembers the ids it has yielded and never yields one twice (a Set of ids —
     * the one per-walk cost that grows with the corpus).
     */
    public Iterable<OperationalObject> allMatching(ObjectQuery filter) {
        return () -> new java.util.Iterator<>() {
            private List<OperationalObject> page = List.of();
            private int index = 0;
            private int offset = 0;
            private boolean last = false;
            private final Set<String> seen = new java.util.HashSet<>();

            @Override public boolean hasNext() {
                while (true) {
                    while (index < page.size()) {
                        if (!seen.contains(page.get(index).id())) return true;   // idempotent: next() records it
                        index++;   // already yielded on an earlier page — skip the shifted duplicate
                    }
                    if (last) return false;
                    page = store.query(new ObjectQuery(filter.objectType(), filter.status(), filter.severity(),
                            filter.assignee(), filter.owner(), filter.correlationId(), filter.textContains(),
                            ObjectQuery.MAX_LIMIT, offset, filter.closedBefore(), true, filter.openOnly()));
                    index = 0;
                    offset += page.size();
                    last = page.size() < ObjectQuery.MAX_LIMIT;
                }
            }

            @Override public OperationalObject next() {
                if (!hasNext()) throw new NoSuchElementException();
                seen.add(page.get(index).id());
                return page.get(index++);
            }
        };
    }

    /**
     * The not-yet-terminal objects of {@code type} for a {@code correlationId} — used to avoid opening a
     * duplicate object while one is still being handled (e.g. an alert that keeps breaching).
     */
    public List<OperationalObject> active(ObjectType type, String correlationId) {
        Workflow wf = workflow(type);
        List<OperationalObject> out = new ArrayList<>();   // every page, only the non-terminal ones kept
        // openOnly pushes closed_at = 0 into the store; isTerminal still catches a state made terminal later.
        for (OperationalObject o : allMatching(ObjectQuery.builder().objectType(type).correlationId(correlationId)
                .openOnly(true).build()))
            if (!wf.isTerminal(o.status())) out.add(o);
        return out.reversed();   // newest-first, as before

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
        int total = 0;
        // Every object of the type, a page at a time — never one MAX_LIMIT page (ASSURE-IMPACT-LEDGER-RESIDUALS-1).
        for (OperationalObject o : allMatching(ObjectQuery.builder().objectType(type).build())) {
            if (o.isInert()) continue;   // an unknown-typed row is never counted (nor stamped): it is outside every workflow
            total++;
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
        out.put("total", total);
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
        require(objectId);
        tagAssignments.add(com.gamma.objects.TagAssignment.of(
                tag, com.gamma.objects.AnnotationKinds.OBJECT, objectId, actor));
        return projectTags(objectId, System.currentTimeMillis());
    }

    /** Remove one tag from an object and re-project the CSV. Idempotent; returns the updated object. */
    public OperationalObject removeTag(String objectId, String tag) {
        require(objectId);
        tagAssignments.remove(tag, com.gamma.objects.AnnotationKinds.OBJECT, objectId);
        return projectTags(objectId, System.currentTimeMillis());
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
        for (OperationalObject o : allMatching(ObjectQuery.builder().build())) {
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
        Tag renamed = new Tag(to, existing.createdAt(), existing.extra());   // validates the new name before anything mutates
        if (renamed.name().equals(existing.name())) return new TagVocabularyChange(0, 0, List.of());

        // Collect the affected objects BEFORE the rename — afterwards the old name has no edges left.
        List<String> affected = objectTargetsOf(existing.name());
        int edges = tagAssignments.rename(existing.name(), renamed.name());
        tags.remove(existing.name());
        tags.put(renamed.name(), renamed);

        List<String> followed = new java.util.ArrayList<>();
        for (TagRule rule : tagRules()) {
            if (!rule.tag().equals(existing.name())) continue;
            tagRules.put(rule.name(), new TagRule(rule.name(), renamed.name(), rule.filter(), rule.createdAt(), rule.extra()));
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
            projectTags(id, now);
            done++;
        }
        return done;
    }

    /** Rewrite an object's CSV attribute from the assignment store — the projection, never the reverse. */
    private OperationalObject projectTags(String id, long now) {
        return rmw(id, o -> o.withAttributes(Map.of(ATTR_TAGS, String.join(",", tagsOf(id))), now));
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
        for (OperationalObject o : allMatching(ObjectQuery.builder().build())) {
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

    // ── seam for object-type behaviour modules (MODULE-REORG-P7) ─────────────────────────────────────
    // The Case operations (Case Rules, merge, split, open-from-entities) live in the optional
    // inspecto-case-management module, which reaches this service only through the two members below.

    private final Map<Class<?>, Object> extensions = new ConcurrentHashMap<>();

    /**
     * The per-service singleton of an add-on collaborator, created on first use by {@code factory} and kept for
     * the life of this service - how an optional module attaches state (e.g. its rule registry) to one Space's
     * engine without this class naming it.
     */
    @SuppressWarnings("unchecked")
    public <T> T extension(Class<T> type, java.util.function.Function<ObjectService, T> factory) {
        return (T) extensions.computeIfAbsent(type, t -> factory.apply(this));
    }

    /** The generic stores + locking helpers an add-on collaborator is handed. */
    public ObjectSubstrate substrate() { return substrate; }

    /** Compensation for a failed multi-object write (handed to object-type modules through {@link ObjectSubstrate}): remove one object it created, cascade first (see {@link #purge}). */
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


    private final ObjectSubstrate substrate = new Substrate();

    private final class Substrate implements ObjectSubstrate {
        public ObjectStore store() { return store; }
        public LinkStore links() { return links; }
        public NoteStore notes() { return notes; }
        public com.gamma.ops.tag.TagAssignmentStore tagAssignments() { return tagAssignments; }
        public OperationalObject require(String id) { return ObjectService.this.require(id); }
        public OperationalObject rmw(String id, java.util.function.UnaryOperator<OperationalObject> change) { return ObjectService.this.rmw(id, change); }
        public String eventSource() { return SOURCE; }
        public void discard(String objectId, String actor, RuntimeException cause) { ObjectService.this.discard(objectId, actor, cause); }
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
        if (assignee == null || assignee.isBlank()) throw new IllegalArgumentException("assign needs an 'assignee'");
        String target = assignee.trim();
        long now = System.currentTimeMillis();
        OperationalObject[] read = new OperationalObject[1];   // the row the winning attempt actually read
        OperationalObject updated = rmw(id, o -> { read[0] = o; return o.withAssignee(target, now); });
        OperationalObject obj = read[0];
        String from = obj.assignee();
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
            // Re-read per attempt: the assignee write above is done and must not be re-run (or re-audited).
            return retrying(() -> {
                OperationalObject cur = require(id);
                return wf.apply(cur.status(), "assign").isPresent()
                        ? commit(cur, wf, wf.apply(cur.status(), "assign").get(), "assign", actor, null)
                        : cur;
            });
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
        return retrying(() -> {
            OperationalObject obj = require(id);
            String u = user.trim();
            List<String> current = new ArrayList<>(obj.watchers());
            boolean changed = add ? (!current.contains(u) && current.add(u)) : current.remove(u);
            if (!changed) return obj;   // idempotent — no write, no event
            return store.update(obj.withAttributes(
                    Map.of(ATTR_WATCHERS, String.join(",", current)), System.currentTimeMillis()));
        });
    }

    /**
     * SLA sweep (Phase 3): breach every {@link ObjectType#INCIDENT} that has passed its {@link #ATTR_DUE_AT}
     * deadline while still being worked. An incident qualifies when it carries a {@code dueAt} attribute at
     * or before {@code now}, is not yet {@code RESOLVED}, is in no terminal state of its registered
     * {@link Workflow} (the default {@code ARCHIVED}, or a {@code *_workflow.toon}'s own, e.g. {@code CLOSED}) and
     * carries no {@code closedAt}, and has not already breached.
     *
     * <p>The stop set is the resolution gate's ({@link #decidesIncident}: {@code RESOLVED} + every custom terminal
     * state) PLUS {@code ARCHIVED}: the gate exempts {@code ARCHIVED} because archiving records no outcome, but
     * an archived Incident is equally no longer being worked, so its clock stops too (`IMPORT-RESIDUALS-1`).
     * The state check matters beyond {@code closedAt}: an object can sit in a terminal state with no
     * {@code closedAt} — a workflow file that later made its state terminal, or an imported object. Each new breach stamps a {@link #ATTR_SLA_BREACHED_AT} marker (so repeated sweeps
     * never re-fire) and emits an {@link EventType#OBJECT_SLA_BREACH} event onto {@link EventLog#global()},
     * so the breach surfaces in the Event Viewer next to the incident's {@code OBJECT_ACTIVITY} history.
     *
     * <p><b>Authored governance (ASSURE-WORKFLOW-SLA-1).</b> Every object type with an {@link SlaPolicy} is swept too.
     * The policy stamps {@code dueAt} (resolution) and {@link #ATTR_RESPONSE_DUE_AT} from the priority's targets in its
     * business calendar — never over an operator-set {@code dueAt} — and a response deadline passed while the object
     * still sits in its workflow's initial state is a {@code target: response} breach, once. Each matching
     * {@link EscalationRule} then fires at most once per breach ({@link #escalate}).
     *
     * <p>Intended to be driven by {@link com.gamma.util.Scheduler} (see {@code CollectorService}); {@code now}
     * is injected so the schedule and tests evaluate against the same clock. Safe to call with no incidents.
     *
     * @param now the wall-clock instant (epoch millis) to evaluate deadlines against
     * @return the number of incidents newly breached by this sweep
     */
    public int sweepIncidentSla(long now) {
        GovernanceRegistry.Snapshot gov = governance();
        java.util.Set<ObjectType> types = java.util.EnumSet.of(ObjectType.INCIDENT);
        types.addAll(gov.slaPolicies().keySet());
        for (EscalationRule r : gov.escalationRules()) types.add(r.objectType());
        int breached = 0;
        int[] escalations = {0};
        for (ObjectType type : types) {
            Workflow wf = workflow(type);
            SlaPolicy policy = gov.slaPolicies().get(type);
            List<EscalationRule> rules = gov.escalationRules().stream().filter(r -> r.objectType() == type).toList();
            // Every object of the type, a page at a time — one newest-first MAX_LIMIT page never reached the oldest overdue ones.
            for (OperationalObject o : allMatching(ObjectQuery.builder().objectType(type).build())) {
                if (stopped(o, wf)) continue;
                // Write against the CURRENT stored object, not the page's copy: a stale copy would re-emit a breach
                // already stamped, and its update would overwrite a status change made since the page was read.
                OperationalObject current = store.get(o.id()).orElse(null);
                if (current == null || stopped(current, wf) || !o.status().equalsIgnoreCase(current.status())) continue;
                try {
                    if (policy != null) current = stampPolicy(current, policy, now);
                    OperationalObject after = breachResponse(current, wf, now);
                    long dueAt = parseEpoch(after.attributes().get(ATTR_DUE_AT));
                    if (dueAt > 0 && dueAt <= now && !after.attributes().containsKey(ATTR_SLA_BREACHED_AT)) {
                        after = store.update(after.withAttributes(Map.of(ATTR_SLA_BREACHED_AT, Long.toString(now)), now));
                        emitBreach(after, "resolution", dueAt, now);
                        breached++;
                    }
                    if (!rules.isEmpty()) escalate(after, rules, now, escalations);
                } catch (ObjectVersionConflictException contended) {
                    // Someone changed the object under the sweep: that step wrote nothing, and the stamped markers
                    // make every step idempotent, so the NEXT sweep re-evaluates it against the fresh row.
                }
            }
        }
        return breached;
    }

    /**
     * The sweep's stop set: settled ({@code closedAt}), any terminal state of the registered workflow, {@code RESOLVED}
     * (the resolution clock stops) and {@code ARCHIVED} (dismissed, even where not terminal).
     */
    private static boolean stopped(OperationalObject o, Workflow wf) {
        return o.isClosed() || wf.isTerminal(o.status())
                || "RESOLVED".equalsIgnoreCase(o.status()) || "ARCHIVED".equalsIgnoreCase(o.status());
    }

    /**
     * Stamp the deadlines {@code policy} sets for the object's priority, counted in the policy's business calendar
     * from {@code createdAt}. An operator-set {@code dueAt} (one no policy stamped) is left alone; policy-stamped ones
     * are recomputed when the priority changes, until a breach has been recorded.
     */
    private OperationalObject stampPolicy(OperationalObject o, SlaPolicy policy, long now) {
        Map<String, String> a = o.attributes();
        boolean operatorSet = a.get(ATTR_DUE_AT) != null && !a.get(ATTR_DUE_AT).isBlank() && a.get(ATTR_SLA_POLICY) == null;
        if (operatorSet || a.containsKey(ATTR_SLA_BREACHED_AT)) return o;
        String priority = o.priority() == null ? "" : o.priority().trim().toUpperCase(java.util.Locale.ROOT);
        if (a.get(ATTR_SLA_POLICY) != null && priority.equals(a.get(ATTR_SLA_PRIORITY))) return o;
        SlaPolicy.Target t = policy.targetFor(priority).orElse(null);
        if (t == null) return o;
        Map<String, String> stamp = new java.util.LinkedHashMap<>();
        stamp.put(ATTR_SLA_POLICY, policy.objectType().name());
        stamp.put(ATTR_SLA_PRIORITY, priority);
        if (t.resolutionMinutes() != null)
            stamp.put(ATTR_DUE_AT, Long.toString(policy.calendar().addWorkingMinutes(o.createdAt(), t.resolutionMinutes())));
        if (t.responseMinutes() != null && !a.containsKey(ATTR_SLA_RESPONSE_BREACHED_AT))
            stamp.put(ATTR_RESPONSE_DUE_AT, Long.toString(policy.calendar().addWorkingMinutes(o.createdAt(), t.responseMinutes())));
        return store.update(o.withAttributes(stamp, now));
    }

    /** A response breach: the response deadline passed while the object still sits in its workflow's initial state. */
    private OperationalObject breachResponse(OperationalObject o, Workflow wf, long now) {
        long due = parseEpoch(o.attributes().get(ATTR_RESPONSE_DUE_AT));
        if (due <= 0 || due > now || o.attributes().containsKey(ATTR_SLA_RESPONSE_BREACHED_AT)
                || !wf.initialState().equalsIgnoreCase(o.status())) return o;
        OperationalObject marked = store.update(o.withAttributes(Map.of(ATTR_SLA_RESPONSE_BREACHED_AT, Long.toString(now)), now));
        emitBreach(marked, "response", due, now);
        return marked;
    }

    private void emitBreach(OperationalObject o, String target, long dueAt, long now) {
        EventLog.current().emit(Event.builder(EventType.OBJECT_SLA_BREACH)
                .level(EventLevel.WARN)
                .source(SOURCE)
                .correlationId(o.correlationId())
                .message(o.objectType() + " " + o.id() + " breached " + ("response".equals(target) ? "response " : "")
                        + "SLA: due " + dueAt + ", overdue " + (now - dueAt) + "ms")
                .attr("objectId", o.id())
                .attr("objectType", o.objectType().name())
                .attr("status", o.status())
                .attr("severity", o.severity())
                .attr("assignee", o.assignee())
                .attr("target", target)
                .attr("dueAt", dueAt)
                .attr("overdueMs", now - dueAt));
    }

    /**
     * Apply every matching Escalation Rule that has not yet fired for this breach. The firing is recorded on the
     * object ({@link #ATTR_ESCALATIONS}, {@code <rule>@<breach stamp>}) in the SAME write as the rule's effect, so a
     * later sweep — or a crash between two sweeps — never repeats it; each firing is audited as one
     * {@link EventType#OBJECT_ESCALATED} event (WARN when the rule notifies, which the built-in notification rule
     * picks up; INFO otherwise). Bounded by {@link #MAX_ESCALATIONS_PER_SWEEP}.
     */
    /**
     * The row an Escalation Rule's {@code when} is evaluated over: {@code type}, {@code status} (the workflow state,
     * upper-cased, NOT folded), {@code priority} (trimmed), {@code severity}, {@code category} (the attribute),
     * {@code assignee}, {@code ageMinutes}, {@code minutesToDue} (negative once overdue; {@code Integer.MAX_VALUE} with no deadline, because the
     * Condition Language reads a blank cell as 0 and an empty value would look due NOW),
     * {@code resolutionBreached}/{@code responseBreached}/{@code escalated} as 0/1. Absent text is empty.
     */
    static Map<String, Object> escalationContext(OperationalObject o, long now) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("type", o.typeName());
        row.put("status", o.status() == null ? "" : o.status().trim().toUpperCase(java.util.Locale.ROOT));
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

    private void escalate(OperationalObject o, List<EscalationRule> rules, long now, int[] done) {
        OperationalObject cur = o;
        for (EscalationRule r : rules) {
            if (done[0] >= MAX_ESCALATIONS_PER_SWEEP) return;
            Map<String, Object> match = r.matchTree();       // the priority sugar ANDed with the authored `when`
            if (match != null && com.gamma.query.ConditionTree.matched(match, List.of(escalationContext(cur, now))) != 1) continue;
            String marker = switch (r.on()) {
                case BREACH -> cur.attributes().get("response".equals(r.target()) ? ATTR_SLA_RESPONSE_BREACHED_AT : ATTR_SLA_BREACHED_AT);
                case AGE -> now - cur.createdAt() >= r.afterMinutes() * 60_000L ? "age" : null;
            };
            if (marker == null || marker.isBlank()) continue;
            String entry = r.id() + "@" + marker;
            List<String> fired = new ArrayList<>(csv(cur.attributes().get(ATTR_ESCALATIONS)));
            if (fired.contains(entry)) continue;                               // this rule already fired for this breach
            fired.add(entry);
            String fromAssignee = cur.assignee();
            String fromPriority = cur.priority();
            OperationalObject next = cur.withAttributes(Map.of(ATTR_ESCALATIONS, String.join(",", fired), "escalated", "true"), now);
            if (r.raisePriority()) {
                String raised = EscalationRule.raised(fromPriority);
                if (raised != null && !raised.equals(fromPriority)) next = next.withPriority(raised, now);
            }
            if (r.reassign() != null) next = next.withAssignee(r.reassign(), now);
            cur = store.update(next);
            done[0]++;
            String actor = "escalation-rule:" + r.id();
            if (r.reassign() != null && !r.reassign().equals(fromAssignee))
                EventLog.current().emit(Event.builder(EventType.OBJECT_ASSIGNED)
                        .level(EventLevel.INFO).source(SOURCE).correlationId(cur.correlationId())
                        .message(cur.objectType() + " " + cur.id() + " assigned to " + r.reassign() + " by " + actor)
                        .attr("objectId", cur.id()).attr("objectType", cur.objectType().name())
                        .attr("from", fromAssignee).attr("to", r.reassign()).attr("actor", actor));
            EventLog.current().emit(Event.builder(EventType.OBJECT_ESCALATED)
                    .level(r.notifies() ? EventLevel.WARN : EventLevel.INFO)
                    .source(SOURCE)
                    .correlationId(cur.correlationId())
                    .message(cur.objectType() + " " + cur.id() + " escalated by " + actor
                            + (r.on() == EscalationRule.Trigger.BREACH ? " on its " + r.target() + " SLA breach" : " at age " + r.afterMinutes() + "m"))
                    .attr("objectId", cur.id())
                    .attr("objectType", cur.objectType().name())
                    .attr("rule", r.id())
                    .attr("trigger", r.on().name().toLowerCase(java.util.Locale.ROOT))
                    .attr("breach", marker)
                    .attr("actor", actor)
                    .attr("notify", r.notifies())
                    .attr("fromPriority", fromPriority)
                    .attr("toPriority", cur.priority())
                    .attr("fromAssignee", fromAssignee)
                    .attr("toAssignee", cur.assignee()));
        }
    }

    private static List<String> csv(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) if (!p.isBlank()) out.add(p.trim());
        return out;
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
        return addLink(from, to.typeName(), toId, relationship, actor);
    }

    /**
     * As {@link #link} but the far end is a <b>subject outside the object store</b> ({@code subjectType} +
     * {@code subjectId}; e.g. the Alert an Incident was escalated from, which lives in the Alert store). Only the
     * near end must exist. The edge reads back through {@link #linksOf} / {@link #graph} like any other — the graph
     * simply has no node for a subject it does not hold, as it never had for a deleted object.
     */
    public ObjectLink linkSubject(String fromId, String subjectType, String subjectId, String relationship,
                                  String actor) {
        return addLink(require(fromId), subjectType, subjectId, relationship, actor);
    }

    private ObjectLink addLink(OperationalObject from, String toType, String toId, String relationship, String actor) {
        String fromId = from.id();
        String rel = (relationship == null || relationship.isBlank()) ? LinkRelationship.RELATED_TO : relationship;
        for (ObjectLink existing : links.incident(fromId)) {
            if (existing.fromId().equals(fromId) && existing.toId().equals(toId)
                    && existing.relationship().equalsIgnoreCase(rel))
                return existing;   // already linked — idempotent
        }
        ObjectLink created = links.add(ObjectLink.of(fromId, from.objectType(), toId, toType, rel));
        EventLog.current().emit(Event.builder(EventType.OBJECT_LINKED)
                .level(EventLevel.INFO)
                .source(SOURCE)
                .correlationId(from.correlationId())
                .message(from.objectType() + " " + fromId + " " + created.relationship() + " "
                        + toType + " " + toId + (actor == null ? "" : " (by " + actor + ")"))
                .attr("objectId", fromId)
                .attr("from", fromId)
                .attr("fromType", from.objectType().name())
                .attr("to", toId)
                .attr("toType", toType)
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
        m.put("objectType", o.typeName());
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
     * <p>⚠ <b>A purge is not "all trace removed" (MNT-14 G3).</b> {@link com.gamma.audit.EventStore} is append-only by
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
        OperationalObject o = store.get(id).orElseThrow(() -> new NoSuchElementException("no object with id '" + id + "'"));
        // The one chokepoint every mutator (transition / patch / assign / link / note / tag / purge / ...) passes
        // through: an inert object (a stored type this build does not know) is refused here, never rewritten.
        if (o.isInert()) throw new InertObjectException(id, o.typeName());
        return o;
    }

    /** How many times a lost optimistic-lock race is re-run before the conflict is surfaced (409 at the edge). */
    static final int MAX_RMW_ATTEMPTS = 10;

    /**
     * Run a read-modify-write that is a PURE function of the fresh read (every side effect - events, tag
     * edges - happens after its {@code store.update}, so a conflict leaves none behind): on an
     * {@link ObjectVersionConflictException} the whole body is re-run, re-reading the object, up to
     * {@link #MAX_RMW_ATTEMPTS} times, then the conflict propagates.
     */
    private <T> T retrying(java.util.function.Supplier<T> rmw) {
        for (int attempt = 1; ; attempt++) {
            try {
                return rmw.get();
            } catch (ObjectVersionConflictException lost) {
                if (attempt >= MAX_RMW_ATTEMPTS) throw lost;
            }
        }
    }

    /** Read {@code id} fresh, apply {@code change} (pure), persist, retrying a lost race - see {@link #retrying}. */
    private OperationalObject rmw(String id, java.util.function.UnaryOperator<OperationalObject> change) {
        return retrying(() -> store.update(change.apply(require(id))));
    }

    private OperationalObject commit(OperationalObject obj, Workflow wf, String target,
                                     String action, String actor, String disposition) {
        boolean resolvingIncident = decidesIncident(obj, wf, target);
        if (disposition != null) {
            // WS-10: a Disposition rides the Incident's resolve and nothing else — a Case's lives in its Findings.
            if (!resolvingIncident)
                throw new IllegalArgumentException("a disposition is recorded when an Incident is resolved"
                        + (obj.objectType() == ObjectType.CASE ? " — a Case's Disposition is part of its Findings" : ""));
            String d = disposition.trim().toUpperCase(java.util.Locale.ROOT);
            if (com.gamma.objects.FindingsSpec.ARCHIVED_UNDECIDED.equals(d))
                throw new IllegalArgumentException("disposition " + d + " is stamped when an undecided Incident is "
                        + "archived and cannot be chosen on resolve — pick one of "
                        + com.gamma.objects.FindingsSpec.CHOOSABLE_DISPOSITIONS);
            if (!com.gamma.objects.FindingsSpec.CHOOSABLE_DISPOSITIONS.contains(d))
                throw new IllegalArgumentException("disposition '" + disposition + "' is not one of "
                        + com.gamma.objects.FindingsSpec.CHOOSABLE_DISPOSITIONS);
            obj = obj.withAttributes(Map.of(ATTR_DISPOSITION, d), obj.updatedAt());
        }
        if (resolvingIncident) {
            List<String> gaps = incidentResolutionGaps(obj);
            if (!gaps.isEmpty())
                throw new IllegalStateException(
                        "incident resolution blocked — missing: " + String.join(", ", gaps));
        }
        long now = System.currentTimeMillis();
        OperationalObject next = obj.withStatus(target, now, wf.isTerminal(target));
        // WS-10: leaving RESOLVED/ARCHIVED for a working state (reopen) un-decides the outcome — a stale
        // Disposition must not satisfy the next resolve. Blank, not removed: the bag merge cannot delete a key.
        boolean reopeningIncident = obj.objectType() == ObjectType.INCIDENT
                && (decidesIncident(obj, wf, obj.status()) || "ARCHIVED".equalsIgnoreCase(obj.status()))
                && !resolvingIncident && !"ARCHIVED".equalsIgnoreCase(target);
        if (reopeningIncident && obj.attributes().get(ATTR_DISPOSITION) != null)
            next = next.withAttributes(Map.of(ATTR_DISPOSITION, ""), now);
        // D-A (operator 2026-09-29): archiving an undecided Incident stamps ARCHIVED_UNDECIDED, so every archived
        // Incident carries a Disposition; one archived WITH a Disposition keeps it. A reopen clears it (above).
        String held = obj.attributes().get(ATTR_DISPOSITION);
        boolean stampUndecided = obj.objectType() == ObjectType.INCIDENT && "ARCHIVED".equalsIgnoreCase(target)
                && !"ARCHIVED".equalsIgnoreCase(obj.status()) && (held == null || held.isBlank());
        if (stampUndecided)
            next = next.withAttributes(Map.of(ATTR_DISPOSITION, com.gamma.objects.FindingsSpec.ARCHIVED_UNDECIDED), now);
        // INCIDENT-KPI-MTTR-1: commit() is the single place every status change lands, so stamping here
        // cannot be bypassed by transition / transitionTo / resolve. Overwrites on a re-resolve on
        // purpose — see ATTR_RESOLVED_AT.
        if ("RESOLVED".equalsIgnoreCase(target)) next = next.withAttributes(Map.of(ATTR_RESOLVED_AT, Long.toString(now)), now);
        OperationalObject updated = store.update(next);
        var event = Event.builder(EventType.OBJECT_ACTIVITY)
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
                .attr("actor", actor);
        // WS-10: the outcome is part of the record of the resolve that decided it
        if (resolvingIncident) event.attr("disposition", obj.attributes().get(ATTR_DISPOSITION));
        if (stampUndecided) event.attr("disposition", com.gamma.objects.FindingsSpec.ARCHIVED_UNDECIDED);
        EventLog.current().emit(event);
        return updated;
    }

    /**
     * Whether moving {@code obj} into {@code state} DECIDES an Incident — the moves the resolution gate
     * ({@link #incidentResolutionGaps}) and the Disposition ride: {@code RESOLVED}, and every TERMINAL state of the
     * registered workflow except {@code ARCHIVED} (`SEC-IMPORT-OPS-CONFIGS-1`). Keyed on the literal
     * {@code RESOLVED} alone, a {@code *_workflow.toon} with a {@code CLOSED} terminal state finished an Incident
     * with no Disposition and no postmortem.
     *
     * <p>⚠ {@code ARCHIVED} is deliberately NOT gated: the default lifecycle archives from anywhere
     * ({@code IDENTIFIED|DIAGNOSING → ARCHIVED}, the mail Trash — {@code WorkflowTest}), a dismissal that records
     * no outcome, while the RESOLVED → ARCHIVED path already passed the gate on the way. So a custom workflow buys
     * nothing the default does not already allow: its only ungated terminal move is the same Trash.
     */
    static boolean decidesIncident(OperationalObject obj, Workflow wf, String state) {
        if (obj.objectType() != ObjectType.INCIDENT || state == null) return false;
        if ("RESOLVED".equalsIgnoreCase(state)) return true;
        return wf.isTerminal(state) && !"ARCHIVED".equalsIgnoreCase(state);
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
        // WS-10: an Incident resolves with a decided outcome from the ladder, never without one.
        String disposition = obj.attributes().get(ATTR_DISPOSITION);   // List.of(...).contains(null) throws
        if (disposition == null || !com.gamma.objects.FindingsSpec.CHOOSABLE_DISPOSITIONS.contains(disposition))
            gaps.add("disposition");
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
