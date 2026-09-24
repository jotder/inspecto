import { describe, expect, it } from 'vitest';

import ATTRIBUTE_CONTRACT from 'app/inspecto/contracts/attribute-spec.contract.json';
import { FindingsSpecDef } from 'app/inspecto/api';

import {
    CONTROL_CHOICES,
    FieldDraft,
    fieldsAbove,
    fromWire,
    labelToKey,
    labelToOptionValue,
    newChoice,
    newField,
    toWire,
    validateDraft,
} from './findings-spec-editor.model';

/** The built-in Case spec exactly as `GET /findings/case` serves it (`FindingsSpec.defaultFor`). */
const BUILT_IN: FindingsSpecDef = {
    objectType: 'case',
    sections: [
        {
            key: 'disposition',
            label: 'Disposition',
            type: 'select',
            tier: 'required',
            required: false,
            options: [
                { value: 'CONFIRMED', label: 'Confirmed' },
                { value: 'FALSE_POSITIVE', label: 'False positive' },
                { value: 'RECOVERED', label: 'Recovered' },
                { value: 'WRITTEN_OFF', label: 'Written off' },
                { value: 'INCONCLUSIVE', label: 'Inconclusive' },
            ],
        },
        { key: 'impactAmount', label: 'Impact amount', type: 'string', tier: 'required', required: false },
        { key: 'recordsAffected', label: 'Records affected', type: 'string', tier: 'required', required: false },
        { key: 'summary', label: 'Summary', type: 'multiline', tier: 'required', required: false },
    ],
};

const messages = (fields: FieldDraft[]): string[] => validateDraft(fields).map((p) => p.message);

describe('labelToKey', () => {
    it('camel-cases a label into a key', () => {
        expect(labelToKey('Root cause category', new Set())).toBe('rootCauseCategory');
    });

    it('drops punctuation and survives a leading digit', () => {
        expect(labelToKey('Amount (USD) — recovered!', new Set())).toBe('amountUsdRecovered');
        expect(labelToKey('3rd party involved', new Set())).toBe('field3rdPartyInvolved');
        expect(labelToKey('???', new Set())).toBe('field');
    });

    it('suffixes a collision instead of reusing a taken key', () => {
        expect(labelToKey('Summary', new Set(['summary']))).toBe('summary2');
        expect(labelToKey('Summary', new Set(['summary', 'summary2']))).toBe('summary3');
    });
});

describe('labelToOptionValue', () => {
    it('matches the ladder style (FALSE_POSITIVE)', () => {
        expect(labelToOptionValue('Card not present', new Set())).toBe('CARD_NOT_PRESENT');
        expect(labelToOptionValue('  Account-takeover ', new Set())).toBe('ACCOUNT_TAKEOVER');
        expect(labelToOptionValue('!!', new Set())).toBe('CHOICE');
        expect(labelToOptionValue('Other', new Set(['OTHER']))).toBe('OTHER_2');
    });
});

describe('fromWire / toWire', () => {
    it("round-trips the built-in spec's shape", () => {
        const wire = toWire('case', fromWire(BUILT_IN));
        expect(wire).toEqual({ name: 'case', objectType: 'case', sections: BUILT_IN.sections });
    });

    it('freezes every loaded key and choice value; a label edit does not move them', () => {
        const fields = fromWire(BUILT_IN);
        expect(fields.every((f) => f.key !== null)).toBe(true);
        fields[0].label = 'Outcome';
        fields[0].choices[1].label = 'Not fraud';
        const wire = toWire('case', fields);
        const first = (wire.sections as { key: string; options: { value: string; label: string }[] }[])[0];
        expect(first.key).toBe('disposition');
        expect(first.options[1]).toEqual({ value: 'FALSE_POSITIVE', label: 'Not fraud' });
    });

    it('derives a new field key and choice values from the labels, avoiding saved keys', () => {
        const fields = fromWire(BUILT_IN);
        const f = newField('Summary');
        f.type = 'select';
        f.choices = [newChoice('Card not present'), newChoice('Card not present')];
        fields.push(f);
        const sections = toWire('case', fields).sections as { key: string; options?: { value: string }[] }[];
        expect(sections[4].key).toBe('summary2');
        expect(sections[4].options!.map((o) => o.value)).toEqual(['CARD_NOT_PRESENT', 'CARD_NOT_PRESENT_2']);
    });

    it('resolves "Show only when" to the target key and, for a choice list, its value', () => {
        const fields = fromWire(BUILT_IN);
        const f = newField('Recovered amount');
        f.type = 'number';
        f.showWhen = { targetUid: fields[0].uid, negated: false, value: fields[0].choices[2].uid };
        fields.push(f);
        const s = (toWire('case', fields).sections as Record<string, unknown>[])[4];
        expect(s['dependsOn']).toEqual({ key: 'disposition', equals: 'RECOVERED' });

        f.showWhen = { targetUid: fields[0].uid, negated: true, value: fields[0].choices[0].uid };
        const n = (toWire('case', fields).sections as Record<string, unknown>[])[4];
        expect(n['dependsOn']).toEqual({ key: 'disposition', notEquals: 'CONFIRMED' });
    });

    it('reads a served dependsOn back onto the choice it names', () => {
        const fields = fromWire({
            objectType: 'case',
            sections: [
                ...BUILT_IN.sections,
                {
                    key: 'recovered',
                    label: 'Recovered amount',
                    type: 'number',
                    tier: 'optional',
                    dependsOn: { key: 'disposition', equals: 'RECOVERED' },
                },
            ],
        });
        expect(fields[4].showWhen).toEqual({ targetUid: fields[0].uid, negated: false, value: fields[0].choices[2].uid });
        expect(fields[4].tier).toBe('optional');
    });

    /** D5: `advanced` is not offered, but a stored one stays readable and is written back untouched. */
    it('keeps a stored advanced tier and an authored default through a save', () => {
        const fields = fromWire({
            objectType: 'case',
            sections: [{ key: 'x', label: 'X', type: 'string', tier: 'advanced', default: 'n/a', pattern: '^[a-z]+$' }],
        });
        const s = (toWire('case', fields).sections as Record<string, unknown>[])[0];
        expect(s).toMatchObject({ key: 'x', tier: 'advanced', default: 'n/a', pattern: '^[a-z]+$', required: false });
    });

    it('writes choices only for the types that use them, and bounds only for a number', () => {
        const f = newField('Amount');
        f.type = 'string';
        f.choices = [newChoice('A')];
        f.min = 1;
        f.max = 5;
        const s = (toWire('case', [f]).sections as Record<string, unknown>[])[0];
        expect(s['options']).toBeUndefined();
        expect(s['min']).toBeUndefined();
        expect(s['max']).toBeUndefined();
    });
});

/**
 * One case per `FindingsSpec.fromMap` rule (`inspecto-engine/.../objects/FindingsSpec.java`), each phrased
 * against the field's LABEL, as the server never does — its messages name the key.
 */
describe('validateDraft', () => {
    it('accepts the built-in spec', () => {
        expect(messages(fromWire(BUILT_IN))).toEqual([]);
    });

    it('needs at least one field', () => {
        expect(messages([])).toEqual(['Add at least one Findings field.']);
    });

    it('needs every field named', () => {
        expect(messages([newField('  ')])).toEqual(['A Findings field has no name.']);
    });

    it('refuses two fields with the same name', () => {
        expect(messages([newField('Outcome'), newField('outcome ')])).toEqual(['Two fields are called “outcome”.']);
    });

    it('needs a choice list to have a choice, each named once', () => {
        const f = newField('Root cause');
        f.type = 'select';
        expect(messages([f])).toEqual(['“Root cause” needs at least one choice.']);
        f.choices = [newChoice(''), newChoice('A'), newChoice('a')];
        expect(messages([f])).toEqual(['“Root cause” has a choice with no name.', '“Root cause” has two choices called “a”.']);
    });

    it('refuses a smallest allowed above the largest', () => {
        const f = newField('Amount');
        f.type = 'number';
        f.min = 10;
        f.max = 1;
        expect(messages([f])).toEqual(['“Amount”: the smallest allowed is larger than the largest.']);
    });

    it('refuses a pattern that is not a valid regular expression', () => {
        const f = newField('Ref');
        f.pattern = '([a-z';
        expect(messages([f])).toEqual(['“Ref”: the pattern is not valid.']);
    });

    it('refuses a "Show only when" whose field was removed, sits below, or whose choice was removed', () => {
        const a = newField('Outcome');
        a.type = 'select';
        a.choices = [newChoice('Win'), newChoice('Loss')];
        const b = newField('Loss amount');
        b.showWhen = { targetUid: a.uid, negated: false, value: a.choices[1].uid };
        expect(messages([a, b])).toEqual([]);

        expect(messages([b, a])).toEqual(['“Loss amount”: “Show only when” must point at a field above it.']);
        a.choices = [a.choices[0]];
        expect(messages([a, b])).toEqual(['“Loss amount”: “Show only when” points at a choice that was removed.']);
        expect(messages([b])).toEqual(['“Loss amount”: “Show only when” points at a field that was removed.']);
        b.showWhen = { targetUid: a.uid, negated: false, value: '' };
        expect(messages([a, b])).toEqual(['“Loss amount”: pick a value for “Show only when”.']);
    });
});

describe('fieldsAbove', () => {
    /**
     * A negative with a probe that would otherwise succeed: the server accepts a FORWARD reference
     * (`FindingsSpec.fromMap` resolves dependsOn after every key is known), so a field below is a legal
     * target on the wire — and must still be absent from the picker.
     */
    it('offers only the fields above, never the field itself or one below', () => {
        const fields = fromWire(BUILT_IN);
        const above = fieldsAbove(fields, fields[2].uid).map((f) => f.key);
        expect(above).toEqual(['disposition', 'impactAmount']);
        expect(above).not.toContain('summary');
        expect(above).not.toContain('recordsAffected');
    });
});

describe('control choices', () => {
    /** Parity with the committed cross-language contract: every wire type has a plain label. */
    it('names every contract type, with the four technical ones under Technical details', () => {
        expect(CONTROL_CHOICES.map((c) => c.type).sort()).toEqual(ATTRIBUTE_CONTRACT.types);
        expect(CONTROL_CHOICES.filter((c) => !c.technical).map((c) => c.type)).toEqual([
            'select',
            'string',
            'multiline',
            'number',
            'boolean',
        ]);
    });
});
