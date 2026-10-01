package com.gamma.la.core;

/**
 * The audit event types Link Analysis and the Geo studio emit — what an analyst looked at, asked for, sealed or revealed.
 *
 * <p>Moved out of {@code com.gamma.event.EventType} (the audit SPI) in D-1, Decision 3 (2026-10-01): the platform audit
 * layer should not enumerate one product's events, and the standalone LA product must be able to add its own. The values
 * are the SAME strings as before — an event type is persisted in the audit trail, so a rename would orphan history —
 * and {@code EventType} constants keep serving every platform event. Use as {@code Event.builder(LinkEventTypes.LINK_PROJECTED)…}.
 */
public final class LinkEventTypes {
    private LinkEventTypes() {}

    /** An Entity Projection was served over a Dataset ({@code POST /inv/projection}). {@code dataset},
     *  {@code rows} and {@code truncated} carry what the analyst actually saw — a projection cut short
     *  by the limit is a partial picture, and an audit that cannot say so is worthless. */
    public static final String LINK_PROJECTED = "LINK_PROJECTED";

    /** An analyst expanded one entity's one-hop neighborhood onto the canvas
     *  ({@code POST /inv/projection/neighbors}) — a different analytic act from the initial projection.
     *  Adds the expanded {@code value} to {@link #LINK_PROJECTED}'s attributes. */
    public static final String LINK_EXPANDED = "LINK_EXPANDED";

    /** A server-side multi-hop traversal was run over a Dataset ({@code POST /inv/traversal/recursive-paths},
     *  LA-11). {@code startNode}, optional {@code targetNode}, {@code maxDepth}, {@code paths} and
     *  {@code truncated} carry what was walked and whether a fence cut it short. */
    public static final String LINK_TRAVERSED = "LINK_TRAVERSED";

    /** A branching motif (structuring &amp; co.) was run over a whole Dataset ({@code POST /inv/pattern/branching},
     *  LA-14b). {@code matches}, {@code truncated} and — when the pattern could not be evaluated —
     *  {@code refusal} carry what the analyst was told. */
    public static final String LINK_PATTERN_MATCHED = "LINK_PATTERN_MATCHED";

    /** A named LA-18 value Measure was read over a whole Dataset ({@code GET /inv/value-measures}). {@code measure},
     *  {@code entities} (how many breach its thresholds) and {@code truncated} carry what the analyst was told. */
    public static final String LINK_VALUE_MEASURED = "LINK_VALUE_MEASURED";

    /** The cross-Dataset schema-relationship model was read ({@code GET /inv/schema/relationships}).
     *  {@code datasetsScanned}/{@code datasetsSkipped}/{@code relationships} carry the sweep's reach;
     *  it spans every Dataset, so it names no single one and cannot truncate. */
    public static final String LINK_SCHEMA_INSPECTED = "LINK_SCHEMA_INSPECTED";

    /** Candidate key columns were profiled for cardinality and value overlap
     *  ({@code POST /inv/schema/overlap-profile}, LA-15). A separate act from
     *  {@link #LINK_SCHEMA_INSPECTED}: that one reads names, this one reads VALUES — it runs aggregates
     *  over every Dataset in scope, so the trail must be able to tell the cheap schema read from the
     *  expensive data read. {@code columnsProfiled}/{@code pairsProfiled}/{@code pairsConsidered} and
     *  {@code truncated} carry the sweep's reach and whether the pair budget cut it short. */
    public static final String LINK_OVERLAP_PROFILED = "LINK_OVERLAP_PROFILED";

    /** A Link Analysis evidence snapshot was SEALED ({@code POST /inv/snapshots}, LA-03).
     *  {@code snapshotId}, {@code nodes} and {@code edges} carry what was frozen. A distinct act from
     *  {@link #LINK_PROJECTED}: that one records what an analyst LOOKED AT, this one records what they
     *  committed to as evidence — the record is immutable from this moment, and a re-POST of the same id
     *  is refused rather than replacing it, so this event has no "updated" counterpart by design. */
    public static final String LINK_SNAPSHOT_SEALED = "LINK_SNAPSHOT_SEALED";

    /** A sealed snapshot was attached to a Case ({@code POST /inv/snapshots/attach}, LA-03).
     *  ⚠ Separate from {@link #LINK_SNAPSHOT_SEALED} because attaching does NOT reopen the sealed record —
     *  it is a relationship, and writing it into the snapshot would invalidate the fingerprint that makes
     *  the snapshot evidence. {@code snapshotId} and {@code caseId} carry the link. */
    public static final String LINK_SNAPSHOT_ATTACHED = "LINK_SNAPSHOT_ATTACHED";

    /** A Link Analysis Investigation was created ({@code POST /inv/investigations}, LA-10) — bound to one
     *  Dataset and projection mapping. {@code investigationId} and {@code dataset} carry the binding. */
    public static final String LINK_INVESTIGATION_CREATED = "LINK_INVESTIGATION_CREATED";

    /** One step was appended to an Investigation's op log ({@code POST /inv/investigations/{id}/ops} or
     *  {@code /undo}, LA-10). {@code op} ({@code undo} for a log edit), {@code step} and, for a Dataset-reading
     *  op, {@code rows}, {@code truncated} and the sealed read's {@code fingerprint}. */
    public static final String LINK_INVESTIGATION_STEPPED = "LINK_INVESTIGATION_STEPPED";

    /** A re-ordered log was FORKED into a new Investigation ({@code POST /inv/investigations/{id}/reorder},
     *  LA-10, decision D-E4). The original is untouched; {@code parentId} and {@code investigationId} carry the
     *  lineage. */
    public static final String LINK_INVESTIGATION_FORKED = "LINK_INVESTIGATION_FORKED";

    /** An Investigation's log was fully re-evaluated ({@code POST /inv/investigations/{id}/replay}, LA-10).
     *  {@code equivalent} reports the incremental/replay equivalence check; with {@code reread},
     *  {@code diverged} reports whether current data no longer matches a sealed read. */
    public static final String LINK_INVESTIGATION_REPLAYED = "LINK_INVESTIGATION_REPLAYED";

    /** An Investigation's data coverage was assessed ({@code GET /inv/investigations/{id}/coverage}, LA-19).
     *  {@code dataset}, {@code expectedDays} and {@code missingDays} (days in the window with no rows at all). */
    public static final String LINK_INVESTIGATION_COVERAGE = "LINK_INVESTIGATION_COVERAGE";

    /** An Investigation's Dossier was issued ({@code GET /inv/investigations/{id}/dossier}, LA-12). {@code at},
     *  {@code format}, the SHA-256 manifest {@code root} and {@code intact} (whether the stored evidence still
     *  agrees with its own recorded hashes) carry what was handed over — issuing evidence is an act, not a view. */
    public static final String LINK_DOSSIER_BUILT = "LINK_DOSSIER_BUILT";

    /** A Dossier manifest was checked against the store ({@code POST /inv/investigations/{id}/dossier/verify},
     *  LA-12). {@code verified}, {@code submittedRoot}, {@code currentRoot} and {@code changed} (artefacts whose
     *  bytes differ) carry the custody verdict. */
    public static final String LINK_DOSSIER_VERIFIED = "LINK_DOSSIER_VERIFIED";

    /** An Investigation's Working Set was read as a derived relation ({@code GET /inv/investigations/{id}/working-set},
     *  LA-20). {@code relation} (entities | links | excluded), {@code rows} served, {@code total},
     *  {@code truncated}, {@code cached} and the relation {@code key} (the sealed log's hash) — the LA-04
     *  query-audit shape, so a read of the relation is as visible as the projection it replaces. */
    public static final String LINK_INVESTIGATION_WORKING_SET_READ = "LINK_INVESTIGATION_WORKING_SET_READ";

    /** An Investigation's op log was saved as an <b>Investigation Template</b> ({@code POST
     *  /inv/investigations/{id}/template}, LA-23). {@code templateId}, {@code investigationId}, {@code parameters}
     *  and {@code dropped} — how many analyst-judgement ops (exclude · hide · keep) were left behind, per D-E8. */
    public static final String LINK_INVESTIGATION_TEMPLATE_SAVED = "LINK_INVESTIGATION_TEMPLATE_SAVED";

    /** An Investigation Template was instantiated into a new Investigation ({@code POST
     *  /inv/investigation-templates/{id}/instantiate}, LA-23). {@code templateId}, {@code investigationId},
     *  {@code dataset} and {@code steps}; every {@code expand} read fresh data and is sealed in the new log. */
    public static final String LINK_INVESTIGATION_TEMPLATE_INSTANTIATED = "LINK_INVESTIGATION_TEMPLATE_INSTANTIATED";

    /** The Measures over an Investigation's Working Set were read ({@code GET /inv/investigations/{id}/measures},
     *  LA-23) — the LA-04 query-audit shape: {@code investigationId}, {@code key} (the sealed log's hash) and
     *  {@code measure} when one was asked for. */
    public static final String LINK_INVESTIGATION_MEASURED = "LINK_INVESTIGATION_MEASURED";

    /** An Alert Rule was bound to a Measure over an Investigation's Working Set ({@code POST
     *  /inv/investigations/{id}/alert-rules}, LA-23) by the Investigation's owner. {@code rule},
     *  {@code investigationId}, {@code relation}, {@code measure}, {@code threshold}. */
    public static final String LINK_INVESTIGATION_ALERT_RULE_BOUND = "LINK_INVESTIGATION_ALERT_RULE_BOUND";

    /** An Investigation was linked to a Case ({@code PUT /inv/investigations/{id}/case}, or {@code caseRef} at create,
     *  LA-24) by its owner. {@code investigationId}, {@code caseId} and {@code verified} (false when Case management
     *  is not installed, so the Case could not be checked and the link grants nothing). */
    public static final String LINK_INVESTIGATION_CASE_LINKED = "LINK_INVESTIGATION_CASE_LINKED";

    /** An Investigation's Case link was removed ({@code DELETE /inv/investigations/{id}/case}, LA-24) by its owner.
     *  {@code investigationId} and the former {@code caseId}. */
    public static final String LINK_INVESTIGATION_CASE_UNLINKED = "LINK_INVESTIGATION_CASE_UNLINKED";

    /** Masked entity ids of an Investigation were revealed ({@code POST /inv/investigations/{id}/reveal}, LA-19 /
     *  D-U6) — per entity, by a holder of {@code canRevealLinkEntities}. {@code investigationId}, {@code tokens}
     *  (the pseudonyms revealed — never the raw values, so the trail does not re-leak them) and {@code count}. */
    public static final String LINK_ENTITY_REVEALED = "LINK_ENTITY_REVEALED";

    /** A sensitive expand was held for four-eyes approval instead of running (LA-19 / D-U7). {@code investigationId},
     *  {@code requestId}, {@code budget}, {@code maxFanOut} and the thresholds it exceeded. Nothing was read. */
    public static final String LINK_EXPANSION_REQUESTED = "LINK_EXPANSION_REQUESTED";

    /** A pending sensitive expand was approved by a DIFFERENT Subject and ran (LA-19 / D-U7). {@code investigationId},
     *  {@code requestId}, {@code requestedBy}, {@code step} (the log step it sealed). */
    public static final String LINK_EXPANSION_APPROVED = "LINK_EXPANSION_APPROVED";

    /** A pending sensitive expand was denied (LA-19 / D-U7); it never ran. {@code investigationId}, {@code requestId},
     *  {@code requestedBy}, {@code reason}. */
    public static final String LINK_EXPANSION_DENIED = "LINK_EXPANSION_DENIED";
}
