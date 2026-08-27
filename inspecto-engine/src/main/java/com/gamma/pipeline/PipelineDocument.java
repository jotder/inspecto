package com.gamma.pipeline;

import com.gamma.api.PublicApi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static com.gamma.util.Values.strOrEmpty;

/**
 * <b>The Pipeline Document generator (ELT amendment §5.1, Phase 5 / UI plan S6a):</b> renders a
 * recipe ({@link RecipeConverter#toRecipe}) as human-readable Markdown for business verification and
 * sign-off. A <b>projection of configuration</b> — never hand-authored, never stored as truth,
 * regenerated on demand.
 *
 * <p><b>Pure and deterministic by construction.</b> No I/O, no clock, no {@code HashMap} iteration:
 * the same recipe + components render byte-identically every time, which is what lets
 * {@code PipelineDocumentContractTest} pin the output against a golden file and what makes the
 * {@code fingerprint} in the header meaningful. Deliberately <b>no generation timestamp</b> — the
 * document is bound to config by the fingerprint, not by when someone pressed export; a timestamp
 * would only break that binding and the determinism gate with it.
 *
 * <p><b>Nothing is silently dropped.</b> Every step key §5.1 does not give a dedicated table still
 * renders through {@link #configTable} — a sign-off document that quietly omitted config would be
 * worse than no document at all. Values under a secret-shaped key are masked ({@link #SECRET_KEYS}).
 *
 * <p>Sample rows per Step (§5.1's "worked examples") are <b>deliberately not here</b>: they require
 * a live dry-run, which is neither pure nor deterministic. They belong to the S6b import/diff loop,
 * which already needs the dry-run seam.
 */
@PublicApi(since = "4.0.0")
public final class PipelineDocument {

    private PipelineDocument() {}

    /** §5.1 "connection (secrets masked)" — any key whose name contains one of these never renders verbatim. */
    private static final Set<String> SECRET_KEYS = Set.of(
            "password", "secret", "token", "credential", "passphrase", "private_key", "access_key");

    private static final String MASK = "\u2022\u2022\u2022\u2022";

    /** Verb → section heading, in the pipeline order {@link RecipeConverter} emits. */
    private static final Map<String, String> VERB_LABEL = Map.of(
            "collect", "Collect", "parse", "Parse", "map", "Map", "transform", "Transform",
            "dedup", "Dedup", "summarize", "Summarize", "route", "Route", "sink", "Sink");

    /**
     * Render {@code recipe} as Markdown.
     *
     * @param id          the pipeline's identity — what every route keys on, and what the reader asked
     *                    for. The config's own {@code name} rides a separate row when it differs, the
     *                    same {@code displayName} split {@code GET /pipelines} already makes.
     * @param recipe      a recipe map ({@code name/active/trigger/steps/guarantees}) — {@link RecipeConverter#toRecipe}
     * @param components  resolved content for every ref the recipe names, keyed by the ref exactly as the
     *                    recipe spells it ({@code schemas/foo}, {@code mappings/foo}, {@code grammars/foo},
     *                    or a legacy plain path). Absent refs render as "not resolved" rather than failing —
     *                    a document must still generate for a pipeline with a dangling ref, and say so.
     * @param fingerprint the config fingerprint binding this document to the config that produced it
     */
    public static String render(String id, Map<String, Object> recipe,
                                Map<String, Map<String, Object>> components, String fingerprint) {
        StringBuilder md = new StringBuilder();
        String title = strOrEmpty(id).isEmpty() ? strOrEmpty(recipe.get("name")) : strOrEmpty(id);
        String configured = strOrEmpty(recipe.get("name"));

        md.append("# Pipeline: ").append(title.isEmpty() ? "(unnamed)" : title).append("\n\n");

        md.append("| | |\n|---|---|\n");
        row(md, "Pipeline", code(title));
        if (!configured.isEmpty() && !configured.equals(title)) row(md, "Name", configured);
        row(md, "Status", Boolean.FALSE.equals(recipe.get("active")) ? "Inactive" : "Active");
        if (recipe.get("trigger") != null) row(md, "Trigger", value(recipe.get("trigger")));
        row(md, "Config fingerprint", code(fingerprint));
        md.append("\n");

        md.append("> Generated from configuration — never hand-authored. The fingerprint above binds this\n")
          .append("> document to the exact configuration that produced it: if it no longer matches, the\n")
          .append("> configuration has changed and this document's sign-off is stale.\n\n");

        List<Map<String, Object>> steps = steps(recipe.get("steps"));
        md.append("## Steps\n\n");
        if (steps.isEmpty()) {
            md.append("_No steps._\n\n");
        } else {
            tableOfSteps(md, steps);
            int n = 1;
            for (Map<String, Object> step : steps) {
                for (Map.Entry<String, Object> e : step.entrySet()) {
                    stepSection(md, n++, e.getKey(), asMap(e.getValue()), components, 3);
                }
            }
        }

        guarantees(md, asMap(recipe.get("guarantees")));
        referencedComponents(md, components);
        return md.toString();
    }

    // ── steps ────────────────────────────────────────────────────────────────────

    /** The chain at a glance, before the per-Step detail — what a reviewer reads first. */
    private static void tableOfSteps(StringBuilder md, List<Map<String, Object>> steps) {
        md.append("| # | Step | Summary |\n|---|---|---|\n");
        int n = 1;
        for (Map<String, Object> step : steps) {
            for (Map.Entry<String, Object> e : step.entrySet()) {
                md.append("| ").append(n++).append(" | ").append(label(e.getKey())).append(" | ")
                  .append(cell(summarize(e.getKey(), asMap(e.getValue())))).append(" |\n");
            }
        }
        md.append("\n");
    }

    /** One line naming what this Step actually does, drawn from the keys that carry its intent. */
    private static String summarize(String verb, Map<String, Object> cfg) {
        return switch (verb) {
            case "collect" -> first(cfg, "connection", "dir", "path", "files");
            case "parse" -> first(cfg, "grammar", "format");
            case "map" -> first(cfg, "schema", "mapping");
            case "transform" -> cfg.containsKey("join") ? "join " + value(cfg.get("join"))
                    : cfg.containsKey("filter") ? "filter " + value(cfg.get("filter")) : "";
            case "dedup" -> cfg.containsKey("key") ? "key " + value(cfg.get("key")) : "";
            case "summarize" -> cfg.containsKey("group_by") ? "by " + value(cfg.get("group_by")) : "";
            case "route" -> branches(cfg).size() + " branch(es)";
            case "sink" -> first(cfg, "database", "table", "format");
            default -> "";
        };
    }

    private static void stepSection(StringBuilder md, int n, String verb, Map<String, Object> cfg,
                                    Map<String, Map<String, Object>> components, int depth) {
        md.append("#".repeat(depth)).append(" ").append(n).append(". ").append(label(verb)).append("\n\n");

        switch (verb) {
            case "map" -> {
                fieldTable(md, cfg, components);
                configTable(md, cfg, Set.of("schema", "mapping"));
            }
            case "route" -> {
                branchTable(md, cfg);
                configTable(md, cfg, Set.of("branches"));
                int sub = 1;
                for (Map.Entry<String, Object> b : branches(cfg).entrySet()) {
                    Map<String, Object> branch = asMap(b.getValue());
                    md.append("#".repeat(depth + 1)).append(" Branch: ").append(b.getKey()).append("\n\n");
                    for (Map<String, Object> step : steps(branch.get("steps"))) {
                        for (Map.Entry<String, Object> e : step.entrySet()) {
                            stepSection(md, sub++, e.getKey(), asMap(e.getValue()), components, depth + 2);
                        }
                    }
                }
            }
            default -> configTable(md, cfg, Set.of());
        }
    }

    /**
     * §5.1's field table for the {@code map} Step — "a straight join of the Schema and Mapping CSVs".
     * Mapping rules drive the rows (they are the output contract); the Schema field of the same name
     * supplies type/unit/description/classification. A schema field with no mapping rule still appears,
     * so a reviewer sees the whole output shape, not just the mapped part.
     */
    private static void fieldTable(StringBuilder md, Map<String, Object> cfg,
                                   Map<String, Map<String, Object>> components) {
        List<Map<String, Object>> rules = rows(components.get(strOrEmpty(cfg.get("mapping"))), "rules");
        List<Map<String, Object>> fields = schemaFields(components.get(strOrEmpty(cfg.get("schema"))));
        if (rules.isEmpty() && fields.isEmpty()) return;

        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> f : fields) byName.put(strOrEmpty(f.get("name")), f);

        md.append("| Target | Source | Kind | Type | Unit | Description | Classification |\n")
          .append("|---|---|---|---|---|---|---|\n");

        Set<String> seen = new java.util.LinkedHashSet<>();
        for (Map<String, Object> r : rules) {
            String target = strOrEmpty(r.get("targetColumn"));
            seen.add(target);
            Map<String, Object> f = byName.getOrDefault(target, Map.of());
            fieldRow(md, target, strOrEmpty(r.get("sourceExpression")),
                    strOrEmpty(r.get("transformType")).isEmpty() ? "DIRECT" : strOrEmpty(r.get("transformType")), f);
        }
        for (Map<String, Object> f : fields) {
            String n = strOrEmpty(f.get("name"));
            if (!seen.contains(n)) fieldRow(md, n, strOrEmpty(f.get("selector")), "", f);
        }
        md.append("\n");
    }

    private static void fieldRow(StringBuilder md, String target, String source, String kind,
                                 Map<String, Object> field) {
        md.append("| ").append(cell(target)).append(" | ").append(cell(source)).append(" | ")
          .append(cell(kind)).append(" | ").append(cell(strOrEmpty(field.get("type")))).append(" | ")
          .append(cell(strOrEmpty(field.get("unit")))).append(" | ")
          .append(cell(strOrEmpty(field.get("description")))).append(" | ")
          .append(cell(strOrEmpty(field.get("classification")))).append(" |\n");
    }

    /** §5.1's route branch table: name, condition, mode, destination chain. */
    private static void branchTable(StringBuilder md, Map<String, Object> cfg) {
        Map<String, Object> branches = branches(cfg);
        if (branches.isEmpty()) return;
        String mode = strOrEmpty(cfg.get("mode"));
        md.append("| Branch | Condition | Mode | Default | Destination |\n|---|---|---|---|---|\n");
        for (Map.Entry<String, Object> e : branches.entrySet()) {
            Map<String, Object> b = asMap(e.getValue());
            List<String> dest = new ArrayList<>();
            for (Map<String, Object> step : steps(b.get("steps"))) {
                for (Map.Entry<String, Object> s : step.entrySet()) {
                    String d = summarize(s.getKey(), asMap(s.getValue()));
                    dest.add(d.isEmpty() ? label(s.getKey()) : label(s.getKey()) + " → " + d);
                }
            }
            md.append("| ").append(cell(e.getKey())).append(" | ").append(cell(value(b.get("when"))))
              .append(" | ").append(cell(mode)).append(" | ")
              .append(Boolean.TRUE.equals(b.get("default")) ? "yes" : "").append(" | ")
              .append(cell(String.join("; ", dest))).append(" |\n");
        }
        md.append("\n");
    }

    /** Every remaining key of a Step, so nothing configured goes unreported. */
    private static void configTable(StringBuilder md, Map<String, Object> cfg, Set<String> skip) {
        List<Map.Entry<String, Object>> shown = new ArrayList<>();
        for (Map.Entry<String, Object> e : cfg.entrySet()) if (!skip.contains(e.getKey())) shown.add(e);
        if (shown.isEmpty()) {
            if (skip.isEmpty()) md.append("_No configuration._\n\n");
            return;
        }
        md.append("| Setting | Value |\n|---|---|\n");
        for (Map.Entry<String, Object> e : shown) row(md, code(e.getKey()), masked(e.getKey(), e.getValue()));
        md.append("\n");
    }

    // ── trailing sections ────────────────────────────────────────────────────────

    /** Guarantees are not Steps (GLOSSARY) — they render as their own checklist section. */
    private static void guarantees(StringBuilder md, Map<String, Object> g) {
        md.append("## Guarantees\n\n");
        if (g.isEmpty()) {
            md.append("_None configured._\n\n");
            return;
        }
        md.append("| Guarantee | Configuration |\n|---|---|\n");
        for (Map.Entry<String, Object> e : g.entrySet()) row(md, label(e.getKey()), masked(e.getKey(), e.getValue()));
        md.append("\n");
    }

    /** What this document was generated from — the sign-off surface for the fingerprint's inputs. */
    private static void referencedComponents(StringBuilder md, Map<String, Map<String, Object>> components) {
        if (components.isEmpty()) return;
        md.append("## Referenced components\n\n| Reference | Resolved |\n|---|---|\n");
        for (Map.Entry<String, Map<String, Object>> e : components.entrySet()) {
            row(md, code(e.getKey()), e.getValue() == null || e.getValue().isEmpty() ? "**not resolved**" : "yes");
        }
        md.append("\n");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static void row(StringBuilder md, String k, String v) {
        md.append("| ").append(k).append(" | ").append(cell(v)).append(" |\n");
    }

    private static String label(String key) {
        String known = VERB_LABEL.get(key);
        if (known != null) return known;
        String spaced = key.replace('_', ' ');
        return spaced.isEmpty() ? spaced : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** A secret-shaped key never renders its value (§5.1). */
    private static String masked(String key, Object v) {
        String k = key.toLowerCase(Locale.ROOT);
        for (String s : SECRET_KEYS) if (k.contains(s)) return MASK;
        return value(v);
    }

    /** Scalars plainly; a list comma-joined; a map as {@code k=v} pairs — always single-line, table-safe. */
    private static String value(Object v) {
        if (v == null) return "";
        if (v instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object o : list) parts.add(value(o));
            return String.join(", ", parts);
        }
        if (v instanceof Map<?, ?> m) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<?, ?> e : m.entrySet()) parts.add(masked(String.valueOf(e.getKey()), e.getValue()).isEmpty()
                    ? String.valueOf(e.getKey())
                    : e.getKey() + "=" + masked(String.valueOf(e.getKey()), e.getValue()));
            return String.join("; ", parts);
        }
        return String.valueOf(v);
    }

    private static String first(Map<String, Object> cfg, String... keys) {
        for (String k : keys) if (cfg.get(k) != null) return value(cfg.get(k));
        return "";
    }

    /** Table cells must not break the row: escape pipes, flatten newlines. */
    private static String cell(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\r\n", " ").replace("\n", " ");
    }

    private static String code(String s) {
        return s == null || s.isEmpty() ? "" : "`" + s + "`";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static Map<String, Object> branches(Map<String, Object> cfg) {
        return asMap(cfg.get("branches"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> steps(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> list)
            for (Object s : list) if (s instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    /** A schema component's fields, under {@code raw.fields} (registry shape) or {@code fields}. */
    private static List<Map<String, Object>> schemaFields(Map<String, Object> schema) {
        if (schema == null) return List.of();
        Map<String, Object> raw = asMap(schema.get("raw"));
        return rows(raw.isEmpty() ? schema : raw, "fields");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> owner, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (owner != null && owner.get(key) instanceof List<?> list)
            for (Object r : list) if (r instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }
}
