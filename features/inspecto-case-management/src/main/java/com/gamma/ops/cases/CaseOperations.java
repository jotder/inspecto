package com.gamma.ops.cases;

import com.gamma.audit.Event;
import com.gamma.audit.EventLevel;
import com.gamma.audit.EventType;
import com.gamma.event.EventLog;
import com.gamma.objects.AnnotationKinds;
import com.gamma.ops.ObjectQuery;
import com.gamma.ops.ObjectService;
import com.gamma.ops.ObjectSubstrate;
import com.gamma.ops.OperationalObject;
import com.gamma.ops.link.LinkRelationship;
import com.gamma.ops.link.ObjectLink;
import com.gamma.ops.tag.TagRule;
import com.gamma.workflow.ObjectType;
import com.gamma.workflow.Workflow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Case-specific operations of the operational-object service (MODULE-REORG-P7, step 1): the Case Rule
 * registry and its evaluation, Merge / Split, and opening a Case from Link Analysis Entities with its
 * compensation. Extracted verbatim from {@code ObjectService}, which no longer names it; the generic object substrate it needs (locking, stores, audit source) arrives through
 * {@link ObjectService} and {@link ObjectSubstrate}.
 */
public final class CaseOperations {

    private final ObjectService svc;
    private final ObjectSubstrate sub;
    private final Map<String, CaseRule> caseRules = new ConcurrentHashMap<>(); // rule-raised-case rules, by name (C5)

    /** Evaluate outcome: matching in-window incidents, how many were newly grouped, and the target case. */
    public record CaseRuleEvaluation(int matched, int grouped, String caseId, boolean opened) {}

    /** Merge outcome: the updated survivor, the absorbed case ids, and how many member links moved. */
    public record MergeResult(OperationalObject survivor, List<String> merged, int membersMoved) {}

    /** Split outcome: the newly opened case and how many member links moved to it. */
    public record SplitResult(OperationalObject part, int membersMoved) {}

    /** One Link Analysis Entity to become a member of a new Case: its node id, source Dataset and label. */
    public record EntityMember(String entityKey, String dataset, String label) {
        public EntityMember {
            // LA-17 D-M6: untyped ids are 'entity:<key>', typed ones '<type>:<key>' (an Entity Type id).
            if (entityKey == null || !entityKey.matches("[a-z][a-z0-9_]{0,31}:.+"))
                throw new IllegalArgumentException("an entity needs its node id ('entity:…' or '<type>:…'), got '" + entityKey + "'");
            if (dataset == null || dataset.isBlank())
                throw new IllegalArgumentException("entity '" + entityKey + "' needs the Dataset it was projected from");
            label = label == null || label.isBlank() ? entityKey : label.trim();
        }
    }

    /** What {@link #openCaseFromEntities} did: the Case, every member it now CONTAINS, and which were new. */
    public record EntityCase(OperationalObject caseObject, List<OperationalObject> members, Set<String> minted) {}

    /** The per-service Case collaborator (one per Space's engine; created on first use, kept for the engine's life). */
    public static CaseOperations of(ObjectService svc) {
        return svc.extension(CaseOperations.class, s -> new CaseOperations(s, s.substrate()));
    }

    CaseOperations(ObjectService svc, ObjectSubstrate sub) {
        this.svc = svc;
        this.sub = sub;
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
        List<OperationalObject> matches = new ArrayList<>();   // every page; only the matches are held
        for (OperationalObject o : svc.allMatching(ObjectQuery.builder().objectType(ObjectType.INCIDENT).build()))
            if (o.createdAt() >= cutoff && rule.matches(o) && !isCaseMember(o.id()))   // not already grouped
                matches.add(o);
        matches = matches.reversed();   // newest-first, as before: matches.get(0) seeds a new Case's correlationId
        if (matches.isEmpty()) return new CaseRuleEvaluation(0, 0, null, false);

        String existing = openCaseRaisedBy(name);
        boolean opened = false;
        String caseId = existing;
        if (caseId == null) {
            if (matches.size() < rule.threshold())
                return new CaseRuleEvaluation(matches.size(), 0, null, false);  // below threshold, no case yet
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put(ObjectService.ATTR_RAISED_BY_RULE, name);
            if (rule.category() != null) attrs.put("category", rule.category());
            if (rule.tags() != null) attrs.put(ObjectService.ATTR_TAGS, rule.tags());
            caseId = svc.open(ObjectType.CASE, rule.title(), "Auto-raised by case rule '" + name + "'",
                    null, null, null, null, matches.get(0).correlationId(), attrs).id();
            opened = true;
        }
        for (OperationalObject inc : matches) svc.link(caseId, inc.id(), LinkRelationship.CONTAINS, "case-rule:" + name);
        return new CaseRuleEvaluation(matches.size(), matches.size(), caseId, opened);
    }

    /** Whether {@code incidentId} is already a {@code CONTAINS} member of some case. */
    private boolean isCaseMember(String incidentId) {
        return sub.links().incident(incidentId).stream()
                .anyMatch(l -> l.toId().equals(incidentId) && LinkRelationship.CONTAINS.equalsIgnoreCase(l.relationship()));
    }

    /** The id of a still-open case previously raised by {@code ruleName}, or null. */
    private String openCaseRaisedBy(String ruleName) {
        Workflow wf = svc.workflow(ObjectType.CASE);
        String newest = null;   // oldest-first walk over every page, so the LAST hit is the newest, as before
        for (OperationalObject c : svc.allMatching(ObjectQuery.builder().objectType(ObjectType.CASE).openOnly(true).build()))
            if (ruleName.equals(c.attributes().get(ObjectService.ATTR_RAISED_BY_RULE)) && !wf.isTerminal(c.status())) newest = c.id();
        return newest;
    }


    // ── case group management: Split & Merge (GLOSSARY §9) ───────────────────────────


    /**
     * <b>Merge</b> {@code sources} into the surviving case {@code survivorId} — likely-similar cases
     * become one group managed as one. Per source: its {@code CONTAINS} members are re-pointed to the
     * survivor (idempotent), tags + watchers union onto the survivor, a {@code MERGED_INTO} trace link
     * + {@link ObjectService#ATTR_MERGED_INTO} marker + comments record the merge, and the source is closed
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
        Set<String> tags = new LinkedHashSet<>(svc.tagsOf(survivor.id()));
        Set<String> watchers = new LinkedHashSet<>(survivor.watchers());
        for (OperationalObject src : absorbed) {
            moved += movePart(src.id(), survivorId, null);
            tags.addAll(svc.tagsOf(src.id()));
            watchers.addAll(src.watchers());
            svc.link(src.id(), survivorId, LinkRelationship.MERGED_INTO, actor);
            sub.rmw(src.id(), o -> o.withAttributes(Map.of(ObjectService.ATTR_MERGED_INTO, survivorId), now)
                    .withStatus("CLOSED", now, true));
            svc.comment(src.id(), actor, "Merged into " + survivorId + (actor == null ? "" : " by " + actor) + ".");
            svc.comment(survivorId, actor, "Absorbed " + src.id() + " (\"" + src.title() + "\")"
                    + (actor == null ? "" : " by " + actor) + ".");
            EventLog.current().emit(Event.builder(EventType.OBJECT_ACTIVITY)
                    .level(EventLevel.INFO)
                    .source(sub.eventSource())
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
            sub.tagAssignments().add(com.gamma.objects.TagAssignment.of(
                    tag, com.gamma.objects.AnnotationKinds.OBJECT, survivorId, actor));
        Map<String, String> union = new LinkedHashMap<>();
        if (!tags.isEmpty()) union.put(ObjectService.ATTR_TAGS, String.join(",", svc.tagsOf(survivorId)));
        if (!watchers.isEmpty()) union.put(ObjectService.ATTR_WATCHERS, String.join(",", watchers));
        OperationalObject updated = union.isEmpty() ? sub.require(survivorId)
                : sub.rmw(survivorId, o -> o.withAttributes(union, now));
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
        for (ObjectLink l : sub.links().incident(caseId)) {
            if (l.fromId().equals(caseId) && LinkRelationship.CONTAINS.equalsIgnoreCase(l.relationship()))
                contained.add(l.toId());
        }
        for (String m : members) {
            if (!contained.contains(m))
                throw new IllegalStateException("case " + caseId + " does not contain member '" + m + "'");
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        String category = original.attributes().get("category");
        List<String> tags = svc.tagsOf(caseId);
        if (category != null) attrs.put("category", category);
        // The new part is created through svc.open(), which adopts this CSV into the assignment sub.store().
        if (!tags.isEmpty()) attrs.put(ObjectService.ATTR_TAGS, String.join(",", tags));
        OperationalObject part = svc.open(ObjectType.CASE, title, "Split from " + caseId + ": " + original.title(),
                original.severity(), original.priority(), original.owner(), null, original.correlationId(), attrs);
        int moved = movePart(caseId, part.id(), new LinkedHashSet<>(members));
        svc.link(part.id(), caseId, LinkRelationship.SPLIT_FROM, actor);
        svc.comment(caseId, actor, "Split " + moved + " member(s) out into " + part.id() + " (\"" + title + "\")"
                + (actor == null ? "" : " by " + actor) + ".");
        svc.comment(part.id(), actor, "Split from " + caseId + (actor == null ? "" : " by " + actor) + ".");
        if (assignee != null && !assignee.isBlank()) svc.assign(part.id(), assignee, actor);
        EventLog.current().emit(Event.builder(EventType.OBJECT_ACTIVITY)
                .level(EventLevel.INFO)
                .source(sub.eventSource())
                .correlationId(original.correlationId())
                .message("CASE " + part.id() + " split from " + caseId + " (" + moved + " member(s))"
                        + (actor == null ? "" : " by " + actor))
                .attr("objectId", part.id())
                .attr("action", "split")
                .attr("original", caseId)
                .attr("membersMoved", moved)
                .attr("actor", actor));
        return new SplitResult(sub.require(part.id()), moved);
    }

    /**
     * Re-point {@code CONTAINS} member edges from {@code fromCase} to {@code toCase} — all of them, or
     * only {@code onlyMembers} when non-null. Idempotent per member; returns how many edges moved.
     */
    private int movePart(String fromCase, String toCase, Set<String> onlyMembers) {
        int moved = 0;
        for (ObjectLink l : sub.links().incident(fromCase)) {
            if (!l.fromId().equals(fromCase) || !LinkRelationship.CONTAINS.equalsIgnoreCase(l.relationship())) continue;
            if (onlyMembers != null && !onlyMembers.contains(l.toId())) continue;
            sub.links().remove(fromCase, l.toId(), LinkRelationship.CONTAINS);
            svc.link(toCase, l.toId(), LinkRelationship.CONTAINS, null);   // idempotent add + OBJECT_LINKED audit
            moved++;
        }
        return moved;
    }

    /**
     * Open a Case whose first members are minted from Link Analysis Entities (LA-CASE-CREATE-IN-PLACE-1,
     * operator decision 2026-09-23: <i>mint from the node</i>). Each Entity becomes an INCIDENT keyed by
     * {@link ObjectService#ATTR_ENTITY_KEY} + {@link ObjectService#ATTR_ENTITY_DATASET}; one that already exists — and that the caller
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
     * @param owner   the Case's owner — the creating Subject when known (plan §5.10), else null
     * @throws IllegalArgumentException blank title, no members, an existing member that is not an INCIDENT
     * @throws NoSuchElementException   an existing member that is absent or not visible (existence-hiding)
     */
    public synchronized EntityCase openCaseFromEntities(String title, String description, List<EntityMember> entities,
                                                        List<String> existingMembers,
                                                        java.util.function.Predicate<OperationalObject> visible,
                                                        String actor, String owner) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("a Case needs a 'title'");
        if (entities.isEmpty() && existingMembers.isEmpty())
            throw new IllegalArgumentException("a Case CONTAINS its members — name at least one entity");
        // ── resolve (no writes) ──
        Map<String, OperationalObject> members = new LinkedHashMap<>();
        for (String id : existingMembers) {
            OperationalObject o = sub.store().get(id).filter(visible).orElseThrow(
                    () -> new NoSuchElementException("no object with id '" + id + "'"));
            if (o.objectType() != ObjectType.INCIDENT)
                throw new IllegalArgumentException("a Case member must be an INCIDENT, but " + id + " is a " + o.typeName());
            members.put(o.id(), o);
        }
        Map<List<String>, EntityMember> toMint = new LinkedHashMap<>();
        Map<List<String>, OperationalObject> reused = new LinkedHashMap<>();
        for (EntityMember e : entities) {
            List<String> identity = List.of(e.entityKey(), e.dataset());
            if (toMint.containsKey(identity) || reused.containsKey(identity)) continue;
            Optional<OperationalObject> existing = sub.store().findByAttributes(ObjectType.INCIDENT,
                    Map.of(ObjectService.ATTR_ENTITY_KEY, e.entityKey(), ObjectService.ATTR_ENTITY_DATASET, e.dataset()), ObjectQuery.MAX_LIMIT)
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
                attrs.put(ObjectService.ATTR_ENTITY_KEY, e.entityKey());
                attrs.put(ObjectService.ATTR_ENTITY_DATASET, e.dataset());
                OperationalObject minted = svc.open(ObjectType.INCIDENT, e.label(),
                        "Raised from Link Analysis: Entity " + e.entityKey() + " in Dataset " + e.dataset() + ".",
                        null, null, null, null, null, attrs);
                created.add(minted.id());
                members.put(minted.id(), minted);
            }
            OperationalObject kase = svc.open(ObjectType.CASE, title.trim(), description, null, null, owner, null, null, Map.of());
            created.add(kase.id());
            for (String member : members.keySet()) svc.link(kase.id(), member, LinkRelationship.CONTAINS, actor);
            return new EntityCase(sub.require(kase.id()), List.copyOf(members.values()),
                    Set.copyOf(created.subList(0, created.size() - 1)));
        } catch (RuntimeException failure) {
            for (String id : created.reversed()) sub.discard(id, actor, failure);
            throw failure;
        }
    }

    /** The object must exist, be a CASE, and not be closed/merged — the precondition for group operations. */
    private OperationalObject requireActiveCase(String id, String what) {
        OperationalObject o = sub.require(id);
        if (o.objectType() != ObjectType.CASE)
            throw new IllegalStateException(what + " must be a CASE, but " + id + " is a " + o.objectType());
        if (o.isClosed() || o.attributes().containsKey(ObjectService.ATTR_MERGED_INTO))
            throw new IllegalStateException(what + " " + id + " is closed"
                    + (o.attributes().containsKey(ObjectService.ATTR_MERGED_INTO) ? " (already merged)" : ""));
        return o;
    }
}
