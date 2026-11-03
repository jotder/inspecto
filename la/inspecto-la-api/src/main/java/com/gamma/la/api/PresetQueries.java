package com.gamma.la.api;

import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.IndustryPack;
import com.gamma.la.core.DatasetProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static com.gamma.util.SqlIdent.q;

/**
 * Preset queries (LA-PRESET-QUERIES-1, plan {@code docs/superpower/la-basic-discovery-plan.md} §3 P3): the questions an
 * industry already asks of its relationship data, run with a few parameters and answered as a table that can be drawn.
 *
 * <p><b>Declarative, never free SQL</b> (Q-2). An {@code aggregate} preset is one fixed shape - group the links by one
 * end, count them, count the distinct other ends, sum the amount, optionally keep one entity's links or some kinds,
 * keep the groups at or above a minimum - rendered here over the Dataset's mapped ROLES (From, To, Kind of link, When,
 * Amount): every identifier a validated, quoted column, every value a bound parameter. A {@code measure} preset is an
 * existing Value Measure ({@link ValueMeasures}) with preset thresholds. A preset whose roles the Dataset does not map
 * is not offered (Q-5).
 *
 * <p>Two deliberate departures from the plan, assistant-decided under delegation (2026-11-03, plan §6a): the shape is
 * rendered here, not by the engine's {@code MeasureCompiler}, because la-api may not depend on the engine (LA separation
 * D-1) - and it gains a real HAVING that way; and presets are code keyed by Industry pack id, not pack-file entries,
 * until a second reader needs them as data.
 */
final class PresetQueries {

    private PresetQueries() {}

    static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    static final List<String> ROLES = List.of("from", "to", "kind", "time", "amount");
    static final int MAX_ROWS = 500;

    /** A parameter the analyst sets: {@code entity} (an id or an alias), {@code number}, {@code kinds} (comma list) or {@code window}. */
    record Param(String name, String label, String type, String dflt) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("label", label);
            m.put("type", type);
            m.put("default", dflt);
            return m;
        }
    }

    /** The Dataset's columns by role; absent roles are null. */
    record Roles(String from, String to, String kind, String time, String amount) {
        String get(String role) {
            return switch (role) {
                case "from" -> from;
                case "to" -> to;
                case "kind" -> kind;
                case "time" -> time;
                case "amount" -> amount;
                default -> null;
            };
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String r : ROLES) m.put(r, get(r));
            return m;
        }
    }

    /** One result column: its id in the rows, its label, and whether it holds an entity id (masked like exploration). */
    record Column(String id, String label, boolean entity) {
        Map<String, Object> toMap() {
            return Map.of("id", id, "label", label, "entity", entity);
        }
    }

    /**
     * The aggregate shape. {@code by}: the role grouped on ({@code from} or {@code to}). {@code measures}, in order, from
     * {@code counterparts} (the other end's distinct values), {@code links} (a count) and {@code amount} (the total; left
     * out when the Dataset maps no amount). {@code entityParam}: keep only the links whose From is that parameter's
     * entity. {@code kindsParam}: keep only those kinds of link. {@code minParam}: keep the groups whose FIRST measure is
     * at least that parameter. The first measure also orders the answer, highest first.
     */
    record Aggregate(String by, List<String> measures, String entityParam, String kindsParam, String minParam) {}

    /**
     * One preset: an {@code aggregate}, a Value Measure ({@code measure}) or a {@code graph} shape (a fixed statement over
     * the links joined to themselves - {@link #graphSql}); exactly one is set.
     */
    record Preset(String id, String label, String description, List<String> needs, List<Param> params,
                  Aggregate aggregate, String measure, String graph) {
        Preset(String id, String label, String description, List<String> needs, List<Param> params,
               Aggregate aggregate, String measure) {
            this(id, label, description, needs, params, aggregate, measure, null);
        }

        boolean offeredFor(Roles r) {
            for (String n : needs) if (r.get(n) == null) return false;
            return true;
        }

        Param param(String name) {
            for (Param p : params) if (p.name().equals(name)) return p;
            return null;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("label", label);
            m.put("description", description);
            m.put("kind", graph != null ? "graph" : measure != null ? "measure" : "aggregate");
            m.put("needs", needs);
            m.put("params", params.stream().map(Param::toMap).toList());
            return m;
        }
    }

    // ── the catalogue ────────────────────────────────────────────────────────────────────────────────────────────────

    private static final Param TOP = new Param("top", "How many rows", "number", "50");
    private static final Param KINDS = new Param("kinds", "Only these kinds of link (comma list, empty = all)", "kinds", "");

    private static final Preset FAN_IN = new Preset("fan_in", "Reached by many",
            "Entities that many different others link to - a collector, a mule, a popular merchant.",
            List.of("from", "to"),
            List.of(new Param("min", "At least this many distinct senders", "number", "5"), KINDS, TOP),
            new Aggregate("to", List.of("counterparts", "links"), null, "kinds", "min"), null);

    private static final Preset FAN_OUT = new Preset("fan_out", "Reaching many",
            "Entities that link to many different others - a distributor, a spammer, a layering hub.",
            List.of("from", "to"),
            List.of(new Param("min", "At least this many distinct receivers", "number", "5"), KINDS, TOP),
            new Aggregate("from", List.of("counterparts", "links"), null, "kinds", "min"), null);

    private static final Preset COUNTERPARTS = new Preset("counterparts", "Top counterparts of one entity",
            "Who one entity links to most, with how often and, when the Dataset has an amount, how much.",
            List.of("from", "to"),
            List.of(new Param("entity", "The entity", "entity", ""), KINDS, TOP),
            new Aggregate("to", List.of("links", "amount"), "entity", "kinds", null), null);

    private static final Preset TOP_SENDERS = new Preset("top_senders", "Biggest senders by amount",
            "Entities that send the most in total, with how many links.",
            List.of("from", "to", "amount"),
            List.of(KINDS, TOP),
            new Aggregate("from", List.of("amount", "links"), null, "kinds", null), null);

    /** Mobile money pack: money in, then out again within hours (the Value Measure timeToCashOut). */
    private static final Preset QUICK_CASH_OUT = new Preset("quick_cash_out", "Money in, then out again within hours",
            "Entities whose money leaves again soon after it arrives - the median hours from money in to the next link of the chosen kinds out.",
            List.of("from", "to", "kind", "time", "amount"),
            List.of(new Param("maxHours", "Within this many hours", "number", "24"),
                    new Param("minInbound", "At least this much received", "number", "1000"),
                    new Param("cashOutKinds", "Kinds of link that take money out (comma list)", "kinds", "cash_out"),
                    new Param("last", "Over the last (for example 30d or 24h; at most the Space's window limit)", "window", "30d")),
            null, "timeToCashOut");

    /** Telecom pack: entities linked to many others through one shared item (the pack's shared_device link kind). */
    private static final Preset SHARED_DEVICE = new Preset("shared_device", "Linked through one shared item",
            "Entities linked to many others by a kind of link that means sharing one item - many identities on one thing.",
            List.of("from", "to", "kind"),
            List.of(new Param("min", "At least this many entities linked this way", "number", "2"),
                    new Param("kinds", "The kind of link that means sharing one item", "kinds", "shared_device"), TOP),
            new Aggregate("from", List.of("counterparts"), null, "kinds", "min"), null);

    /** Pairs that link both ways: A to B and B to A. */
    private static final Preset TWO_WAY = new Preset("two_way", "Two-way pairs",
            "Pairs of entities that link to each other in both directions - something going back and forth between them.",
            List.of("from", "to"),
            List.of(new Param("min", "At least this many links each way", "number", "1"), TOP),
            null, null, "two_way");

    /** Loops of three: A to B, B to C, C back to A. */
    private static final Preset SHORT_LOOPS = new Preset("short_loops", "Short loops",
            "Three entities linked in a circle (A to B to C and back to A) - round-tripping, layering. Very busy entities are left out so it stays quick.",
            List.of("from", "to"),
            List.of(new Param("maxLinks", "Leave out entities with more than this many links in and out", "number", "50"), TOP),
            null, null, "short_loops");

    /** Entities first seen lately that already have many links. */
    private static final Preset NEWCOMERS = new Preset("newcomers", "Newcomers",
            "Entities first seen in the last days of the data that already link to many others - fresh accounts ramping up.",
            List.of("from", "to", "time"),
            List.of(new Param("days", "First seen in the last this many days of the data", "number", "7"),
                    new Param("min", "At least this many links", "number", "5"), TOP),
            null, null, "newcomers");

    /** Everyone within two steps of one entity, either direction. */
    private static final Preset TWO_STEPS = new Preset("two_steps", "Two steps from one entity",
            "Who is around one entity: its direct counterparts, then theirs - and through how many of them.",
            List.of("from", "to"),
            List.of(new Param("entity", "The entity", "entity", ""), TOP),
            null, null, "two_steps");

    /** Who to look at first: every Space offers these; a pack puts its own questions ahead of them. */
    private static final List<Preset> CORE = List.of(FAN_IN, FAN_OUT, TWO_WAY, SHORT_LOOPS, NEWCOMERS, TOP_SENDERS, COUNTERPARTS,
            TWO_STEPS);

    /** The presets a Space with Industry pack {@code packId} offers (null = none stated: the core set alone). */
    static List<Preset> forPack(String packId) {
        List<Preset> out = new ArrayList<>();
        if ("mobile-money".equals(packId)) out.add(QUICK_CASH_OUT);
        if ("telecom".equals(packId)) out.add(SHARED_DEVICE);
        out.addAll(CORE);
        return out;
    }

    static Preset find(String packId, String id) {
        for (Preset p : forPack(packId)) if (p.id().equals(id)) return p;
        return null;
    }

    // ── values ───────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The analyst's value for {@code name}, else the preset's default; null when both are blank. */
    static String value(Preset p, Map<String, String> given, String name) {
        String v = given.get(name);
        if (v == null || v.isBlank()) v = p.param(name) == null ? null : p.param(name).dflt();
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** A {@code number} parameter: finite and at least 0. */
    static double number(Preset p, Map<String, String> given, String name) {
        String v = value(p, given, name);
        try {
            double d = Double.parseDouble(v);
            if (Double.isFinite(d) && d >= 0) return d;
        } catch (NumberFormatException | NullPointerException notANumber) {
            // refused below
        }
        throw new IllegalArgumentException("'" + name + "' must be a number of at least 0");
    }

    static List<String> kinds(String list) {
        List<String> out = new ArrayList<>();
        if (list != null) for (String k : list.split(",")) if (!k.isBlank() && !out.contains(k.trim())) out.add(k.trim());
        return out;
    }

    /** A {@code days} parameter: a whole number of days, 1 to 3660 (ten years). */
    static int wholeDays(double days) {
        if (days < 1 || days > 3660 || days != Math.floor(days))
            throw new IllegalArgumentException("'days' must be a whole number of days, 1 to 3660");
        return (int) days;
    }

    /** The row cap: the {@code top} parameter, 1..{@link #MAX_ROWS}. */
    static int top(Preset p, Map<String, String> given) {
        if (p.param("top") == null) return MAX_ROWS;
        return (int) Math.max(1, Math.min(MAX_ROWS, number(p, given, "top")));
    }

    // ── the graph shapes ─────────────────────────────────────────────────────────────────────────────────────────────

    /** The result columns of a graph shape, in order; entity columns are masked like exploration. */
    static List<Column> graphColumns(String shape) {
        return switch (shape) {
            case "two_way" -> List.of(new Column("entity", "Entity", true), new Column("counterpart", "Counterpart", true),
                    new Column("links_out", "Links out", false), new Column("links_back", "Links back", false));
            case "short_loops" -> List.of(new Column("entity", "Entity", true), new Column("second", "Then", true),
                    new Column("third", "Then, back to the first", true), new Column("links", "Links along the loop", false));
            case "newcomers" -> List.of(new Column("entity", "Entity", true), new Column("first_seen", "First seen", false),
                    new Column("links", "Links", false), new Column("counterparts", "Distinct counterparts", false));
            case "two_steps" -> List.of(new Column("entity", "Entity", true), new Column("steps", "Steps away", false),
                    new Column("via", "Links (1 step) or ways in (2 steps)", false));
            default -> throw new IllegalStateException(shape);
        };
    }

    /**
     * The statement for a graph shape over the links {@code __l(s, t[, ts])} - the From and To ends as text, self-links and
     * empty ends left out. {@code n(name)} reads a number parameter; {@code entity} is the (unmasked) entity or null.
     * Every value is a bound parameter, every identifier a quoted, real column.
     */
    static String graphSql(String datasetId, String shape, Roles r, java.util.function.ToDoubleFunction<String> n, String entity,
                           List<String> binds) {
        String s = q(r.from()), t = q(r.to());
        String ts = "newcomers".equals(shape) ? ", TRY_CAST(" + q(r.time()) + " AS TIMESTAMP) AS ts" : "";
        String links = "WITH __l AS (SELECT CAST(" + s + " AS VARCHAR) AS s, CAST(" + t + " AS VARCHAR) AS t" + ts + " FROM "
                + q(datasetId) + " WHERE " + s + " IS NOT NULL AND " + t + " IS NOT NULL AND CAST(" + s + " AS VARCHAR) <> CAST("
                + t + " AS VARCHAR))";
        return switch (shape) {
            case "two_way" -> {
                binds.add(String.valueOf(n.applyAsDouble("min")));
                yield links + ", __e AS (SELECT s, t, count(*) AS n FROM __l GROUP BY s, t)"
                        + " SELECT a.s AS entity, a.t AS counterpart, a.n AS links_out, b.n AS links_back"
                        + " FROM __e a JOIN __e b ON a.s = b.t AND a.t = b.s"
                        + " WHERE a.s < a.t AND least(a.n, b.n) >= CAST(? AS DOUBLE)"
                        + " ORDER BY a.n + b.n DESC, entity, counterpart";
            }
            case "short_loops" -> {
                binds.add(String.valueOf(n.applyAsDouble("maxLinks")));
                // each loop once, its smallest id first, the most links first; busy entities (many links in and out - the
                // index build's measure, LA-INDEX-PAIRS-LOOPS-1) left out before the joins
                yield links + ", __e AS (SELECT s, t, count(*) AS n FROM __l GROUP BY s, t),"
                        + " __d AS (SELECT v FROM (SELECT s AS v FROM __l UNION ALL SELECT t FROM __l) GROUP BY v"
                        + " HAVING count(*) > CAST(? AS DOUBLE)),"
                        + " __k AS (SELECT * FROM __e WHERE s NOT IN (SELECT v FROM __d) AND t NOT IN (SELECT v FROM __d))"
                        + " SELECT a.s AS entity, a.t AS second, b.t AS third, a.n + b.n + c.n AS links FROM __k a JOIN __k b ON b.s = a.t"
                        + " JOIN __k c ON c.s = b.t AND c.t = a.s"
                        + " WHERE b.t <> a.s AND a.s < a.t AND a.s < b.t ORDER BY links DESC, entity, second, third";
            }
            case "newcomers" -> {
                binds.add(String.valueOf(wholeDays(n.applyAsDouble("days"))));
                binds.add(String.valueOf(n.applyAsDouble("min")));
                // "lately" is the last days of the DATA (its newest link), not of the calendar: an old extract still answers
                yield links + ", __b AS (SELECT s AS e, t AS o, ts FROM __l UNION ALL SELECT t, s, ts FROM __l)"
                        + " SELECT e AS entity, min(ts) AS first_seen, count(*) AS links, count(DISTINCT o) AS counterparts"
                        + " FROM __b GROUP BY e"
                        + " HAVING min(ts) > (SELECT max(ts) FROM __l) - to_days(CAST(? AS INTEGER))"
                        + " AND count(*) >= CAST(? AS DOUBLE) ORDER BY links DESC, entity";
            }
            case "two_steps" -> {
                if (entity == null) throw new IllegalArgumentException("'entity' is required");
                binds.add(entity);
                binds.add(entity);
                // self-links are already out of __l, so the entity never appears at step 1; the second bind keeps it out of step 2
                yield links + ", __u AS (SELECT s AS a, t AS b FROM __l UNION ALL SELECT t, s FROM __l),"
                        + " __1 AS (SELECT b AS v, count(*) AS n FROM __u WHERE a = ? GROUP BY b)"
                        + " SELECT v AS entity, 1 AS steps, n AS via FROM __1"
                        + " UNION ALL SELECT u.b, 2, count(DISTINCT u.a) FROM __u u JOIN __1 ON u.a = __1.v"
                        + " WHERE u.b <> ? AND u.b NOT IN (SELECT v FROM __1) GROUP BY u.b"
                        + " ORDER BY steps, via DESC, entity";
            }
            default -> throw new IllegalStateException(shape);
        };
    }

    // ── the aggregate render ─────────────────────────────────────────────────────────────────────────────────────────

    /** The result columns of an aggregate preset, in order. */
    static List<Column> columns(Aggregate a, Roles r) {
        List<Column> cols = new ArrayList<>(List.of(new Column("entity", "Entity", true)));
        for (String m : a.measures())
            switch (m) {
                case "counterparts" -> cols.add(new Column(m, "Distinct counterparts", false));
                case "links" -> cols.add(new Column(m, "Links", false));
                case "amount" -> { if (r.amount() != null) cols.add(new Column(m, "Total " + r.amount(), false)); }
                default -> throw new IllegalStateException(m);
            }
        return cols;
    }

    /**
     * The statement for an aggregate preset: {@code binds} receives the entity, the kinds and the minimum in that order.
     * {@code entity} is the (unmasked) From value or null; {@code kinds} may be empty; {@code min} null = no minimum.
     */
    static String aggregateSql(String datasetId, Aggregate a, Roles r, List<Column> cols, String entity, List<String> kinds,
                               Double min, List<String> binds) {
        String by = q("from".equals(a.by()) ? r.from() : r.to());
        String other = q("from".equals(a.by()) ? r.to() : r.from());
        List<String> select = new ArrayList<>();
        for (Column c : cols)
            select.add(switch (c.id()) {
                case "entity" -> "CAST(" + by + " AS VARCHAR) AS entity";
                case "counterparts" -> "COUNT(DISTINCT " + other + ") AS counterparts";
                case "links" -> "COUNT(*) AS links";
                case "amount" -> "SUM(TRY_CAST(" + q(r.amount()) + " AS DOUBLE)) AS amount";
                default -> throw new IllegalStateException(c.id());
            });
        StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", select)).append(" FROM ").append(q(datasetId))
                .append(" WHERE ").append(q(r.from())).append(" IS NOT NULL AND ").append(q(r.to())).append(" IS NOT NULL");
        if (entity != null) {
            sql.append(" AND CAST(").append(q(r.from())).append(" AS VARCHAR) = ?");
            binds.add(entity);
        }
        if (!kinds.isEmpty() && r.kind() == null)
            throw new IllegalArgumentException("'" + a.kindsParam() + "' needs the Dataset to map a kind of link; it does not");
        if (!kinds.isEmpty()) {
            sql.append(" AND CAST(").append(q(r.kind())).append(" AS VARCHAR) IN (");
            for (int i = 0; i < kinds.size(); i++) {
                sql.append(i == 0 ? "?" : ", ?");
                binds.add(kinds.get(i));
            }
            sql.append(")");
        }
        String first = select.get(1).substring(select.get(1).lastIndexOf(" AS ") + 4);
        String firstExpr = select.get(1).substring(0, select.get(1).lastIndexOf(" AS "));
        sql.append(" GROUP BY 1");
        if (min != null) {
            sql.append(" HAVING ").append(firstExpr).append(" >= CAST(? AS DOUBLE)");
            binds.add(String.valueOf(min));
        }
        return sql.append(" ORDER BY ").append(first).append(" DESC NULLS LAST, entity").toString();
    }

    // ── roles ────────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The Dataset's columns by role. From, To, Kind and When come from its {@code defaultLinkMapping} when it states one
     * (Add data and Mappings do); else From and To are its first two columns classified as an Entity Type and Kind a
     * column named {@code kind} or {@code *_kind}. When (if not mapped) is the {@code eventDate}, else the first date
     * or timestamp column; Amount is the first number column whose name the Industry pack hints ({@code measure_hints}).
     * {@code columns} are the relation's REAL columns ({@link DatasetProvider.Column}, coarse types). A link mapping that
     * names a column the relation has not got is refused ({@link IllegalArgumentException}, LA-PIPELINE-MAPPINGS-1); a
     * role found any other way is simply absent when its column is.
     */
    static Roles roles(Map<String, Object> dataset, List<DatasetProvider.Column> columns, List<EntityTypes.EntityType> types,
                       IndustryPack pack) {
        Map<String, String> real = new LinkedHashMap<>();   // lower-case name -> the relation's own spelling
        Map<String, String> type = new LinkedHashMap<>();   // the relation's spelling -> coarse type
        for (DatasetProvider.Column c : columns)
            if (SAFE_IDENT.matcher(c.name()).matches()) {
                real.put(c.name().toLowerCase(Locale.ROOT), c.name());
                type.put(c.name(), c.type() == null ? "" : c.type().toLowerCase(Locale.ROOT));
            }
        String from = null, to = null, kind = null, time = null;
        if (dataset.get("defaultLinkMapping") instanceof Map<?, ?> m) {
            // LA-PIPELINE-MAPPINGS-1 (§3.3): a link mapping that names a column the data no longer has is refused, never
            // quietly replaced by the classified columns - that would answer a different graph under the Mapping's name
            List<String> gone = new ArrayList<>(), unreadable = new ArrayList<>();
            for (String k : List.of("from", "to", "kind", "time", "amount"))
                if (m.get(k) instanceof String s && !s.isBlank() && col(real, s) == null)
                    (SAFE_IDENT.matcher(s.trim()).matches() ? gone : unreadable).add(s.trim());
            if (!unreadable.isEmpty())
                throw new IllegalArgumentException("its link mapping reads " + String.join(", ", unreadable)
                        + ": preset questions read only columns named with letters, digits and _");
            if (!gone.isEmpty())
                throw new IllegalArgumentException("its link mapping reads " + String.join(", ", gone)
                        + ", which its data does not have (any more) - edit the Mapping or the Dataset's link mapping");
            from = col(real, m.get("from"));
            to = col(real, m.get("to"));
            kind = col(real, m.get("kind"));
            time = col(real, m.get("time"));
        }
        if (from == null || to == null) {
            List<String> classified = new ArrayList<>();
            if (dataset.get("columns") instanceof List<?> cols)
                for (Object o : cols)
                    if (o instanceof Map<?, ?> c && col(real, c.get("name")) != null && c.get("classification") instanceof String k
                            && claimed(k, types)) classified.add(col(real, c.get("name")));
            from = classified.size() >= 2 ? classified.get(0) : null;
            to = classified.size() >= 2 ? classified.get(1) : null;
        }
        if (kind == null)
            for (String n : type.keySet()) {
                String low = n.toLowerCase(Locale.ROOT);
                if (low.equals("kind") || low.endsWith("_kind")) { kind = n; break; }
            }
        if (time == null) time = col(real, dataset.get("eventDate"));
        if (time == null)
            for (Map.Entry<String, String> e : type.entrySet())
                if (e.getValue().equals("date")) { time = e.getKey(); break; }
        List<String> hints = pack == null || pack.profile() == null || pack.profile().measureHints().isEmpty()
                ? List.of("amount", "value") : pack.profile().measureHints();
        String amount = null;
        for (String h : hints) {
            for (Map.Entry<String, String> e : type.entrySet())
                if (e.getValue().equals("number") && e.getKey().toLowerCase(Locale.ROOT).contains(h.toLowerCase(Locale.ROOT))
                        && !e.getKey().equals(from) && !e.getKey().equals(to)) { amount = e.getKey(); break; }
            if (amount != null) break;
        }
        return new Roles(from, to, kind, time, amount);
    }

    private static String col(Map<String, String> real, Object name) {
        return name instanceof String s ? real.get(s.trim().toLowerCase(Locale.ROOT)) : null;
    }

    private static boolean claimed(String classification, List<EntityTypes.EntityType> types) {
        for (EntityTypes.EntityType t : types)
            for (String c : t.classifications()) if (c.trim().equalsIgnoreCase(classification.trim())) return true;
        return false;
    }
}
