import { describe, expect, it } from 'vitest';
import {
    EntityFamily,
    OTHER_FAMILY,
    RecordTypeGuide,
    SHAPE_MATCHES,
    entityTypeHint,
    entityTypeOptions,
    likelyGroup,
} from './entity-type-options';

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

describe('entityTypeOptions narrowed by record type and sample (ENTITY-TYPE-CATALOG-1 P2)', () => {
    const TYPES = [
        { id: 'subscriber', label: 'Subscriber', family: 'party', shape: 'free' },
        { id: 'wallet', label: 'Wallet', family: 'account' },
        { id: 'msisdn', label: 'Phone number', family: 'identifier', shape: 'e164', definition: 'A number.' },
        { id: 'imsi', label: 'Card identity', family: 'identifier', shape: 'digits(15)' },
        { id: 'cell', label: 'Location', family: 'place' },
    ];
    const CALL: RecordTypeGuide = { id: 'call', label: 'Call record', likelyTypes: ['cell', 'msisdn', 'gone'] };

    it('puts the record type likely types first, in its order, then every other type by family', () => {
        const options = entityTypeOptions(TYPES, FAMILIES, { recordType: CALL });
        expect(options.map((o) => [o.group, o.value])).toEqual([
            ['Likely in a call record', 'cell'],
            ['Likely in a call record', 'msisdn'],
            ['Party', 'subscriber'],
            ['Account', 'wallet'],
            ['Identifier', 'imsi'],
        ]);
        expect(options.map((o) => o.value)).toHaveLength(TYPES.length);
    });

    it('marks a type whose shape every sampled value fits, first in its group', () => {
        const options = entityTypeOptions(TYPES, FAMILIES, {
            recordType: CALL,
            sample: ['+447700900123', ' ', '447700900124'],
        });
        expect(options[0]).toEqual({
            value: 'msisdn',
            label: 'Phone number',
            group: 'Likely in a call record',
            hint: 'Shape matches. A number.',
        });
        expect(options[1].value).toBe('cell');
        expect(options.find((o) => o.value === 'subscriber')?.hint).toBeUndefined();
    });

    it('without a record type, the shape-matching types lead in a group of their own', () => {
        const options = entityTypeOptions(TYPES, FAMILIES, { sample: ['001010123456789'] });
        expect(options[0]).toEqual({
            value: 'imsi',
            label: 'Card identity',
            group: SHAPE_MATCHES,
            hint: 'Shape matches.',
        });
        // 15 bare digits fit a phone number only weakly: it follows the strong fit
        expect(options.filter((o) => o.group === SHAPE_MATCHES).map((o) => o.value)).toEqual(['imsi', 'msisdn']);
        // a sample that one value spoils marks nothing, and no context is P0's grouping
        expect(entityTypeOptions(TYPES, FAMILIES, { sample: ['001010123456789', 'n/a'] })[0].group).toBe('Party');
        expect(entityTypeOptions(TYPES, FAMILIES)).toEqual(entityTypeOptions(TYPES, FAMILIES, {}));
    });

    it('reads the record type label as a sentence', () => {
        expect(likelyGroup({ id: 'a', label: 'Transfer', likelyTypes: [] })).toBe('Likely in a transfer');
        expect(likelyGroup({ id: 'a', label: 'SMS record', likelyTypes: [] })).toBe('Likely in an SMS record');
        expect(likelyGroup({ id: 'a', label: 'Airtime top-up', likelyTypes: [] })).toBe('Likely in an airtime top-up');
    });
});

describe('shape marks read on the server (ENTITY-TYPE-RECORD-ROLES-1)', () => {
    const types = [
        { id: 'account', label: 'Account', family: 'account', shape: 'digits(10)' },
        { id: 'phone', label: 'Phone number', family: 'identifier', shape: 'e164' },
    ];
    const families = [
        { id: 'account', label: 'Account', definition: '' },
        { id: 'identifier', label: 'Identifier', definition: '' },
    ];

    it('marks and leads with the types the server says the values fit, a strong fit first', () => {
        const fits = new Map([
            ['account', { strong: false }],
            ['phone', { strong: true }],
        ]);
        const opts = entityTypeOptions(types, families, { fits });
        expect(opts.map((o) => [o.group, o.value])).toEqual([
            ['Shape matches', 'phone'],
            ['Shape matches', 'account'],
        ]);
        expect(opts.every((o) => (o.hint ?? '').startsWith('Shape matches.'))).toBe(true);
    });

    it("marks nothing for an empty answer, and a sample of the screen's own wins over the server's marks", () => {
        expect(entityTypeOptions(types, families, { fits: new Map() }).some((o) => o.group === 'Shape matches')).toBe(
            false,
        );
        const own = entityTypeOptions(types, families, {
            sample: ['+447700900123'],
            fits: new Map([['account', { strong: true }]]),
        });
        expect(own[0]).toMatchObject({ value: 'phone', group: 'Shape matches' });
    });
});
