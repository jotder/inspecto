/**
 * The IANA time-zone vocabulary offered wherever a **source zone** is authored — the pipeline-level
 * `parsing.source_timezone` and the per-column `raw.fields[].timezone`.
 *
 * <p><b>Why a list and not free text.</b> The engine refuses an unknown zone at config load, because
 * DuckDB raises a hard error on one at run time and `TRY()` does not catch it — a typo that loaded
 * would kill every batch. Offering the vocabulary is what keeps that refusal off the operator's path.
 *
 * <p>⚠ <b>Suggestions, not the gate.</b> The server validates against the JVM's
 * `ZoneId.getAvailableZoneIds()` (~604 ids); this is the runtime's ICU list (~418), a strict subset, so
 * everything offered here is accepted. But the two disagree on WHICH SPELLING they carry, and not in
 * the direction you would guess — measured on Node 24 / ICU: this list has **`Asia/Calcutta` and NOT
 * `Asia/Kolkata`**, while the JVM has both. So a config may legitimately name a zone this list never
 * offers, in either direction. The field is therefore free text over a `<datalist>` (and
 * `<inspecto-option-picker>` renders an unmatched value verbatim) — a stored zone always displays and
 * survives a save untouched. ⛔ Never "fix" that by filtering a stored value against this list.
 *
 * <p>⛔ <b>Offset forms are deliberately absent.</b> `+05:30` and `Z` are what an operator reaches for
 * first, and DuckDB rejects both (`Unknown TimeZone`) — a fixed offset also cannot express daylight
 * saving, which is the whole reason a region id is the right unit. The engine refuses them by name.
 */

/**
 * Every offerable zone id, sorted. Empty when the runtime has no `Intl.supportedValuesOf` — the
 * picker then still accepts and displays a stored value, so the field degrades to a plain text box
 * rather than blocking the form.
 */
export function ianaTimeZones(): string[] {
    try {
        // ⚠ `Intl.supportedValuesOf('timeZone')` returns the canonical region list, which does NOT
        // include `UTC` — measured, not assumed. That is the single likeliest answer an operator has
        // ("the file is already in UTC"), and the engine accepts it, so omitting it would send them
        // hunting for `Etc/UTC` or typing an offset the engine refuses. Added explicitly, de-duped in
        // case a future runtime does list it.
        return [...new Set(['UTC', ...Intl.supportedValuesOf('timeZone')])].sort();
    } catch {
        return [];
    }
}

/**
 * The zone list as `<inspecto-option-picker>` options, with a leading blank meaning *inherit*.
 *
 * <p>The blank entry is a **named choice, not "unset"** (the shared picker idiom): picking it writes
 * no key, which is exactly what "inherit the pipeline default, or stay wall-clock" is in the config.
 *
 * @param blankLabel what the absence of a zone means at this level — the two call sites differ
 */
export function timeZoneOptions(blankLabel: string): { value: string; label: string }[] {
    return [{ value: '', label: blankLabel }, ...ianaTimeZones().map((id) => ({ value: id, label: id }))];
}
