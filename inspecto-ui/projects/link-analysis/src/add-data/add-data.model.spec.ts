import { describe, expect, it } from 'vitest';
import {
    AUTOMATION_FALLBACK,
    buildCommit,
    choiceKey,
    editionLock,
    excelLine,
    fileProblem,
    initialColumns,
    initialRoles,
    limitsLine,
    lowChoices,
    suggestDatasetName,
    summaryLines,
} from './add-data.model';
import { accountsDraft, lowDraft, summary, transfersDraft } from './add-data.testing';

describe('Add data model (LA-ONBOARD-1 phase 4)', () => {
    describe('lowChoices - the OF-2 confirm gate', () => {
        it('a draft of sure detections asks for nothing', () => {
            expect(lowChoices(transfersDraft(), true)).toEqual([]);
        });

        it('lists every guess in screen order, by the key the template confirms', () => {
            expect(lowChoices(lowDraft(), true).map((p) => p.key)).toEqual([
                choiceKey.format('header'),
                choiceKey.type('amount'),
                choiceKey.link('from'),
            ]);
        });

        it('a left-out column needs no confirmation, and a closed optional step leaves out its own guesses', () => {
            expect(lowChoices(lowDraft(), true, new Set(['amount'])).map((p) => p.key)).not.toContain('type.amount');
            expect(lowChoices(lowDraft(), false).map((p) => p.key)).toEqual(['format.header', 'type.amount']);
        });
    });

    describe('buildCommit', () => {
        it('every proposal pre-applied: Entity Types explicit, unchanged types omitted, the full link', () => {
            const d = transfersDraft();
            const body = buildCommit(d, 'transfers', '', initialColumns(d), initialRoles(d), { withEntityTypes: true });
            expect(body.name).toBe('transfers');
            expect(body.description).toBeUndefined();
            expect(body.columns.find((c) => c.name === 'from_account')).toEqual({
                name: 'from_account',
                entityType: 'account',
            });
            expect(body.columns.find((c) => c.name === 'ts')).toEqual({ name: 'ts', entityType: null });
            expect(body.primaryEntityType).toBe('account');
            expect(body.link).toEqual({
                from: 'from_account',
                to: 'to_account',
                kind: 'channel',
                time: 'ts',
                amount: 'amount',
            });
            expect(body.identifyingColumn).toBeUndefined();
        });

        it('renames, a changed type and a left-out column; a link role on a left-out column is dropped', () => {
            const d = transfersDraft();
            const cols = initialColumns(d).map((c) =>
                c.name === 'txn_id'
                    ? { ...c, rename: 'transfer_id' }
                    : c.name === 'amount'
                      ? { ...c, type: 'VARCHAR' }
                      : c.name === 'channel'
                        ? { ...c, drop: true }
                        : c,
            );
            const body = buildCommit(d, 'transfers', ' Sept ', cols, initialRoles(d), { withEntityTypes: true });
            expect(body.description).toBe('Sept');
            expect(body.columns.find((c) => c.name === 'txn_id')!.rename).toBe('transfer_id');
            expect(body.columns.find((c) => c.name === 'amount')!.type).toBe('VARCHAR');
            expect(body.columns.find((c) => c.name === 'channel')).toEqual({ name: 'channel', drop: true });
            expect(body.link).toEqual({ from: 'from_account', to: 'to_account', time: 'ts', amount: 'amount' });
        });

        it('no link without two different ends; a primary nobody holds is not sent', () => {
            const d = transfersDraft();
            const roles = { ...initialRoles(d), to: 'from_account', primary: 'msisdn' };
            const body = buildCommit(d, 'x', '', initialColumns(d), roles, { withEntityTypes: true });
            expect(body.link).toBeNull();
            expect(body.primaryEntityType).toBeNull();
        });

        it('the closed optional step (console): sure detections stand, guesses are NOT applied', () => {
            const d = lowDraft();
            d.entityTypes.columns[1].entityType = { ...d.entityTypes.columns[1].entityType, confidence: 'low' };
            const body = buildCommit(d, 'x', '', initialColumns(d), initialRoles(d), { withEntityTypes: false });
            expect(body.columns.find((c) => c.name === 'from_account')!.entityType).toBeNull();
            expect(body.columns.find((c) => c.name === 'to_account')!.entityType).toBe('account');
            expect(body.link).toBeNull(); // the from/to pair was a guess
            expect(body.primaryEntityType).toBe('account');
        });

        it('a Reference names its identifying column and sends no link', () => {
            const d = accountsDraft();
            const body = buildCommit(d, 'accounts', '', initialColumns(d), initialRoles(d), { withEntityTypes: true });
            expect(body.identifyingColumn).toBe('account_id');
            expect(body.link).toBeUndefined();
        });
    });

    it('suggestDatasetName mirrors the server (OnboardImport.datasetId)', () => {
        expect(suggestDatasetName('transfers.csv')).toBe('transfers');
        expect(suggestDatasetName('Sept Transfers (v2).CSV')).toBe('sept_transfers_v2');
        expect(suggestDatasetName('2026-09.csv')).toBe('data_2026_09');
    });

    it('fileProblem: what the browser can already tell', () => {
        expect(fileProblem({ name: 'a.csv', size: 0 }, null)).toContain('empty');
        expect(fileProblem({ name: 'a.xls', size: 10 }, null)).toContain('save the sheet as CSV');
        expect(fileProblem({ name: 'a.zip', size: 10 }, null)).toContain('unpack');
        expect(fileProblem({ name: 'a.csv', size: 2048 }, 1024)).toContain('over this Space');
        expect(fileProblem({ name: 'a.csv', size: 10 }, 1024)).toBe('');
    });

    it('formats and limits in words', () => {
        expect(limitsLine({ maxBytes: 524288000, maxRows: 10000000, maxImports: 20, imports: 3 })).toBe(
            'Up to 500 MB and 10,000,000 rows per file; 20 imports per Space (3 so far).',
        );
        expect(excelLine(true)).toContain('are read too');
        expect(excelLine(false)).toContain('save the sheet as CSV');
        expect(excelLine(null)).toContain('when this installation includes Excel support');
    });

    describe('summaryLines - the result in plain words', () => {
        it('rows read and kept, and the index built', () => {
            expect(summaryLines(summary()).map((l) => l.text)).toEqual([
                '2,215 rows were read from transfers.csv; 2,215 rows were kept.',
                'The link index was built: 2,215 links between 312 entities.',
            ]);
        });

        it('rows set aside, a set-aside file, a replace, an index refused and each Expectation', () => {
            const lines = summaryLines(
                summary({
                    rowsRejected: 12,
                    quarantined: [{ file: 'b.csv', reason: 'wrong column count' }],
                    replaced: true,
                    rowsBefore: 2000,
                    rowsAfter: 2203,
                    index: { result: 'REFUSED', message: 'over the memory budget' },
                    expectations: [
                        { name: 'to_account_present', status: 'PASS', violations: 0 },
                        { name: 'amount_positive', status: 'FAIL', violations: 3 },
                        { name: 'broken', status: 'ERROR', message: 'no such column' },
                    ],
                }),
            );
            expect(lines.map((l) => l.text)).toEqual([
                '2,215 rows were read from transfers.csv; 2,203 rows were kept.',
                '12 rows were set aside because they did not match the column types.',
                'b.csv was set aside: wrong column count.',
                'This replaced the previous data: 2,000 rows before, 2,203 rows now.',
                'The link index was not built: over the memory budget.',
                'Expectation to_account_present: met.',
                'Expectation amount_positive: not met (3 rows).',
                'Expectation broken could not be checked: no such column.',
            ]);
            expect(lines.map((l) => l.tone)).toEqual([
                'success',
                'warning',
                'warning',
                'info',
                'warning',
                'success',
                'warning',
                'error',
            ]);
        });

        it('Run now with nothing waiting says so; a Reference does not report its missing link index', () => {
            expect(
                summaryLines(
                    summary({
                        file: undefined,
                        rowsIn: 0,
                        rowsBefore: 2215,
                        rowsAfter: 2215,
                        rowsChanged: 0,
                        index: { result: 'UP_TO_DATE' },
                    }),
                )[0].text,
            ).toBe('No new file was waiting; the Dataset holds 2,215 rows.');
            const ref = summaryLines(
                summary({
                    kind: 'reference',
                    index: { result: 'SKIPPED', message: 'the Dataset has no link mapping' },
                }),
            );
            expect(ref.map((l) => l.text).join(' ')).not.toContain('index');
        });
    });

    describe('editionLock (A-11, DT-4, DT-5)', () => {
        it('locked where the server says false, with its reason when it gives one', () => {
            expect(
                editionLock(
                    'pipelineAutomation',
                    { pipelineAutomation: false },
                    { pipelineAutomation: 'Standard has it' },
                    'standard',
                    AUTOMATION_FALLBACK,
                ),
            ).toBe('Standard has it');
        });

        it('a server that does not name the feature yet: locked on Basic only, with the fallback line', () => {
            expect(editionLock('pipelineAutomation', {}, {}, 'basic', AUTOMATION_FALLBACK)).toBe(AUTOMATION_FALLBACK);
            expect(editionLock('pipelineAutomation', {}, {}, 'standard', AUTOMATION_FALLBACK)).toBeNull();
            expect(
                editionLock('pipelineAutomation', { pipelineAutomation: true }, {}, 'basic', AUTOMATION_FALLBACK),
            ).toBeNull();
        });
    });
});
