import { describe, expect, it } from 'vitest';
import { DISABLED_REASON, NEEDS_TIME_COLUMN, needsPermission } from './disabled-reasons';
import { patternColumnRefusal } from './pattern-packs';

describe('disabled-reasons (LA-UI-TEXT-PASS-1 X6, X8)', () => {
    it('every phrase is one short sentence, ending in a full stop or an ellipsis', () => {
        for (const phrase of Object.values(DISABLED_REASON)) {
            expect(phrase).toMatch(/^[A-Z][^.]*(\.|…)$/);
            expect(phrase.split(' ').length).toBeLessThanOrEqual(10);
        }
    });

    it('names a permission plainly, never as an id or a capability', () => {
        expect(needsPermission('Incident management')).toBe('Needs Incident management.');
        expect(needsPermission('Incident management')).not.toMatch(/capability/i);
    });

    it('X8: the pattern refusal for a missing time column is the one shared sentence', () => {
        expect(patternColumnRefusal('time', undefined)).toBe(NEEDS_TIME_COLUMN);
        expect(patternColumnRefusal('time', 'first_seen')).toBe(`${NEEDS_TIME_COLUMN} Use first_seen.`);
        expect(NEEDS_TIME_COLUMN).toContain('Settings ▸ Graph model');
    });
});
