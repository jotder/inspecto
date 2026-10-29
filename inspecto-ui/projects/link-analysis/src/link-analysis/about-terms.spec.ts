import { describe, expect, it } from 'vitest';
import { LA_ABOUT_DEFINITIONS, LA_ABOUT_TERMS } from './about-terms';

describe('Link Analysis About terms (UI test 2026-10-29)', () => {
    it('gives every listed term a real definition, so the About never falls back to unrelated docs', () => {
        for (const term of LA_ABOUT_TERMS) expect(LA_ABOUT_DEFINITIONS[term]?.trim(), term).toBeTruthy();
    });

    it('explains the Studio terms in plain words, with no engineer markup (LA-UI-TEXT-PASS-1 X2, T-182)', () => {
        for (const term of ['Graph model', 'Expand', 'Draft', 'Dossier']) expect(LA_ABOUT_TERMS).toContain(term);
        expect(LA_ABOUT_TERMS).not.toContain('Entity Projection');
        for (const text of Object.values(LA_ABOUT_DEFINITIONS)) {
            expect(text).not.toMatch(/[`*⛔✅⚠|]|\b[A-Z]+-\d+\b| - |\bedge\b|\bnode\b|\bseed\b/);
            expect(text).toMatch(/\.$/);
            expect(text.length).toBeLessThanOrEqual(220);
        }
    });

    it('uses the canonical words: Saved view, not the retired Link-Analysis View or Widget', () => {
        expect(LA_ABOUT_TERMS).toContain('Saved view');
        expect(LA_ABOUT_TERMS).not.toContain('Link-Analysis View');
        expect(LA_ABOUT_TERMS).not.toContain('Widget');
        const text = Object.values(LA_ABOUT_DEFINITIONS).join(' ');
        for (const banned of [/\bFlow\b/, /\bData Source\b/, /\bIssue\b/, /\bMetric\b/]) expect(text).not.toMatch(banned);
    });
});
