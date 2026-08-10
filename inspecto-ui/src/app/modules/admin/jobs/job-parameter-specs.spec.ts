import { describe, expect, it } from 'vitest';
import { JobParameterDecl } from 'app/inspecto/api';
import { paramDeclToSpec, paramDeclsToSpecs, paramValueToApi, paramValueToForm } from './job-parameter-specs';

/**
 * The decl → widget generation contract (job-parameter-contract §7.4). One case per row of that table:
 * the point of the contract is that the mapping is decided by the DECLARATION, never guessed from the
 * parameter's name, so each row is pinned rather than sampled.
 */

/** A declaration with the wire defaults `ParameterDecl.toMap()` sends, overridden per case. */
function decl(p: Partial<JobParameterDecl> & { name: string; type: string }): JobParameterDecl {
    return { required: false, deduce: '', default: '', description: '', ...p };
}

describe('paramDeclToSpec', () => {
    it('maps a required STRING param to a required-tier string field with a humanised label', () => {
        const s = paramDeclToSpec(decl({ name: 'sink_dataset', type: 'STRING', required: true, description: 'Output' }));
        expect(s).toMatchObject({ key: 'sink_dataset', label: 'Sink dataset', type: 'string', tier: 'required', required: true });
    });

    it('prefers the declared label over the humanised name', () => {
        expect(paramDeclToSpec(decl({ name: 'out_dir', type: 'STRING', label: 'Delivery folder' })).label).toBe('Delivery folder');
    });

    describe('the §7.4 type → widget rows', () => {
        const widget = (type: string, extra: Partial<JobParameterDecl> = {}) =>
            paramDeclToSpec(decl({ name: 'p', type, ...extra })).type;

        it('maps each declared type to its widget', () => {
            expect(widget('STRING')).toBe('string');
            expect(widget('TEXT')).toBe('multiline');
            expect(widget('INTEGER')).toBe('number');
            expect(widget('DECIMAL')).toBe('number');
            expect(widget('BOOLEAN')).toBe('boolean');
            expect(widget('DATE')).toBe('string');
            expect(widget('INSTANT')).toBe('string');
            expect(widget('EMAIL')).toBe('string');
            expect(widget('DATASET_REF')).toBe('autocomplete');
        });

        it('lets a declared options list win over the type-derived widget', () => {
            const s = paramDeclToSpec(decl({ name: 'scope', type: 'STRING', options: ['status', 'batch'] }));
            expect(s.type).toBe('select');
            expect(s.options).toEqual([
                { value: 'status', label: 'status' },
                { value: 'batch', label: 'batch' },
            ]);
        });

        it('renders multi as a chip list', () => {
            expect(widget('EMAIL', { multi: true })).toBe('list');
        });

        it('no longer sniffs the name `sql` — TEXT is what makes a field multiline', () => {
            // §7.1 retired the sniff; SqlTemplateJobType declares sql as TEXT. A STRING named `sql`
            // now renders single-line, which is the honest reading of what the server declared.
            expect(widget('STRING', { name: 'sql' })).toBe('string');
            expect(paramDeclToSpec(decl({ name: 'sql', type: 'TEXT' })).type).toBe('multiline');
        });
    });

    describe('format presets', () => {
        it('gives DATE and INSTANT an ISO pattern and a format example', () => {
            const d = paramDeclToSpec(decl({ name: 'event_date', type: 'DATE' }));
            expect(d.placeholder).toBe('2026-08-06');
            expect(new RegExp(`^(?:${d.pattern})$`).test('2026-08-06')).toBe(true);
            expect(new RegExp(`^(?:${d.pattern})$`).test('06/08/2026')).toBe(false);

            const i = paramDeclToSpec(decl({ name: 'at', type: 'INSTANT' }));
            expect(new RegExp(`^(?:${i.pattern})$`).test('2026-08-06T00:00:00Z')).toBe(true);
        });

        it('gives EMAIL a permissive address pattern', () => {
            const s = paramDeclToSpec(decl({ name: 'to', type: 'EMAIL' }));
            const re = new RegExp(`^(?:${s.pattern})$`);
            expect(re.test('ops@example.com')).toBe(true);
            expect(re.test('not-an-address')).toBe(false);
        });

        it('lets a declared pattern override the type preset', () => {
            expect(paramDeclToSpec(decl({ name: 'd', type: 'DATE', pattern: '\\d{8}' })).pattern).toBe('\\d{8}');
        });
    });

    describe('placeholder precedence', () => {
        it('prefers an explicit placeholder, then the format example, then the deduce', () => {
            expect(paramDeclToSpec(decl({ name: 'd', type: 'DATE', placeholder: 'pick a day', deduce: '$day(-1)' })).placeholder)
                .toBe('pick a day');
            // The format example beats the deduce: `help` already states the deduce in words, and
            // spending the one hint slot on a repeat would cost the author the format.
            expect(paramDeclToSpec(decl({ name: 'd', type: 'DATE', deduce: '$day(-1)' })).placeholder).toBe('2026-08-06');
            expect(paramDeclToSpec(decl({ name: 's', type: 'STRING', deduce: '$day(-1)' })).placeholder).toBe('$day(-1)');
        });

        it('still surfaces the deduce in the help text alongside the description', () => {
            const s = paramDeclToSpec(decl({ name: 'event_date', type: 'DATE', deduce: '$day(-1)', description: 'Business date' }));
            expect(s.help).toContain('$day(-1)');
            expect(s.help).toContain('Business date');
        });
    });

    describe('tier, bounds, group and secret', () => {
        it('maps the declared tier 1:1, decoupled from required', () => {
            // ADVANCED while still optional, and OPTIONAL while still required: the whole point of the
            // component is that disclosure and validation are two separate facts.
            const advanced = paramDeclToSpec(decl({ name: 'a', type: 'STRING', tier: 'ADVANCED' }));
            expect(advanced.tier).toBe('advanced');
            expect(advanced.required).toBe(false);

            const prominent = paramDeclToSpec(decl({ name: 'b', type: 'STRING', tier: 'OPTIONAL', required: true }));
            expect(prominent.tier).toBe('optional');
            expect(prominent.required).toBe(true);
        });

        it('falls back to required when the server predates the tier component', () => {
            expect(paramDeclToSpec(decl({ name: 'a', type: 'STRING', required: true })).tier).toBe('required');
            expect(paramDeclToSpec(decl({ name: 'b', type: 'STRING' })).tier).toBe('optional');
        });

        it('carries numeric bounds, including a zero bound', () => {
            const s = paramDeclToSpec(decl({ name: 'n', type: 'INTEGER', min: 0, max: 10 }));
            expect(s.min).toBe(0); // 0 is a real bound, not "unset"
            expect(s.max).toBe(10);
        });

        it('omits bounds that are null (unbounded)', () => {
            const s = paramDeclToSpec(decl({ name: 'n', type: 'INTEGER', min: null, max: null }));
            expect('min' in s).toBe(false);
            expect('max' in s).toBe(false);
        });

        it('carries group and secret through', () => {
            const s = paramDeclToSpec(decl({ name: 'pw', type: 'STRING', group: 'Delivery', secret: true }));
            expect(s.group).toBe('Delivery');
            expect(s.secret).toBe(true);
        });

        it('leaves group and secret unset when not declared', () => {
            const s = paramDeclToSpec(decl({ name: 'p', type: 'STRING', group: '', secret: false }));
            expect(s.group).toBeUndefined();
            expect(s.secret).toBeUndefined();
        });
    });

    describe('defaults', () => {
        it('maps an optional param to the optional tier carrying its default', () => {
            const s = paramDeclToSpec(decl({ name: 'scope', type: 'STRING', default: 'status' }));
            expect(s.tier).toBe('optional');
            expect(s.required).toBe(false);
            expect(s.default).toBe('status');
        });

        it("splits a multi param's CSV default into chips", () => {
            // The resolver reads a list-valued param as CSV (§7.5); bound verbatim it would render as
            // ONE chip containing a comma.
            expect(paramDeclToSpec(decl({ name: 'to', type: 'EMAIL', multi: true, default: 'a@x.io, b@x.io' })).default)
                .toEqual(['a@x.io', 'b@x.io']);
        });
    });

    it('maps a list of decls in order', () => {
        const specs = paramDeclsToSpecs([
            decl({ name: 'sql', type: 'TEXT', required: true }),
            decl({ name: 'sources', type: 'STRING' }),
        ]);
        expect(specs.map((s) => s.key)).toEqual(['sql', 'sources']);
    });
});

describe('paramValue round-trip', () => {
    const listSpec = paramDeclToSpec(decl({ name: 'to', type: 'EMAIL', multi: true }));
    const textSpec = paramDeclToSpec(decl({ name: 'sink', type: 'STRING' }));

    it('splits a stored CSV into chips and joins it back', () => {
        const asForm = paramValueToForm(listSpec, 'a@x.io,b@x.io');
        expect(asForm).toEqual(['a@x.io', 'b@x.io']);
        expect(paramValueToApi(asForm)).toBe('a@x.io,b@x.io');
    });

    it('is stable across repeated edits — the value must not nest a little each save', () => {
        let v: unknown = 'a@x.io,b@x.io';
        for (let i = 0; i < 3; i++) v = paramValueToApi(paramValueToForm(listSpec, v));
        expect(v).toBe('a@x.io,b@x.io');
    });

    it('leaves a non-list value alone in both directions', () => {
        expect(paramValueToForm(textSpec, 'orders,extra')).toBe('orders,extra');
        expect(paramValueToApi('orders,extra')).toBe('orders,extra');
    });

    it('reads a blank stored list as null so `required` sees it as empty', () => {
        expect(paramValueToForm(listSpec, '')).toBeNull();
    });
});
