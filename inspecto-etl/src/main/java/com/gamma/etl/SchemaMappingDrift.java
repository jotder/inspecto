package com.gamma.etl;

import com.gamma.config.spec.Finding;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Schema drift into the mapping (AUTHORING-REDESIGN-1 (g), 2026-09-06): a schema document carries BOTH the
 * structure ({@code raw.fields[]}) and the Record Transformer ({@code mapping.fields[]}, or the legacy
 * {@code mapping.rules[]}), and the transformer's SELECT is compiled over the raw table by column NAME. A
 * rename or removal on the Parse side that leaves a {@code from} (or a column argument such as
 * {@code text.join}'s {@code other}) pointing at a column the schema no longer declares used to save 200 and
 * fail at the first row of the first run — the same shape as {@code JOIN_REFERENCE_MISSING}.
 *
 * <p>Pure and static: one document in, cell-level ERROR findings out. It runs on every schema save path
 * ({@code POST /config/write}, {@code PATCH /config/patch}, {@code PUT|POST /components/schema/{id}}) and,
 * unlike the BACKWARD compatibility gate, has no override — the two halves live in one file, so there is no
 * later step that could make the reference resolve.
 *
 * <p>⛔ A {@code custom} row (author-owned SQL) is not checked: its expression is not a column reference,
 * exactly the {@code EXPR} exclusion the cast-failure audit applies. A document with no {@code raw.fields}
 * gates nothing — shape problems are the spec validator's job.
 */
public final class SchemaMappingDrift {

    private SchemaMappingDrift() {}

    /** Every mapping input that names a column absent from {@code raw.fields[]}, as ERROR findings. */
    @SuppressWarnings("unchecked")
    public static List<Finding> check(Map<String, Object> schema) {
        Set<String> declared = declaredColumns(schema);
        if (declared.isEmpty()) return List.of();
        List<Map<String, Object>> fields = recordFields(schema);
        if (fields == null) return List.of();

        List<Finding> out = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            String name = String.valueOf(field.get("name"));
            Object fnId = field.get("fn");
            RecordTransform.Fn fn = RecordTransform.function(
                    fnId == null || String.valueOf(fnId).isBlank() ? RecordTransform.KEEP : String.valueOf(fnId));
            if (fn == null || RecordTransform.CUSTOM.equals(fn.id())) continue;   // unknown fn: compile's refusal; custom: EXPR rule

            if (fn.usesSource()) {
                Object from = field.get("from");
                if (from != null && !String.valueOf(from).isBlank() && !declared.contains(String.valueOf(from))) {
                    out.add(Finding.error("mapping.fields[" + name + "].from",
                            "mapping field '" + name + "' reads column '" + from + "', which raw.fields no longer "
                                    + "declares — the transform would fail at the first row; point it at a declared "
                                    + "column or drop the field (schema drift into the mapping)"));
                }
            }
            Object argsObj = field.get("args");
            if (!(argsObj instanceof Map<?, ?> args)) continue;
            for (RecordTransform.Param p : fn.params()) {
                if (p.type() != RecordTransform.ParamType.COLUMN) continue;
                Object v = args.get(p.name());
                if (v != null && !String.valueOf(v).isBlank() && !declared.contains(String.valueOf(v))) {
                    out.add(Finding.error("mapping.fields[" + name + "].args." + p.name(),
                            "mapping field '" + name + "' (" + fn.id() + ") reads column '" + v + "' for “" + p.label()
                                    + "”, which raw.fields no longer declares — point it at a declared column"));
                }
            }
        }
        return out;
    }

    private static Set<String> declaredColumns(Map<String, Object> schema) {
        Set<String> names = new LinkedHashSet<>();
        if (schema == null || !(schema.get("raw") instanceof Map<?, ?> raw)) return names;
        if (!(raw.get("fields") instanceof List<?> fields)) return names;
        for (Object f : fields)
            if (f instanceof Map<?, ?> fm && fm.get("name") != null) names.add(String.valueOf(fm.get("name")));
        return names;
    }

    /** The Record Transformer rows: {@code mapping.fields[]}, or the legacy {@code rules[]} converted; {@code null} when neither. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> recordFields(Map<String, Object> schema) {
        if (!(schema.get("mapping") instanceof Map<?, ?> mapping)) return null;
        if (mapping.get("fields") instanceof List<?> fields && !fields.isEmpty() && RecordTransform.isFieldList(fields))
            return (List<Map<String, Object>>) fields;
        if (mapping.get("rules") instanceof List<?> rules && !rules.isEmpty())
            return RecordTransform.fromMappingRules((List<Map<String, Object>>) rules);
        return null;
    }
}
