package com.gamma.ops;

import com.gamma.workflow.ObjectType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One mutable operational object — Layer 2 of the Operational Intelligence Platform
 * ({@code docs/superpowers/specs/2026-06-13-operational-intelligence-roadmap.md}). Where an
 * {@link com.gamma.audit.Event} is an immutable fact ("what happened"), an {@code OperationalObject}
 * is a managed thing that <b>changes state</b> ("should I care / am I handling it"): its
 * {@link #status()} walks a {@link com.gamma.workflow.Workflow} (e.g. a {@link ObjectType#CASE}
 * goes {@code OPEN → INVESTIGATING → ESCALATED → RESOLVED → CLOSED}). Because the row mutates it lives in a table store
 * ({@link ObjectStore}), not append-only Parquet.
 *
 * <h3>Shape</h3>
 * The columns mirror the requirement's object model: {@code id, object_type, title, description,
 * status, severity, priority, owner, assignee, created_at, updated_at, closed_at}, plus a
 * {@link #correlationId()} tying the object to the event/batch that spawned it and an extensible
 * {@link #attributes()} bag (e.g. an alert carries {@code rule}/{@code metric}/{@code value}).
 *
 * <p>The record itself is immutable; a lifecycle change produces a new instance via {@link #withStatus}
 * / {@link #withAssignee}, and {@link ObjectStore#update} persists it.
 *
 * <p><b>{@link #version()}</b> is the optimistic-lock counter: the version of the stored row this copy was
 * read at ({@code 0} on a fresh object). {@link ObjectStore#update} writes only if the stored row is still at
 * that version, bumps it, and returns the object at the new version — a racing writer's stale copy fails with
 * {@link ObjectVersionConflictException} instead of silently overwriting. Stores own the counter; the
 * {@code with*} methods carry it unchanged.
 *
 * @since 4.0.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public record OperationalObject(String id, ObjectType objectType, String title, String description,
                                String status, String severity, String priority, String owner,
                                String assignee, String correlationId, Map<String, String> attributes,
                                long createdAt, long updatedAt, long closedAt, long version, String rawType) {

    /**
     * Canonical constructor — validates the keys, defaults text fields, makes {@code attributes} immutable.
     * {@code objectType} may be {@code null} only for an <b>inert</b> object ({@link #inert}): a stored row whose
     * {@code type} this build does not know, which then carries that text as {@code rawType}.
     */
    public OperationalObject {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("object id is required");
        if (objectType == null && (rawType == null || rawType.isBlank()))
            throw new IllegalArgumentException("objectType is required");
        if (objectType != null) rawType = null;
        if (status == null || status.isBlank()) throw new IllegalArgumentException("status is required");
        title = title == null ? "" : title;
        description = description == null ? "" : description;
        attributes = attributes == null || attributes.isEmpty() ? Map.of() : Map.copyOf(attributes);
    }

    /** A typed (non-inert) object — the shape every caller outside the stores builds. */
    public OperationalObject(String id, ObjectType objectType, String title, String description,
                             String status, String severity, String priority, String owner,
                             String assignee, String correlationId, Map<String, String> attributes,
                             long createdAt, long updatedAt, long closedAt, long version) {
        this(id, objectType, title, description, status, severity, priority, owner, assignee, correlationId,
                attributes, createdAt, updatedAt, closedAt, version, null);
    }

    /**
     * A stored row whose {@code type} text names no {@link ObjectType} of this build (a legacy type such as
     * {@code ALERT}, or the type of a module that is not installed). It is loaded <b>inert</b>: listed and readable,
     * never mutated, never rewritten, never dropped, and skipped by the SLA sweep and analytics.
     */
    public static OperationalObject inert(String rawType, String id, String title, String description,
                                          String status, String severity, String priority, String owner,
                                          String assignee, String correlationId, Map<String, String> attributes,
                                          long createdAt, long updatedAt, long closedAt, long version) {
        return new OperationalObject(id, null, title, description, status, severity, priority, owner, assignee,
                correlationId, attributes, createdAt, updatedAt, closedAt, version, rawType);
    }

    /** {@code true} when this row's type is unknown to this build — see {@link #inert}. */
    public boolean isInert() {
        return objectType == null;
    }

    /** The type as stored: the {@link ObjectType} name, or the raw text of an inert row. */
    public String typeName() {
        return objectType != null ? objectType.name() : rawType;
    }

    /** The diagnostic an inert row is listed with. */
    public String inertDiagnostic() {
        return "type " + rawType + " is not installed/known: left untouched";
    }

    private OperationalObject typed() {
        if (isInert()) throw new InertObjectException(id, rawType);
        return this;
    }

    /**
     * A copy in a new {@code status}; {@code now} updates {@code updatedAt}. A terminal target stamps
     * {@code closedAt}; a non-terminal target clears it — moving out of a terminal state (the incident
     * {@code reopen} action, GLOSSARY §9) re-opens the object, so {@link #isClosed()} tracks the state.
     */
    public OperationalObject withStatus(String newStatus, long now, boolean terminal) {
        typed();
        return new OperationalObject(id, objectType, title, description, newStatus, severity, priority,
                owner, assignee, correlationId, attributes, createdAt, now, terminal ? now : 0, version, rawType);
    }

    /** A copy reassigned to {@code newAssignee} (touches {@code updatedAt}). */
    public OperationalObject withAssignee(String newAssignee, long now) {
        typed();
        return new OperationalObject(id, objectType, title, description, status, severity, priority,
                owner, newAssignee, correlationId, attributes, createdAt, now, closedAt, version, rawType);
    }

    /** A copy at a new {@code severity} (INC-4 escalation bump); touches {@code updatedAt}. */
    public OperationalObject withSeverity(String newSeverity, long now) {
        typed();
        return new OperationalObject(id, objectType, title, description, status, newSeverity, priority,
                owner, assignee, correlationId, attributes, createdAt, now, closedAt, version, rawType);
    }

    /** A copy at a new {@code priority} (operator triage — the {@code PATCH /objects/{id}} Prioritize); touches {@code updatedAt}. */
    public OperationalObject withPriority(String newPriority, long now) {
        typed();
        return new OperationalObject(id, objectType, title, description, status, severity, newPriority,
                owner, assignee, correlationId, attributes, createdAt, now, closedAt, version, rawType);
    }

    /**
     * The object's watcher list (INC-4) — the comma-separated {@code watchers} attribute parsed into a list,
     * or empty. Watchers are subscribers notified when the object changes; they ride the attribute bag so
     * they persist with the object across either store backend.
     */
    public java.util.List<String> watchers() {
        String raw = attributes.get("watchers");
        if (raw == null || raw.isBlank()) return java.util.List.of();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String s : raw.split(",")) { String t = s.trim(); if (!t.isEmpty()) out.add(t); }
        return out;
    }

    /**
     * A copy with {@code updates} merged over the current {@link #attributes()} (updates win; null keys
     * or values are skipped); touches {@code updatedAt}. Used to stamp derived markers such as the
     * Phase-3 {@code slaBreachedAt} without disturbing status or assignment.
     */
    public OperationalObject withAttributes(Map<String, String> updates, long now) {
        typed();
        Map<String, String> merged = new LinkedHashMap<>(attributes);
        if (updates != null) updates.forEach((k, v) -> { if (k != null && v != null) merged.put(k, v); });
        return new OperationalObject(id, objectType, title, description, status, severity, priority,
                owner, assignee, correlationId, merged, createdAt, now, closedAt, version, rawType);
    }

    /** {@code true} once {@link #closedAt()} is set (the object reached a terminal state). */
    /** This object as the store must hold it at {@code newVersion} (the stores bump it; callers never do). */
    OperationalObject withVersion(long newVersion) {
        return new OperationalObject(id, objectType, title, description, status, severity, priority,
                owner, assignee, correlationId, attributes, createdAt, updatedAt, closedAt, newVersion, rawType);
    }

    public boolean isClosed() {
        return closedAt > 0;
    }

    /** JSON-ready view (stable key order) — backs the {@code /objects} API. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("objectType", typeName());
        if (isInert()) {
            m.put("inert", true);
            m.put("diagnostic", inertDiagnostic());
        }
        m.put("title", title);
        m.put("description", description);
        m.put("status", status);
        m.put("severity", severity);
        m.put("priority", priority);
        m.put("owner", owner);
        m.put("assignee", assignee);
        m.put("correlationId", correlationId);
        m.put("attributes", attributes);
        m.put("watchers", watchers());
        // WS-10: the typed impact with its DERIVED outstanding (confirmed − recovered), computed here on every
        // read and never stored; absent when the object carries none.
        Impact.of(this).ifPresent(i -> m.put("impact", i.toMap()));
        m.put("createdAt", createdAt);
        m.put("updatedAt", updatedAt);
        m.put("closedAt", closedAt);
        m.put("version", version);
        return m;
    }

    /** Start a builder for a new object of {@code type}; {@code id} auto-generates and timestamps default to now. */
    public static Builder builder(ObjectType type) {
        return new Builder(type);
    }

    /** Fluent builder — {@code type} and {@code status} are required; the rest are optional. */
    public static final class Builder {
        private final ObjectType objectType;
        private String id = null;
        private String title = "";
        private String description = "";
        private String status;
        private String severity;
        private String priority;
        private String owner;
        private String assignee;
        private String correlationId;
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private long createdAt = System.currentTimeMillis();
        private long updatedAt = createdAt;
        private long closedAt = 0;

        private Builder(ObjectType type) { this.objectType = type; }

        public Builder id(String id) { this.id = id; return this; }
        public Builder title(String title) { this.title = title; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder severity(String severity) { this.severity = severity; return this; }
        public Builder priority(String priority) { this.priority = priority; return this; }
        public Builder owner(String owner) { this.owner = owner; return this; }
        public Builder assignee(String assignee) { this.assignee = assignee; return this; }
        public Builder correlationId(String id) { this.correlationId = id; return this; }
        public Builder createdAt(long ms) { this.createdAt = ms; return this; }
        public Builder updatedAt(long ms) { this.updatedAt = ms; return this; }

        /** Add one attribute; {@code null} key or value is ignored. */
        public Builder attr(String key, Object value) {
            if (key != null && value != null) attributes.put(key, String.valueOf(value));
            return this;
        }

        /** Add all of {@code attrs} (skips null values). */
        public Builder attributes(Map<String, String> attrs) {
            if (attrs != null) attrs.forEach(this::attr);
            return this;
        }

        public OperationalObject build() {
            String oid = (id == null || id.isBlank())
                    ? objectType.name() + "-" + UUID.randomUUID()
                    : id;
            return new OperationalObject(oid, objectType, title, description, status, severity, priority,
                    owner, assignee, correlationId, attributes, createdAt, updatedAt, closedAt, 0);
        }
    }
}
