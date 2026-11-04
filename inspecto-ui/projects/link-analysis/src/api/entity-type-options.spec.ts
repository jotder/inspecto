import { describe, expect, it } from 'vitest';
import { EntityFamily, OTHER_FAMILY, entityTypeHint, entityTypeOptions } from './entity-type-options';

/** A synthetic catalogue: the families the server serves, in its order. */
const FAMILIES: EntityFamily[] = [
    { id: 'party', label: 'Party', definition: 'A person.' },
    { id: 'account', label: 'Account', definition: 'Holds a balance.' },
    { id: 'identifier', label: 'Identifier', definition: 'A number that names something.' },
    { id: 'place', label: 'Place', definition: 'Where.' },
];

describe('entityTypeOptions (ENTITY-TYPE-CATALOG-1 P0)', () => {
    it('groups the types by family in the families order, keeping their own order within a family', () => {
        const options = entityTypeOptions(
            [
                { id: 'msisdn', label: 'Phone number', family: 'identifier' },
                { id: 'wallet', label: 'Stored-value account', family: 'account' },
                { id: 'subscriber', label: 'Subscriber', family: 'party' },
                { id: 'account', label: 'Account', family: 'account' },
                { id: 'imsi', label: 'Subscriber identity', family: 'identifier' },
            ],
            FAMILIES,
        );
        expect(options.map((o) => [o.group, o.value])).toEqual([
            ['Party', 'subscriber'],
            ['Account', 'wallet'],
            ['Account', 'account'],
            ['Identifier', 'msisdn'],
            ['Identifier', 'imsi'],
        ]);
    });

    it('puts a type with no family, or a family the server did not list, last under Other', () => {
        const options = entityTypeOptions(
            [
                { id: 'note', label: 'Free text' },
                { id: 'cell', label: 'Location', family: 'place' },
                { id: 'odd', label: 'Odd', family: 'telephony' },
            ],
            FAMILIES,
        );
        expect(options.map((o) => [o.group, o.value])).toEqual([
            ['Place', 'cell'],
            [OTHER_FAMILY, 'note'],
            [OTHER_FAMILY, 'odd'],
        ]);
    });

    it('shows the definition and an example under each choice, and the label (else the id) as its name', () => {
        const [b, a] = entityTypeOptions(
            // Party sorts before Identifier
            [
                {
                    id: 'msisdn',
                    label: 'MSISDN',
                    family: 'identifier',
                    definition: 'A telephone number.',
                    example: '+44 7700 900123',
                },
                { id: 'x', label: '', family: 'party' },
            ],
            FAMILIES,
        );
        expect(b).toEqual({ value: 'x', label: 'x', group: 'Party' });
        expect(a).toEqual({
            value: 'msisdn',
            label: 'MSISDN',
            group: 'Identifier',
            hint: 'A telephone number. e.g. +44 7700 900123',
        });
    });

    it('draws no groups when the server lists no families (an older server)', () => {
        const options = entityTypeOptions([{ id: 'msisdn', label: 'Phone number', family: 'identifier' }], []);
        expect(options).toEqual([{ value: 'msisdn', label: 'Phone number' }]);
    });

    it('a hint is the definition alone, the example alone, or nothing', () => {
        expect(entityTypeHint({ id: 'a', label: 'A', definition: ' Def. ' })).toBe('Def.');
        expect(entityTypeHint({ id: 'a', label: 'A', example: 'X-1' })).toBe('e.g. X-1');
        expect(entityTypeHint({ id: 'a', label: 'A', definition: ' ', example: null })).toBeUndefined();
    });
});
