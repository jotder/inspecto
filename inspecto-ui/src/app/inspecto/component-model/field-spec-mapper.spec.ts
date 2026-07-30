import { describe, expect, it } from 'vitest';
import { fieldSpecsToAttributes } from './field-spec-mapper';

describe('fieldSpecsToAttributes', () => {
    it('maps the served FieldSpec vocabulary onto AttributeSpecs with flat keys', () => {
        const specs = fieldSpecsToAttributes([
            { path: 'delimited.delimiter', label: 'Delimiter', type: 'STRING', defaultValue: ',' },
            { path: 'delimited.has_header', label: 'Header', type: 'BOOL', defaultValue: true },
            { path: 'fixedwidth.min_record_length', label: 'Min length', type: 'INT' },
            {
                path: 'fixedwidth.trim', label: 'Trim', type: 'ENUM',
                enumValues: ['BOTH', 'NONE'], defaultValue: 'BOTH',
            },
            { path: 'text_regex.pattern', label: 'Pattern', type: 'STRING', required: true },
        ]);
        expect(specs.map((s) => [s.key, s.type])).toEqual([
            ['delimited__delimiter', 'string'],
            ['delimited__has_header', 'boolean'],
            ['fixedwidth__min_record_length', 'number'],
            ['fixedwidth__trim', 'select'],
            ['text_regex__pattern', 'string'],
        ]);
        expect(specs[0].default).toBe(',');
        expect(specs[3].options).toEqual([
            { value: 'BOTH', label: 'BOTH' },
            { value: 'NONE', label: 'NONE' },
        ]);
        expect(specs[4].tier).toBe('required');
        expect(specs[4].required).toBe(true);
        expect(specs[0].tier).toBe('optional');
    });

    it('SKIPS field shapes this build cannot render generically — never guesses a control', () => {
        const specs = fieldSpecsToAttributes([
            { path: 'fixedwidth.fields', label: 'Fields', type: 'LIST', required: true },
            { path: 'x.map', label: 'Map', type: 'MAP' },
            { path: 'x.new', label: 'Future', type: 'HOLOGRAM' },
            { path: '', label: 'No path', type: 'STRING' },
            { path: 'ok', label: 'Ok', type: 'STRING' },
        ]);
        expect(specs.map((s) => s.key)).toEqual(['ok']);
    });

    it('turns a visibleWhen hint into a dependsOn equals-clause on the flattened key', () => {
        const [s] = fieldSpecsToAttributes([
            { path: 'txt.record_length', label: 'Len', type: 'INT', visibleWhen: 'txt.frontend=fixedwidth' },
        ]);
        expect(s.dependsOn).toEqual({ key: 'txt__frontend', equals: 'fixedwidth' });
    });
});
