import { describe, expect, it } from 'vitest';
import { derivedSchemaRows, inferredSchemaFormats, inferredSchemaTypes } from './parse-output-schema';

/**
 * BASIC-ELT-PROFILE-1 U3: the parse preview serves one type answer per column with the format a date is read with. A
 * derived schema row keeps that format, because a `DATE` of `March 22, 2025` is only a date WITH `%B %d, %Y` - without
 * it the engine lands the value NULL.
 */
describe('derivedSchemaRows - the inferred date formats travel with the types', () => {
    const preview = {
        columnTypes: [
            { name: 'd', type: 'DATE', format: '%B %d, %Y' },
            { name: 'n', type: 'BIGINT' },
            { name: 't', type: 'TIMESTAMP', format: '%Y-%m-%dT%H:%M:%S' },
            { name: 'h', type: 'TIME' },
        ],
    };

    it('reads the types and formats by position', () => {
        expect(inferredSchemaTypes(preview)).toEqual(['DATE', 'BIGINT', 'TIMESTAMP', 'TIME']);
        expect(inferredSchemaFormats(preview)).toEqual(['%B %d, %Y', undefined, '%Y-%m-%dT%H:%M:%S', undefined]);
        expect(inferredSchemaFormats({})).toBeNull();
    });

    it('a date row carries its format; any other row carries none', () => {
        const rows = derivedSchemaRows(
            'delimited',
            ['d', 'n', 't', 'h'],
            inferredSchemaTypes(preview),
            inferredSchemaFormats(preview),
        );
        expect(rows.map((r) => [r.type, r.format])).toEqual([
            ['DATE', '%B %d, %Y'],
            ['BIGINT', undefined],
            ['TIMESTAMP', '%Y-%m-%dT%H:%M:%S'],
            ['TIME', undefined],
        ]);
    });

    it('without formats (an older caller) the rows are as before', () => {
        const rows = derivedSchemaRows('delimited', ['d'], ['DATE']);
        expect(rows).toEqual([{ include: true, name: 'd', selector: '0', type: 'DATE' }]);
    });
});
