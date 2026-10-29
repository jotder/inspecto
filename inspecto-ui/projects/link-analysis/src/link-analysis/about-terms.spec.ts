import { describe, expect, it } from 'vitest';
import { LA_ABOUT_DEFINITIONS, LA_ABOUT_TERMS } from './about-terms';

describe('Link Analysis About terms (UI test 2026-10-29)', () => {
    it('gives every listed term a real definition, so the About never falls back to unrelated docs', () => {
        for (const term of LA_ABOUT_TERMS) expect(LA_ABOUT_DEFINITIONS[term]?.trim(), term).toBeTruthy();
    });

    it('uses the canonical words: Saved view, not the retired Link-Analysis View or Widget', () => {
        expect(LA_ABOUT_TERMS).toContain('Saved view');
        expect(LA_ABOUT_TERMS).not.toContain('Link-Analysis View');
        expect(LA_ABOUT_TERMS).not.toContain('Widget');
        const text = Object.values(LA_ABOUT_DEFINITIONS).join(' ');
        for (const banned of [/\bFlow\b/, /\bData Source\b/, /\bIssue\b/, /\bMetric\b/]) expect(text).not.toMatch(banned);
    });
});
