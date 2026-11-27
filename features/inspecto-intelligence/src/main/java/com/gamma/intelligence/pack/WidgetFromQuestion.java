package com.gamma.intelligence.pack;

import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.gamma.access.ComponentAccess;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.mask.EvidenceMasker;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.MeasureCompiler;
import com.gamma.query.ResultSetDescriptor;
import com.gamma.sql.SqlGuard;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code widget_from_question} (BI-INSIGHTS-1 P4, AQ-0; design {@code 2026-11-27-bi-insights-p4-language-plan.md} §6):
 * Ask the Dashboard. The model never states a number and never writes SQL: it fills a CLOSED vocabulary (at most
 * {@link #MAX_BREAKDOWNS} breakdowns, filters, one trailing window, one comparison, top-N) over the Datasets the
 * caller may view, and this class validates it, builds the {@code /bi/query}-shaped body, runs it through
 * {@link MeasureCompiler#parse} and the SQL guard, and returns a DRAFT Widget plus a templated one-line caption.
 * Nothing is persisted here: the Widget is saved only through the gated {@code component_apply}.
 *
 * <p>Two calls, one tool. With no {@code measures} it returns the VOCABULARY the model may use: per visible Dataset,
 * each column's name, role, type and the aggregates its role allows — NO values and no rows (it reads only the
 * Dataset's stored column list); a column the Space masks is listed by name and flagged {@code masked}. With
 * {@code measures} it builds the draft or refuses in words.
 *
 * <p><b>Who may see what.</b> The tool call carries capabilities, not a user id ({@link ToolCaller}), so a restricted
 * Dataset (one with a share list) is visible only to a caller who holds {@code canConfigureAccess}
 * ({@link ComponentAccess#canViewAs}); an owner or a share-listed user is not recognised and the Dataset is hidden —
 * fail closed. A caller with no Subject ({@link ToolCaller#UNRESTRICTED}, Basic) sees every Dataset; an unbound call
 * sees only unrestricted ones. A masked column is refused as {@code /bi/query} refuses it unless the caller may
 * reveal ({@link EgressMasking#REVEAL}).
 */
final class WidgetFromQuestion {

    static final String TOOL = "widget_from_question";
    static final int MAX_BREAKDOWNS = 2;
    static final int MAX_TOP_N = 50;

    /** Aggregates any column may take. */
    private static final List<String> ANY = List.of("count", "countDistinct");
    /** Plus min / max for a date (they order dates, not sum them). */
    private static final List<String> ORDERED = List.of("count", "countDistinct", "min", "max");
    private static final List<String> GRAINS = List.of("auto", "day", "week", "month");
    private static final List<String> COMPARES = List.of("previous", "lastYear", "average");
    private static final List<String> OPS = List.of("=", "!=", ">", ">=", "<", "<=", "like", "in", "isNull", "notNull");
    private static final Set<String> NUMERIC = Set.copyOf(MeasureCompiler.NUMERIC_AGGS);
    private static final Set<String> REVEALING = Set.of("min", "max", "sum", "avg", "median", "stddev", "variance",
            "p05", "p25", "p75", "p90", "p95", "p99");
    private static final List<String> MEASURE_AGGS;

    static {
        Set<String> all = new LinkedHashSet<>(ORDERED);
        all.addAll(MeasureCompiler.NUMERIC_AGGS);
        MEASURE_AGGS = List.copyOf(all);
    }

    private static final Pattern WHY = Pattern.compile("(?i)\\b(why|what caused|reason for|root cause|due to)\\b");
    private static final Pattern FORECAST = Pattern.compile(
            "(?i)\\b(forecast|predict|projection|project(ed)?|will be|next (week|month|quarter|year)|expected)\\b");
    private static final Pattern JOIN = Pattern.compile("(?i)\\b(join|joined|combine (the )?datasets?|across datasets)\\b");

    /** A refusal in words; {@code code} is the machine form the UI keys its reply on. */
    private record Refusal(String code, String message) {}

    private WidgetFromQuestion() {
    }

    /** The visible Dataset: its id, config, and its columns as the model may see them. */
    private record Visible(String id, Map<String, Object> config, List<Col> columns) {
        Col column(String name) {
            for (Col c : columns) if (c.name.equalsIgnoreCase(name)) return c;
            return null;
        }
    }

    private record Col(String name, String role, String type, boolean masked) {
        /** What the model is told: a masked column is only ever counted (what {@code /bi/query} lets a non-revealer do). */
        List<String> listed() {
            return masked ? ANY : aggregates();
        }

        List<String> aggregates() {
            return switch (role) {
                case "measure" -> "number".equals(type) ? MEASURE_AGGS : ANY;
                case "temporal" -> ORDERED;
                default -> ANY;
            };
        }
    }

    static ToolResult invoke(ToolCall call, ComponentStore components) {
        if (components == null) {
            // No registry means no Dataset anyone could view: the same answer as any unknown column, never a draft.
            return refuse(new Refusal("no-visible-dataset", "I can't see any dataset to ask about"));
        }
        ToolCaller caller = ToolCaller.currentOr(new ToolCaller(Set.of()));   // unbound = fail closed
        boolean reveal = caller.holds(EgressMasking.REVEAL);
        Map<String, Object> args = call.arguments() == null ? Map.of() : call.arguments();

        List<Visible> visible = visibleDatasets(components, caller, stringList(args.get("preferred")));
        if (!(args.get("measures") instanceof List<?> ms) || ms.isEmpty()) {
            return new ToolResult(true, vocabulary(visible), null, Map.of());
        }

        Refusal early = screen(args);
        if (early != null) return refuse(early);
        try {
            return build(args, visible, reveal);
        } catch (Refused r) {
            return refuse(r.refusal);
        }
    }

    // ── the vocabulary (what the model sees) ────────────────────────────────────

    private static Map<String, Object> vocabulary(List<Visible> visible) {
        List<Map<String, Object>> datasets = new ArrayList<>();
        for (Visible d : visible) {
            List<Map<String, Object>> cols = new ArrayList<>();
            for (Col c : d.columns) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", c.name);
                if (c.masked) {
                    m.put("masked", true);   // by name only: no role, type or values
                } else {
                    m.put("role", c.role);
                    m.put("type", c.type);
                }
                m.put("aggregates", c.listed());
                cols.add(m);
            }
            datasets.add(Map.of("id", d.id, "columns", cols));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("datasets", datasets);
        out.put("maxBreakdowns", MAX_BREAKDOWNS);
        out.put("grains", GRAINS);
        out.put("filterOps", OPS);
        out.put("compare", COMPARES);
        out.put("maxTopN", MAX_TOP_N);
        out.put("window", "trailing days over one temporal column: {field?, days}");
        return out;
    }

    private static List<Visible> visibleDatasets(ComponentStore store, ToolCaller caller, List<String> preferred) {
        Path configRoot = store.root().getParent();
        ViewStore views = new ViewStore(configRoot.resolve("views"));
        List<Visible> out = new ArrayList<>();
        for (ComponentRegistry.Component c : store.list("dataset")) {
            if (!viewable(caller, c.content(), configRoot)) continue;
            Set<String> masked;
            try {
                masked = EvidenceMasker.sensitiveColumns(store, views, c.name());
            } catch (RuntimeException unreadable) {
                masked = Set.of(EvidenceMasker.UNKNOWN_LINEAGE);   // cannot trace -> treat every undeclared column as masked
            }
            out.add(new Visible(c.name(), c.content(), columns(c.content(), masked)));
        }
        out.sort((a, b) -> {
            int pa = preferred.indexOf(a.id), pb = preferred.indexOf(b.id);
            if (pa < 0) pa = Integer.MAX_VALUE;
            if (pb < 0) pb = Integer.MAX_VALUE;
            return pa != pb ? Integer.compare(pa, pb) : a.id.compareTo(b.id);
        });
        return out;
    }

    private static boolean viewable(ToolCaller caller, Map<String, Object> content, Path configRoot) {
        if (caller.capabilities() == null) return true;   // no Subject (Basic) or a system caller
        return ComponentAccess.canViewAs(null, caller.capabilities(), content, configRoot)
                == ComponentAccess.AsOwner.ALLOWED;
    }

    private static List<Col> columns(Map<String, Object> dataset, Set<String> masked) {
        boolean all = masked.contains(EvidenceMasker.UNKNOWN_LINEAGE);
        List<Col> out = new ArrayList<>();
        if (!(dataset.get("columns") instanceof List<?> cols)) return out;
        for (Object o : cols) {
            if (!(o instanceof Map<?, ?> c) || c.get("name") == null) continue;
            String name = String.valueOf(c.get("name")).trim();
            if (name.isEmpty() || Boolean.TRUE.equals(c.get("hidden"))) continue;
            String type = ResultSetDescriptor.columnType(c.get("type") == null ? null : String.valueOf(c.get("type")));
            String role = roleOf(c.get("role"), name, type);
            boolean isMasked = masked.contains(name.toLowerCase(Locale.ROOT))
                    || (all && c.get("classification") == null);
            out.add(new Col(name, role, type, isMasked));
        }
        return out;
    }

    /** The stored role folded to the three a chart binds by; absent, derived from the type as the UI does. */
    private static String roleOf(Object stored, String name, String type) {
        String r = stored == null ? "" : String.valueOf(stored).trim().toLowerCase(Locale.ROOT);
        if (r.equals("measure") || r.equals("temporal")) return r;
        if (!r.isEmpty()) return "dimension";   // entity, detail, dimension
        if ("date".equals(type)) return "temporal";
        if ("number".equals(type) && !name.toLowerCase(Locale.ROOT).matches("(.*_)?id")) return "measure";
        return "dimension";
    }

    // ── refusals that need no Dataset ───────────────────────────────────────────

    private static Refusal screen(Map<String, Object> args) {
        String intent = str(args.get("intent"));
        String question = str(args.get("question"));
        if ("cause".equals(intent) || (question != null && WHY.matcher(question).find()))
            return new Refusal("cause", "Ask doesn't explain why something moved - use Explain this change on the tile");
        if ("forecast".equals(intent) || (question != null && FORECAST.matcher(question).find()))
            return new Refusal("forecast", "Ask doesn't author forecasts - add a forecast band from the tile's suggestions");
        if ("join".equals(intent) || "calculated".equals(intent) || (question != null && JOIN.matcher(question).find()))
            return unsupported("joins and calculated measures are not supported");
        return null;
    }

    private static Refusal unsupported(String what) {
        return new Refusal("unsupported", what + ". Supported: up to " + MAX_BREAKDOWNS + " breakdowns, filters, a "
                + "trailing window, one comparison (" + COMPARES + ") and a top-N over one dataset, with aggregates "
                + MEASURE_AGGS);
    }

    // ── building the draft ──────────────────────────────────────────────────────

    private static final class Refused extends RuntimeException {
        final transient Refusal refusal;

        Refused(String code, String message) {
            super(message, null, false, false);
            this.refusal = new Refusal(code, message);
        }
    }

    private static ToolResult build(Map<String, Object> args, List<Visible> visible, boolean reveal) {
        List<Map<String, Object>> measuresIn = mapList(args.get("measures"));
        List<String> groupBy = stringList(args.get("groupBy"));
        if (groupBy.size() > MAX_BREAKDOWNS)
            throw new Refused("unsupported", unsupported("more than " + MAX_BREAKDOWNS + " breakdowns").message());
        List<Map<String, Object>> filtersIn = mapList(args.get("filters"));
        Map<String, Object> windowIn = args.get("window") instanceof Map<?, ?> w ? cast(w) : null;

        // every column the question names, to find the Dataset when none was named
        List<String> named = new ArrayList<>(groupBy);
        for (Map<String, Object> m : measuresIn) if (str(m.get("field")) != null) named.add(str(m.get("field")));
        for (Map<String, Object> f : filtersIn) if (str(f.get("field")) != null) named.add(str(f.get("field")));
        Visible ds = pickDataset(str(args.get("dataset")), named, visible);

        // measures
        List<Map<String, Object>> measures = new ArrayList<>();
        for (Map<String, Object> m : measuresIn) {
            String agg = canonicalAgg(str(m.get("agg")));
            if (agg == null)
                throw new Refused("unsupported-aggregate", "'" + str(m.get("agg")) + "' is not a supported aggregate; use "
                        + MEASURE_AGGS);
            String field = str(m.get("field"));
            if (!"count".equals(agg)) {
                Col c = require(ds, field);
                refuseMasked(c, agg + " over", reveal || !REVEALING.contains(agg));   // count / countDistinct stay allowed
                if (!c.aggregates().contains(agg))
                    throw new Refused("unsupported-aggregate", "'" + agg + "' does not suit " + c.role() + " column '"
                            + c.name + "'; its aggregates are " + c.aggregates());
                field = c.name;
            } else field = null;
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("agg", agg);
            if (field != null) mm.put("field", field);
            measures.add(mm);
        }

        // breakdowns, grain
        List<String> groups = new ArrayList<>();
        for (String g : groupBy) groups.add(require(ds, g).name);
        String grain = str(args.get("grain"));
        if (grain != null && !GRAINS.contains(grain))
            throw new Refused("unsupported", "unknown time grain '" + grain + "'; use " + GRAINS);
        Map<String, String> grains = new LinkedHashMap<>();
        String temporalGroup = null;
        for (String g : groups) if ("temporal".equals(ds.column(g).role)) temporalGroup = temporalGroup == null ? g : temporalGroup;
        if (grain != null && !"auto".equals(grain)) {
            if (temporalGroup == null)
                throw new Refused("unsupported", "a time grain needs a date column among the breakdowns");
            grains.put(temporalGroup, grain);
        }

        // filters: the value is carried verbatim - never matched, corrected or guessed
        List<Map<String, Object>> filters = new ArrayList<>();
        for (Map<String, Object> f : filtersIn) {
            Col c = require(ds, str(f.get("field")));
            refuseMasked(c, "filtering", reveal);
            String op = str(f.get("op")) == null ? "=" : str(f.get("op"));
            if (!OPS.contains(op))
                throw new Refused("unsupported", "unknown filter operator '" + op + "'; use " + OPS);
            Map<String, Object> ff = new LinkedHashMap<>();
            ff.put("field", c.name);
            ff.put("op", op);
            if (f.containsKey("value")) ff.put("value", f.get("value"));
            filters.add(ff);
        }

        // trailing window
        Map<String, Object> window = null;
        if (windowIn != null) {
            Object days = windowIn.get("days");
            if (!(days instanceof Number n) || n.intValue() != n.doubleValue() || n.intValue() < 1)
                throw new Refused("unsupported", "a trailing window needs a whole number of days");
            String field = str(windowIn.get("field"));
            Col c;
            if (field != null) c = require(ds, field);
            else {
                List<Col> temporal = ds.columns.stream().filter(x -> "temporal".equals(x.role) && !x.masked).toList();
                if (temporal.size() != 1)
                    throw new Refused("which-column", "which date column should the window use? "
                            + temporal.stream().map(Col::name).toList());
                c = temporal.get(0);
            }
            if (!"temporal".equals(c.role))
                throw new Refused("unsupported", "a window needs a date column; '" + c.name + "' is " + c.role);
            refuseMasked(c, "windowing", reveal);
            window = new LinkedHashMap<>();
            window.put("field", c.name);
            window.put("days", n.intValue());
            window.put("asOf", str(windowIn.get("asOf")) != null ? str(windowIn.get("asOf")) : LocalDate.now().toString());
        }

        // compare
        Map<String, Object> compare = null;
        String cmp = str(args.get("compare"));
        if (cmp != null) {
            if (!COMPARES.contains(cmp))
                throw new Refused("unsupported", "unknown comparison '" + cmp + "'; use " + COMPARES);
            compare = Map.of("kind", cmp);
        }

        // top N
        Integer topN = null;
        if (args.get("topN") != null) {
            if (!(args.get("topN") instanceof Number n) || n.intValue() != n.doubleValue()
                    || n.intValue() < 1 || n.intValue() > MAX_TOP_N)
                throw new Refused("unsupported", "topN must be a whole number from 1 to " + MAX_TOP_N);
            topN = n.intValue();
        }
        String order = str(args.get("order"));
        if (order != null && !order.equals("asc") && !order.equals("desc"))
            throw new Refused("unsupported", "order must be asc or desc");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataset", ds.id);
        body.put("measures", measures);
        if (!groups.isEmpty()) body.put("groupBy", groups);
        if (!grains.isEmpty()) body.put("grains", grains);
        if (!filters.isEmpty()) body.put("filters", filters);
        if (window != null) body.put("window", window);
        if (compare != null) body.put("compare", compare);
        if (topN != null) {
            if (groups.isEmpty()) throw new Refused("unsupported", "topN needs a breakdown to rank");
            body.put("limit", topN);
            MeasureCompiler.Measure first = new MeasureCompiler.Measure(
                    (String) measures.get(0).get("agg"), (String) measures.get(0).get("field"));
            body.put("orderBy", List.of(Map.of("field", first.id(), "dir", order == null ? "desc" : order)));
        } else if (order != null && !groups.isEmpty()) {
            MeasureCompiler.Measure first = new MeasureCompiler.Measure(
                    (String) measures.get(0).get("agg"), (String) measures.get(0).get("field"));
            body.put("orderBy", List.of(Map.of("field", first.id(), "dir", order)));
        }

        // the same parse + compile + guard /bi/query runs; a refusal is the grammar's own, in words
        MeasureCompiler.Spec spec;
        try {
            spec = MeasureCompiler.parse(body, 1000, 10_000);
            List<Finding> findings = SqlGuard.check(MeasureCompiler.compile(spec).sql());
            if (!findings.isEmpty()) throw new Refused("invalid", "the question compiles to a query the SQL guard refuses");
        } catch (IllegalArgumentException bad) {
            throw new Refused("invalid", bad.getMessage());
        }

        String caption = caption(measures, groups, grain, filters, window, compare, topN, order);
        Map<String, Object> widget = widget(ds.id, measures, groups, grains, temporalGroup, compare, topN, caption);
        List<Finding> findings = ConfigLoader.filesystem().validate(ConfigSpecs.widget(), widget);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "widget");
        result.put("id", null);
        result.put("clean", findings.isEmpty());
        result.put("findings", findings.stream().map(f -> Map.of("path", f.fieldPath(),
                "message", f.message())).toList());
        result.put("draft", widget);
        result.put("query", body);       // what the preview runs through POST /bi/query, as the caller
        result.put("caption", caption);  // templated; there is no model-written answer
        result.put("persisted", false);
        return new ToolResult(true, result, null, Map.of());
    }

    private static Visible pickDataset(String named, List<String> columns, List<Visible> visible) {
        if (named != null) {
            for (Visible d : visible) if (d.id.equals(named)) return d;
            throw new Refused("unknown-dataset", "I can't see a dataset called '" + named + "'");
        }
        List<Visible> fit = visible.stream()
                .filter(d -> columns.stream().allMatch(c -> d.column(c) != null)).toList();
        if (fit.isEmpty()) {
            for (String c : columns)
                if (visible.stream().noneMatch(d -> d.column(c) != null)) throw unknownColumn(c, visible);
            throw new Refused("which-dataset", "no single dataset has all of " + columns);
        }
        if (fit.size() > 1)
            throw new Refused("which-dataset", "which dataset? " + fit.stream().map(Visible::id).toList());
        return fit.get(0);
    }

    private static Col require(Visible ds, String name) {
        Col c = name == null ? null : ds.column(name);
        if (c == null) throw unknownColumn(name, List.of(ds));
        return c;
    }

    /** Never confirms a hidden column exists; the nearest VISIBLE names are the only suggestions. */
    private static Refused unknownColumn(String name, List<Visible> visible) {
        List<String> near = new ArrayList<>();
        if (name != null)
            for (Visible d : visible)
                for (Col c : d.columns)
                    if (!c.masked && distance(name.toLowerCase(Locale.ROOT), c.name.toLowerCase(Locale.ROOT))
                            <= Math.max(2, name.length() / 3) && !near.contains(c.name)) near.add(c.name);
        return new Refused("unknown-column", "I can't see a column called '" + name + "'"
                + (near.isEmpty() ? "" : " - did you mean " + near + "?"));
    }

    /** The masking rule of {@code BiResultMasking.vet}: a masked key is refused unless the caller may reveal. */
    private static void refuseMasked(Col c, String what, boolean allowed) {
        if (c.masked && !allowed)
            throw new Refused("masked-column", what + " column '" + c.name + "' is refused: it is a masked key, and "
                    + what + " it would leak its raw values; the caller needs " + EgressMasking.REVEAL);
    }

    private static String canonicalAgg(String agg) {
        if (agg == null) return null;
        for (String a : MEASURE_AGGS) if (a.equalsIgnoreCase(agg.trim())) return a;
        return null;
    }

    // ── the draft Widget and its caption ────────────────────────────────────────

    private static Map<String, Object> widget(String dataset, List<Map<String, Object>> measures, List<String> groups,
                                              Map<String, String> grains, String temporalGroup,
                                              Map<String, Object> compare, Integer topN, String caption) {
        Map<String, Object> controls = new LinkedHashMap<>();
        String viz;
        if (groups.isEmpty()) {
            viz = "kpi";
            controls.put("value", measures);
        } else {
            viz = temporalGroup != null && groups.get(0).equals(temporalGroup) ? "line" : "bar";
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("field", groups.get(0));
            if (grains.containsKey(groups.get(0))) x.put("grain", grains.get(groups.get(0)));
            controls.put("x", List.of(x));
            controls.put("y", measures);
            if (groups.size() > 1) controls.put("series", List.of(Map.of("field", groups.get(1))));
        }
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("title", caption);
        if (compare != null) options.put("compareKind", compare.get("kind"));
        if (topN != null) options.put("limit", topN);
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("vizType", viz);
        w.put("datasetId", dataset);
        w.put("controls", controls);
        w.put("options", options);
        return w;
    }

    /** "Sum of revenue by region, last 8 weeks, compared with the previous period" - the spec in words, so a
     *  reader can check the model understood the question. Numbers are the spec's, never the data's. */
    private static String caption(List<Map<String, Object>> measures, List<String> groups, String grain,
                                  List<Map<String, Object>> filters, Map<String, Object> window,
                                  Map<String, Object> compare, Integer topN, String order) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> m : measures) {
            String agg = (String) m.get("agg"), field = (String) m.get("field");
            parts.add("count".equals(agg) ? "Count of rows" : words(agg) + " of " + field);
        }
        StringBuilder s = new StringBuilder(String.join(" and ", parts));
        if (!groups.isEmpty()) {
            s.append(" by ").append(String.join(" and ", groups));
            if (grain != null && !"auto".equals(grain)) s.append(" (").append(grain).append(")");
        }
        if (topN != null) s.append(", ").append("asc".equals(order) ? "bottom " : "top ").append(topN);
        for (Map<String, Object> f : filters) {
            s.append(", where ").append(f.get("field")).append(' ').append(f.get("op"));
            if (f.containsKey("value")) s.append(' ').append(f.get("value"));
        }
        if (window != null) s.append(", last ").append(window.get("days")).append(" days");
        if (compare != null) s.append(", compared with ").append(switch ((String) compare.get("kind")) {
            case "previous" -> "the previous period";
            case "lastYear" -> "the same period last year";
            default -> "the average";
        });
        return s.toString();
    }

    /** The caption's name for an aggregate (a lookup, not a second grammar); a percentile reads "Percentile 95". */
    private static final Map<String, String> AGG_WORDS = Map.of("countDistinct", "Distinct count", "avg", "Average",
            "median", "Median", "stddev", "Standard deviation", "variance", "Variance", "min", "Minimum",
            "max", "Maximum", "sum", "Sum");

    private static String words(String agg) {
        String w = AGG_WORDS.get(agg);
        if (w != null) return w;
        return agg.startsWith("p") ? "Percentile " + Integer.parseInt(agg.substring(1)) : agg;
    }

    // ── small helpers ───────────────────────────────────────────────────────────

    private static ToolResult refuse(Refusal r) {
        return new ToolResult(false, null, r.message(), Map.of("refusal", r.code()));
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static List<String> stringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) if (str(x) != null) out.add(str(x));
        return out;
    }

    private static List<Map<String, Object>> mapList(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) if (x instanceof Map<?, ?> m) out.add(cast(m));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1], prev[j]) + 1,
                        prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
