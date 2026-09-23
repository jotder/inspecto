package com.gamma.service;

import com.gamma.config.io.ConfigCodec;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.TypeFlow;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.service.AffectedPipelines.Change;
import com.gamma.service.AffectedPipelines.Hit;
import com.gamma.service.AffectedPipelines.Status;
import com.gamma.service.AffectedPipelines.Tier;
import com.gamma.service.AffectedPipelines.Verdict;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The reader-dependent half of {@link AffectedPipelines} ({@code DUCKLE-C7-AFFECTED-CONTRACTS-1}): for each
 * Pipeline a change reaches <em>directly</em>, diff its output columns before and after the change and judge
 * each removed or retyped column against the readers that exist in config. Exactly four outcomes, never
 * collapsed: {@link Tier#BREAKING} (a reader reads it), {@link Tier#POSSIBLY_BREAKING} (no reader in config
 * reads it — never "compatible", a reader outside config may), {@link Tier#REVALIDATE} (a reader's column use,
 * or the producer's output, cannot be determined — downstream of a transform there is no column lineage) and
 * {@link Tier#ADDITIVE} (a new column, which is not a contract break).
 *
 * <p>Nothing here parses SQL. Output columns come from {@link TypeFlow#sinkColumns} (DuckDB {@code DESCRIBE}
 * over the SELECT the engine runs); a reader's column use comes only from structured config (a parquet
 * consumer's {@code raw.fields[].selector}, a Dataset's declared {@code columns[]}, a Widget's
 * {@code controls.*.field}). Anything else is REVALIDATE, with the reason.
 */
final class ContractVerdicts {

    private ContractVerdicts() {
    }

    /** A column set, or why it is unknown. Names keyed lower-case (DuckDB identifiers are case-insensitive). */
    private record Cols(Map<String, String> byName, String unknown) {
        static Cols unknown(String why) {
            return new Cols(null, why);
        }
    }

    /** What a reader reads: a set of lower-cased column names, or why that is unknown. */
    private record Reads(String reader, Set<String> columns, String unknown) {
    }

    private final static class Ctx {
        final Path root;
        final ConfigRegistry registry;
        final Map<Path, Change> changed = new LinkedHashMap<>();
        final Map<String, List<String>> consumers;
        final List<ComponentRegistry.Component> datasets;
        final List<ComponentRegistry.Component> widgets;
        final Map<String, List<String>> datasetsOf = new HashMap<>();
        final List<Verdict> out = new ArrayList<>();

        Ctx(Path root, ConfigRegistry registry, List<Change> changes, Map<String, List<String>> consumers) {
            this.root = root;
            this.registry = registry;
            for (Change c : changes) this.changed.put(c.file(), c);
            this.consumers = consumers;
            ComponentRegistry reg = ComponentRegistry.scan(root.resolve("registry"));
            this.datasets = reg.ofType("dataset");
            this.widgets = reg.ofType("widget");
        }

        List<String> datasetsOf(String pipeline) {
            return datasetsOf.computeIfAbsent(pipeline, p -> PipelineDependents.scan(root, p).dependents().stream()
                    .filter(d -> "dataset".equals(d.kind())).map(PipelineDependents.Dependent::name).toList());
        }
    }

    /**
     * @param changes   the changes that are real (formatting-only edits already dropped)
     * @param hits      every reached Pipeline; the ones whose chain is {@code file → pipeline} are judged as producers
     * @param consumers Dataset id → the Pipelines whose {@code collector.dataset} names it
     */
    static List<Verdict> judge(Path root, ConfigRegistry registry, List<Change> changes,
                               Map<String, Hit> hits, Map<String, List<String>> consumers) {
        Ctx ctx = new Ctx(root, registry, changes, consumers);
        for (Hit h : hits.values()) {
            if (h.chain().size() == 2) producer(ctx, h.pipeline());
        }
        datasetDefinitions(ctx);
        return List.copyOf(ctx.out);
    }

    /** One directly-changed Pipeline: deleted, unloadable, new, or diffable. */
    private static void producer(Ctx ctx, String id) {
        ConfigRegistry.Entry e = ctx.registry.all().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
        if (e == null) {
            boolean fails = ctx.registry.failures().stream().anyMatch(f -> id.equals(f.name()));
            if (fails) {
                everyReader(ctx, id, Tier.REVALIDATE, null,
                        "producer Pipeline '" + id + "' no longer loads, so its output columns cannot be derived");
            } else {
                everyReader(ctx, id, Tier.BREAKING, null,
                        "producer Pipeline '" + id + "' is deleted; nothing writes this Dataset any more");
            }
            return;
        }
        Change own = ctx.changed.get(e.path().toAbsolutePath().normalize());
        if (own != null && own.status() == Status.ADDED) return;   // a new producer has no prior contract

        Cols after = columns(e.config(), null);
        Cols before;
        PipelineConfig beforeCfg = e.config();
        String beforeWhy = null;
        if (own != null) {
            if (own.before() == null) {
                beforeWhy = "its pre-change content is unavailable";
            } else {
                try {
                    beforeCfg = PipelineConfig.fromMap(ConfigCodec.toMap(own.before()), e.path().getParent());
                } catch (Exception ex) {
                    beforeWhy = "its pre-change config does not parse: " + ex.getMessage();
                }
            }
        }
        if (beforeWhy == null) {
            Map<String, Object> override = null;
            for (Path f : beforeCfg.referencedFiles()) {
                if (f == null) continue;
                Change ch = ctx.changed.get(f.toAbsolutePath().normalize());
                if (ch == null) continue;
                if (ch.before() != null && isSelfContainedSchema(f, beforeCfg)) {
                    try {
                        override = ConfigCodec.toMap(ch.before());
                    } catch (Exception ex) {
                        beforeWhy = "the pre-change schema does not decode: " + ex.getMessage();
                    }
                } else {
                    beforeWhy = "'" + f.getFileName() + "' changed and is not a self-contained schema file, "
                            + "so the pre-change output is not reconstructed";
                }
            }
            before = beforeWhy == null ? columns(beforeCfg, override) : Cols.unknown(beforeWhy);
        } else {
            before = Cols.unknown(beforeWhy);
        }

        if (before.unknown() != null || after.unknown() != null) {
            String why = after.unknown() != null ? after.unknown() : before.unknown();
            everyReader(ctx, id, Tier.REVALIDATE, null,
                    "the output columns of '" + id + "' cannot be compared: " + why);
            return;
        }

        Set<String> tainted = new LinkedHashSet<>();
        for (Map.Entry<String, String> col : before.byName().entrySet()) {
            String name = col.getKey();
            String now = after.byName().get(name);
            String change;
            if (now == null) change = "removed";
            else if (!Objects.equals(col.getValue(), now) && col.getValue() != null && now != null)
                change = "retyped " + col.getValue() + " -> " + now;
            else continue;
            boolean read = false;
            for (String ds : ctx.datasetsOf(id)) {
                for (Reads r : readers(ctx, ds)) {
                    if (r.columns() == null) {
                        ctx.out.add(new Verdict(Tier.REVALIDATE, "pipeline:" + id, name, r.reader(),
                                "column " + change + "; which columns " + r.reader() + " reads is not determined: "
                                        + r.unknown()));
                        taint(tainted, r.reader());
                    } else if (r.columns().contains(name)) {
                        ctx.out.add(new Verdict(Tier.BREAKING, "pipeline:" + id, name, r.reader(),
                                "column " + change + " and " + r.reader() + " reads it (via dataset:" + ds + ")"));
                        taint(tainted, r.reader());
                        read = true;
                    }
                }
            }
            if (!read) {
                ctx.out.add(new Verdict(Tier.POSSIBLY_BREAKING, "pipeline:" + id, name, null, "column " + change
                        + "; no reader in config reads it, but a reader outside config (a query, a BI tool, "
                        + "an export) may"));
            }
        }
        for (String name : after.byName().keySet()) {
            if (!before.byName().containsKey(name)) {
                ctx.out.add(new Verdict(Tier.ADDITIVE, "pipeline:" + id, name, null, "column added; not a contract break"));
            }
        }
        downstream(ctx, id, tainted);
    }

    private static void taint(Set<String> tainted, String reader) {
        if (reader.startsWith("pipeline:")) tainted.add(reader.substring("pipeline:".length()));
    }

    /** One tier for every reader of every Dataset {@code id} produces; pipeline readers taint their downstream. */
    private static void everyReader(Ctx ctx, String id, Tier tier, String column, String reason) {
        Set<String> tainted = new LinkedHashSet<>();
        for (String ds : ctx.datasetsOf(id)) {
            ctx.out.add(new Verdict(tier, "pipeline:" + id, column, "dataset:" + ds, reason));
            for (Reads r : readers(ctx, ds)) {
                if (r.reader().startsWith("dataset:")) continue;
                ctx.out.add(new Verdict(tier, "pipeline:" + id, column, r.reader(), reason));
                taint(tainted, r.reader());
            }
        }
        downstream(ctx, id, tainted);
    }

    /**
     * Everything behind a consuming Pipeline that is itself broken or unjudgeable is REVALIDATE: its own output
     * may have changed, and there is no column lineage through it to say how.
     */
    private static void downstream(Ctx ctx, String producer, Set<String> tainted) {
        Set<String> seen = new HashSet<>(tainted);
        seen.add(producer);
        Deque<String> queue = new ArrayDeque<>(tainted);
        while (!queue.isEmpty()) {
            String via = queue.poll();
            for (String ds : ctx.datasetsOf(via)) {
                for (Reads r : readers(ctx, ds)) {
                    String reader = r.reader();
                    if (reader.startsWith("pipeline:") && !seen.add(reader.substring("pipeline:".length()))) continue;
                    ctx.out.add(new Verdict(Tier.REVALIDATE, "pipeline:" + producer, null, reader, "downstream of pipeline:"
                            + via + ", whose contract with '" + producer + "' changed; column lineage past a "
                            + "consuming Pipeline is not traced"));
                    if (reader.startsWith("pipeline:")) queue.add(reader.substring("pipeline:".length()));
                }
            }
        }
    }

    /** A deleted Dataset breaks everything reading it; a contract-relevant edit asks them to revalidate. */
    private static void datasetDefinitions(Ctx ctx) {
        Path dir = ctx.root.resolve("registry").resolve("datasets");
        for (Change ch : ctx.changed.values()) {
            if (ch.file().getParent() == null || !ch.file().getParent().equals(dir)) continue;
            String id = ctx.datasets.stream().filter(c -> c.path().toAbsolutePath().normalize().equals(ch.file()))
                    .map(ComponentRegistry.Component::name).findFirst().orElse(null);
            Map<String, Object> before = null;
            try {
                if (ch.before() != null) before = ConfigCodec.toMap(ch.before());
            } catch (Exception ignored) {
                // undecodable pre-change content: treated as unknown below
            }
            if (id == null) id = before != null && before.get("name") != null
                    ? String.valueOf(before.get("name")).trim() : stem(ch.file());
            String subject = "dataset:" + id;
            if (ch.status() == Status.DELETED) {
                for (Reads r : readers(ctx, id)) {
                    if (r.reader().startsWith("dataset:")) continue;
                    ctx.out.add(new Verdict(Tier.BREAKING, subject, null, r.reader(), "Dataset '" + id + "' is deleted"));
                }
            } else if (ch.status() == Status.MODIFIED) {
                String id0 = id;
                Map<String, Object> now = ctx.datasets.stream().filter(c -> c.name().equals(id0))
                        .map(ComponentRegistry.Component::content).findFirst().orElse(Map.of());
                List<String> contractKeys = List.of("physicalRef", "sourceName", "columns", "calculated");
                Map<String, Object> was = before;
                boolean contract = was == null
                        || contractKeys.stream().anyMatch(k -> !Objects.equals(was.get(k), now.get(k)));
                if (!contract) continue;   // metadata-only (description, tags): the readers' contract is untouched
                for (Reads r : readers(ctx, id)) {
                    if (r.reader().startsWith("dataset:")) continue;
                    ctx.out.add(new Verdict(Tier.REVALIDATE, subject, null, r.reader(), "Dataset '" + id
                            + "' changed its physicalRef / sourceName / columns / calculated; that edit is not "
                            + "diffed column by column"));
                }
            }
        }
    }

    /** Every reader of Dataset {@code ds} that config names: the Dataset itself, consumer Pipelines, Widgets. */
    private static List<Reads> readers(Ctx ctx, String ds) {
        List<Reads> out = new ArrayList<>();
        ctx.datasets.stream().filter(c -> c.name().equals(ds)).findFirst().ifPresent(c -> out.add(datasetReads(c)));
        for (String p : ctx.consumers.getOrDefault(ds, List.of())) {
            PipelineConfig cfg = ctx.registry.get(p).orElse(null);
            out.add(cfg == null ? new Reads("pipeline:" + p, null, "it does not load")
                    : consumerReads("pipeline:" + p, cfg));
        }
        for (ComponentRegistry.Component w : ctx.widgets) {
            Object dsId = w.content().get("datasetId");
            if (dsId != null && ds.equals(String.valueOf(dsId).trim())) out.add(widgetReads(w));
        }
        return out;
    }

    /** A Dataset reads its declared {@code columns[].name}; a {@code calculated} expression is free SQL. */
    private static Reads datasetReads(ComponentRegistry.Component c) {
        String who = "dataset:" + c.name();
        Map<String, Object> m = c.content();
        if (m.get("calculated") instanceof List<?> l && !l.isEmpty())
            return new Reads(who, null, "its calculated columns are free SQL");
        Set<String> cols = new LinkedHashSet<>();
        if (m.get("columns") instanceof List<?> l) {
            for (Object o : l) if (o instanceof Map<?, ?> col && col.get("name") != null) cols.add(lower(col.get("name")));
        }
        return new Reads(who, cols, null);
    }

    /**
     * A {@code connector: dataset} consumer reads what its schema selects: on the parquet frontend a raw field's
     * {@code selector} IS the parquet column name ({@code DuckDbCsvIngester.buildParquetReadSpec}). Any other
     * frontend or schema shape is not determined here.
     */
    private static Reads consumerReads(String who, PipelineConfig cfg) {
        Map<String, Object> schema = cfg.schemas().single();
        if (cfg.parquet() == null || schema == null)
            return new Reads(who, null, "it does not read the Dataset through a single parquet schema, so its "
                    + "selectors are not column names");
        Set<String> cols = new LinkedHashSet<>();
        if (schema.get("raw") instanceof Map<?, ?> raw && raw.get("fields") instanceof List<?> fields) {
            for (Object f : fields) {
                if (f instanceof Map<?, ?> fm && fm.get("selector") != null) cols.add(lower(fm.get("selector")));
            }
        }
        return new Reads(who, cols, null);
    }

    /** A Widget reads its {@code controls.*.field}s; a saved query (free SQL) or no controls is not determined. */
    private static Reads widgetReads(ComponentRegistry.Component w) {
        String who = "widget:" + w.name();
        Map<String, Object> m = w.content();
        if (m.get("queryId") != null) return new Reads(who, null, "it reads through a saved query (free SQL)");
        if (!(m.get("controls") instanceof Map<?, ?> controls) || controls.isEmpty())
            return new Reads(who, null, "it names no controls, so it shows the Dataset's columns wholesale");
        Set<String> cols = new LinkedHashSet<>();
        for (Object v : controls.values()) {
            if (v instanceof Map<?, ?> one && one.get("field") != null) cols.add(lower(one.get("field")));
            if (v instanceof List<?> many)
                for (Object o : many) if (o instanceof Map<?, ?> one && one.get("field") != null) cols.add(lower(one.get("field")));
        }
        return new Reads(who, cols, null);
    }

    /**
     * The columns a Pipeline writes, from {@link TypeFlow#sinkColumns} over its single schema (or
     * {@code schemaOverride}, a pre-change schema). Only a chain of {@code filter} / {@code dedup} / {@code lookup}
     * keeps the shape known — the same rule {@code ConfigRoutes.walkSteps} applies at save.
     */
    private static Cols columns(PipelineConfig cfg, Map<String, Object> schemaOverride) {
        List<String> lookupTargets = new ArrayList<>();
        for (PipelineConfig.Step s : cfg.steps()) {
            String kind = s.kind();
            if (PipelineConfig.Step.FILTER.equals(kind) || PipelineConfig.Step.DEDUP.equals(kind)) continue;
            if (PipelineConfig.Step.LOOKUP.equals(kind)) {
                if (s.config() != null && s.config().get("target") != null) lookupTargets.add(String.valueOf(s.config().get("target")));
                continue;
            }
            return Cols.unknown("a '" + kind + "' step reshapes its rows and there is no column lineage through it");
        }
        PipelineConfig.Schemas sc = cfg.schemas();
        if (sc.single() == null)
            return Cols.unknown(sc.segments() != null || sc.selector() != null
                    ? "it writes more than one schema; this slice diffs a single-schema Pipeline"
                    : "it declares no schema");
        Map<String, Object> schema = schemaOverride != null ? schemaOverride : sc.single();
        boolean typed = sc.ingesterClass() != null && !sc.ingesterClass().isBlank();
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (TypeFlow.Column c : TypeFlow.sinkColumns(schema, cfg, typed)) out.put(lower(c.name()), c.type());
        } catch (RuntimeException ex) {
            return Cols.unknown("its schema does not compile to a transform: " + ex.getMessage());
        }
        for (String t : lookupTargets) out.putIfAbsent(lower(t), "VARCHAR");
        return new Cols(out, null);
    }

    /** Is {@code file} the Pipeline's whole single schema (no sibling structure / mapping merged in)? */
    private static boolean isSelfContainedSchema(Path file, PipelineConfig cfg) {
        if (cfg.schemas().single() == null || !Files.isRegularFile(file)) return false;
        try {
            return com.gamma.util.ToonHelper.load(file.toString()).equals(cfg.schemas().single());
        } catch (Exception e) {
            return false;
        }
    }

    private static String lower(Object o) {
        return String.valueOf(o).trim().toLowerCase(Locale.ROOT);
    }

    private static String stem(Path p) {
        String n = p.getFileName().toString();
        return n.endsWith(".toon") ? n.substring(0, n.length() - ".toon".length()) : n;
    }
}
