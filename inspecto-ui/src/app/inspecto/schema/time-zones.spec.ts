import { describe, expect, it } from 'vitest';
import { ianaTimeZones, timeZoneOptions } from './time-zones';

/**
 * The zone vocabulary offered wherever a source zone is authored. The load-bearing property is not
 * "the list is long" but that everything on it is a value the ENGINE accepts — the server gates on
 * IANA region ids and refuses the offset forms an operator reaches for first.
 */
describe('time-zones', () => {
    it('offers real IANA region ids', () => {
        const zones = ianaTimeZones();
        expect(zones.length).toBeGreaterThan(100);
        expect(zones).toContain('Europe/Berlin');
        // ⚠ Asserted by EITHER spelling on purpose: the runtime's ICU list and the JVM's ZoneId set
        // disagree on which one they carry, and not in the obvious direction — measured on Node 24,
        // this list has Asia/Calcutta and NOT Asia/Kolkata. The engine accepts both, so pinning one
        // spelling here would break on an ICU bump for no reason.
        expect(zones.some((z) => z === 'Asia/Kolkata' || z === 'Asia/Calcutta')).toBe(true);
    });

    it('always offers UTC — ICU omits it, and it is the likeliest answer of all', () => {
        expect(ianaTimeZones()).toContain('UTC');
    });

    it('is sorted, so the picker is scannable', () => {
        const zones = ianaTimeZones();
        expect(zones).toEqual([...zones].sort());
    });

    it('⛔ never offers an offset form — DuckDB rejects those and the engine refuses them by name', () => {
        const zones = ianaTimeZones();
        expect(zones).not.toContain('+05:30');
        expect(zones).not.toContain('Z');
        expect(zones.some((z) => z.startsWith('+') || z.startsWith('-'))).toBe(false);
    });

    it('leads with a blank option that NAMES what no zone means, rather than reading as unset', () => {
        const opts = timeZoneOptions('Wall clock, as written (default)');
        expect(opts[0]).toEqual({ value: '', label: 'Wall clock, as written (default)' });
        expect(opts.length).toBe(ianaTimeZones().length + 1);
    });
});
