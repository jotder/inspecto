package com.gamma.la.api;

import com.gamma.access.ComponentAccess;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.FingerprintBudget;
import com.gamma.la.core.InvestigationEvaluator;
import com.gamma.spi.http.ApiContext;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The Dossier's <b>data-trust footer</b> (LA-DATA-TRUST-1 S9): how fresh and how complete the Investigation's Dataset was
 * when the Dossier was exported - data-as-of, the index version and whether it is stale, the days with no rows, the Files
 * still waiting for the index.
 *
 * <p>🔴 <b>Never sealed.</b> The footer is assembled in the route AFTER {@link GraphDossierBuilder#build} and is a sibling of
 * {@code manifest}, never inside it, so the manifest {@code root}, {@code /dossier/verify} and replay cannot see it; on the
 * bundle it is excluded from {@link DossierBundleRoutes#seal} next to {@code generatedAt}. It is evidence of the
 * data's state at export time, not of the Investigation, and an edited footer does NOT fail verification - it says so itself
 * ({@code sealed:false}).
 *
 * <p>Every reading is reused, none re-derived: {@link InvestigationCoverageRoutes#gaps} (days), {@link DatasetFilesRoutes#read}
 * (Files), {@link IndexRoutes#indexesOf} (index), {@code DatasetProvider#dataAsOf} (as-of). Counts, days and versions only: no entity
 * key, no File name, no Collector, so there is nothing to mask. A part that cannot be read is {@code null} and the footer names
 * it in {@code notAssessed} - an absent reading is never "no gaps".
 */
final class DossierDataFooter {

    static final String NOTE = "The state of the Dataset at export time. It is NOT part of the custody hash or the bundle seal, it is "
            + "not signed, and editing it does not fail verification: it is evidence of the data's state when exported, not evidence "
            + "about the Investigation.";

    private DossierDataFooter() {}

    static Map<String, Object> build(ApiContext api, HttpExchange ex, DossierRoutes.Opened o, List<String> log, int at) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("sealed", false);
        f.put("generatedAt", Instant.now().toString());
        f.put("note", NOTE);
        String id = o.inv().dataset();
        f.put("dataset", id);
        List<String> notAssessed = new ArrayList<>();
        f.put("asOf", null);
        f.put("asOfDay", null);
        f.put("indexVersion", null);
        f.put("indexStale", null);
        f.put("filesWaiting", null);
        f.put("window", null);
        f.put("expectedDays", null);
        f.put("missingDays", null);
        f.put("notAssessed", notAssessed);

        Map<String, Object> dataset = id == null ? null : DatasetProviders.require().dataset(o.writeRoot(), id).orElse(null);
        if (dataset == null || !ComponentAccess.canView(ex, dataset)) {
            notAssessed.add("dataset");                    // gone or not viewable: say nothing about it, as the other Dataset reads do
            return f;
        }
        try {
            OptionalLong asOf = DatasetProviders.require().dataAsOf(dataset, id, api.dataRoot(), o.writeRoot());
            if (asOf.isPresent()) {
                f.put("asOf", Instant.ofEpochMilli(asOf.getAsLong()).toString());
                f.put("asOfDay", coveredDay(asOf.getAsLong()));
            } else notAssessed.add("asOf");
        } catch (RuntimeException unreadable) {
            notAssessed.add("asOf");
        }
        try {
            Optional<Map<String, Object>> newest = IndexRoutes.indexesOf(api, o.writeRoot(), new com.gamma.la.core.DatasetProvider.Entry(id, dataset),
                    FingerprintBudget.forRequest()).stream().max(Comparator.comparing(m -> String.valueOf(m.get("builtAt"))));
            if (newest.isPresent()) {
                f.put("indexVersion", newest.get().get("version"));
                f.put("indexStale", newest.get().get("stale"));
            } else notAssessed.add("index");
        } catch (RuntimeException unreadable) {
            notAssessed.add("index");
        }
        List<?> fileDays = List.of();
        try {
            Map<String, Object> files = DatasetFilesRoutes.read(api, o.writeRoot(), dataset, id, 1, 0, null);
            if (Boolean.TRUE.equals(files.get("assessed")) && files.get("summary") instanceof Map<?, ?> s) f.put("filesWaiting", s.get("waiting"));
            else notAssessed.add("files");
            if (files.get("days") instanceof List<?> d) fileDays = d;
        } catch (RuntimeException unreadable) {
            notAssessed.add("files");
        }
        days(api, ex, o, log, at, f, notAssessed, fileDays);
        return f;
    }

    /**
     * The last UTC day the Dataset covers, ISO. An instant at exactly UTC midnight is how {@code dataAsOf} states "covered through
     * the end of a DATE" (newest event day 2026-09-04 reads {@code 2026-09-05T00:00:00Z}), so it names the day BEFORE - the same
     * reading the SPA's {@code formatDay} gives the strip; any other instant names its own day.
     */
    static String coveredDay(long asOfMillis) {
        Instant at = Instant.ofEpochMilli(asOfMillis);
        java.time.LocalDate d = at.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        return (asOfMillis % 86_400_000L == 0 ? d.minusDays(1) : d).toString();
    }

    /** The missing days over the window in force at the Dossier's step; not assessed without a time column or a bounded window. */
    @SuppressWarnings("unchecked")
    private static void days(ApiContext api, HttpExchange ex, DossierRoutes.Opened o, List<String> log, int at, Map<String, Object> f,
                             List<String> notAssessed, List<?> fileDays) {
        try {
            if (o.inv().header().get("timeCol") == null) {
                notAssessed.add("days");
                return;
            }
            List<Map<String, Object>> entries = new ArrayList<>();
            for (String line : log) entries.add(ApiContext.JSON.readValue(line, Map.class));
            Map<String, Object> window = InvestigationEvaluator.evaluate(entries, at, null).window;
            if (window == null || window.get("from") == null || window.get("to") == null) {
                // An Investigation with no window still has days to judge: the span of the Files delivered (S10 live drive).
                window = filesSpan(fileDays);
                if (window == null) {
                    notAssessed.add("days");
                    return;
                }
                f.put("windowBasis", "files");
            }
            InvestigationCoverageRoutes.Gaps g = InvestigationCoverageRoutes.gaps(api, ex, o.inv(), window);
            f.put("window", Map.of("from", String.valueOf(window.get("from")), "to", String.valueOf(window.get("to")), "zone", g.zone()));
            f.put("expectedDays", g.expected().size());
            f.put("missingDays", g.missing());
        } catch (java.io.IOException | RuntimeException unreadable) {
            notAssessed.add("days");
        }
    }

    /** [first Files day 00:00Z, day after the last 00:00Z), or null when no Files day is known. */
    static Map<String, Object> filesSpan(List<?> fileDays) {
        java.util.TreeSet<java.time.LocalDate> days = new java.util.TreeSet<>();
        for (Object d : fileDays) {
            if (d instanceof Map<?, ?> m && m.get("day") instanceof String day) {
                try {
                    days.add(java.time.LocalDate.parse(day));
                } catch (java.time.format.DateTimeParseException ignored) {
                    // a day that is not an ISO date takes no part in the span
                }
            }
        }
        if (days.isEmpty()) return null;
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("from", days.first() + "T00:00:00Z");
        w.put("to", days.last().plusDays(1) + "T00:00:00Z");
        return w;
    }
}
