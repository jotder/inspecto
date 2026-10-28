import { describe, expect, it } from 'vitest';
import type { ComponentDef } from '@inspecto/core/api';
import type { EntityListSummary } from '@inspecto/link-analysis/api/inv.service';
import { LinkAnalysisView } from '@inspecto/link-analysis/link-analysis/link-analysis.service';
import { caseRows, importedDatasets, lastImportLine } from './my-work';

const def = (name: string, content: Record<string, unknown>, modified?: string | null): ComponentDef => ({
    type: 'link-analysis-view',
    name,
    ref: `link-analysis-view/${name}`,
    content,
    ...(modified !== undefined ? { modified } : {}),
});
const decode = (id: string, c: Record<string, unknown>): LinkAnalysisView => ({
    id,
    name: (c['name'] as string) ?? id,
    description: c['description'] as string | undefined,
    sourceId: 'entity-projection',
    query: {},
    lists: c['lists'] as LinkAnalysisView['lists'],
});
const lists = [
    { id: 'suspects', title: 'Suspects' },
    { id: 'noisy', title: 'Busy merchants' },
] as EntityListSummary[];

describe('My work - cases (LA-ONBOARD-1 phase 7, A-1b, UC-14)', () => {
    it('most recent first, each with when it was saved and the lists it uses by title', () => {
        const rows = caseRows(
            [
                def('older', { name: 'Older' }, '2026-10-01T09:00:00Z'),
                def('ring', { name: 'Ring', description: 'The mules', lists: { start: 'suspects', exclusions: ['noisy'] } }, '2026-10-21T09:00:00Z'),
                def('undated', { name: 'A undated' }, null),
            ],
            decode,
            lists,
        );
        expect(rows.map((r) => r.id)).toEqual(['ring', 'older', 'undated']);
        expect(rows[0]).toMatchObject({
            name: 'Ring',
            description: 'The mules',
            saved: 'Saved 21 Oct 2026',
            lists: 'Lists: started from Suspects; leaves out Busy merchants',
        });
        expect(rows[1].lists).toBe('');
        expect(rows[2].saved).toBe(''); // the server did not say: no invented date
    });

    it('a list that is gone is named by its id rather than dropped', () => {
        const [row] = caseRows([def('x', { name: 'X', lists: { exclusions: ['retired-1'] } }, null)], decode, []);
        expect(row.lists).toBe('Lists: leaves out retired-1');
    });
});

describe('My work - what changed in your data (UC-14)', () => {
    it('only the Datasets Add data made count, by their import Pipeline', () => {
        const ds = (name: string, content: Record<string, unknown>): ComponentDef => ({ type: 'dataset', name, ref: '', content });
        expect(
            importedDatasets([
                ds('transfers', { origin: 'import', importPipeline: 'transfers' }),
                ds('mule_transfers', { kind: 'physical' }), // built by a Pipeline: not an import
                ds('forged', { origin: 'import' }),
            ]),
        ).toEqual([{ id: 'transfers', pipeline: 'transfers' }]);
    });

    it('the first import reads as +rows; a re-import as rows loaded (the ledger does not say replace or add)', () => {
        const run = (end: string, out: string, rejected = '0', status = 'SUCCESS') => ({
            status,
            end_time: end,
            total_output_rows: out,
            rejected_rows: rejected,
        });
        expect(lastImportLine('transfers', [run('2026-10-01T08:00:00Z', '2104')])).toEqual({
            text: 'transfers: imported 1 Oct 2026, +2,104 rows',
            at: Date.parse('2026-10-01T08:00:00Z'),
        });
        expect(
            lastImportLine('transfers', [
                run('2026-10-01T08:00:00Z', '2104'),
                run('2026-10-02T08:00:00Z', '99', '0', 'FAILED'),
                run('2026-10-03T08:00:00Z', '2300', '4'),
            ])?.text,
        ).toBe('transfers: re-imported 3 Oct 2026, 2,300 rows loaded, 4 rows set aside');
        expect(lastImportLine('transfers', [run('2026-10-01T08:00:00Z', '1', '0', 'FAILED')])).toBeNull();
    });

    it('a re-import with a recorded lastImport says replaced or appended and the net change', () => {
        const run = (end: string, out: string, rejected = '0') => ({
            status: 'SUCCESS',
            consignment_id: `c-${end}`,
            end_time: end,
            total_output_rows: out,
            rejected_rows: rejected,
        });
        // the ledger writes the server's local time with no zone; the match is by batch id, never by clock
        const first = run('2026-10-01 08:00:00', '2104');
        const at = Date.parse('2026-10-03T02:30:01Z');
        const batch = 'c-2026-10-03 08:00:00';
        const replaced = { at, replaced: true, rowsBefore: 2104, rowsAfter: 2000, batch };
        expect(lastImportLine('transfers', [first, run('2026-10-03 08:00:00', '2000', '4')], replaced)).toEqual({
            text: 'transfers: replaced 3 Oct 2026, 2,104 → 2,000 rows (−104), 4 rows set aside',
            at,
        });
        const appended = { at, replaced: false, rowsBefore: 2104, rowsAfter: 2300, batch };
        expect(lastImportLine('transfers', [first, run('2026-10-03 08:00:00', '196')], appended)?.text).toBe(
            'transfers: appended 3 Oct 2026, +196 rows (now 2,300)',
        );
        // a later Run now records no lastImport: the ledger wording wins, no stale net change
        expect(
            lastImportLine(
                'transfers',
                [first, run('2026-10-03 08:00:00', '196'), run('2026-10-05 08:00:00', '2300')],
                appended,
            )?.text,
        ).toBe('transfers: re-imported 5 Oct 2026, 2,300 rows loaded');
        // the first import keeps its own wording
        expect(lastImportLine('transfers', [first], { ...appended, rowsBefore: 0, rowsAfter: 2104 })?.text).toBe(
            'transfers: imported 1 Oct 2026, +2,104 rows',
        );
    });

    it('importedDatasets reads a well-formed lastImport and ignores a malformed one', () => {
        const ds = (name: string, content: Record<string, unknown>): ComponentDef => ({ type: 'dataset', name, ref: '', content });
        const [good, bad] = importedDatasets([
            ds('a', {
                origin: 'import',
                importPipeline: 'a',
                lastImport: { at: '2026-10-03T08:00:00Z', replaced: true, rowsBefore: 5, rowsAfter: 7, batch: 'c1' },
            }),
            ds('b', { origin: 'import', importPipeline: 'b', lastImport: { at: 'never', replaced: true } }),
        ]);
        expect(good.last).toEqual({
            at: Date.parse('2026-10-03T08:00:00Z'),
            replaced: true,
            rowsBefore: 5,
            rowsAfter: 7,
            batch: 'c1',
        });
        expect(bad.last).toBeUndefined();
    });
});
