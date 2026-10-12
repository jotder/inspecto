package com.gamma.la.api;

import com.gamma.audit.Event;
import com.gamma.audit.EventSink;
import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.entitystore.MaskTokens;
import com.gamma.access.WriteGates;
import com.gamma.la.core.DatasetProvider;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.util.ColumnClassification;
import com.gamma.util.SqlIdent;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code POST /inv/reference-rows} — the rows of a reference Dataset for a SMALL set of entity keys, so the Link Analysis
 * node dialog's Risk / Enrichment sections and the server-algorithms panel's "Fill nodeScores from indicators" never read
 * a whole Dataset over {@code /db/query} (which masks nothing) and look entities up in the browser.
 *
 * <p><b>Body</b> {@code {dataset, keyCol, keys: string[] (1..}{@value #MAX_KEYS}{@code ), columns?: string[], investigationId?}}.
 * <b>Response</b> {@code {dataset, keyCol, columns, rows:[{key, row}], matchedKeys, unresolved, truncated, masking}}: each
 * row is tagged with the key AS ASKED (so a caller holding aliases maps rows back without ever seeing a raw id), at most
 * {@value #MAX_ROWS} rows with {@code truncated} saying the cap bit; {@code unresolved} lists the asked ALIASES the server
 * does not know, and {@code rawKeysRefused} counts the raw keys the masking hides (counted, never echoed: a response under
 * masking carries no raw id, even one the caller sent).
 *
 * <p><b>Gates, fail closed in order:</b> write root unset 503 · malformed body 422 · more than {@value #MAX_KEYS} keys 413
 * · {@code investigationId} given: the Investigation read gate (404 absent, 403 not a reader) · unknown or not-viewable
 * Dataset 404 (R3, the {@link InvRoutes#relationFor} gate every {@code /inv} Dataset read shares — all or nothing) ·
 * more keys than the Space's four-eyes row budget 403 ({@link InvRoutes#refuseIfSensitive}, as every stateless read) ·
 * a {@code keyCol}/{@code columns} name the relation has not got 422. Identifiers are validated AND quoted; key values are
 * only ever BOUND parameters.
 *
 * <p><b>Keys in</b> — resolved the way the other {@code /inv} reads resolve them: a pseudonym the named Investigation
 * issued ({@link EntityMasking#reveal}), else an alias an exploration read served for this Space
 * ({@link ExplorationMasking#lookup}). Under masking a RAW key is treated as naming nothing (as {@code /inv/graph/runs}
 * treats a hidden raw id), so the route is never an oracle for raw identifiers.
 *
 * <p><b>Values out</b> — the Space's {@code maskingMode}, the {@link ExplorationMasking} rule: {@code none} shows raw. Else
 * the key column is masked whenever the exploration rule masks {@code keyCol} or any asked key was an alias/pseudonym (the
 * caller is not entitled to the raw value it stands for), and every other column is masked when its registry or lineage
 * classification is claimed by a masked Entity Type or is one of {@link ColumnClassification#SENSITIVE}, and under
 * {@code all} every value is masked too except fractional numbers and booleans (indicator scores) — an integral column
 * may be an unclassified id; an alias resolves only when the exploration rule covers {@code keyCol}, so it cannot be
 * matched against an arbitrary column as a membership oracle; an untraceable
 * lineage masks every column (fail closed). A masked value is the Space's exploration alias, the same one every other
 * exploration read shows for that id.
 */
final class ReferenceRows {

    static final int MAX_KEYS = 500;
    static final int MAX_ROWS = 2_000;
    private static final String KEY_ALIAS_BASE = "__ref_key";

    private ReferenceRows() {}

    static Object handle(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "reference rows read");                    // 503
        String datasetId = ApiContext.str(body, "dataset");
        if (datasetId == null || datasetId.isBlank())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'dataset'");
        String keyCol = InvRoutes.ident(body, "keyCol", true);                                          // 422
        List<String> wanted = identList(body.get("columns"));
        List<String> keys = keyList(body.get("keys"));                                                  // 422 · 413
        String invId = ApiContext.str(body, "investigationId");

        EntityMasking invMask = null;
        if (invId != null) {
            InvestigationRoutes.Inv inv = InvestigationRoutes.openForRead(api, ex, invId);             // 404 · 403
            invMask = EntityMasking.of(inv, List.of());
        }
        String relationSql = InvRoutes.relationFor(api, ex, writeRoot, datasetId);                     // 404 · 422
        // D-U7: a reference Dataset holds one row per entity, so the keys asked bound the entities read (no fan-out).
        InvRoutes.refuseIfSensitive(writeRoot, "a reference read of " + keys.size() + " keys", keys.size(), 0);   // 403

        List<String> relationCols = InvRoutes.relationColumns(datasetId, relationSql);
        String keyName = canonical(relationCols, keyCol);
        // the internal key column must not shadow a real one: suffix until it names no column of the relation
        String keyAlias = KEY_ALIAS_BASE;
        for (int i = 1; InvRoutes.containsIgnoreCase(relationCols, keyAlias); i++) keyAlias = KEY_ALIAS_BASE + "_" + i;
        List<String> cols = new ArrayList<>();
        for (String w : wanted.isEmpty() ? relationCols : wanted) {
            String c = canonical(relationCols, w);
            if (!cols.contains(c)) cols.add(c);
        }
        if (!cols.contains(keyName)) cols.add(0, keyName);

        ExplorationMasking explore = ExplorationMasking.of(writeRoot, datasetId, Map.of("sourceCol", keyName, "targetCol", keyName));
        String mode = LinkAnalysisSettings.forRoot(writeRoot).effectiveMaskingMode();
        boolean masking = !"none".equals(mode);

        // keys in: as asked -> raw
        Map<String, List<String>> askedByRaw = new LinkedHashMap<>();
        List<String> unresolved = new ArrayList<>();
        int rawRefused = 0;
        boolean aliasAsked = false;
        for (String k : keys) {
            String raw = null;
            if (k.startsWith(MaskTokens.TOKEN_PREFIX)) {
                aliasAsked = true;
                // an alias resolves only against a key column the masking rule covers: matched against any other column,
                // the raw value behind it would answer "is this entity in that column?" without ever being shown
                if (explore.active()) {
                    if (invMask != null) raw = invMask.reveal(k);
                    if (raw == null) raw = ExplorationMasking.lookup(writeRoot, k);
                }
            } else if (!(invMask != null && invMask.hidesRaw(k)) && !explore.active()) {
                raw = k;
            }
            if (raw == null && k.startsWith(MaskTokens.TOKEN_PREFIX)) unresolved.add(k);
            else if (raw == null) rawRefused++;
            else askedByRaw.computeIfAbsent(raw, r -> new ArrayList<>()).add(k);
        }

        boolean maskKey = masking && (explore.active() || aliasAsked);
        ExplorationMasking out = maskKey && !explore.active() ? ExplorationMasking.forced(writeRoot, mode,
                "an aliased key was asked, so its entity id is shown as an alias") : explore;
        Set<String> maskedCols = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (masking) maskedCols.addAll(classifiedColumns(writeRoot, datasetId, cols));
        if (maskKey) maskedCols.add(keyName);
        ExplorationMasking colMask = maskedCols.isEmpty() ? out : ExplorationMasking.forced(writeRoot, mode, "classified column");

        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        Set<String> matched = new LinkedHashSet<>();
        if (!askedByRaw.isEmpty()) {
            StringBuilder sql = new StringBuilder("SELECT ");
            for (String c : cols) sql.append(SqlIdent.q(c)).append(", ");
            sql.append("CAST(").append(SqlIdent.q(keyName)).append(" AS VARCHAR) AS ").append(SqlIdent.q(keyAlias))
               .append(" FROM ").append(SqlIdent.q(datasetId)).append(" WHERE ");
            List<String> binds = new ArrayList<>(askedByRaw.keySet());
            sql.append(keyPredicate(SqlIdent.q(keyName), keyType(datasetId, relationSql, keyName), binds));
            sql.append(" ORDER BY ").append(SqlIdent.q(keyAlias));
            DatasetProvider.Result r;
            try {
                r = DatasetProviders.require().run(new DatasetProvider.Request(datasetId, relationSql, sql.toString(),
                        MAX_ROWS, 0, List.of(), List.of(), binds));
            } catch (SQLException e) {
                throw DatasetReadFault.of("reference read of dataset '" + datasetId + "' failed", datasetId, null, e);
            }
            truncated = r.truncated();
            for (Map<String, Object> row : r.rows()) {
                String raw = String.valueOf(row.get(keyAlias));
                Map<String, Object> shown = new LinkedHashMap<>();
                for (String c : cols) {
                    Object v = row.get(c);
                    // `all` masks every id: text and integral values (an unclassified BIGINT msisdn / imsi) are ids until
                    // proven otherwise; only fractional numbers and flags - indicator scores - pass
                    boolean hide = maskedCols.contains(c) || "all".equals(mode) && v != null && !passesUnderAll(v);
                    shown.put(c, hide ? colMask.out(v) : v);
                }
                for (String asked : askedByRaw.getOrDefault(raw, List.of())) {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("key", asked);
                    o.put("row", shown);
                    rows.add(o);
                    matched.add(asked);
                }
            }
        }

        audit(ex, datasetId, keys.size(), matched.size(), rows.size(), truncated);
        Map<String, Object> describe = out.describe();
        describe.put("masked", maskKey || !maskedCols.isEmpty());
        describe.put("maskedColumns", List.copyOf(maskedCols));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("dataset", datasetId);
        res.put("keyCol", keyName);
        res.put("columns", cols);
        res.put("rows", rows);
        res.put("matchedKeys", matched.size());
        res.put("unresolved", unresolved);
        res.put("rawKeysRefused", rawRefused);
        res.put("truncated", truncated);
        res.put("masking", describe);
        return res;
    }

    /** Integer column types a key can be bound as, with their inclusive ranges. */
    private static final Map<String, java.math.BigInteger[]> INTEGER_RANGES = Map.of(
            "TINYINT", range(Byte.MIN_VALUE, Byte.MAX_VALUE), "SMALLINT", range(Short.MIN_VALUE, Short.MAX_VALUE),
            "INTEGER", range(Integer.MIN_VALUE, Integer.MAX_VALUE), "BIGINT", range(Long.MIN_VALUE, Long.MAX_VALUE),
            "UTINYINT", range(0, 255), "USMALLINT", range(0, 65_535), "UINTEGER", range(0, 4_294_967_295L),
            "UBIGINT", new java.math.BigInteger[]{java.math.BigInteger.ZERO,
                    java.math.BigInteger.ONE.shiftLeft(64).subtract(java.math.BigInteger.ONE)});

    private static java.math.BigInteger[] range(long lo, long hi) {
        return new java.math.BigInteger[]{java.math.BigInteger.valueOf(lo), java.math.BigInteger.valueOf(hi)};
    }

    /**
     * The key-match predicate, shaped so DuckDB can prune row groups by min/max: a VARCHAR key column is compared
     * directly; an integer key column is compared to typed binds when EVERY key is the canonical decimal spelling of a
     * value in that type's range (so the match is exactly the {@code CAST(.. AS VARCHAR)} match); anything else falls
     * back to the CAST form, which never fails on a non-numeric key. Keys are always bound, never inlined.
     */
    static String keyPredicate(String quotedKey, String keyType, List<String> keys) {
        String t = keyType == null ? "" : keyType.trim().toUpperCase(Locale.ROOT);
        String lhs, slot;
        if ("VARCHAR".equals(t)) {
            lhs = quotedKey;
            slot = "?";
        } else if (INTEGER_RANGES.containsKey(t) && keys.stream().allMatch(k -> canonicalInteger(k, INTEGER_RANGES.get(t)))) {
            lhs = quotedKey;
            slot = "CAST(? AS " + t + ")";
        } else {
            lhs = "CAST(" + quotedKey + " AS VARCHAR)";
            slot = "?";
        }
        StringBuilder sb = new StringBuilder(lhs).append(" IN (");
        for (int i = 0; i < keys.size(); i++) sb.append(i == 0 ? "" : ", ").append(slot);
        return sb.append(')').toString();
    }

    private static boolean canonicalInteger(String k, java.math.BigInteger[] range) {
        if (!k.matches("-?(0|[1-9][0-9]{0,39})") || "-0".equals(k)) return false;
        java.math.BigInteger v = new java.math.BigInteger(k);
        return v.compareTo(range[0]) >= 0 && v.compareTo(range[1]) <= 0;
    }

    /** The key column's DuckDB type name ({@code typeof} of one row), or {@code null} when unknown (CAST fallback). */
    private static String keyType(String datasetId, String relationSql, String keyName) {
        try {
            DatasetProvider.Result r = DatasetProviders.require().run(new DatasetProvider.Request(datasetId, relationSql,
                    "SELECT typeof(" + SqlIdent.q(keyName) + ") AS t FROM " + SqlIdent.q(datasetId) + " LIMIT 1",
                    1, 0, List.of(), List.of()));
            return r.rows().isEmpty() ? null : String.valueOf(r.rows().get(0).get("t"));
        } catch (Exception unknown) {
            return null;
        }
    }

    /** Under {@code all}: only floating / decimal numbers and booleans are shown unmasked. */
    private static boolean passesUnderAll(Object v) {
        return v instanceof Boolean || v instanceof Double || v instanceof Float || v instanceof java.math.BigDecimal;
    }

    /** The relation's own spelling of {@code name}, or 422 naming it. */
    private static String canonical(List<String> relationCols, String name) {
        for (String c : relationCols) if (c.equalsIgnoreCase(name)) return c;
        throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unknown column '" + name + "' — not a column of the dataset");
    }

    private static List<String> identList(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'columns' must be a list");
        List<String> out = new ArrayList<>();
        for (Object o : list)
            out.add(InvRoutes.ident(Map.of("columns", String.valueOf(o)), "columns", true));
        return out;
    }

    private static List<String> keyList(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'keys', a non-empty list of strings");
        if (list.size() > MAX_KEYS)
            throw new ApiException(413, ErrorCodes.PAYLOAD_TOO_LARGE, "at most " + MAX_KEYS + " keys per call, got " + list.size()
                    + " — ask in chunks");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Object o : list) {
            if (!(o instanceof String s) || s.isBlank())
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "every 'keys' entry must be a non-blank string");
            out.add(s.trim());
        }
        return List.copyOf(out);
    }

    /** Columns of {@code cols} whose classification is claimed by a masked Entity Type or is SENSITIVE; untraceable lineage -> all. */
    private static Set<String> classifiedColumns(Path writeRoot, String datasetId, List<String> cols) {
        List<EntityTypes.EntityType> types = LinkAnalysisSettings.forRoot(writeRoot).effectiveEntityTypes();
        java.util.function.Predicate<String> masked = cls -> {
            String c = ColumnClassification.normalise(cls);
            if (c == null) return false;
            if (ColumnClassification.SENSITIVE.contains(c)) return true;
            return types.stream().anyMatch(t -> t.masked() && t.classifications().stream().anyMatch(x -> x.trim().equalsIgnoreCase(c)));
        };
        DatasetProvider provider = DatasetProviders.require();
        Map<String, Object> ds = provider.dataset(writeRoot, datasetId).orElse(Map.of());
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, String> lineage = provider.schemaClassification(writeRoot, ds, masked);
        if (lineage.containsKey(DatasetProvider.UNKNOWN_LINEAGE)) {
            out.addAll(cols);
            return out;
        }
        for (String c : cols) {
            String cls = lineage.get(c.toLowerCase(Locale.ROOT));
            if (cls != null && masked.test(cls)) out.add(c);
        }
        if (ds.get("columns") instanceof List<?> reg)
            for (Object o : reg)
                if (o instanceof Map<?, ?> m && m.get("classification") != null && masked.test(String.valueOf(m.get("classification"))))
                    for (String c : cols) if (c.equalsIgnoreCase(String.valueOf(m.get("name")))) out.add(c);
        return out;
    }

    /** Best effort, like every LA read (LA-04): never fails the call; keys are counted, never named. */
    private static void audit(HttpExchange ex, String datasetId, int asked, int matched, int rows, boolean truncated) {
        try {
            EventSink.current().emit(Event.builder(LinkEventTypes.LINK_REFERENCE_READ).source("inv")
                    .message("link.reference.read " + datasetId + " — " + matched + " of " + asked + " keys, " + rows + " rows"
                            + (truncated ? " (truncated)" : ""))
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("link.reference.read").actionCategory("analysis")
                    .target("dataset", datasetId)
                    .attr("dataset", datasetId).attr("keys", asked).attr("matchedKeys", matched)
                    .attr("rows", rows).attr("truncated", truncated));
        } catch (RuntimeException ignore) {
            // best effort — the audit must never fail the analyst's read
        }
    }
}
