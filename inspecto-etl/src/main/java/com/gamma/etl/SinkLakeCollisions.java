package com.gamma.etl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single statement of when two {@code sinks[]} destinations would register into the SAME DuckLake
 * table — a shape that is REFUSED (operator decision 2026-09-23, {@code SINK-DUCKLAKE-SHARED-LAKE-DUPLICATES-1}).
 *
 * <p><b>Why refused.</b> {@link DuckLakeRegistrar#register} registers each destination's files into that
 * destination's effective lake (per sink since {@code SINK-DUCKLAKE-IGNORED-1}). When two destinations'
 * effective lakes are one catalog and they name one table, every batch INSERTs both destinations' files
 * into that table — so a routed pipeline's table holds every row once per destination that received it,
 * and a fan-out pipeline's holds every row twice. Nothing fails; the lake is silently wrong.
 *
 * <p><b>The table compared is the one that would actually be registered.</b> {@code register} is handed
 * {@code batch.table()}, which OVERRIDES a lake block's {@code table} key: it is non-null exactly when the
 * pipeline selects among schemas ({@code processing.schemas[].table}; {@code ConsignmentPlanner} maps a
 * blank or {@code default} table to {@code null}). So under a multi-schema pipeline a per-sink
 * {@code table} key cannot separate two sinks sharing a catalog — only a different {@code schema} or
 * catalog can — and the refusal says so.
 *
 * <p>Identity is {@code catalog_url} (trimmed, verbatim) + {@code schema} (default {@code main}) + table.
 * Only ENABLED lakes register, so only they collide.
 *
 * <p>Two callers, as for {@link RouteArming} and for the same reason: {@link PipelineConfig#prepare()}
 * (parsed config, throws the first refusal) and the control plane's save/validate path (an unparsed
 * DRAFT map, which reports every refusal as a {@code Finding}). One copy of the rule, plain data in.
 */
public final class SinkLakeCollisions {

    private SinkLakeCollisions() {}

    /** One destination: its {@code database} and its EFFECTIVE {@code ducklake} block ({@code null} = none). */
    public record Destination(String database, Map<?, ?> duckLake) {}

    /**
     * Every pair of destinations that would register into one DuckLake table — empty means none.
     *
     * @param sinks        every destination with its effective lake, in declaration order
     * @param schemaTables the {@code processing.schemas[].table} values of a multi-schema pipeline, as
     *                     authored (blank / {@code default} / {@code null} = "the lake block's table");
     *                     empty for a single-schema pipeline
     */
    public static List<String> refusals(List<Destination> sinks, List<String> schemaTables) {
        List<String> out = new ArrayList<>();
        Map<String, Integer> claimedBy = new HashMap<>();
        Set<String> reportedPairs = new HashSet<>();
        for (int i = 0; i < sinks.size(); i++) {
            Map<?, ?> dl = sinks.get(i).duckLake();
            if (dl == null || !Boolean.parseBoolean(String.valueOf(dl.get("enabled")))) continue;
            String catalog = dl.get("catalog_url") == null ? "" : String.valueOf(dl.get("catalog_url")).trim();
            String schema = blank(dl.get("schema")) ? "main" : String.valueOf(dl.get("schema"));
            String lakeTable = blank(dl.get("table")) ? null : String.valueOf(dl.get("table"));
            // table → true when it comes from processing.schemas[].table (overriding this lake's key)
            Map<String, Boolean> targets = new java.util.LinkedHashMap<>();
            if (schemaTables.isEmpty()) targets.put(lakeTable, false);
            for (String t : schemaTables) {
                boolean overrides = !blank(t) && !"default".equals(t);
                targets.putIfAbsent(overrides ? t : lakeTable, overrides);
            }
            for (Map.Entry<String, Boolean> t : targets.entrySet()) {
                String key = catalog + '\u0000' + schema + '\u0000' + t.getKey();
                Integer j = claimedBy.putIfAbsent(key, i);
                if (j == null || !reportedPairs.add(j + ":" + i)) continue;
                out.add(message(sinks.get(j).database(), sinks.get(i).database(), catalog, schema,
                        t.getKey(), t.getValue()));
            }
        }
        return out;
    }

    private static String message(String a, String b, String catalog, String schema, String table,
                                  boolean fromSchemas) {
        String where = "DuckLake catalog '" + catalog + "' table " + schema + "."
                + (table == null ? "<unset>" : table);
        String fix = fromSchemas
                ? " The table '" + table + "' comes from processing.schemas[].table, which overrides a"
                        + " ducklake block's table key, so a per-sink table cannot separate them — give one"
                        + " sinks[] entry its own ducklake schema or catalog_url"
                : " Give each sinks[] entry its own ducklake table (e.g. ducklake: {table: ...} on the"
                        + " entry), or a different catalog_url";
        return "sinks[] destinations '" + a + "' and '" + b + "' both register into " + where
                + " (each entry's effective ducklake: its own block, else output.ducklake), so every batch"
                + " would insert both destinations' files into one table and duplicate its rows." + fix + ".";
    }

    private static boolean blank(Object v) {
        return v == null || String.valueOf(v).isBlank();
    }

    // ── draft-map forms (the save path holds an unparsed map) ─────────────────────────────────

    /**
     * Every {@code sinks[]} entry of a draft with its effective lake — the entry's own {@code ducklake}
     * map, else {@code output.ducklake} — mirroring {@code PipelineConfig.resolveSinks}. Empty when the
     * draft has no {@code sinks:} list (the one-destination shorthand cannot collide with itself).
     */
    public static List<Destination> draftDestinations(Object sinks, Object output) {
        List<Destination> out = new ArrayList<>();
        if (!(sinks instanceof List<?> list)) return out;
        Map<?, ?> inherited = output instanceof Map<?, ?> o && o.get("ducklake") instanceof Map<?, ?> d ? d : null;
        for (Object s : list) {
            if (!(s instanceof Map<?, ?> m)) continue;
            Map<?, ?> own = m.get("ducklake") instanceof Map<?, ?> d ? d : null;
            out.add(new Destination(String.valueOf(m.get("database")), own != null ? own : inherited));
        }
        return out;
    }

    /**
     * The {@code processing.schemas[].table} values of a draft, as {@code PipelineConfigParser} reads
     * them — empty when a plugin ingester is set (the parser ignores {@code schemas[]} then) or when the
     * draft is single-schema.
     */
    public static List<String> draftSchemaTables(Map<?, ?> processing, Map<?, ?> parsing) {
        if (processing == null) return List.of();
        Object plugin = parsing == null ? null : parsing.get("plugin");
        if (!blank(processing.get("ingester"))
                || (plugin instanceof Map<?, ?> pm && !blank(pm.get("ingester")))) return List.of();
        if (!(processing.get("schemas") instanceof List<?> defs)) return List.of();
        Set<String> tables = new LinkedHashSet<>();
        for (Object e : defs)
            if (e instanceof Map<?, ?> m) tables.add(m.get("table") == null ? null : String.valueOf(m.get("table")));
        return new ArrayList<>(tables);
    }
}
