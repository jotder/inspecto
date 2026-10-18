import { describe, expect, it } from 'vitest';
import { groupLabel } from './reconciliation-detail.component';

describe('groupLabel', () => {
    it('names the day total when only part of it is loaded', () => {
        expect(groupLabel('Value break', 50, 120)).toBe('Value break (50 of 120)');
    });

    it('stays `Type (n)` when everything is loaded or the total is unknown', () => {
        expect(groupLabel('Missing right', 3, 3)).toBe('Missing right (3)');
        expect(groupLabel('Missing right', 3, null)).toBe('Missing right (3)');
    });
});
