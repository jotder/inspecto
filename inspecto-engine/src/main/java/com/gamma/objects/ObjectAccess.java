package com.gamma.objects;

import com.gamma.api.PublicApi;
import com.gamma.event.Event;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The core's seam onto <b>operational objects</b> — Alerts → Incidents → Cases → Tasks and their notes,
 * links and tags (EDITIONS {@code CP-11}; EDG-01 cell 7, 2026-09-08).
 *
 * <p>The implementation is {@code com.gamma.ops.ObjectService} in the optional {@code inspecto-ops} module.
 * On the Personal build that module is not bundled, so core holds {@link Optional#empty()} here and every
 * operational-object surface answers 503. Core itself never names an {@code ObjectService},
 * {@code ObjectStore} or {@code OperationalObject}: those types leave with the domain.
 *
 * <h3>Why every method is in core types</h3>
 * This interface is declared in {@code inspecto-engine}, which the module depends on — never the reverse —
 * so an ops type in any signature here would defeat the extraction. Two consequences worth stating, because
 * both were deliberate rather than accidental:
 * <ul>
 *   <li>{@link #link} takes the relationship as a {@code String}. The {@code LinkRelationship} enum is
 *       domain vocabulary and stays in the module.</li>
 *   <li>{@link #summary} returns plain data instead of performing the SEC-7d visibility check itself. The
 *       check needs {@code ApiContext}/{@code HttpExchange}, which live in the {@code inspecto} module
 *       <em>above</em> this one and are unreachable from here — so core keeps the ABAC decision and applies
 *       it to this map, the same shape {@code ComponentAccess.requireView} already uses.</li>
 * </ul>
 *
 * <p>⚠ {@link ObjectType} and the other five relocated vocabulary types stay in <b>core</b> ({@code
 * com.gamma.objects}) precisely so this interface can be expressed at all — they had no store coupling and
 * sat in {@code com.gamma.ops} by topic, not by dependency.
 *
 * @since 4.0.0
 */
@PublicApi(since = "4.0.0")
public interface ObjectAccess {

    /**
     * Whether a non-terminal object of {@code kind} already exists for {@code scope} (the correlation id).
     * The dedupe check behind {@code ExpectationRoutes}, {@code DecisionRoutes} and {@code ReconRunJob},
     * each of which only ever asks "is one already open?".
     */
    boolean hasActive(ObjectType kind, String scope);

    /**
     * As {@link #hasActive}, but matching on attribute values too — every entry in
     * {@code matchAttributes} must equal the candidate's corresponding attribute.
     *
     * <p>⚠ A <b>compound</b> key is supported on purpose: the gap/conservation bridge dedupes on
     * {@code rule} plus {@code node} or {@code expected}, which a single-attribute check cannot express.
     * An empty map matches any active object of that kind in scope, i.e. degenerates to
     * {@link #hasActive}.
     */
    boolean hasActiveMatching(ObjectType kind, String scope, Map<String, String> matchAttributes);

    /**
     * Open an object and return its id.
     *
     * <p>⚠ Returns the {@code String} id, not the object. Every caller in the codebase either discards the
     * result or reads {@code .id()} off it, so handing back the whole record would put an ops type in this
     * signature for no caller's benefit.
     */
    String open(ObjectType kind, String title, String description, String severity,
                String scope, Map<String, String> attributes);

    /** Relate two objects — {@code relationship} is the {@code LinkRelationship} name, e.g. {@code
     *  "ESCALATED_FROM"} (the enum is module vocabulary). Used by alert→incident promotion. */
    void link(String fromId, String toId, String relationship, String actor);

    /** Assign a tag to any taggable target ({@link AnnotationKinds}), cross-entity. */
    void addTag(String tag, String targetKind, String targetId, String actor);

    /** The tag names assigned to one target, or empty when none. */
    List<String> tagsOf(String targetKind, String targetId);

    /** The ids of every target of {@code targetKind} carrying {@code tag} — the reverse lookup. */
    List<String> targetIdsForTag(String tag, String targetKind);

    /**
     * A flat view of one object for core's visibility gate: {@code id}, {@code correlationId},
     * {@code owner}, {@code assignee} and {@code attributes}. Empty when no such object exists.
     */
    Optional<Map<String, Object>> summary(String objectId);

    /**
     * The module's {@code EventLog} subscriber, which promotes qualifying events (a sequence gap, a
     * conservation imbalance) into managed ALERT objects — or empty when it has none to contribute.
     *
     * <p>⚠ This exists because {@code CollectorService} used to construct
     * {@code new com.gamma.ops.EventObjectBridge(objects)} unconditionally, <b>by fully-qualified name with
     * no import</b> — a coupling an import census cannot see (the trap that broke cell 4's build). Core now
     * asks for a subscriber and registers whatever it gets, so a bundle without the module simply has no
     * promotion step. ⛔ The events themselves are still recorded either way: gating the promotion must
     * never gate the event, which is what EDITIONS {@code SP-CTL-02} keeps promising Personal.
     */
    Optional<Consumer<Event>> eventSubscriber();
}
