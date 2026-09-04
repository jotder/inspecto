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

    it('offers every error-handling knob on the robustness section', () => {
        for (const key of KNOBS) {
            const s = spec(key);
            expect(s, `${key} is offered`).toBeTruthy();
            expect(s!.section, `${key} lives on the robustness section`).toBe('robustness');
        }
    });

    /**
     * Review 2026-09-04 (parse-pane-redesign-plan.md R4): a knob whose engine default is known shows
     * that default as a REAL value — written to the key, a no-op for the engine — instead of an
     * off-looking tri-state toggle with "default is ON" in its help. Grounded against
     * PipelineConfigParser/DuckDbCsvIngester: absent ignore_errors/store_rejects are true, null_padding
     * is false for delimited (true for line readers), the reject tables are reject_errors/reject_scans.
     */
    it('shows each engine default as a real default value', () => {
        expect(spec('delimited__ignore_errors')!.default).toBe(true);
        expect(spec('delimited__store_rejects')!.default).toBe(true);
        expect(spec('delimited__null_padding')!.default, 'delimited pads nothing by default').toBe(false);
        expect(spec('delimited__rejects_table')!.default).toBe('reject_errors');
        expect(spec('delimited__rejects_scan')!.default).toBe('reject_scans');
        expect(spec('delimited__rejects_table')!.placeholder).toBeUndefined();
        expect(spec('delimited__rejects_scan')!.placeholder).toBeUndefined();
    });

    it('keeps no default where writing one would change or over-specify behaviour', () => {
        expect(spec('delimited__rejects_limit')!.default, 'no natural value — blank = unlimited').toBeUndefined();
        expect(spec('delimited__engine')!.default, 'engine keeps no default — blank IS auto').toBeUndefined();
        for (const key of ['delimited__comment', 'delimited__date_formats', 'delimited__timestamp_formats', 'delimited__null_strings']) {
            expect(spec(key)!.default, `${key}: writing a value changes parsing`).toBeUndefined();
            expect(spec(key)!.placeholder, `${key}: suggestion lives in help, not a placeholder`).toBeUndefined();
        }
    });

    /**
     * Auto is the SELECTED choice without being a written value: its option value is blank, and the
     * editor drops a blank whose default is blank. Asserted because the obvious "fix" — `default:
     * 'auto'` — is exactly what the engine's own recorded decision forbids.
     */
    it('shows Auto as the engine default by giving it the blank value', () => {
        const engine = spec('delimited__engine')!;
        expect(engine.options?.[0].value).toBe('');
        expect(engine.options?.[0].label).toContain('Auto');
        expect(engine.options?.map((o) => o.value)).toEqual(['', 'duckdb', 'java']);
    });

    it('caps the rejects limit at zero', () => {
        expect(spec('delimited__rejects_limit')!.min).toBe(0);
    });
});
