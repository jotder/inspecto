import { describe, expect, it } from 'vitest';
import { JobExpressionDecl, JobParameterDecl } from 'app/inspecto/api';
import { paramDeclToSpec, paramDeclsToSpecs, paramTokens, paramValueToApi, paramValueToForm, tokensForParam } from './job-parameter-specs';

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

/**
 * The token picker's offer set (§8.5, step 13). Three filters decide it, and each one exists because the
 * alternative is a value that authors cleanly and then fails at fire time — a picker that offers an
 * unresolvable token is worse than no picker, because the author has no reason to doubt it.
 */
describe('tokensForParam', () => {
    /** A catalog entry with the wire defaults `ExpressionDecl.toMap()` sends, overridden per case. */
    function ex(p: Partial<JobExpressionDecl> & { token: string; yields: string }): JobExpressionDecl {
        return {
            form: 'LITERAL', description: '', example: '', contextFree: true, preview: '',
            availableIn: ['cron', 'manual', 'on_pipeline', 'on_signal'],
            ...p,
        };
    }

    const today = ex({ token: '$today', yields: 'DATE', description: 'The fire date', example: '2026-08-07', preview: '2026-08-10' });
    const now = ex({ token: '$now', yields: 'INSTANT', example: '2026-08-07T06:00:00Z', preview: '2026-08-10T13:00:00Z' });
    const signal = ex({
        token: '$signal.', form: 'PREFIX', yields: 'STRING', availableIn: ['on_signal'], contextFree: false,
        description: "A field of the firing Signal's payload", example: '$signal.dataset', preview: '$signal.dataset',
    });
    const day = ex({ token: '$day(n)', form: 'FUNCTION', yields: 'DATE', description: 'Shifted by n days', example: '$day(-1)', preview: '2026-08-09' });
    const catalog = [today, now, signal, day];

    const param = (p: Partial<JobParameterDecl> & { name: string; type: string }) => decl(p);

    it('withholds every token from a declaration that opted out of expressions', () => {
        // The `sql.template` body: its own $-namespace, so a picker there would author a literal that
        // merely looks like a token.
        expect(tokensForParam(param({ name: 'sql', type: 'TEXT', expressions: false }), catalog, 'cron')).toEqual([]);
    });

    it('offers $signal.* only on a signal trigger', () => {
        const onSignal = tokensForParam(param({ name: 'to', type: 'STRING' }), catalog, 'on_signal');
        const onCron = tokensForParam(param({ name: 'to', type: 'STRING' }), catalog, 'cron');
        expect(onSignal.map((t) => t.token)).toContain('$signal.dataset');
        expect(onCron.map((t) => t.token)).not.toContain('$signal.dataset');
    });

    it('filters on the yields, keeping DATE and INSTANT apart', () => {
        // A DATE resolution would fail `matchesType(INSTANT, …)` at fire time, and the reverse too.
        const onDate = tokensForParam(param({ name: 'd', type: 'DATE' }), catalog, 'cron').map((t) => t.token);
        expect(onDate).toContain('$today');
        expect(onDate).not.toContain('$now');
        const onInstant = tokensForParam(param({ name: 'i', type: 'INSTANT' }), catalog, 'cron').map((t) => t.token);
        expect(onInstant).toContain('$now');
        expect(onInstant).not.toContain('$today');
    });

    it('offers every token on a STRING or TEXT field, because everything resolves to text', () => {
        for (const type of ['STRING', 'TEXT']) {
            expect(tokensForParam(param({ name: 'x', type }), catalog, 'cron').map((t) => t.token))
                .toEqual(['$today', '$now', '$day(-1)']);
        }
    });

    it('offers a STRING-yielding token on a typed field, as the engine cannot pre-judge one either', () => {
        // $signal.<field> legitimately carries an address; only the resolved value can be judged, and
        // ParameterResolver re-validates it after resolution.
        expect(tokensForParam(param({ name: 'to', type: 'EMAIL' }), catalog, 'on_signal').map((t) => t.token))
            .toEqual(['$signal.dataset']);
    });

    it('offers the TYPEABLE form of a shaped token, never the shape itself', () => {
        // `$day(n)` is a shape the registry cannot evaluate — inserting it authors an unknown expression.
        const t = tokensForParam(param({ name: 'd', type: 'DATE' }), catalog, 'cron').find((x) => x.token.startsWith('$day'));
        expect(t!.token).toBe('$day(-1)');
        expect(t!.description).toContain('shape: $day(n)');
    });

    it('drops the preview line when it would only repeat the token being offered', () => {
        // A context-bound token previews AS its sample: "$signal.dataset → $signal.dataset" is noise.
        const [t] = tokensForParam(param({ name: 'x', type: 'STRING' }), catalog, 'on_signal').filter((x) => x.token === '$signal.dataset');
        expect(t.preview).toBeUndefined();
        const [d] = tokensForParam(param({ name: 'x', type: 'DATE' }), catalog, 'cron');
        expect(d.preview).toBe('2026-08-10');
    });
});

describe('paramTokens', () => {
    const catalog: JobExpressionDecl[] = [{
        token: '$today', form: 'LITERAL', yields: 'DATE', description: 'The fire date', example: '2026-08-07',
        availableIn: ['cron', 'manual', 'on_pipeline', 'on_signal'], contextFree: true, preview: '2026-08-10',
    }];

    it('omits a parameter with nothing to offer, so the renderer draws no picker there', () => {
        const map = paramTokens(
            [decl({ name: 'day', type: 'DATE' }), decl({ name: 'sql', type: 'TEXT', expressions: false })],
            catalog,
            'cron',
        );
        expect(Object.keys(map)).toEqual(['day']);
    });
});
