import { MSISDN_PATTERN, normaliseMsisdn } from './investigate-number';
import { laLimit } from './la-limits';

/** Any other id (an account, a device): one token of letters, digits and `. : @ _ - + /`, kept exactly as typed. */
const ID_PATTERN = /^[\w.:@+/-]{1,200}$/;

/** A phone number is normalised (separators dropped); any other id token is kept as typed; null = neither. */
function seedId(part: string): string | null {
    const phone = normaliseMsisdn(part);
    if (MSISDN_PATTERN.test(phone)) return phone;
    return ID_PATTERN.test(part) ? part : null;
}

/** A pasted list of numbers, read: the seeds to send, and what was left out and why (nothing is dropped silently). */
export interface SeedList {
    /** Normalised, de-duplicated, in the order pasted, at most `connect.max_seeds` (the server's seed cap for one `connect`). */
    ids: string[];
    /** Entries that are neither a number nor an id token (illegal characters, or over 200), as typed. */
    invalid: string[];
    /** Valid numbers beyond the cap, normalised. */
    overCap: string[];
    /** How many entries repeated an earlier one. */
    duplicates: number;
}

/**
 * Read a pasted list of MSISDNs or other ids (accounts, devices): split on newlines, commas, semicolons, tabs and spaces, normalise each as the one-number flow does
 * ({@link normaliseMsisdn}), keep the valid ones once each up to the cap. Unknown numbers (not in the Dataset) are not found here -
 * the `connect` step lists the seeds whose first read found no link.
 */
export function parseSeedList(text: string): SeedList {
    const ids: string[] = [];
    const invalid: string[] = [];
    const overCap: string[] = [];
    const seen = new Set<string>();
    let duplicates = 0;
    // a number may be typed with internal spaces ("555 123 4567") only when one entry sits on its own line, so lines come first
    const entries = text
        .split(/[\r\n;,]+/)
        .map((e) => e.trim())
        .filter((e) => e);
    for (const raw of entries) {
        const parts = seedId(raw) !== null ? [raw] : raw.split(/\s+/);
        for (const part of parts) {
            const id = seedId(part);
            if (id === null) {
                invalid.push(part);
                continue;
            }
            if (seen.has(id)) {
                duplicates++;
                continue;
            }
            seen.add(id);
            if (ids.length < laLimit('connect.max_seeds')) ids.push(id);
            else overCap.push(id);
        }
    }
    return { ids, invalid, overCap, duplicates };
}
