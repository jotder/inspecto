import { MAX_CONNECT_SEEDS, parseSeedList } from './seed-list';

describe('parseSeedList (LA-SEED-CONNECT-1)', () => {
    it('reads numbers split by lines, commas, semicolons and spaces, normalised and once each', () => {
        const r = parseSeedList('555-123-4567\n(555) 123 4568, 5551234567;+4915112345678 5551234569');
        expect(r.ids).toEqual(['5551234567', '5551234568', '+4915112345678', '5551234569']);
        expect(r.duplicates).toBe(1);
        expect(r.invalid).toEqual([]);
    });

    it('lists what is not a number instead of dropping it', () => {
        const r = parseSeedList('5551234567\nhello\n12\n5551234568');
        expect(r.ids).toEqual(['5551234567', '5551234568']);
        expect(r.invalid).toEqual(['hello', '12']);
    });

    it('caps the seeds and names the overflow', () => {
        const numbers = Array.from({ length: MAX_CONNECT_SEEDS + 3 }, (_, i) => String(5551230000 + i));
        const r = parseSeedList(numbers.join('\n'));
        expect(r.ids.length).toBe(MAX_CONNECT_SEEDS);
        expect(r.overCap).toEqual(numbers.slice(MAX_CONNECT_SEEDS));
    });

    it('is empty for blank text', () => {
        expect(parseSeedList('  \n ')).toEqual({ ids: [], invalid: [], overCap: [], duplicates: 0 });
    });
});
