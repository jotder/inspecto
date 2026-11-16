import { describe, expect, it } from 'vitest';
import type { G6GraphData } from '@inspecto/core/graph';
import { LinkAnalysisView } from './link-analysis.service';
import {
    bindingWords,
    columnPreview,
    pickFollowTheMoney,
    plainColumn,
    rankDatasets,
    suggestLinkColumns,
    topSeeds,
    wordsOf,
} from './la-starter';

const cols = (...names: string[]) => names.map((name) => ({ name }));

function view(
    id: string,
    datasetId: string,
    description = '',
    sourceId: LinkAnalysisView['sourceId'] = 'entity-projection',
) {
    return {
        id,
        name: id,
        description,
        sourceId,
        query: { projection: { datasetId, sourceCol: 'a', targetCol: 'b' } },
    } as LinkAnalysisView;
}

describe('la-starter - link-shape heuristic', () => {
    it('splits snake, kebab and camel case into words', () => {
        expect(wordsOf('PAYER_ACCOUNT')).toEqual(['payer', 'account']);
        expect(wordsOf('calledNumber')).toEqual(['called', 'number']);
        expect(wordsOf('src-ip')).toEqual(['src', 'ip']);
    });

    it.each([
        [['TXN_ID', 'PAYER_ACCOUNT', 'PAYEE_ACCOUNT', 'AMOUNT'], 'PAYER_ACCOUNT', 'PAYEE_ACCOUNT'],
        [['src', 'dst', 'bytes'], 'src', 'dst'],
        [['caller', 'callee'], 'caller', 'callee'],
        [['calling_number', 'called_number'], 'calling_number', 'called_number'],
        [['sender', 'receiver'], 'sender', 'receiver'],
        [['source', 'target'], 'source', 'target'],
        [['from_account', 'to_account'], 'from_account', 'to_account'],
        [['ACCOUNT_A', 'ACCOUNT_B'], 'ACCOUNT_A', 'ACCOUNT_B'],
    ])('finds the pair in %j', (names, source, target) => {
        const s = suggestLinkColumns(cols(...(names as string[])));
        expect(s?.source).toBe(source);
        expect(s?.target).toBe(target);
    });

    it('prefers the telling pair and the one whose rest of the name matches', () => {
        const s = suggestLinkColumns(cols('from_date', 'to_date', 'payer_id', 'payee_id'));
        expect(s?.source).toBe('payer_id');
        const t = suggestLinkColumns(cols('payer_a', 'payee_a', 'payee_b'));
        expect(t?.target).toBe('payee_a');
    });

    it('needs both ends to be string-like and distinct', () => {
        expect(suggestLinkColumns(cols('payer'))).toBeNull();
        expect(suggestLinkColumns(cols('order_id', 'amount', 'region'))).toBeNull();
        expect(
            suggestLinkColumns([
                { name: 'src', type: 'number' },
                { name: 'dst', type: 'number' },
            ]),
        ).toBeNull();
        expect(
            suggestLinkColumns([
                { name: 'src', type: 'string' },
                { name: 'dst', type: 'string' },
            ]),
        ).not.toBeNull();
    });

    it('previews the first columns and counts the rest', () => {
        expect(columnPreview(cols('a', 'b'))).toBe('a, b');
        expect(columnPreview(cols('a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'))).toBe('a, b, c, d, e, f +2 more');
        expect(columnPreview([])).toBe('');
    });
});

describe('la-starter - dataset ranking', () => {
    const datasets = [
        { id: 'maintenance_backups', name: 'maintenance_backups', columns: cols('file', 'size') },
        { id: 'zeta', name: 'zeta', columns: cols('sender', 'receiver') },
        {
            id: 'mule',
            name: 'mule',
            description: 'Transfers. Synthetic.',
            columns: cols('PAYER_ACCOUNT', 'PAYEE_ACCOUNT'),
        },
        { id: 'undeclared', name: 'undeclared', columns: [] },
    ];

    it('lists link-shaped Datasets first, best pair first, the rest after by name', () => {
        const ranked = rankDatasets(datasets);
        expect(ranked.map((r) => r.id)).toEqual(['mule', 'zeta', 'maintenance_backups', 'undeclared']);
    });

    it('gives every Dataset a one-line reason and a column preview', () => {
        const [mule, , backups, undeclared] = rankDatasets(datasets);
        expect(mule.hint).toContain('PAYER_ACCOUNT and PAYEE_ACCOUNT look like the two ends of a link');
        expect(mule.hint).toContain('Transfers.');
        expect(mule.hint).toContain('Columns: PAYER_ACCOUNT, PAYEE_ACCOUNT.');
        expect(backups.hint).not.toContain('from/to'); // T-171: the picker groups it instead of a line per row
        expect(undeclared.hint).toContain('read when you pick it');
        expect(mule.shape).toMatchObject({ source: 'PAYER_ACCOUNT', target: 'PAYEE_ACCOUNT' });
        expect(backups.shape).toBeNull();
        expect([backups.checked, undeclared.checked]).toEqual([true, false]); // unread columns: not yet known either way
    });

    it('uses probed columns for a Dataset that declares none', () => {
        const ranked = rankDatasets(datasets, new Map([['undeclared', cols('src', 'dst')]]));
        expect(ranked.find((r) => r.id === 'undeclared')?.shape?.source).toBe('src');
    });
});

describe('la-starter - follow the money', () => {
    const datasets = [{ id: 'mule' }, { id: 'tele' }];

    it('picks the money-themed Entity/Link view over a Dataset this Space has', () => {
        const picked = pickFollowTheMoney(
            [
                view('roaming', 'tele', 'Roaming footprint'),
                view('mule_structuring', 'mule', 'Structuring of cash'),
                view('mule_layering_ring', 'mule', 'Money-mule layering ring'),
            ],
            datasets,
        );
        expect(picked?.id).toBe('mule_layering_ring');
    });

    it('is null when no view fits - the card is hidden, never wrong', () => {
        expect(pickFollowTheMoney([], datasets)).toBeNull();
        expect(pickFollowTheMoney([view('roaming', 'tele', 'Roaming footprint')], datasets)).toBeNull();
        // a money view over a Dataset the Space does not have would load nothing
        expect(pickFollowTheMoney([view('mule_layering_ring', 'gone', 'money')], datasets)).toBeNull();
        // a lineage view is not an Entity/Link example
        expect(pickFollowTheMoney([view('money', 'mule', 'money', 'lineage')], datasets)).toBeNull();
    });
});

describe('la-starter - top seeds', () => {
    const graph: G6GraphData = {
        nodes: [
            { id: 'a', data: { label: 'A', kind: 'entity', spellings: ['acct-a', 'ACCT A'] } },
            { id: 'b', data: { label: 'B', kind: 'entity' } },
            { id: 'm', data: { label: 'M', kind: 'entity', spellings: ['masked:0123456789abcdef'] } },
            { id: 'c', data: { label: 'C', kind: 'entity' } },
        ],
        edges: [],
    };
    const ranked = ['a', 'm', 'ghost', 'b', 'c'].map((id) => ({ id, label: id.toUpperCase() }));

    it('takes the top n in rank order with their raw ids, skipping masked and unknown nodes', () => {
        const seeds = topSeeds(ranked, graph, 2);
        expect(seeds).toEqual([
            { id: 'a', label: 'A', ids: ['acct-a', 'ACCT A'] },
            { id: 'b', label: 'B', ids: ['B'] },
        ]);
    });

    it('is empty without a graph', () => {
        expect(topSeeds(ranked, null)).toEqual([]);
    });
});

describe('bindingWords - an Investigation binding in plain words (LA-FULL-CYCLE-TEST-1 presentability 2)', () => {
    const h = { dataset: 'mule_transfers_dataset', sourceCol: 'PAYER_ACCOUNT', targetCol: 'PAYEE_ACCOUNT' };

    it('says the columns as words, whatever their casing', () => {
        expect(plainColumn('PAYER_ACCOUNT')).toBe('payer account');
        expect(plainColumn('fromAccount')).toBe('from account');
        expect(plainColumn('caller-msisdn')).toBe('caller msisdn');
        expect(plainColumn('A')).toBe('a');
    });

    it('names the Dataset by its title, else its description, else its id; the raw names stay for the tooltip', () => {
        const described = {
            id: 'mule_transfers_dataset',
            name: 'mule_transfers_dataset',
            description:
                'Account-to-account transfers carrying a planted money-mule layering ring - the Link Analysis showcase. Synthetic.',
        };
        expect(bindingWords(h, described)).toEqual({
            // the whole first sentence, longer than the picker's 90 characters: never cut mid-word here
            dataset:
                'Account-to-account transfers carrying a planted money-mule layering ring - the Link Analysis showcase.',
            links: 'Links from payer account to payee account',
            raw: 'mule_transfers_dataset · PAYER_ACCOUNT → PAYEE_ACCOUNT',
        });
        expect(bindingWords(h, { ...described, name: 'Mule transfers' }).dataset).toBe('Mule transfers');
        expect(bindingWords(h, { id: h.dataset, name: h.dataset }).dataset).toBe('mule_transfers_dataset');
        expect(bindingWords(h, null).dataset).toBe('mule_transfers_dataset');
    });
});
