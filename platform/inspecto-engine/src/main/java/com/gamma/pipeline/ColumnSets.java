package com.gamma.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.sql.SqlGuard;
import com.gamma.util.MappingCsv;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The column sets a Space's config publishes, and the judging of the census COLUMN links against them
 * (`SPACE-LINK-CENSUS-1` LC-5, report-only).
 *
 * <p><b>What a set is.</b> A Pipeline's store offers the canonical names of its Schema's {@code mapping.fields}
 * (or the {@code processing.map.fields} that outrank them), plus the hive partition columns
 * {@code year/month/day} and any {@code filename_column}. A Dataset offers the columns of the store it reads (a
 * {@code sourceName} / {@code physicalRef} naming a Pipeline) plus its {@code calculated} columns; a Dataset whose
 * store is not a Pipeline's offers its declared {@code columns}. Anything else is UNKNOWN, and a link judged
 * against an unknown set stays {@link LinkGraph.Status#UNCHECKED} — never a guessed finding. Kept unknown on
 * purpose: a Schema whose mapping comes from {@code mapping.rules}, a {@code mapping_file} or a sibling
 * {@code _mapping.csv}; plural {@code processing.schemas}; a virtual Dataset without declared {@code columns}.
 *
 * <p><b>Matching.</b> Every consumer binds its column through DuckDB (Widget controls, {@code dateField},
 * Expectations via {@code ConditionSql}, Decision Rules via {@code ConditionSql} in {@code DecisionRuleApplier},
 * Reconciliations via {@code ReconService}), and DuckDB identifiers match case-insensitively even when quoted —
 * so a link matches its column case-insensitively. Expectation {@code column}/{@code refColumn} and
 * Reconciliation {@code keyColumns} are trimmed first, as their loaders trim. A name starting {@code __} is a
 * system column ({@code __batch_id}, {@code __tags}, lineage) and stays unchecked.
 *
 * <p><b>SQL text</b> (Enrichment {@code transform}, Query {@code text}, virtual Dataset {@code sql}, Dataset
 * {@code calculated[].expr}) is read through DuckDB's own parse tree ({@link SqlGuard#parseSelectNode}, the
 * parser {@code VIRTUAL-DATASET-SQL-RENAME-1} uses for relations). Only a single plain SELECT over named tables
 * and joins is analysed; a CTE, subquery, set operation, lambda, VALUES list or table function makes the whole
 * text NOT_ANALYSED, as does SQL the parser refuses (fail closed, DuckDB 2.0 shape). A reference that cannot be
 * attributed to one relation, or to a relation whose columns are unknown, is NOT_ANALYSED — never DANGLING.
 */
final class ColumnSets {

    /** Columns whose store layout adds them to every pipeline store (PartitionWriter.DEFAULT_PARTITION_COLS). */
    private static final List<String> PARTITION_COLUMNS = List.of("year", "month", "day");

    /** A known column set: lower-cased name → where it is published ({@code schema:<path>}, {@code pipeline:<id>},
     *  {@code dataset:<name>}). */
    record Cols(Map<String, String> byLower) {
        String origin(String column) {
            return byLower.get(column.toLowerCase(Locale.ROOT));
        }
    }

    /** One judged column reference. */
    record Judged(String key, String value, LinkGraph.Status status, Set<String> origins) {}

    private final Map<String, Cols> pipelineStores = new HashMap<>();       // pipeline id (lower) → set or null
    private final Map<String, String> storeDirs = new HashMap<>();          // normalized dirs.database → id (lower)
    private final Map<String, Map<?, ?>> datasets = new HashMap<>();
    private final Map<String, Cols> datasetCols = new HashMap<>();
    private final Set<String> datasetsResolving = new LinkedHashSet<>();

    ColumnSets(List<Path> pipelineFiles, List<Map<String, Object>> pipelineDocs,
               List<ComponentRegistry.Component> datasetComponents) {
        for (int i = 0; i < pipelineFiles.size(); i++) {
            Map<String, Object> doc = pipelineDocs.get(i);
            if (doc == null) continue;
            String id = pipelineId(doc);
            if (id == null) continue;
            pipelineStores.put(id, pipelineCols(pipelineFiles.get(i), doc, id));
            if (doc.get("dirs") instanceof Map<?, ?> dirs && !blank(dirs.get("database")))
                storeDirs.put(normDir(String.valueOf(dirs.get("database"))), id);
        }
        for (ComponentRegistry.Component c : datasetComponents) datasets.put(c.name(), c.content());
    }

    // ── the sets ────────────────────────────────────────────────────────────────────────────

    private static String pipelineId(Map<String, Object> raw) {
        if (!blank(raw.get("id"))) return String.valueOf(raw.get("id")).trim().toLowerCase(Locale.ROOT);
        if (!blank(raw.get("name")))
            return String.valueOf(raw.get("name")).trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return null;
    }

    /** The store a Pipeline writes, or {@code null} when its columns cannot be known from config alone. */
    private static Cols pipelineCols(Path file, Map<String, Object> doc, String id) {
        if (!(doc.get("processing") instanceof Map<?, ?> proc)) return null;
        if (proc.get("schemas") != null || proc.get("mapping_file") != null) return null;
        List<?> fields = proc.get("map") instanceof Map<?, ?> map && map.get("fields") instanceof List<?> l ? l : null;
        String origin = "pipeline:" + id;
        List<String> named = new ArrayList<>();
        // The Schema is read even when processing.map.fields outranks its mapping: its partitions[] still apply
        // (PartitionDef.fromSchema). An unreadable Schema leaves the set unknown.
        if (blank(proc.get("schema_file"))) return null;
        Path schema = schemaPath(file, String.valueOf(proc.get("schema_file")).trim());
        if (schema == null || !Files.isRegularFile(schema)) return null;
        Map<String, Object> s = LinkGraph.decodeQuietly(schema);
        if (s == null) return null;
        // a Schema's own partition columns are store columns too (excel_example: item_category)
        if (s.get("partitions") instanceof List<?> parts)
            for (Object pt : parts)
                if (pt instanceof Map<?, ?> pm && !blank(pm.get("column")))
                    named.add(String.valueOf(pm.get("column")));
        if (fields == null) {
            if (Files.exists(MappingCsv.siblingFor(schema))) return null;
            if (!(s.get("mapping") instanceof Map<?, ?> mapping) || !(mapping.get("fields") instanceof List<?> l)) return null;
            fields = l;
            origin = schemaOrigin(schema);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Object f : fields) {
            if (!(f instanceof Map<?, ?> m) || blank(m.get("name"))) return null;   // a shape not read here
            out.putIfAbsent(String.valueOf(m.get("name")).trim().toLowerCase(Locale.ROOT), origin);
        }
        if (out.isEmpty()) return null;
        for (String p : PARTITION_COLUMNS) out.putIfAbsent(p, "pipeline:" + id);
        collect(doc, "filename_column", named);
        for (String n : named) out.putIfAbsent(n.trim().toLowerCase(Locale.ROOT), "pipeline:" + id);
        return new Cols(out);
    }

    /** {@code schema/<id>} names {@code registry/schemas/<id>.toon}; anything else resolves beside the Pipeline
     *  (PipelineConfigParser.resolveSchemaRef). */
    private static Path schemaPath(Path pipelineFile, String ref) {
        Path dir = pipelineFile.toAbsolutePath().getParent();
        if (dir == null) return null;
        try {
            // PipelineConfigParser.SCHEMA_REF_PREFIX
            String rel = ref.startsWith("schema/") ? "registry/schemas/" + ref.substring("schema/".length()) + ".toon" : ref;
            return dir.resolve(rel).normalize();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** The origin a Schema file's columns carry; the impact read compares against it. */
    static String schemaOrigin(Path schemaFile) {
        return "schema:" + schemaFile.toAbsolutePath().normalize();
    }

    /** The store {@code name} names, when it is a Pipeline's ({@code orders} or {@code orders/database}). */
    private Cols store(String name) {
        if (name == null) return null;
        String n = name.trim().replace('\\', '/');
        int slash = n.indexOf('/');
        if (slash >= 0) {
            if (!n.substring(slash + 1).equals("database")) return null;
            n = n.substring(0, slash);
        }
        return pipelineStores.get(n.toLowerCase(Locale.ROOT));
    }

    private boolean isPipelineStore(String name) {
        if (name == null) return false;
        String n = name.trim().replace('\\', '/');
        int slash = n.indexOf('/');
        return pipelineStores.containsKey((slash < 0 ? n : n.substring(0, slash)).toLowerCase(Locale.ROOT));
    }

    /** The columns a Dataset offers, or {@code null} when unknown. */
    Cols dataset(String name) {
        if (name == null || !datasets.containsKey(name)) return null;
        if (datasetCols.containsKey(name)) return datasetCols.get(name);
        if (!datasetsResolving.add(name)) return null;   // defensive: no Dataset reads another today
        Map<?, ?> d = datasets.get(name);
        Map<String, String> out = new LinkedHashMap<>();
        String storeName = !blank(d.get("sourceName")) ? String.valueOf(d.get("sourceName"))
                : !blank(d.get("physicalRef")) ? String.valueOf(d.get("physicalRef")) : null;
        boolean virtual = d.get("sql") != null;
        Cols base = virtual ? null : store(storeName);
        boolean known;
        if (base != null) {
            out.putAll(base.byLower());
            known = true;
        } else if ((virtual || !isPipelineStore(storeName)) && d.get("columns") instanceof List<?> cols && !cols.isEmpty()) {
            known = true;
            for (Object c : cols) {
                if (!(c instanceof Map<?, ?> m) || blank(m.get("name"))) { known = false; break; }
                out.putIfAbsent(String.valueOf(m.get("name")).trim().toLowerCase(Locale.ROOT), "dataset:" + name);
            }
        } else {
            known = false;
        }
        if (known && d.get("calculated") instanceof List<?> calc)
            for (Object c : calc)
                if (c instanceof Map<?, ?> m && !blank(m.get("name")))
                    out.putIfAbsent(String.valueOf(m.get("name")).trim().toLowerCase(Locale.ROOT), "dataset:" + name);
        Cols r = known ? new Cols(out) : null;
        datasetCols.put(name, r);
        datasetsResolving.remove(name);
        return r;
    }

    // ── judging the plain COLUMN keys ───────────────────────────────────────────────────────

    /** The set(s) a holder's column link reads; an empty list = unknown. One entry per Reconciliation side. */
    List<Cols> setsFor(String kind, String keyPath, String name, Map<?, ?> doc) {
        return switch (kind) {
            case "dataset" -> one(dataset(name));
            case "widget" -> one(dataset(str(doc.get("datasetId"))));
            case "expectation" -> {
                if (keyPath.equals("refColumn")) yield one(store(str(doc.get("refDataset"))));
                yield one(ruleTarget(doc, true));
            }
            case "decision-rule" -> one(ruleTarget(doc, false));
            case "reconciliation" -> reconSides(doc);
            default -> List.of();
        };
    }

    /** An Expectation's / Decision Rule's subject: {@code targetType} pipeline (the default when absent) names a
     *  store; an Expectation may also target a Dataset. Any other subject (a Job) is unknown. */
    private Cols ruleTarget(Map<?, ?> doc, boolean expectation) {
        String tt = doc.containsKey("targetType") ? String.valueOf(doc.get("targetType")) : "pipeline";
        String target = str(doc.get("target"));
        if ("pipeline".equalsIgnoreCase(tt)) return store(target);
        if (expectation && "dataset".equalsIgnoreCase(tt)) return dataset(target == null ? null : target.trim());
        return null;
    }

    /** Every side whose columns are known; a side with a {@code columnMap} renames its columns, so it is skipped. */
    private List<Cols> reconSides(Map<?, ?> doc) {
        List<String> sides = new ArrayList<>();
        if (doc.get("datasets") instanceof List<?> l) {
            for (Object o : l) if (o != null) sides.add(String.valueOf(o));
        } else {
            for (String k : List.of("leftDataset", "rightDataset", "thirdDataset"))
                if (!blank(doc.get(k))) sides.add(String.valueOf(doc.get(k)));
        }
        Map<?, ?> columnMap = doc.get("columnMap") instanceof Map<?, ?> m ? m : Map.of();
        List<Cols> out = new ArrayList<>();
        for (String s : sides) {
            if (columnMap.get(s) instanceof Map<?, ?> cm && !cm.isEmpty()) return List.of();   // not read here
            Cols c = dataset(s);
            if (c != null) out.add(c);
        }
        return out;
    }

    private static final Set<String> TRIMMED = Set.of("expectation:column", "expectation:refColumn",
            "reconciliation:keyColumns[]");

    /** A COLUMN value against the holder's set(s): DANGLING when a known set lacks it. */
    static LinkGraph.Status judge(LinkCensus.Link l, String value, List<Cols> sets, Set<String> origins) {
        String col = TRIMMED.contains(l.fromKind() + ":" + l.keyPath()) ? value.trim() : value;
        if (sets.isEmpty() || col.startsWith("__")) return LinkGraph.Status.UNCHECKED;
        boolean all = true;
        for (Cols c : sets) {
            String o = c.origin(col);
            if (o == null) all = false;
            else origins.add(o);
        }
        if (!all) origins.clear();
        return all ? LinkGraph.Status.RESOLVED : LinkGraph.Status.DANGLING;
    }

    // ── SQL text ────────────────────────────────────────────────────────────────────────────

    /**
     * The column references of one SQL text held at {@code key}. {@code relations} maps a relation name (lower)
     * to its set, or to {@code null} when the name is known but its columns are not. {@code wrapExpr} reads the
     * text as one expression over a single relation ({@code calculated[].expr}).
     */
    static List<Judged> sql(String key, String text, Function<String, Cols> relations, String exprRelation) {
        String sql = exprRelation == null ? text : "SELECT (" + text + ") FROM " + com.gamma.util.SqlIdent.q(exprRelation);
        JsonNode node = SqlGuard.parseSelectNode(sql);
        if (node == null) return List.of(notAnalysed(key, "the DuckDB parser does not read it as one SELECT"));
        if (!"SELECT_NODE".equals(node.path("type").asText(""))) return List.of(notAnalysed(key, "a " + node.path("type").asText("?") + " is not analysed"));
        String why = unsupported(node, true);
        if (why != null) return List.of(notAnalysed(key, why));

        // relations: alias (or table name) → name
        Map<String, String> byAlias = new LinkedHashMap<>();
        if (!relations(node.path("from_table"), byAlias)) return List.of(notAnalysed(key, "a FROM item is not a named table"));
        Set<String> aliases = new java.util.HashSet<>();
        for (JsonNode item : node.path("select_list")) {
            String a = item.path("alias").asText("");
            if (!a.isEmpty()) aliases.add(a.toLowerCase(Locale.ROOT));
        }
        List<List<String>> refs = new ArrayList<>();
        if (!columnRefs(node, refs)) return List.of(notAnalysed(key, "a column reference has a shape not read here"));

        Map<String, Judged> out = new LinkedHashMap<>();
        for (List<String> parts : refs) {
            String rel, col;
            if (parts.size() == 1) {
                col = parts.get(0);
                if (aliases.contains(col.toLowerCase(Locale.ROOT))) continue;   // an output alias, not a column
                if (byAlias.size() != 1) {
                    out.putIfAbsent("?." + col.toLowerCase(Locale.ROOT), notAnalysed(key, col));
                    continue;
                }
                rel = byAlias.values().iterator().next();
            } else if (parts.size() == 2 && byAlias.containsKey(parts.get(0).toLowerCase(Locale.ROOT))) {
                rel = byAlias.get(parts.get(0).toLowerCase(Locale.ROOT));
                col = parts.get(1);
            } else {
                String joined = String.join(".", parts);
                out.putIfAbsent("?." + joined.toLowerCase(Locale.ROOT), notAnalysed(key, joined));
                continue;
            }
            String dedup = rel + "." + col.toLowerCase(Locale.ROOT);
            if (out.containsKey(dedup)) continue;
            Cols set = relations.apply(rel);
            if (col.startsWith("__")) {
                out.put(dedup, new Judged(key, col, LinkGraph.Status.UNCHECKED, Set.of()));
            } else if (set == null) {
                out.put(dedup, new Judged(key, col, LinkGraph.Status.NOT_ANALYSED, Set.of()));
            } else {
                String o = set.origin(col);
                out.put(dedup, new Judged(key, col, o == null ? LinkGraph.Status.DANGLING : LinkGraph.Status.RESOLVED,
                        o == null ? Set.of() : Set.of(o)));
            }
        }
        return List.copyOf(out.values());
    }

    private static Judged notAnalysed(String key, String what) {
        return new Judged(key, what, LinkGraph.Status.NOT_ANALYSED, Set.of());
    }

    /** Why the tree is outside what is analysed, or {@code null}. */
    private static String unsupported(JsonNode n, boolean root) {
        if (n.isObject()) {
            String type = n.path("type").asText(""), cls = n.path("class").asText("");
            if (!root && (type.endsWith("_NODE"))) return "a nested query is not analysed";
            if (type.equals("SUBQUERY") || cls.equals("SUBQUERY")) return "a subquery is not analysed";
            if (type.equals("LAMBDA") || cls.equals("LAMBDA")) return "a lambda is not analysed";
            if (n.path("cte_map").path("map").size() > 0) return "a CTE is not analysed";
        }
        if (n.isContainerNode())
            for (JsonNode c : n) {
                String why = unsupported(c, false);
                if (why != null) return why;
            }
        return null;
    }

    /** Collect the named tables of a FROM tree; false on any other relation kind. */
    private static boolean relations(JsonNode from, Map<String, String> byAlias) {
        if (from.isMissingNode() || from.isNull()) return true;
        String type = from.path("type").asText("");
        switch (type) {
            case "EMPTY", "EMPTY_FROM" -> { return true; }
            case "JOIN" -> { return relations(from.path("left"), byAlias) && relations(from.path("right"), byAlias); }
            case "BASE_TABLE" -> {
                if (!from.path("catalog_name").asText("").isEmpty() || !from.path("schema_name").asText("").isEmpty())
                    return false;
                String table = from.path("table_name").asText("");
                if (table.isEmpty()) return false;
                // t(a, b) renames the table's columns: not read here
                if (from.path("column_name_alias").size() > 0) return false;
                String alias = from.path("alias").asText("");
                String k = (alias.isEmpty() ? table : alias).toLowerCase(Locale.ROOT);
                if (byAlias.containsKey(k)) return false;
                byAlias.put(k, table.toLowerCase(Locale.ROOT));
                return true;
            }
            default -> { return false; }
        }
    }

    /** Every COLUMN_REF's {@code column_names}; false on a COLUMN_REF without that array (fail closed). */
    private static boolean columnRefs(JsonNode n, List<List<String>> out) {
        if (n.isObject() && "COLUMN_REF".equals(n.path("type").asText(""))) {
            JsonNode names = n.path("column_names");
            if (!names.isArray() || names.isEmpty()) return false;
            List<String> parts = new ArrayList<>();
            for (JsonNode p : names) {
                if (!p.isTextual() || p.asText().isEmpty()) return false;
                parts.add(p.asText());
            }
            out.add(parts);
            return true;
        }
        if (n.isContainerNode())
            for (JsonNode c : n) if (!columnRefs(c, out)) return false;
        return true;
    }

    /** The relation resolver of one SQL holder. */
    Function<String, Cols> relationsFor(String kind, String name, Map<?, ?> doc) {
        return switch (kind) {
            case "enrichment" -> rel -> {
                if (rel.equals("input") && doc.get("input") instanceof Map<?, ?> in && !blank(in.get("database"))) {
                    String id = storeDirs.get(normDir(String.valueOf(in.get("database"))));
                    return id == null ? null : pipelineStores.get(id);
                }
                if (doc.get("references") instanceof Map<?, ?> refs)
                    for (Map.Entry<?, ?> e : refs.entrySet())
                        if (String.valueOf(e.getKey()).equalsIgnoreCase(rel) && e.getValue() instanceof Map<?, ?> r
                                && !blank(r.get("ref")))
                            return pipelineStores.get(String.valueOf(r.get("ref")).trim().toLowerCase(Locale.ROOT));
                return null;
            };
            case "query" -> rel -> {
                String ds = str(doc.get("datasetId"));
                return ds != null && ds.equalsIgnoreCase(rel) ? dataset(ds) : null;
            };
            case "dataset" -> rel -> {
                String sn = str(doc.get("sourceName"));
                return sn != null && sn.trim().equalsIgnoreCase(rel) ? store(sn) : null;
            };
            default -> rel -> null;
        };
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private static List<Cols> one(Cols c) {
        return c == null ? List.of() : List.of(c);
    }

    private static String normDir(String d) {
        String s = d.trim().replace('\\', '/');
        while (s.startsWith("./")) s = s.substring(2);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.toLowerCase(Locale.ROOT);
    }

    private static void collect(Object node, String key, List<String> out) {
        if (node instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (key.equals(String.valueOf(e.getKey())) && e.getValue() instanceof String s && !s.isBlank()) out.add(s);
                collect(e.getValue(), key, out);
            }
        } else if (node instanceof List<?> l) {
            for (Object o : l) collect(o, key, out);
        }
    }

    private static String str(Object v) {
        return blank(v) ? null : String.valueOf(v);
    }

    private static boolean blank(Object v) {
        return v == null || v.toString().isBlank();
    }
}
