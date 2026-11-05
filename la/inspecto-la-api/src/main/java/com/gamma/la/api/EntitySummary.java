package com.gamma.la.api;

import com.gamma.entitystore.EntityTypes;
import com.gamma.la.core.DatasetProvider;
import com.gamma.la.core.DatasetProviders;
import com.gamma.sql.SqlSandboxPolicy;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.gamma.util.SqlIdent.q;

/**
 * The entity summary (LA-ENTITY-SUMMARY-1, plan {@code docs/superpower/la-basic-discovery-plan.md} §3 P2): the lite profile of
 * one entity over ONE Dataset's links, for Basic - first and last seen, its links in and out, its counterparts ranked by links
 * and by the primary Measure, its kinds of link and its activity per day. The full Entity profile pages (every bound Dataset,
 * lineage, relationships, shapes) stay Standard (D-LAE4).
 *
 * <p><b>One statement.</b> The Dataset's links that name the entity at either end are grouped once by
 * {@code GROUPING SETS ((), (o), (k), (d))} - the headline, per counterpart, per kind, per day - and each set is cut by a window
 * rank (V4: a one-key read of IMS's 39.4 M links takes 3 to 4 s, so four reads would not do). Keys are compared normalised, as
 * the Entity profile compares them ({@link EntityKeySql}); every value is bound, every identifier a real, quoted column.
 */
final class EntitySummary {

    private EntitySummary() {}

    /** Counterparts listed per ranking, kinds listed, days of activity (before the entity's last seen day). */
    static final int TOP = 20, MAX_KINDS = 50, DAYS = 90;
    private static final ZoneId UTC = ZoneId.of("UTC");   // DuckDB refuses "Z"

    /**
     * The summary of {@code key} (the normalised typed key) over {@code t}'s links. Counterpart ids are RAW: the route masks
     * them. Throws on a read that failed or ran past {@code timeoutS}.
     */
    static Map<String, Object> summary(PresetRoutes.Target t, EntityTypes.EntityType type, String key, String measureUnit,
                                       int timeoutS) throws Exception {
        PresetQueries.Roles r = t.roles();
        String kf = EntityKeySql.key(type.normaliser(), q(r.from())), kt = EntityKeySql.key(type.normaliser(), q(r.to()));
        // o = the other end; NULL for a self-link and for a link whose other end is empty - such a link counts, it has no
        // counterpart (the entity is never its own counterpart). A self-link counts as out.
        String sql = "WITH __e AS (SELECT CASE WHEN " + kf + " = ? AND " + kt + " = ? THEN NULL WHEN " + kf + " = ? THEN NULLIF(CAST(" + q(r.to())
                + " AS VARCHAR), '') ELSE NULLIF(CAST(" + q(r.from()) + " AS VARCHAR), '') END AS o, (" + kf + " = ?) AS out_, "
                + (r.kind() == null ? "CAST(NULL AS VARCHAR)" : "CAST(" + q(r.kind()) + " AS VARCHAR)") + " AS k, "
                + (r.time() == null ? "CAST(NULL AS TIMESTAMP)" : "TRY_CAST(" + q(r.time()) + " AS TIMESTAMP)") + " AS ts, "
                + (r.amount() == null ? "CAST(NULL AS DOUBLE)" : "TRY_CAST(" + q(r.amount()) + " AS DOUBLE)") + " AS v"
                + " FROM " + q(t.datasetId()) + " WHERE " + kf + " = ? OR " + kt + " = ?),"
                + " __g AS (SELECT GROUPING(o) AS go, GROUPING(k) AS gk, GROUPING(d) AS gd, o, k, d, COUNT(*) AS n,"
                + " COUNT(*) FILTER (WHERE out_) AS n_out, SUM(v) AS a, SUM(v) FILTER (WHERE out_) AS a_out,"
                + " strftime(MIN(ts), '%Y-%m-%dT%H:%M:%SZ') AS mn, strftime(MAX(ts), '%Y-%m-%dT%H:%M:%SZ') AS mx, COUNT(DISTINCT o) AS nc"
                + " FROM (SELECT *, CAST(ts AS DATE) AS d FROM __e) AS __d GROUP BY GROUPING SETS ((), (o), (k), (d))),"
                + " __r AS (SELECT *, row_number() OVER (PARTITION BY go, gk, gd ORDER BY (go = 0 AND o IS NULL), n DESC, o, k) AS rn,"
                + " row_number() OVER (PARTITION BY go, gk, gd ORDER BY (go = 0 AND o IS NULL), a DESC NULLS LAST, o) AS ra,"
                + " MAX(d) OVER (PARTITION BY go, gk, gd) AS lastd FROM __g)"
                + " SELECT go, gk, gd, o, k, CAST(d AS VARCHAR) AS day, n, n_out, a, a_out, mn, mx, nc, rn, ra FROM __r"
                + " WHERE (go = 1 AND gk = 1 AND gd = 1) OR (go = 0 AND o IS NOT NULL AND (rn <= " + TOP + " OR ra <= " + TOP + "))"
                + " OR (gk = 0 AND rn <= " + (MAX_KINDS + 1) + ") OR (gd = 0 AND d > lastd - " + DAYS + ")"
                + " ORDER BY go, gk, gd, rn";
        List<Map<String, Object>> rows = DatasetProviders.require().run(new DatasetProvider.Request(t.datasetId(), t.relationSql(), sql,
                1 + 2 * TOP + 1 + MAX_KINDS + 1 + DAYS + 1, 0, List.of(), List.of(), List.of(key, key, key, key, key, key)),
                SqlSandboxPolicy.withCaps(null, 0, timeoutS), UTC).rows();

        Map<String, Object> head = null;
        List<Map<String, Object>> byLinks = new ArrayList<>(), byAmount = new ArrayList<>(), kinds = new ArrayList<>(), days = new ArrayList<>();
        List<Map<String, Object>> amountRanked = new ArrayList<>();
        boolean kindsCut = false;
        for (Map<String, Object> row : rows) {
            long go = ColumnProfile.num(row.get("go")), gk = ColumnProfile.num(row.get("gk")), gd = ColumnProfile.num(row.get("gd"));
            if (go == 1 && gk == 1 && gd == 1) head = row;
            else if (go == 0) {
                Map<String, Object> c = counterpart(row, r.amount() != null);
                if (ColumnProfile.num(row.get("rn")) <= TOP) byLinks.add(c);
                if (r.amount() != null && ColumnProfile.num(row.get("ra")) <= TOP && row.get("a") != null) amountRanked.add(row);
            } else if (gk == 0 && r.kind() != null) {
                if (ColumnProfile.num(row.get("rn")) > MAX_KINDS) { kindsCut = true; continue; }
                Map<String, Object> k = new LinkedHashMap<>();
                k.put("kind", row.get("k"));
                k.put("links", ColumnProfile.num(row.get("n")));
                k.put("out", ColumnProfile.num(row.get("n_out")));
                k.put("in", ColumnProfile.num(row.get("n")) - ColumnProfile.num(row.get("n_out")));
                kinds.add(k);
            } else if (gd == 0 && r.time() != null && row.get("day") != null) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("day", String.valueOf(row.get("day")));
                d.put("links", ColumnProfile.num(row.get("n")));
                days.add(d);
            }
        }
        amountRanked.sort((a, b) -> Long.compare(ColumnProfile.num(a.get("ra")), ColumnProfile.num(b.get("ra"))));
        for (Map<String, Object> row : amountRanked) byAmount.add(counterpart(row, true));
        days.sort((a, b) -> String.valueOf(a.get("day")).compareTo(String.valueOf(b.get("day"))));

        long links = head == null ? 0 : ColumnProfile.num(head.get("n")), out = head == null ? 0 : ColumnProfile.num(head.get("n_out"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("links", links);
        m.put("linksOut", out);
        m.put("linksIn", links - out);
        m.put("counterparts", head == null ? 0 : ColumnProfile.num(head.get("nc")));
        m.put("firstSeen", head == null ? null : head.get("mn"));
        m.put("lastSeen", head == null ? null : head.get("mx"));
        Map<String, Object> measure = null;
        if (r.amount() != null) {
            measure = new LinkedHashMap<>();
            measure.put("column", r.amount());
            measure.put("unit", measureUnit);
            Double total = head == null ? null : EntityProfiles.number(head.get("a"));
            Double sent = head == null ? null : EntityProfiles.number(head.get("a_out"));
            measure.put("out", sent);
            measure.put("in", total == null ? null : EntityProfiles.number(total - (sent == null ? 0 : sent)));
        }
        m.put("measure", measure);
        m.put("counterpartsByLinks", byLinks);
        m.put("counterpartsByAmount", r.amount() == null ? null : byAmount);
        m.put("kinds", r.kind() == null ? null : kinds);
        m.put("kindsTruncated", kindsCut);
        m.put("activity", r.time() == null ? null : days);
        return m;
    }

    private static Map<String, Object> counterpart(Map<String, Object> row, boolean withAmount) {
        Map<String, Object> c = new LinkedHashMap<>();
        long n = ColumnProfile.num(row.get("n")), out = ColumnProfile.num(row.get("n_out"));
        c.put("entity", row.get("o") == null ? null : String.valueOf(row.get("o")));
        c.put("links", n);
        c.put("out", out);
        c.put("in", n - out);
        if (withAmount) c.put("amount", EntityProfiles.number(row.get("a")));
        return c;
    }
}
