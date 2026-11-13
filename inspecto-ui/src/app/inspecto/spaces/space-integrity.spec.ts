import { describe, expect, it } from 'vitest';
import { spaceIntegrityView } from './space-integrity';

describe('spaceIntegrityView (LC-4)', () => {
    it('shows nothing unless the report is ready — never a zero nobody measured', () => {
        expect(spaceIntegrityView(undefined)).toBeNull();
        expect(spaceIntegrityView(null)).toBeNull();
        for (const status of ['computing', 'disabled', 'unavailable', 'failed']) {
            expect(spaceIntegrityView({ status })).toBeNull();
        }
    });

    it('sums broken links, duplicates and outside changes and names each in the detail', () => {
        const v = spaceIntegrityView({ status: 'ready', broken: 2, duplicates: 1, changedOutside: 1200 });
        expect(v?.total).toBe(1203);
        expect(v?.label).toBe('1,203 integrity findings');
        expect(v?.detail).toBe('Broken links: 2 · Duplicates: 1 · Changed outside the server: 1,200');
    });

    it('says a ready zero and a single finding in words', () => {
        expect(spaceIntegrityView({ status: 'ready', broken: 0, duplicates: 0, changedOutside: 0 })?.label).toBe(
            'No integrity findings',
        );
        expect(spaceIntegrityView({ status: 'ready', broken: 1, duplicates: 0, changedOutside: 0 })?.label).toBe(
            '1 integrity finding',
        );
    });
});
