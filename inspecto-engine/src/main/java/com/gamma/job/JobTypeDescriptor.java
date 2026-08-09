package com.gamma.job;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The catalog metadata for one Job Type (§6.1): a stable {@code id} (the {@code type:} string), a human
 * title/description, the {@link ParameterDecl}s it needs (R3 — the "query a Job Type for its required
 * parameters" interface), the signal types it may {@code emit}, the {@link ArtifactDecl}s it may
 * record, and the Platform Service ids it {@code requires} (platform-services plan S1-2 — resolved
 * fail-closed at registration, granted to every Run's {@link JobContext#services()}). Served by
 * {@code GET /jobs/types/{id}} to drive authoring forms, wiring, and the grants panel.
 */
public record JobTypeDescriptor(String id, String title, String description,
                                List<ParameterDecl> parameters, List<String> emits,
                                List<ArtifactDecl> artifacts, List<String> requires) {

    public JobTypeDescriptor {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        emits      = emits == null ? List.of() : List.copyOf(emits);
        artifacts  = artifacts == null ? List.of() : List.copyOf(artifacts);
        requires   = requires == null ? List.of() : List.copyOf(requires);
    }

    /** Convenience for a type granted no Platform Services (the pre-S1-2 shape — most types). */
    public JobTypeDescriptor(String id, String title, String description,
                             List<ParameterDecl> parameters, List<String> emits,
                             List<ArtifactDecl> artifacts) {
        this(id, title, description, parameters, emits, artifacts, List.of());
    }

    /** Convenience for a type with no emitted signals / artifacts / service grants declared. */
    public JobTypeDescriptor(String id, String title, String description, List<ParameterDecl> parameters) {
        this(id, title, description, parameters, List.of(), List.of(), List.of());
    }

    /** JSON view for the API. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("description", description);
        m.put("parameters", parameters.stream().map(p -> {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.name());
            pm.put("type", p.type().name());
            pm.put("required", p.required());
            pm.put("deduce", p.deduce() == null ? "" : p.deduce());
            pm.put("default", p.defaultValue() == null ? "" : p.defaultValue());
            pm.put("description", p.description() == null ? "" : p.description());
            // The rendering + validation contract (job-parameter-contract §7.2). Empty string / empty list
            // for "unset", matching the keys above, so the UI's mirror needs no null handling. min/max stay
            // null when unbounded: 0 is a meaningful bound and must not be confused with "no bound".
            pm.put("label", p.label() == null ? "" : p.label());
            pm.put("tier", p.tier().name());
            pm.put("options", p.options());
            pm.put("pattern", p.pattern() == null ? "" : p.pattern());
            pm.put("min", p.min());
            pm.put("max", p.max());
            pm.put("placeholder", p.placeholder() == null ? "" : p.placeholder());
            pm.put("group", p.group() == null ? "" : p.group());
            pm.put("multi", p.multi());
            pm.put("secret", p.secret());
            pm.put("expressions", p.expressions());
            return pm;
        }).toList());
        m.put("emits", emits);
        m.put("artifacts", artifacts.stream()
                .map(a -> Map.of("name", a.name(), "kind", a.kind())).toList());
        // The type's declared Platform Service grants (S1-2) — the operator sees a pack's reach
        // before arming anything (plan §3.2).
        m.put("requires", requires);
        return m;
    }
}
