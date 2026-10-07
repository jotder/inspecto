package com.gamma.config.spec;

import java.time.ZoneId;
import java.util.List;

/**
 * What a <b>source time zone</b> and a temporal <b>format list</b> are allowed to say.
 *
 * <p>This is the shared grammar behind two gates that must never disagree:
 * <ul>
 *   <li>the engine's fail-closed refusal at config load ({@code com.gamma.etl.SourceZones}, which
 *       delegates here), and</li>
 *   <li>the control plane's {@code CrossFieldRule}s in {@link ConfigSpecs#pipeline()}, which turn the
 *       same refusals into a 422 at the moment of authoring.</li>
 * </ul>
 *
 * <p>⚠ <b>It lives here, in the LOWER module, on purpose.</b> {@code inspecto-config} sits below
 * {@code inspecto-etl}, so a rule in {@code ConfigSpecs} cannot import the engine's copy — the
 * codebase's usual answer to that is a mirror plus a test pinning the two equal (see
 * {@code processing.unpack.data_extensions} in {@link ConfigSpecs}). These predicates need nothing
 * from the engine, so they are <b>delegated to rather than mirrored</b> and there is no second copy
 * to drift. Do not move them up.
 *
 * <p>Both rules are measured facts about DuckDB, not style choices — see
 * {@code okf/backend/engine/duckdb.md} §<i>The source time zone for temporal data</i>.
 */
public final class SourceZoneGrammar {

    private SourceZoneGrammar() {}

    /**
     * Why {@code zone} is not an acceptable source zone, or {@code null} if it is.
     *
     * <p>Accepts exactly {@link ZoneId#getAvailableZoneIds()}. 🔴 <b>{@code ZoneId.of} alone is NOT a
     * valid gate</b>: it accepts the offset forms {@code +05:30} and {@code Z}, which DuckDB's
     * {@code timezone()} rejects outright as <i>Unknown TimeZone</i>. The available-ids set is
     * measured to be a strict subset of DuckDB's {@code pg_timezone_names()}, so anything passing here
     * is guaranteed to evaluate; it also excludes the lower-case spellings DuckDB would accept but no
     * other config key allows.
     *
     * <p>⚠ Not to be confused with {@code ConfigSpecs.meta()}'s {@code domain-timezone-resolvable}
     * rule, which uses plain {@code ZoneId.of} — correctly, because that value is resolved by the
     * <em>JVM</em> for date math and never reaches DuckDB. Same-looking check, different engine,
     * different correct answer.
     */
    public static String zoneRefusal(String zone, String origin) {
        if (zone == null || zone.isBlank())
            return origin + " is blank — remove the key or name a zone";
        if (ZoneId.getAvailableZoneIds().contains(zone)) return null;
        String hint = zone.startsWith("+") || zone.startsWith("-") || "Z".equals(zone)
                ? " — a fixed offset is not accepted (it cannot express daylight saving); use the "
                  + "IANA region id for the source's zone, e.g. 'Asia/Kolkata'"
                : " — use an IANA region id such as 'Asia/Kolkata', 'Europe/Berlin' or 'UTC'";
        return origin + ": unknown time zone '" + zone + "'" + hint;
    }

    /**
     * The zone directive in a strptime format, or {@code 0} for none.
     *
     * <p>🔴 There are exactly two, {@code %z} (numeric offset) and {@code %Z} (zone name), found by
     * sweeping every ASCII letter against the live engine rather than by reading the strptime docs.
     * Both make {@code TRY_STRPTIME} return {@code TIMESTAMP WITH TIME ZONE}; every other accepted
     * directive returns a naive type.
     *
     * <p>⚠ {@code %%} is an escaped literal percent, so the scan consumes it as a pair — {@code '%%z'}
     * is the two characters {@code %z} in the input text and is NOT a directive, while {@code '%%%z'}
     * (a literal percent then a real one) is.
     */
    public static char zoneDirectiveIn(String format) {
        if (format == null) return 0;
        for (int i = 0; i < format.length() - 1; i++) {
            if (format.charAt(i) != '%') continue;
            char next = format.charAt(i + 1);
            if (next == '%') { i++; continue; }          // escaped literal — consume the pair
            if (next == 'z' || next == 'Z') return next;
        }
        return 0;
    }

    /**
     * Why {@code formats} is not an acceptable format list, or {@code null} if it is.
     *
     * <p><b>Why a zone directive is refused rather than honoured.</b> {@code TRY_STRPTIME} reads the
     * offset correctly, and then the trailing {@code ::TIMESTAMP} every caller applies re-renders that
     * instant in the <b>session zone, i.e. the host</b>, throwing the offset away. Measured on DuckDB
     * 1.5.2.1: {@code 2026-03-01 10:00:00+00:00} lands as {@code 15:30} / {@code 11:00} / {@code 10:00}
     * / {@code 05:00} under {@code Asia/Calcutta} / {@code Europe/Berlin} / {@code UTC} /
     * {@code America/New_York}. It is worse with a declared source zone (the host render happens first,
     * so the value can land on a different calendar day per host), and a date-format entry moves
     * {@code DATE_*} partition keys the same way.
     *
     * <p>⛔ Making the data's own offset <em>win</em> — a precedence tier above
     * {@code raw.fields[].timezone_column} — is a deliberate build, not something to reach by relaxing
     * this gate.
     */
    public static String formatRefusal(List<String> formats, String origin) {
        if (formats == null) return null;
        for (String fmt : formats) {
            char directive = zoneDirectiveIn(fmt);
            if (directive == 0) continue;
            return origin + ": format '" + fmt + "' uses the zone directive '%" + directive
                    + "', which is not supported. The offset it parses is then re-rendered in the "
                    + "SERVER's zone, so the same file would import differently on different machines "
                    + "— the opposite of what a source zone is for. Remove the directive and declare "
                    + "the zone instead: parsing.source_timezone for the whole pipeline, or "
                    + "raw.fields[].timezone / .timezone_column for one column. (Write '%%" + directive
                    + "' if you meant a literal '%" + directive + "' in the text.)";
        }
        return null;
    }
}
