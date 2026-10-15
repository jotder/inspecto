import { describe, expect, it } from 'vitest';
import { parseSeedList } from './seed-list';
import { laLimit } from '@inspecto/link-analysis/link-analysis/la-limits';

describe('parseSeedList (LA-SEED-CONNECT-1)', () => {
    it('reads numbers split by lines, commas, semicolons and spaces, normalised and once each', () => {
        const r = parseSeedList('555-123-4567\n(555) 123 4568, 5551234567;+4915112345678 5551234569');
        expect(r.ids).toEqual(['5551234567', '5551234568', '+4915112345678', '5551234569']);
        expect(r.duplicates).toBe(1);
        expect(r.invalid).toEqual([]);
    });

    it('keeps account ids as typed and lists what is neither a number nor an id', () => {
        const r = parseSeedList('MULE-HUB-01\nACC-1006, <script>\n5551234567\nMULE-HUB-01');
        expect(r.ids).toEqual(['MULE-HUB-01', 'ACC-1006', '5551234567']);
        expect(r.invalid).toEqual(['<script>']);
        expect(r.duplicates).toBe(1);
    });

    it('caps the seeds and names the overflow', () => {
        const numbers = Array.from({ length: laLimit('connect.max_seeds') + 3 }, (_, i) => String(5551230000 + i));
        const r = parseSeedList(numbers.join('\n'));
        expect(r.ids.length).toBe(laLimit('connect.max_seeds'));
        expect(r.overCap).toEqual(numbers.slice(laLimit('connect.max_seeds')));
    });

    it('is empty for blank text', () => {
        expect(parseSeedList('  \n ')).toEqual({ ids: [], invalid: [], overCap: [], duplicates: 0 });
    });
});
