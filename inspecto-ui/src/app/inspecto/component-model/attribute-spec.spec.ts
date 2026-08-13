import { describe, expect, it } from 'vitest';
import {
    AttributeSpec,
    attributeValidator,
    byTier,
    defaultsFor,
    isRequired,
    validateAttributes,
    visibleSpecs,
} from './attribute-spec';

const SPECS: AttributeSpec[] = [
    { key: 'name', label: 'Name', type: 'identifier', tier: 'required' },
    {
        key: 'type',
        label: 'Type',
        type: 'select',
        tier: 'required',
        options: [
            { value: 'enrich', label: 'Enrich' },
            { value: 'report', label: 'Report' },
        ],
    },
    {
        key: 'cron',
        label: 'Cron',
        type: 'string',
        tier: 'optional',
        pattern: '[0-9*/ ,-]+',
        dependsOn: { key: 'type', equals: 'report' },
    },
    { key: 'threads', label: 'Threads', type: 'number', tier: 'advanced', default: 4, min: 1, max: 64 },
    { key: 'enabled', label: 'Enabled', type: 'boolean', tier: 'optional', default: true },
];

describe('attribute-spec', () => {
    it('collects declared defaults', () => {
        expect(defaultsFor(SPECS)).toEqual({ threads: 4, enabled: true });
    });

    it('groups by tier in declaration order', () => {
        const t = byTier(SPECS);
        expect(t.required.map((s) => s.key)).toEqual(['name', 'type']);
        expect(t.optional.map((s) => s.key)).toEqual(['cron', 'enabled']);
        expect(t.advanced.map((s) => s.key)).toEqual(['threads']);
    });

    it('hides dependsOn attributes until their controller matches — and skips their validation', () => {
        expect(visibleSpecs(SPECS, { type: 'enrich' }).map((s) => s.key)).not.toContain('cron');
        expect(visibleSpecs(SPECS, { type: 'report' }).map((s) => s.key)).toContain('cron');
        // invalid cron, but hidden ⇒ no finding
        const hidden = validateAttributes(SPECS, { name: 'x', type: 'enrich', cron: '!!!' });
        expect(hidden).toEqual([]);
        const shown = validateAttributes(SPECS, { name: 'x', type: 'report', cron: '!!!' });
        expect(shown.map((f) => f.path)).toEqual(['cron']);
    });

    it('flags missing required, bad select, bad identifier, out-of-range number', () => {
        const findings = validateAttributes(SPECS, { name: '0bad', type: 'nope', threads: 128 });
        expect(findings.map((f) => f.path).sort()).toEqual(['name', 'threads', 'type']);
        expect(findings.every((f) => f.severity === 'error')).toBe(true);
        expect(validateAttributes(SPECS, {}).map((f) => f.path)).toEqual(['name', 'type']);
    });

    it('decouples required-validation from the always-visible tier', () => {
        // An always-visible (required tier) field explicitly marked not required must not be flagged blank.
        const specs: AttributeSpec[] = [
            { key: 'title', label: 'Title', type: 'string', tier: 'required', required: false },
            { key: 'name', label: 'Name', type: 'string', tier: 'required' },
        ];
        expect(isRequired(specs[0])).toBe(false);
        expect(isRequired(specs[1])).toBe(true); // defaults from the tier
        expect(validateAttributes(specs, {}).map((f) => f.path)).toEqual(['name']); // title omitted ⇒ no finding
    });

    it('hides a notEquals dependsOn attribute exactly when the controller matches', () => {
        const specs: AttributeSpec[] = [
            {
                key: 'kind',
                label: 'Kind',
                type: 'select',
                tier: 'required',
                options: [
                    { value: 'non_null', label: 'Non-null' },
                    { value: 'condition', label: 'Condition' },
                ],
            },
            {
                key: 'column',
                label: 'Column',
                type: 'string',
                tier: 'required',
                dependsOn: { key: 'kind', notEquals: 'condition' },
            },
        ];
        expect(visibleSpecs(specs, { kind: 'condition' }).map((s) => s.key)).not.toContain('column');
        expect(visibleSpecs(specs, { kind: 'non_null' }).map((s) => s.key)).toContain('column');
        // required but hidden by notEquals ⇒ no finding
        expect(validateAttributes(specs, { kind: 'condition' }).map((f) => f.path)).toEqual([]);
        expect(validateAttributes(specs, { kind: 'non_null' }).map((f) => f.path)).toEqual(['column']);
    });

    /**
     * `type: 'list'` (D7). An EMPTY list is blank, not "a value" — it has to agree with Angular's own
     * `Validators.required`, which treats `[]` as empty; otherwise the framework-free validator and the
     * reactive form disagree about the same value and one of them silently wins.
     */
    it('treats an empty list as blank and validates list entries as text', () => {
        const specs: AttributeSpec[] = [
            { key: 'tags', label: 'Tags', type: 'list', tier: 'required' },
            { key: 'opt', label: 'Opt', type: 'list', tier: 'optional' },
        ];
        // empty / missing ⇒ blank ⇒ only the required one complains
        expect(validateAttributes(specs, { tags: [], opt: [] }).map((f) => f.path)).toEqual(['tags']);
        expect(validateAttributes(specs, {}).map((f) => f.path)).toEqual(['tags']);
        // a non-empty list satisfies required
        expect(validateAttributes(specs, { tags: ['a'] })).toEqual([]);
        // wrong shapes are reported, not coerced
        expect(validateAttributes(specs, { tags: 'a,b' }).map((f) => f.message)).toEqual([
            'Tags must be a list of text values',
        ]);
        expect(validateAttributes(specs, { tags: [1, 2] }).map((f) => f.path)).toEqual(['tags']);
    });

    /**
     * `pattern` on a `list` means "every ITEM must match" — the value is the array, never one string.
     * The framework-free validator and the renderer's `ValidatorFn` share `listPatternViolation` so the
     * two cannot disagree about the same value.
     */
    it('applies pattern per list entry, naming the first offender', () => {
        const specs: AttributeSpec[] = [
            { key: 'to', label: 'To', type: 'list', tier: 'optional', pattern: '[^@\\s]+@[^@\\s]+\\.[^@\\s]+' },
        ];

        expect(validateAttributes(specs, { to: ['a@x.io', 'b@x.io'] })).toEqual([]);
        expect(validateAttributes(specs, { to: ['a@x.io', 'nope'] }).map((f) => f.message)).toEqual([
            'To: "nope" has an invalid format',
        ]);
        // Not the array's toString(): "a@x.io,b@x.io" fails an email pattern though every entry passes.
        expect(validateAttributes(specs, { to: ['a@x.io', 'b@x.io'] }).length).toBe(0);
    });

    it('accepts a fully valid config and wraps as a kind validator', () => {
        const validate = attributeValidator(SPECS);
        expect(validate({ name: 'daily_kpi', type: 'report', cron: '0 2 * * *', threads: 8, enabled: false })).toEqual(
            [],
        );
        expect(validate(null).map((f) => f.path)).toEqual(['name', 'type']);
    });
});
