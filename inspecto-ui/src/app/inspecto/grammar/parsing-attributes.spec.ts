import { describe, expect, it } from 'vitest';
import { parsingAttributesFor } from './parsing-attributes';

/**
 * The delimited Robustness tab's error-handling knobs (2026-08-23). These pin the ONE property that
 * makes them safe to add to a shipped grammar: every one is TRI-STATE — no `default`, so the control
 * initialises to null, nothing is written until the author touches it, and a stored grammar round-trips
 * unchanged. A `default` here would materialise into every `value()` and mutate faithful copies.
 */
describe('parsingAttributesFor — delimited error handling', () => {
    const SPECS = parsingAttributesFor('delimited');
    const spec = (key: string) => SPECS.find((s) => s.key === key);

    const KNOBS = [
        'delimited__ignore_errors',
        'delimited__null_padding',
        'delimited__store_rejects',
        'delimited__rejects_table',
        'delimited__rejects_scan',
        'delimited__rejects_limit',
    ];

    it('offers every error-handling knob on the robustness tab', () => {
        for (const key of KNOBS) {
            const s = spec(key);
            expect(s, `${key} is offered`).toBeTruthy();
            expect(s!.tab, `${key} lives on the robustness tab`).toBe('robustness');
        }
    });

    /** 🔴 The rule this whole surface rests on — see the file header. */
    it('gives none of them a default, so an untouched grammar writes nothing', () => {
        for (const key of KNOBS) {
            expect(spec(key)!.default, `${key} must have no default`).toBeUndefined();
        }
        expect(spec('delimited__engine')!.default, 'engine keeps no default either').toBeUndefined();
    });

    /**
     * Auto is the SELECTED choice without being a written value: its option value is blank, and the
     * editor drops a blank whose default is blank. Asserted because the obvious "fix" — `default:
     * 'auto'` — is exactly what the no-default rule forbids.
     */
    it('shows Auto as the engine default by giving it the blank value', () => {
        const engine = spec('delimited__engine')!;
        expect(engine.options?.[0].value).toBe('');
        expect(engine.options?.[0].label).toContain('Auto');
        expect(engine.options?.map((o) => o.value)).toEqual(['', 'duckdb', 'java']);
    });

    /**
     * The two knobs whose ENGINE default is on render as an off-looking toggle (a tri-state control
     * has no third visual state), so their help text must say so — the toggle alone would read as the
     * opposite of the truth.
     */
    it('states the on-by-default behaviour in the help text of the knobs that have it', () => {
        expect(spec('delimited__ignore_errors')!.help).toContain('default is ON');
        expect(spec('delimited__store_rejects')!.help).toContain('default is ON');
    });

    it('caps the rejects limit at zero and names the default table names as placeholders', () => {
        expect(spec('delimited__rejects_limit')!.min).toBe(0);
        expect(spec('delimited__rejects_table')!.placeholder).toBe('reject_errors');
        expect(spec('delimited__rejects_scan')!.placeholder).toBe('reject_scans');
    });
});
