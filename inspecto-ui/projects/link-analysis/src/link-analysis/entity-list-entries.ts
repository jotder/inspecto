import { EntityListRangeEntry } from '@inspecto/link-analysis/api/inv.service';

/**
 * ASSURE-ENTITY-LISTS-RESIDUALS-1 (2): the analyst's one-entry-per-line text, split into exact keys and range
 * entries for `POST /entity-lists/{id}/members`. Framework-free; the server stays the gate (it normalises, checks
 * equal-length bounds and refuses a CIDR block with host bits set).
 *
 * - `10.1.0.0/16` → `{cidr}` (any line holding a `/`)
 * - `447800000000..447899999999` → `{from, to}`
 * - `+4478*` → `{prefix: "+4478"}`
 * - anything else → an exact key
 */
export interface ParsedEntries {
    keys: string[];
    ranges: EntityListRangeEntry[];
    /** One message per refused line, naming the line. Non-empty ⇒ nothing is sent. */
    errors: string[];
}

export function parseEntityListEntries(text: string): ParsedEntries {
    const keys: string[] = [];
    const ranges: EntityListRangeEntry[] = [];
    const errors: string[] = [];
    text.split(/\r?\n/).forEach((raw, i) => {
        const line = raw.trim();
        if (!line) return;
        const at = `Line ${i + 1}`;
        if (line.includes('/')) {
            if (!/^[0-9A-Fa-f:.]+\/\d{1,3}$/.test(line))
                errors.push(`${at}: “${line}” is not a CIDR block (e.g. 10.1.0.0/16).`);
            else ranges.push({ cidr: line });
        } else if (line.includes('..')) {
            const [from, to, ...rest] = line.split('..').map((s) => s.trim());
            if (rest.length || !from || !to) errors.push(`${at}: a range is written low..high.`);
            else if (from.length !== to.length) errors.push(`${at}: both ends of a range need the same length.`);
            else if (from > to) errors.push(`${at}: the low end is above the high end.`);
            else ranges.push({ from, to });
        } else if (line.endsWith('*')) {
            const prefix = line.slice(0, -1).trim();
            if (!prefix || prefix.includes('*')) errors.push(`${at}: a prefix is written like +4478*.`);
            else ranges.push({ prefix });
        } else if (line.includes('*')) {
            errors.push(`${at}: “*” is only allowed at the end, as a prefix.`);
        } else {
            keys.push(line);
        }
    });
    return { keys, ranges, errors };
}

/** A `datetime-local` value (local time) → the ISO instant the server takes; blank → undefined (permanent). */
export function expiryInstant(local: string, now: Date = new Date()): { iso?: string; error?: string } {
    if (!local.trim()) return {};
    const d = new Date(local);
    if (Number.isNaN(d.getTime())) return { error: 'Not a date and time.' };
    if (d.getTime() <= now.getTime()) return { error: 'The expiry must be in the future.' };
    return { iso: d.toISOString() };
}
