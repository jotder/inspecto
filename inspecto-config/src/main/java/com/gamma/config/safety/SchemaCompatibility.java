package com.gamma.config.safety;

import com.gamma.config.spec.Finding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The schema compatibility save-gate (ELT final amendment §3.4.2, D-10): the schema registry's core
 * value — a compatibility class enforced on edit — without a registry service. Diffs an existing
 * schema's {@code raw.fields} against a draft's under the <b>BACKWARD</b> class:
 *
 * <ul>
 *   <li><b>allowed in place</b> — adding a field; widening a field's type ({@code INTEGER → DOUBLE},
 *       anything {@code → VARCHAR}, {@code DATE → TIMESTAMP});</li>
 *   <li><b>breaking (ERROR)</b> — removing a field (a rename reads as remove+add), narrowing or
 *       otherwise changing a type outside the widening lattice, or moving a field's
 *       {@code selector} (existing raw files would parse into different columns).</li>
 * </ul>
 *
 * <p>Findings are cell-level: anchored to {@code raw.fields[NAME]} / {@code …[NAME].type} /
 * {@code …[NAME].selector}, so a grid editor can mark the exact cell. The route that holds both
 * versions ({@code /config/write} / {@code /config/patch}) calls this; the escape hatches are
 * copy-to-a-new-name or the explicit {@code compatibility: "none"} override — both caller-side,
 * deliberately not expressible here.
 *
 * <p>Pure and static, like the rest of this package: no I/O, no state.
 */
public final class SchemaCompatibility {

    private SchemaCompatibility() {}

    /** Widenings allowed in place: old type → the strictly-more-general types it may become. */
    private static final Map<String, Set<String>> WIDENINGS = Map.of(
            "INTEGER",   Set.of("BIGINT", "DOUBLE"),
            "BIGINT",    Set.of("DOUBLE"),
            "DATE",      Set.of("TIMESTAMP")
    );

    /**
     * BACKWARD-diff {@code existing} → {@code draft} (both full schema config maps). Returns
     * cell-level ERROR findings for every breaking change; empty when the edit is compatible.
     * A malformed side contributes nothing — shape problems are the spec validator's job.
     */
    public static List<Finding> check(Map<String, Object> existing, Map<String, Object> draft) {
        Map<String, Map<String, Object>> oldFields = fieldsByName(existing);
        Map<String, Map<String, Object>> newFields = fieldsByName(draft);
        if (oldFields.isEmpty()) return List.of();   // nothing to be compatible WITH

        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : oldFields.entrySet()) {
            String name = e.getKey();
            Map<String, Object> nf = newFields.get(name);
            if (nf == null) {
                findings.add(Finding.error("raw.fields[" + name + "]",
                        "breaking change (BACKWARD): field '" + name + "' removed — existing data and "
                        + "referencing Mappings still expect it; copy the schema to a new name, or pass "
                        + "compatibility: \"none\" to override"));
                continue;
            }
            String oldType = str(e.getValue().get("type"));
            String newType = str(nf.get("type"));
            if (!oldType.isEmpty() && !oldType.equals(newType) && !isWidening(oldType, newType)) {
                findings.add(Finding.error("raw.fields[" + name + "].type",
                        "breaking change (BACKWARD): type of '" + name + "' changed " + oldType + " → "
                        + (newType.isEmpty() ? "(none)" : newType) + " (not a widening) — already-written "
                        + "data would not read back; copy to a new name, or pass compatibility: \"none\""));
            }
            String oldSel = raw(e.getValue().get("selector"));
            String newSel = raw(nf.get("selector"));
            if (!oldSel.isEmpty() && !oldSel.equals(newSel)) {
                findings.add(Finding.error("raw.fields[" + name + "].selector",
                        "breaking change (BACKWARD): selector of '" + name + "' moved '" + oldSel + "' → '"
                        + newSel + "' — existing raw files would parse into different columns; copy to a "
                        + "new name, or pass compatibility: \"none\""));
            }
        }
        return findings;
    }

    private static boolean isWidening(String oldType, String newType) {
        if ("VARCHAR".equals(newType)) return true;   // everything reads back as text
        return WIDENINGS.getOrDefault(oldType, Set.of()).contains(newType);
    }

    /** {@code raw.fields[].name → field map}, in declaration order; empty on any shape mismatch. */
    private static Map<String, Map<String, Object>> fieldsByName(Map<String, Object> schema) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (schema == null || !(schema.get("raw") instanceof Map<?, ?> raw)) return out;
        if (!(raw.get("fields") instanceof List<?> fields)) return out;
        for (Object f : fields) {
            if (f instanceof Map<?, ?> fm) {
                Object name = fm.get("name");
                if (name != null && !String.valueOf(name).isBlank()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) fm;
                    out.put(String.valueOf(name).trim(), m);
                }
            }
        }
        return out;
    }

    /** Types compare case-insensitively (normalised upper); selectors are compared verbatim. */
    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim().toUpperCase();
    }

    private static String raw(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
