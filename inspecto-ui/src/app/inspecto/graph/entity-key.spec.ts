import { describe, expect, it } from 'vitest';
import { ENTITY_NORMALISERS, EntityNormaliser, normalizeEntityKey, normalizeTypedKey, typedEntityKey } from './entity-key';
import fixture from './entity-normaliser-parity.fixture.json';

/**
 * LA-17 step 2 parity — the browser half. `entity-normaliser-parity.fixture.json` is ALSO run by the Java
 * `EntityTypesTest`; a case passing on one side only is a split identity.
 */
type Case = { normaliser: EntityNormaliser; input: string; expected: string };
const cases = fixture.cases as Case[];

describe('normalizeTypedKey (browser half of the shared normaliser fixture)', () => {
    it.each(cases)('$normaliser: $input', ({ normaliser, input, expected }) => {
        expect(normalizeTypedKey(input, normaliser)).toBe(expected);
    });

    it('normalizeEntityKey is the default normaliser', () => {
        for (const c of cases.filter((x) => x.normaliser === 'default')) {
            expect(normalizeEntityKey(c.input)).toBe(c.expected);
        }
    });

    it('the fixture covers every normaliser, and names no other', () => {
        const used = new Set(cases.map((c) => c.normaliser));
        expect([...used].sort()).toEqual([...ENTITY_NORMALISERS].sort());
    });
});

describe('typedEntityKey', () => {
    it('prefixes the normalised key with the Entity Type', () => {
        expect(typedEntityKey('phone', ' 0044 7700-900123', 'e164')).toBe('phone:+447700900123');
        expect(typedEntityKey('imei', '35-209900-176148-1', 'digits')).toBe('imei:352099001761481');
        expect(typedEntityKey('iban', '  gb29 nwbk  6016 ', 'upper-trim')).toBe('iban:GB29 NWBK 6016');
        expect(typedEntityKey('org', ' Acme  Ltd.', 'default')).toBe('org:acme ltd');
    });

    it('two spellings of one typed entity get one id', () => {
        expect(typedEntityKey('phone', '+44 7700 900123', 'e164')).toBe(
            typedEntityKey('phone', '0044 7700-900123', 'e164'),
        );
    });
});
