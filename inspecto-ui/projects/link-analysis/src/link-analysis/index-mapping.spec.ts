import { describe, expect, it } from 'vitest';
import { indexMappingAttributes, indexMappingRequest } from './index-mapping';

/** LA-INDEX-SPA-SURFACES-1: the body a NEW index mapping sends to `POST /inv/index/builds`. */
describe('indexMappingRequest', () => {
    it('keeps the set keys, trims them, drops blanks, and is always a full build', () => {
        expect(
            indexMappingRequest({
                dataset: ' wires ',
                sourceCol: 'from',
                targetCol: 'to',
                kindCol: '',
                timeCol: 'ts',
                timeColZone: 'Europe/Berlin',
                weightCol: null,
                attrCols: ['ch', ' ', 'amt'],
            }),
        ).toEqual({
            dataset: 'wires',
            sourceCol: 'from',
            targetCol: 'to',
            timeCol: 'ts',
            timeColZone: 'Europe/Berlin',
            attrCols: ['ch', 'amt'],
            mode: 'full',
        });
    });

    it('never sends a zone without its time column (the server refuses it)', () => {
        const q = indexMappingRequest({ dataset: 'd', sourceCol: 'a', targetCol: 'b', timeColZone: 'UTC' });
        expect(q).not.toHaveProperty('timeColZone');
        expect(q).not.toHaveProperty('attrCols');
    });

    it('requires only the Dataset and the two link columns', () => {
        const required = indexMappingAttributes()
            .filter((s) => s.required !== false)
            .map((s) => s.key);
        expect(required).toEqual(['dataset', 'sourceCol', 'targetCol']);
    });
});
