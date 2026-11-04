import { describe, expect, it } from 'vitest';
import fixture from './entity-shape-parity.fixture.json';
import { ShapeFit, VALUE_SHAPE_NAMES, isValueShape, sampleFits, shapeFit, shapeSpecificity } from './value-shape';

/**
 * ENTITY-TYPE-CATALOG-1 parity — the browser half. `entity-shape-parity.fixture.json` is ALSO run by the Java
 * `ValueShapesTest`; a value that fits on one side only would mark a column "shape matches" while Add data disagrees.
 */
type Case = { shape: string; input: string; expected: ShapeFit };
const cases = fixture.cases as Case[];

describe('shapeFit (browser half of the shared value-shape fixture)', () => {
    it.each(cases)('$shape: $input', ({ shape, input, expected }) => {
        expect(shapeFit(shape, input)).toBe(expected);
    });

    it('the vocabulary is closed', () => {
        for (const s of fixture.valid) expect(isValueShape(s), s).toBe(true);
        for (const s of fixture.invalid) expect(isValueShape(s), s).toBe(false);
        expect(shapeFit('\\d+', '123')).toBe('none');
    });

    it('the fixture covers every shape and every fit', () => {
        const names = new Set(
            cases.map((c) => (c.shape.includes('(') ? c.shape.slice(0, c.shape.indexOf('(')) : c.shape)),
        );
        expect([...names].sort()).toEqual([...VALUE_SHAPE_NAMES].sort());
        expect([...new Set(cases.map((c) => c.expected))].sort()).toEqual(['none', 'strong', 'weak']);
    });
});

describe('shapeSpecificity', () => {
    it('ranks a narrower shape higher, as the Java side', () => {
        expect(shapeSpecificity('luhn(15)')).toBeGreaterThan(shapeSpecificity('digits(15)'));
        expect(shapeSpecificity('digits(15)')).toBeGreaterThan(shapeSpecificity('e164'));
        expect(shapeSpecificity('e164')).toBeGreaterThan(shapeSpecificity('free'));
        expect(shapeSpecificity(null)).toBe(0);
    });
});

describe('sampleFits', () => {
    it('counts fitting non-blank values and says whether all fit', () => {
        expect(sampleFits('e164', ['+447700900123', '447700900123', '', null, ' '])).toEqual({
            fit: 2,
            of: 2,
            all: true,
            strong: false,
        });
        expect(sampleFits('e164', ['+447700900123', '+447700900124'])).toEqual({
            fit: 2,
            of: 2,
            all: true,
            strong: true,
        });
        expect(sampleFits('e164', ['+447700900123', 'n/a'])).toEqual({ fit: 1, of: 2, all: false, strong: false });
        expect(sampleFits('free', ['anything'])).toEqual({ fit: 0, of: 1, all: false, strong: false });
        expect(sampleFits('e164', [])).toEqual({ fit: 0, of: 0, all: false, strong: false });
    });
});
