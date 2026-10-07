package com.gamma.la.api;

import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.DatasetProvider;
import com.gamma.util.SqlIdent;
import com.gamma.sql.SqlSandboxPolicy;

import com.gamma.control.ApiException;
import com.gamma.control.EntityTypes;
import com.gamma.control.ErrorCodes;
import com.gamma.entitylist.EntityFactLog;
import com.gamma.entitylist.EntityListFacts;
import com.gamma.entitylist.EntityRegistry;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>LA-18 value Measures</b> ({@code docs/archived-documents/plans-archive/link-analysis-backlog-plan.md} §2.6.1, DECIDED 2026-09-30) —
 * named Measures with VISIBLE thresholds over the WHOLE Dataset, never an opaque score. Each answers the entities
 * that breach its thresholds in a {@code [from, to)} window; an Alert Rule ({@code alert.valueMeasure}) watches the
 * COUNT of those entities and fires when it is above 0 (one Alert per rule, never per entity — G-42).
 *
 * <p>🔴 <b>Whole Dataset, never the view.</b> The relation is the Dataset's own ({@link InvRoutes#relationFor}); no
 * filter is accepted, so a view narrowed to {@code AMOUNT ≥ 5 000} cannot remove the sub-threshold legs structuring
 * is made of — the §2.6 trap.
 *
 * <p>Fences: every identifier checked against the relation's real columns by the caller and quoted here; every value
 * bound; a {@value #TIMEOUT_SECONDS} s statement timeout; at most {@value #MAX_ENTITIES} entities leave DuckDB
 * (more ⇒ {@code truncated}); the window at most {@value #MAX_WINDOW_DAYS} days.
 *
 * <p><b>Window and timezone contract.</b> Exactly one of a fixed {@code from}/{@code to} or a ROLLING
 * {@code last: <N>h|<N>d}. Window bounds are UTC wall-clock timestamps. {@code last} is stored relative and resolved
 * at EVALUATION time (every GET, every bind, every Alert sweep) against the server clock in UTC to
 * {@code [now − N, now)}, truncated to the second, so an armed Alert Rule never watches a stale window. Every
 * statement runs with the DuckDB session {@code TimeZone} set to {@code UTC} (the session default is the HOST's), so
 * a {@code TIMESTAMPTZ} {@code timeCol} is read as UTC wall time, and a naive {@code TIMESTAMP} one as it is — i.e.
 * it is ASSUMED to be UTC.
 *
 * <p><b>Agent list</b> ({@code cashOutConcentration} only): an optional {@code agentList} — an Entity List of Entity
 * Type {@code agent} — restricts the AGENTS answered to the list's live exact members, each payee compared under the
 * list's SEALED normaliser (D-M9); the share's denominator stays ALL cash-out in the window. When the list renders
 * masked (the Space's {@code maskingMode}), each answered agent is the list's own mask token, never the raw value.
 */
public final class ValueMeasures {

    public static final int MAX_ENTITIES = 10_000;
    public static final int TIMEOUT_SECONDS = 10;
    public static final int MAX_WINDOW_DAYS = 31;
    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** The Measures an Alert Rule may watch — {@code AlertRule#VALUE_MEASURES} mirrors this (pinned by test). */
    public static final Set<String> ALERTABLE = Set.of("passThrough", "velocity", "timeToCashOut", "cashOutConcentration",
            "structuring", "benefitTransfer");
    /** Plus the one that is a weighting, not a test: readable, never alertable. */
    public static final String VALUE_WEIGHTED_LINKS = "valueWeightedLinks";

    /** Each Measure's thresholds with their defaults (§2.6.1) — every one visible in the answer and editable. */
    public static final Map<String, Map<String, Double>> DEFAULTS = Map.of(
            "passThrough", Map.of("minInbound", 10_000d, "minRatio", 0.90),
            "velocity", Map.of("minInbound", 10_000d, "maxHours", 24d),
            "timeToCashOut", Map.of("minInbound", 10_000d, "maxHours", 48d),
            "cashOutConcentration", Map.of("minShare", 0.20, "minPayers", 5d),
            "structuring", Map.of("min", 900d, "max", 1_000d, "minLegs", 10d, "minPayers", 5d),
            "benefitTransfer", Map.of("maxHours", 72d, "minShare", 0.50, "minRecipients", 5d),
            VALUE_WEIGHTED_LINKS, Map.of());
    /** The Measures that read a list of link kinds from {@code kindCol}. */
    private static final Map<String, String> KIND_LIST = Map.of("timeToCashOut", "cashOutKinds",
            "cashOutConcentration", "cashOutKinds", "benefitTransfer", "benefitKinds");
    private static final Set<String> COMMON = Set.of("name", "valueCol", "timeCol", "from", "to", "last");
    /** The one Measure an {@code agent} Entity List may restrict, and the key naming that list. */
    private static final String AGENT_MEASURE = "cashOutConcentration";
    public static final String AGENT_LIST = "agentList";
    private static final String AGENT_TYPE = "agent";
    /** A rolling window: {@code <N>h} or {@code <N>d}. */
    private static final Pattern LAST = Pattern.compile("([1-9][0-9]{0,5})([hd])");
    private static final java.time.ZoneId UTC = java.time.ZoneId.of("UTC");   // DuckDB refuses "Z"

    /**
     * One parsed, validated Measure: its name, columns, RESOLVED window ({@code from}/{@code to}, UTC), thresholds
     * (defaults filled), kind list, the rolling {@code last} it was resolved from (null = fixed) and {@code agentList}.
     */
    public record Spec(String name, String valueCol, String timeCol, String from, String to, Map<String, Double> thresholds,
                List<String> kinds, String last, String agentList) {

        /** The block as stored on an Alert Rule and answered to the caller — every threshold spelled out. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("valueCol", valueCol);
            m.put("timeCol", timeCol);
            if (last != null) m.put("last", last);   // rolling: stored relative, resolved at every evaluation
            else {
                m.put("from", from);
                m.put("to", to);
            }
            m.putAll(thresholds);
            if (KIND_LIST.containsKey(name)) m.put(KIND_LIST.get(name), kinds);
            if (agentList != null) m.put(AGENT_LIST, agentList);
            return m;
        }

        List<String> columns() {
            return List.of(valueCol, timeCol);
        }
    }

    /** The answer: the breaching entities (or the weighted pairs), whether capped, and the rows the window skipped. */
    public record Result(List<Map<String, Object>> entities, boolean truncated, long rowsInWindow, long unvalued) {}

    private ValueMeasures() {}

    /** An {@code agentList} resolved: the list's live exact members (normalised keys), its normaliser, its mask key
     *  ({@code null} = the list renders raw), and the identity fact log's head {@code atSeq}/{@code atHash} it was read
     *  at - the list VERSION an Alert firing records (A3, operator 2026-09-30); the rule itself keeps reading live. */
    public record Agents(String normaliser, Set<String> members, byte[] maskKey, long atSeq, String atHash) {
        Agents(String normaliser, Set<String> members, byte[] maskKey) {
            this(normaliser, members, maskKey, 0, null);
        }
    }

    /** {@link #parse(Map, boolean, Clock)} against the server clock (UTC). */
    public static Spec parse(Map<String, Object> block, boolean alertable) {
        return parse(block, alertable, Clock.systemUTC());
    }

    /**
     * Parse and validate a block {@code {name, valueCol, timeCol, from + to | last, …thresholds}}; defaults fill the
     * rest. A {@code last} window is resolved HERE against {@code clock} in UTC — so every evaluation re-parses.
     */
    public static Spec parse(Map<String, Object> block, boolean alertable, Clock clock) {
        String name = str(block.get("name"));
        if (name == null || !DEFAULTS.containsKey(name) || (alertable && !ALERTABLE.contains(name)))
            throw new IllegalArgumentException("value measure 'name' must be one of "
                    + (alertable ? ALERTABLE : DEFAULTS.keySet()) + ", got '" + name + "'");
        Map<String, Double> defaults = DEFAULTS.get(name);
        String kindKey = KIND_LIST.get(name);
        for (String k : block.keySet())
            if (!COMMON.contains(k) && !defaults.containsKey(k) && !k.equals(kindKey)
                    && !(k.equals(AGENT_LIST) && name.equals(AGENT_MEASURE)))
                throw new IllegalArgumentException("'" + k + "' is not a setting of " + name + " — its settings are "
                        + COMMON + " + " + defaults.keySet() + (kindKey == null ? "" : " + " + kindKey)
                        + (name.equals(AGENT_MEASURE) ? " + " + AGENT_LIST : ""));
        String valueCol = ident(block, "valueCol");
        String timeCol = ident(block, "timeCol");
        String last = str(block.get("last"));
        boolean fixed = str(block.get("from")) != null || str(block.get("to")) != null;
        if (fixed == (last != null))
            throw new IllegalArgumentException("value measure needs exactly one window: 'from' + 'to', or 'last' "
                    + "(<N>h or <N>d — rolling, resolved in UTC at evaluation)");
        LocalDateTime from, to;
        if (last != null) {
            Matcher lm = LAST.matcher(last);
            if (!lm.matches())
                throw new IllegalArgumentException("'last' must be <N>h or <N>d (e.g. 24h, 7d), got '" + last + "'");
            long n = Long.parseLong(lm.group(1));
            Duration span = lm.group(2).equals("h") ? Duration.ofHours(n) : Duration.ofDays(n);
            if (span.compareTo(Duration.ofDays(MAX_WINDOW_DAYS)) > 0)
                throw new IllegalArgumentException("the window may span at most " + MAX_WINDOW_DAYS + " days");
            to = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
            from = to.minus(span);
        } else {
            from = instant(block, "from");
            to = instant(block, "to");
            if (!to.isAfter(from)) throw new IllegalArgumentException("'to' must be after 'from'");
            if (Duration.between(from, to).compareTo(Duration.ofDays(MAX_WINDOW_DAYS)) > 0)
                throw new IllegalArgumentException("the window may span at most " + MAX_WINDOW_DAYS + " days");
        }
        String agentList = str(block.get(AGENT_LIST));
        if (agentList != null && !EntityListFacts.LIST_ID.matcher(agentList).matches())
            throw new IllegalArgumentException("'" + AGENT_LIST + "' must be an Entity List id matching "
                    + EntityListFacts.LIST_ID.pattern());
        Map<String, Double> thresholds = new LinkedHashMap<>();
        for (var d : new java.util.TreeMap<>(defaults).entrySet()) {
            Object v = block.get(d.getKey());
            double x;
            if (v == null) x = d.getValue();
            else if (v instanceof Number n) x = n.doubleValue();
            else try {
                x = Double.parseDouble(String.valueOf(v).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + d.getKey() + "' must be a number, got '" + v + "'");
            }
            if (!Double.isFinite(x) || x < 0) throw new IllegalArgumentException("'" + d.getKey() + "' must be ≥ 0");
            thresholds.put(d.getKey(), x);
        }
        if ("structuring".equals(name) && thresholds.get("max") <= thresholds.get("min"))
            throw new IllegalArgumentException("structuring 'max' must be above 'min'");
        List<String> kinds = List.of();
        if (kindKey != null) {
            Object raw = block.get(kindKey);
            List<?> items = raw instanceof List<?> l ? l : raw == null ? List.of() : List.of(String.valueOf(raw).split(","));
            kinds = items.stream().map(o -> String.valueOf(o).trim()).filter(s -> !s.isEmpty()).distinct().toList();
            if (kinds.isEmpty())
                throw new IllegalArgumentException(name + " needs '" + kindKey + "' — the link kinds that mark it");
        }
        return new Spec(name, valueCol, timeCol, from.toString(), to.toString(), thresholds, kinds, last, agentList);
    }

    /** The one-line statement of a Measure's thresholds, e.g. {@code passThrough ≥ 0.9 with inbound ≥ 10000}. */
    public static String label(Spec s) {
        Map<String, Double> t = s.thresholds();
        return switch (s.name()) {
            case "passThrough" -> "out ÷ in ≥ " + js(t.get("minRatio")) + " with inbound ≥ " + js(t.get("minInbound"));
            case "velocity" -> "median hours from inbound to next outbound ≤ " + js(t.get("maxHours"))
                    + " with inbound ≥ " + js(t.get("minInbound"));
            case "timeToCashOut" -> "median hours from inbound to next cash-out " + s.kinds() + " ≤ "
                    + js(t.get("maxHours")) + " with inbound ≥ " + js(t.get("minInbound"));
            case "cashOutConcentration" -> "share of all cash-out " + s.kinds() + " ≥ " + js(t.get("minShare"))
                    + " from ≥ " + js(t.get("minPayers")) + " payers"
                    + (s.agentList() == null ? "" : ", agents in Entity List " + s.agentList());
            case "structuring" -> "≥ " + js(t.get("minLegs")) + " legs " + js(t.get("min")) + " ≤ " + s.valueCol()
                    + " < " + js(t.get("max")) + " from ≥ " + js(t.get("minPayers")) + " payers";
            case "benefitTransfer" -> "≥ " + js(t.get("minRecipients")) + " recipients of " + s.kinds()
                    + " forwarding ≥ " + js(t.get("minShare")) + " of it within " + js(t.get("maxHours")) + " h";
            default -> "sum and count of " + s.valueCol() + " per pair";
        };
    }

    /**
     * Evaluate one Measure over the WHOLE relation {@code relationSql} (registered as {@code datasetId}).
     * {@code kindCol} may be null except for a Measure that reads link kinds.
     */
    public static Result evaluate(String datasetId, String relationSql, String sourceCol, String targetCol, String kindCol,
                           Spec s) throws SQLException, java.io.IOException {
        return evaluate(datasetId, relationSql, sourceCol, targetCol, kindCol, s, null);
    }

    /** As above, with the Measure's {@code agentList} resolved by {@link #agents} ({@code null} iff it names none). */
    public static Result evaluate(String datasetId, String relationSql, String sourceCol, String targetCol, String kindCol,
                           Spec s, Agents agents) throws SQLException, java.io.IOException {
        if ((s.agentList() != null) != (agents != null))
            throw new IllegalStateException("an agentList is resolved before evaluation, and only then");
        if (KIND_LIST.containsKey(s.name()) && kindCol == null)
            throw new IllegalArgumentException(s.name() + " needs a link-kind column (linkKindCol)");
        List<String> binds = new ArrayList<>(List.of(s.from(), s.to()));
        String base = "WITH __l AS (SELECT CAST(" + q(sourceCol) + " AS VARCHAR) AS s, CAST(" + q(targetCol)
                + " AS VARCHAR) AS t, " + (kindCol == null ? "CAST(NULL AS VARCHAR)" : "CAST(" + q(kindCol) + " AS VARCHAR)")
                + " AS k, TRY_CAST(" + q(s.valueCol()) + " AS DOUBLE) AS v, TRY_CAST(" + q(s.timeCol())
                + " AS TIMESTAMP) AS ts FROM " + q(datasetId) + " WHERE " + q(sourceCol) + " IS NOT NULL AND "
                + q(targetCol) + " IS NOT NULL), __x AS (SELECT * FROM __l WHERE ts >= CAST(? AS TIMESTAMP) AND ts < "
                + "CAST(? AS TIMESTAMP)), __w AS (SELECT * FROM __x WHERE v IS NOT NULL)";
        SqlSandboxPolicy policy = SqlSandboxPolicy.withCaps(null, 0, TIMEOUT_SECONDS);

        Map<String, Object> stats = DatasetProviders.require().run(new DatasetProvider.Request(datasetId, relationSql,
                base + " SELECT count(*) AS n, count(*) FILTER (WHERE v IS NULL) AS unvalued FROM __x", 1, 0,
                List.of(), List.of(), List.copyOf(binds)), policy, UTC).rows().get(0);

        Map<String, Double> t = s.thresholds();
        String sql = switch (s.name()) {
            case "passThrough" -> {
                binds.add(num(t.get("minInbound")));
                binds.add(num(t.get("minRatio")));
                yield base + ", __in AS (SELECT t AS e, sum(v) AS inbound, min(ts) AS first_in FROM __w GROUP BY t),"
                        + " __out AS (SELECT w.s AS e, sum(w.v) AS outbound FROM __w w JOIN __in i ON w.s = i.e"
                        + " WHERE w.ts >= i.first_in GROUP BY w.s),"
                        + " __r AS (SELECT i.e AS entity, i.inbound, coalesce(o.outbound, 0) AS outbound,"
                        + " coalesce(o.outbound, 0) / i.inbound AS ratio FROM __in i LEFT JOIN __out o ON o.e = i.e)"
                        + " SELECT entity, inbound, outbound, ratio, 1 - ratio AS retention FROM __r"
                        + " WHERE inbound >= CAST(? AS DOUBLE) AND ratio >= CAST(? AS DOUBLE) ORDER BY ratio DESC, entity";
            }
            case "velocity", "timeToCashOut" -> {
                String outKinds = "";
                if (s.name().equals("timeToCashOut")) outKinds = " AND o.k IN (" + placeholders(s.kinds(), binds) + ")";
                binds.add(num(t.get("minInbound")));
                binds.add(num(t.get("maxHours")));
                yield base + ", __in AS (SELECT t AS e, sum(v) AS inbound FROM __w GROUP BY t),"
                        + " __g AS (SELECT i.t AS e, (SELECT min(o.ts) FROM __w o WHERE o.s = i.t AND o.ts >= i.ts"
                        + outKinds + ") AS nxt, i.ts FROM __w i)"
                        + " SELECT g.e AS entity, median(date_diff('second', g.ts, g.nxt)) / 3600.0 AS hours,"
                        + " count(*) AS legs, min(n.inbound) AS inbound FROM __g g JOIN __in n ON n.e = g.e"
                        + " WHERE g.nxt IS NOT NULL GROUP BY g.e"
                        + " HAVING min(n.inbound) >= CAST(? AS DOUBLE) AND median(date_diff('second', g.ts, g.nxt))"
                        + " / 3600.0 <= CAST(? AS DOUBLE) ORDER BY hours, entity";
            }
            case "cashOutConcentration" -> {
                String kinds = placeholders(s.kinds(), binds);
                String cte = base + ", __c AS (SELECT * FROM __w WHERE k IN (" + kinds + "))";
                String only = agents == null ? "" : " WHERE " + agentPayees(datasetId, relationSql, cte, binds, agents, policy);
                binds.add(num(t.get("minShare")));
                binds.add(num(t.get("minPayers")));
                yield cte
                        + " SELECT t AS entity, sum(v) AS cashOut, sum(v) / (SELECT sum(v) FROM __c) AS share,"
                        + " count(DISTINCT s) AS payers FROM __c" + only + " GROUP BY t"
                        + " HAVING sum(v) / (SELECT sum(v) FROM __c) >= CAST(? AS DOUBLE)"
                        + " AND count(DISTINCT s) >= CAST(? AS DOUBLE) ORDER BY share DESC, entity";
            }
            case "structuring" -> {
                binds.add(num(t.get("min")));
                binds.add(num(t.get("max")));
                binds.add(num(t.get("minLegs")));
                binds.add(num(t.get("minPayers")));
                yield base + " SELECT t AS entity, count(*) AS legs, count(DISTINCT s) AS payers, sum(v) AS total"
                        + " FROM __w WHERE v >= CAST(? AS DOUBLE) AND v < CAST(? AS DOUBLE) GROUP BY t"
                        + " HAVING count(*) >= CAST(? AS DOUBLE) AND count(DISTINCT s) >= CAST(? AS DOUBLE)"
                        + " ORDER BY legs DESC, entity";
            }
            case "benefitTransfer" -> {
                String kinds = placeholders(s.kinds(), binds);
                binds.add(num(t.get("maxHours")));
                binds.add(num(t.get("minShare")));
                binds.add(num(t.get("minRecipients")));
                yield base + ", __b AS (SELECT t AS r, v, ts FROM __w WHERE k IN (" + kinds + ")),"
                        + " __sk AS (SELECT DISTINCT o.t AS c, b.r AS r, o.s AS os, o.ts AS ots, o.v AS ov FROM __b b"
                        + " JOIN __w o ON o.s = b.r AND date_diff('second', b.ts, o.ts) BETWEEN 0"
                        + " AND CAST(? AS DOUBLE) * 3600 AND o.v >= CAST(? AS DOUBLE) * b.v)"
                        + " SELECT c AS entity, count(DISTINCT r) AS recipients, sum(ov) AS forwarded FROM __sk"
                        + " GROUP BY c HAVING count(DISTINCT r) >= CAST(? AS DOUBLE) ORDER BY recipients DESC, entity";
            }
            default -> base + " SELECT s AS source, t AS target, k AS kind, sum(v) AS total, count(*) AS links"
                    + " FROM __w GROUP BY s, t, k ORDER BY total DESC, source, target";
        };
        DatasetProvider.Result r = DatasetProviders.require().run(new DatasetProvider.Request(datasetId, relationSql, sql,
                MAX_ENTITIES, 0, List.of(), List.of(), binds), policy, UTC);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : r.rows()) {
            Map<String, Object> out = new LinkedHashMap<>(row);
            if (agents != null && agents.maskKey() != null)   // the list's own token — its members never leave raw
                out.put("entity", EntityMasking.token(agents.maskKey(),
                        EntityTypes.normalise(agents.normaliser(), String.valueOf(row.get("entity")))));
            rows.add(out);
        }
        return new Result(rows, r.truncated(), ((Number) stats.get("n")).longValue(),
                ((Number) stats.get("unvalued")).longValue());
    }

    /**
     * Evaluate {@code s} over the whole Dataset an Investigation is bound to: its {@code sourceCol}/{@code targetCol}/
     * {@code linkKindCol} roles, the Measure's own {@code valueCol}/{@code timeCol}, every one checked against the
     * relation's real columns first. Throws {@link IllegalArgumentException} for an unknown column.
     */
    public static Result forInvestigation(String datasetId, String relationSql, Map<String, Object> header, Spec s,
                                   Agents agents) throws SQLException, java.io.IOException {
        String src = str(header.get("sourceCol")), tgt = str(header.get("targetCol")), kind = str(header.get("linkKindCol"));
        List<String> columns = InvRoutes.relationColumns(datasetId, relationSql);
        for (String col : java.util.Arrays.asList(src, tgt, kind, s.valueCol(), s.timeCol()))
            if (col != null && !InvRoutes.containsIgnoreCase(columns, col))
                throw new IllegalArgumentException("unknown column '" + col + "' — not a column of dataset '" + datasetId + "'");
        return evaluate(datasetId, relationSql, src, tgt, kind, s, agents);
    }

    /**
     * Resolve the Measure's {@code agentList} at the identity fact log's HEAD (live, so an armed rule follows the
     * list): {@code null} when it names none. Refusals: unknown list 404 · retired 409 · Entity Type not
     * {@code agent} 422. Only EXACT live members count (range entries are not applied, as with {@code seedBy}).
     */
    public static Agents agents(Path writeRoot, Spec s) throws java.io.IOException {
        if (s.agentList() == null) return null;
        EntityFactLog log = new EntityFactLog(writeRoot);
        EntityFactLog.Log head = EntityListFacts.read(log);
        EntityRegistry.EntityList l = EntityRegistry.fold(head.facts(), head.headSeq()).get(s.agentList());
        if (l == null)
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "entity list '" + s.agentList() + "' not found");
        if (l.retired())
            throw new ApiException(409, ErrorCodes.CONFLICT, "entity list '" + s.agentList() + "' is retired");
        if (!AGENT_TYPE.equals(l.entityType()))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + AGENT_LIST + "' must name an Entity "
                    + "List of Entity Type '" + AGENT_TYPE + "'; '" + s.agentList() + "' is '" + l.entityType() + "'");
        byte[] key = EntityListFacts.masked(writeRoot, l) ? EntityMasking.key(log.directory()) : null;
        return new Agents(l.normaliser(), Set.copyOf(l.liveMembers(java.time.Instant.now())), key,
                head.headSeq(), head.headHash());
    }

    /**
     * The predicate restricting {@code __c} to the agents' payees: the DISTINCT cash-out payees in the window (at most
     * {@link #MAX_ENTITIES}, more ⇒ refused, never sampled) normalised in Java under the list's sealed normaliser; the
     * raw values whose key is a member are BOUND. No member matches ⇒ {@code FALSE}.
     */
    private static String agentPayees(String datasetId, String relationSql, String cte, List<String> binds,
                                      Agents agents, SqlSandboxPolicy policy) throws SQLException, java.io.IOException {
        DatasetProvider.Result payees = DatasetProviders.require().run(new DatasetProvider.Request(datasetId, relationSql,
                cte + " SELECT DISTINCT t AS p FROM __c", MAX_ENTITIES, 0, List.of(), List.of(), List.copyOf(binds)),
                policy, UTC);
        if (payees.truncated())
            throw new IllegalArgumentException("more than " + MAX_ENTITIES + " distinct cash-out payees in the window — "
                    + "never a silent sample; narrow the window");
        List<String> raw = new ArrayList<>();
        for (Map<String, Object> row : payees.rows()) {
            String p = String.valueOf(row.get("p"));
            if (agents.members().contains(EntityTypes.normalise(agents.normaliser(), p))) raw.add(p);
        }
        return raw.isEmpty() ? "FALSE" : "t IN (" + placeholders(raw, binds) + ")";
    }

    private static String placeholders(List<String> values, List<String> binds) {
        binds.addAll(values);
        return String.join(", ", java.util.Collections.nCopies(values.size(), "?"));
    }

    private static String ident(Map<String, Object> block, String key) {
        String v = str(block.get(key));
        if (v == null) throw new IllegalArgumentException("value measure needs '" + key + "'");
        if (!SAFE_IDENT.matcher(v).matches())
            throw new IllegalArgumentException("unsafe column identifier '" + v + "' for " + key);
        return v;
    }

    private static LocalDateTime instant(Map<String, Object> block, String key) {
        String v = str(block.get(key));
        if (v == null) throw new IllegalArgumentException("value measure needs '" + key + "' (the window is required)");
        try {
            if (v.length() == 10) return LocalDate.parse(v).atStartOfDay();
            String t = v.replace(' ', 'T');
            // a Z or an offset is normalised to UTC, the zone every window is read in (plan §5.10)
            return t.endsWith("Z") || t.matches(".*T.*[+-]\\d{2}:\\d{2}$")
                    ? java.time.OffsetDateTime.parse(t).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()
                    : LocalDateTime.parse(t);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'" + key + "' must be an ISO date or date-time, got '" + v + "'");
        }
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String num(double d) {
        return Double.toString(d);
    }

    /** Numbers print as JS does (no trailing {@code .0}), as {@code PatternRoutes.label} does. */
    private static String js(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
    }

    private static String q(String ident) {
        return SqlIdent.q(ident);
    }
}
