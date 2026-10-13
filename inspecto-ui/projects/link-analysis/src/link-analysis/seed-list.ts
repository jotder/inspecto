import { MSISDN_PATTERN, normaliseMsisdn } from './investigate-number';

/** The server's seed cap for one `connect` (`ConnectSeeds.MAX_SEEDS`): 20 seeds are 190 pairs from 20 searches. */
export const MAX_CONNECT_SEEDS = 20;
/** The server's hop ceiling for one `connect` (`ConnectSeeds.MAX_HOPS`). */
export const MAX_CONNECT_HOPS = 3;

/** A pasted list of numbers, read: the seeds to send, and what was left out and why (nothing is dropped silently). */
export interface SeedList {
    /** Normalised, de-duplicated, in the order pasted, at most {@link MAX_CONNECT_SEEDS}. */
    ids: string[];
    /** Entries that are not a number (6 to 15 digits, optional `+`), as typed. */
    invalid: string[];
    /** Valid numbers beyond the cap, normalised. */
    overCap: string[];
    /** How many entries repeated an earlier one. */
    duplicates: number;
}

/**
 * Read a pasted list of MSISDNs: split on newlines, commas, semicolons, tabs and spaces, normalise each as the one-number flow does
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
        const parts = MSISDN_PATTERN.test(normaliseMsisdn(raw)) ? [raw] : raw.split(/\s+/);
        for (const part of parts) {
            const id = normaliseMsisdn(part);
            if (!MSISDN_PATTERN.test(id)) {
                invalid.push(part);
                continue;
            }
            if (seen.has(id)) {
                duplicates++;
                continue;
            }
            seen.add(id);
            if (ids.length < MAX_CONNECT_SEEDS) ids.push(id);
            else overCap.push(id);
        }
    }
    return { ids, invalid, overCap, duplicates };
}
