import type { Choice, ImportSummary, OnboardDraft } from './add-data.model';

/** SPEC-ONLY: drafts shaped like `POST /onboard/files` answered for the walkthrough's `transfers.csv` (2026-10-22, live). */
const hi = <T>(value: T, evidence = 'every sampled value agrees'): Choice<T> => ({
    value,
    evidence,
    confidence: 'high',
});
const lo = <T>(value: T, evidence = 'a guess'): Choice<T> => ({ value, evidence, confidence: 'low' });

export function transfersDraft(over: Partial<OnboardDraft> = {}): OnboardDraft {
    return {
        draftId: 'd0123456789abcdef0123456789abcdef',
        kind: 'stream',
        file: { name: 'transfers.csv', bytes: 123870, rows: 2215 },
        format: {
            type: hi('csv'),
            delimiter: hi(',', "every one of 1000 sampled lines splits into 6 fields on ','"),
            header: hi(true),
            quote: hi('"'),
        },
        columns: [
            { name: 'txn_id', header: 'txn_id', type: { ...hi('VARCHAR'), format: null }, sample: ['T1', 'T2', 'T3'] },
            {
                name: 'from_account',
                header: 'from_account',
                type: { ...hi('VARCHAR'), format: null },
                sample: ['ACC-0180'],
            },
            {
                name: 'to_account',
                header: 'to_account',
                type: { ...hi('VARCHAR'), format: null },
                sample: ['ACC-0090'],
            },
            { name: 'amount', header: 'amount', type: { ...hi('DOUBLE'), format: null }, sample: ['221.92'] },
            { name: 'channel', header: 'channel', type: { ...hi('VARCHAR'), format: null }, sample: ['agent'] },
            {
                name: 'ts',
                header: 'ts',
                type: { ...hi('TIMESTAMP', 'all 1000 sampled values parse as timestamp'), format: '%Y-%m-%d %H:%M:%S' },
                sample: ['2026-09-21 03:58:24'],
            },
        ],
        rows: [
            {
                txn_id: 'T1',
                from_account: 'ACC-0180',
                to_account: 'ACC-0090',
                amount: '221.92',
                channel: 'agent',
                ts: '2026-09-21 03:58:24',
            },
            {
                txn_id: 'T2',
                from_account: 'ACC-0159',
                to_account: 'ACC-0261',
                amount: '371.13',
                channel: 'app',
                ts: '2026-09-30 22:06:31',
            },
        ],
        entityTypes: {
            columns: [
                { column: 'txn_id', entityType: hi<string | null>(null) },
                {
                    column: 'from_account',
                    entityType: {
                        ...hi<string | null>('account', "the name 'from_account' says account"),
                        classification: 'ACCOUNT',
                    },
                },
                {
                    column: 'to_account',
                    entityType: {
                        ...hi<string | null>('account', "the name 'to_account' says account"),
                        classification: 'ACCOUNT',
                    },
                },
                { column: 'amount', entityType: hi<string | null>(null) },
                { column: 'channel', entityType: hi<string | null>(null) },
                { column: 'ts', entityType: hi<string | null>(null) },
            ],
            primary: hi<string | null>('account', '2 columns hold account'),
            link: {
                from: hi<string | null>('from_account', 'the names say from_account → to_account'),
                to: hi<string | null>('to_account', 'the names say from_account → to_account'),
                kind: hi<string | null>('channel'),
                time: hi<string | null>('ts'),
                amount: hi<string | null>('amount'),
                details: hi(['txn_id']),
            },
        },
        limits: {
            maxBytes: 524288000,
            maxRows: 10000000,
            maxImports: 20,
            imports: 0,
            inlineMaxBytes: 67108864,
            excel: true,
        },
        ...over,
    };
}

/** The same file where three choices are guesses: the header line, the amount column's type and the from/to pair. */
export function lowDraft(): OnboardDraft {
    const d = transfersDraft();
    d.format.header = lo(true, 'the first line holds names, but every column is text, so it could also be data');
    d.columns[3] = {
        ...d.columns[3],
        type: { ...lo('DOUBLE', '40 of 50 sampled values parse as double'), format: null },
    };
    d.entityTypes.link = {
        ...d.entityTypes.link!,
        from: lo<string | null>('from_account', 'the names do not say which way, so column order'),
        to: lo<string | null>('to_account', 'the names do not say which way, so column order'),
    };
    return d;
}

/** Details about entities (`accounts.csv`), as the server proposes them. */
export function accountsDraft(): OnboardDraft {
    return {
        draftId: 'dfedcba9876543210fedcba9876543210',
        kind: 'reference',
        file: { name: 'accounts.csv', bytes: 9000, rows: 312 },
        format: { type: hi('csv'), delimiter: hi(','), header: hi(true), quote: hi('"') },
        columns: [
            {
                name: 'account_id',
                header: 'account_id',
                type: { ...hi('VARCHAR'), format: null },
                sample: ['ACC-0001'],
            },
            { name: 'holder', header: 'holder', type: { ...hi('VARCHAR'), format: null }, sample: ['Holder 1'] },
            { name: 'kyc_level', header: 'kyc_level', type: { ...hi('VARCHAR'), format: null }, sample: ['basic'] },
        ],
        rows: [{ account_id: 'ACC-0001', holder: 'Holder 1', kyc_level: 'basic' }],
        entityTypes: {
            columns: [
                { column: 'account_id', entityType: { ...hi<string | null>('account'), classification: 'ACCOUNT' } },
                { column: 'holder', entityType: hi<string | null>(null) },
                { column: 'kyc_level', entityType: hi<string | null>(null) },
            ],
            primary: hi<string | null>('account'),
            identifying: {
                ...hi<string | null>('account_id', 'every one of its 312 sampled values is different'),
                entityType: 'account',
            },
        },
        limits: {
            maxBytes: 524288000,
            maxRows: 10000000,
            maxImports: 20,
            imports: 1,
            inlineMaxBytes: 67108864,
            excel: false,
        },
    };
}

export function summary(over: Partial<ImportSummary> = {}): ImportSummary {
    return {
        dataset: 'transfers',
        pipeline: 'transfers',
        kind: 'stream',
        file: 'transfers.csv',
        rowsIn: 2215,
        rowsRejected: 0,
        quarantined: [],
        rowsBefore: 0,
        rowsAfter: 2215,
        rowsChanged: 2215,
        replaced: false,
        index: { result: 'BUILT', message: 'built', edges: 2215, nodes: 312 },
        expectations: [],
        ...over,
    };
}
