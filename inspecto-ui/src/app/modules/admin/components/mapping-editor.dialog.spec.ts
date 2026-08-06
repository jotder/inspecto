import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import {
    AuthoredPipeline, ComponentDef, ComponentsService, Finding, PipelineDryRunResult, PipelinesService,
} from 'app/inspecto/api';
import { CsvImport } from 'app/inspecto/components/editable-grid.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { MappingEditorDialog, diffRules, previewGraph } from './mapping-editor.dialog';

const DEF: ComponentDef = {
    type: 'mapping',
    name: 'cdr_map',
    ref: 'mapping/cdr_map',
    content: {
        description: 'kept verbatim',
        rules: [
            { targetColumn: 'AMOUNT', sourceExpression: 'amt', transformType: 'DIRECT' },
            { targetColumn: 'DAY', sourceExpression: "strftime(ts, '%Y-%m-%d')", transformType: 'EXPR' },
        ],
    },
};

/**
 * Stand-in for the server's projection: DIRECT rules copy their source column, EXPR yields null (no SQL
 * engine here — the same honest limit the offline mock handler documents). Keeping it rule-driven is the
 * point: a canned row set would make the before/after preview identical no matter what changed.
 */
function projectDryRun(candidate: AuthoredPipeline | undefined,
                       sample: Record<string, unknown>[]): PipelineDryRunResult {
    const map = candidate?.nodes.find((n) => n.id === 'map');
    const rules = (map?.config?.['rules'] ?? []) as Record<string, string>[];
    const rows = sample.map((row) => Object.fromEntries(rules.map((r) => [
        r.targetColumn,
        (r.transformType ?? '').toUpperCase() === 'EXPR' ? null : row[r.sourceExpression] ?? null,
    ])));
    return {
        seedNode: 'seed',
        nodes: [{ node: 'map', type: 'transform.map', relations: [{ rel: 'data', rowCount: rows.length, rows }] }],
        sinks: [],
    };
}

async function create(def?: ComponentDef, findings: Finding[] = []) {
    const ref = { close: vi.fn(), disableClose: false };
    const saved = { ...DEF };
    const api = {
        create: vi.fn(() => of(saved)),
        update: vi.fn(() => of(saved)),
        validateMapping: vi.fn(() => of({ type: 'mapping', findings, clean: findings.length === 0 })),
    };
    const pipelines = {
        dryRunAuthored: vi.fn((_id: string, sample: Record<string, unknown>[], candidate?: AuthoredPipeline) =>
            of(projectDryRun(candidate, sample))),
    };
    const toast = { success: vi.fn(), error: vi.fn(), warning: vi.fn() };
    TestBed.configureTestingModule({
        imports: [MappingEditorDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { def } },
            { provide: MatDialogRef, useValue: ref },
            { provide: ComponentsService, useValue: api },
            { provide: PipelinesService, useValue: pipelines },
            { provide: ToastrService, useValue: toast },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: () => Promise.resolve(true) } },
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
        ],
    });
    // the data-table hosts a @defer block (its CodeMirror editor) — compile before creating
    await TestBed.compileComponents();
    const fixture = TestBed.createComponent(MappingEditorDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref, api, pipelines, toast };
}

/** A CSV import event as `<inspecto-editable-grid>` emits it. */
function importOf(rows: Record<string, string>[], over: Partial<CsvImport> = {}): CsvImport {
    return {
        rows,
        unknownHeaders: [],
        missingColumns: [],
        applied: true,
        fileName: 'rules.csv',
        ...over,
    };
}

/** A change event carrying an uploaded file, as the sample-data input emits it. */
function fileEvent(name: string, text: string): Event {
    return { target: { files: [new File([text], name, { type: 'text/csv' })], value: '' } } as unknown as Event;
}

describe('MappingEditorDialog', () => {
    it('loads the rules as grid rows and saves them back via component update, keys verbatim', async () => {
        const { fixture, c, ref, api } = await create(DEF);
        expect(c.rows()).toEqual([
            { targetColumn: 'AMOUNT', sourceExpression: 'amt', transformType: 'DIRECT' },
            { targetColumn: 'DAY', sourceExpression: "strftime(ts, '%Y-%m-%d')", transformType: 'EXPR' },
        ]);

        c.save();
        expect(api.update).toHaveBeenCalledWith('mapping', 'cdr_map', expect.objectContaining({
            // content keys beyond rules survive the save (verbatim-sections rule)
            description: 'kept verbatim',
            rules: DEF.content['rules'],
        }));
        expect(ref.close).toHaveBeenCalledWith({ saved: expect.objectContaining({ name: 'cdr_map' }) });
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('create mode requires an id, drops all-blank rows, and refuses an empty rule list', async () => {
        const { c, api } = await create();
        c.save();
        expect(api.create).not.toHaveBeenCalled(); // no id yet

        c.id.setValue('new_map');
        c.save();
        expect(api.create).not.toHaveBeenCalled(); // no rules yet — refused, not saved empty

        c.onRows([
            { targetColumn: ' A ', sourceExpression: 'a', transformType: 'DIRECT' },
            { targetColumn: '', sourceExpression: '  ', transformType: '' }, // all-blank — dropped
        ]);
        c.save();
        expect(api.create).toHaveBeenCalledWith('mapping', expect.objectContaining({
            id: 'new_map',
            rules: [{ targetColumn: 'A', sourceExpression: 'a', transformType: 'DIRECT' }],
        }));
    });

    describe('import loop (S6b)', () => {
        it('validates on save and refuses to write when the server reports an ERROR', async () => {
            const { c, api, toast } = await create(DEF, [
                { severity: 'ERROR', fieldPath: 'rules[1].transformType', message: "Unknown transform type 'EXPER'." },
            ]);
            c.save();
            expect(api.validateMapping).toHaveBeenCalledWith(DEF.content['rules']);
            expect(api.update).not.toHaveBeenCalled();
            expect(toast.error).toHaveBeenCalled();
            // and the finding lands on the exact cell the grid marks
            expect(c.cellFindings().get('1|transformType')).toEqual({
                severity: 'error',
                message: "Unknown transform type 'EXPER'.",
            });
        });

        it('a whole-set finding is listed but marks no cell', async () => {
            const { c } = await create(DEF, [{ severity: 'ERROR', fieldPath: '', message: 'A mapping needs at least one rule.' }]);
            c.save();
            expect(c.findings().length).toBe(1);
            expect(c.cellFindings().size).toBe(0);
        });

        it('a validate transport error warns but still lets the save through (the server re-checks)', async () => {
            const { c, api, toast } = await create(DEF);
            api.validateMapping.mockReturnValue(throwError(() => ({ status: 500 })));
            c.save();
            expect(toast.warning).toHaveBeenCalled();
            expect(api.update).toHaveBeenCalled();
        });

        it('an import diffs the new rules against the old and validates them', async () => {
            const { c, api } = await create(DEF);
            c.onRows([
                { targetColumn: 'AMOUNT', sourceExpression: 'amount_cents', transformType: 'DIRECT' }, // changed
                { targetColumn: 'MSISDN', sourceExpression: 'msisdn', transformType: 'DIRECT' },       // added
            ]);
            c.onImported(importOf(c.rows()));
            expect(c.diff()).toEqual([
                { change: 'Changed', targetColumn: 'AMOUNT', before: 'amt', after: 'amount_cents' },
                { change: 'Added', targetColumn: 'MSISDN', before: '', after: 'msisdn' },
                // DAY was in the file's predecessor but not the import
                { change: 'Removed', targetColumn: 'DAY', before: "EXPR(strftime(ts, '%Y-%m-%d'))", after: '' },
            ]);
            expect(api.validateMapping).toHaveBeenCalled();
            expect(c.importNote()?.variant).toBe('success');
        });

        it('a refused import (no matching header) says so and leaves the rules alone', async () => {
            const { c, api } = await create(DEF);
            const before = c.rows();
            c.onImported(importOf([], { applied: false, unknownHeaders: ['col_a'], fileName: 'wrong.csv' }));
            expect(c.rows()).toEqual(before);
            expect(c.diff()).toEqual([]);
            expect(c.importNote()?.variant).toBe('error');
            expect(c.importNote()?.title).toContain('wrong.csv');
            expect(api.validateMapping).not.toHaveBeenCalled(); // nothing changed — nothing to check
        });

        it('an import with unmatched header columns warns rather than passing silently', async () => {
            const { c } = await create(DEF);
            c.onImported(importOf(c.rows(), { unknownHeaders: ['notes'], missingColumns: ['transformType'] }));
            expect(c.importNote()?.variant).toBe('warning');
            expect(c.importNote()?.message).toContain('notes');
            expect(c.importNote()?.message).toContain('transformType');
        });

        it('editing a row clears stale findings so no cell stays marked at the wrong index', async () => {
            const { c } = await create(DEF, [
                { severity: 'ERROR', fieldPath: 'rules[1].targetColumn', message: 'nope' },
            ]);
            c.save();
            expect(c.cellFindings().size).toBe(1);
            c.onRows([{ targetColumn: 'A', sourceExpression: 'a', transformType: '' }]);
            expect(c.cellFindings().size).toBe(0);
        });
    });

    describe('output-row preview (§2.5)', () => {
        /** Import a rename of AMOUNT's source, then upload a sample — the state both previews run over. */
        async function withSample(sample = 'amt,ts\n150,2026-06-24\n') {
            const made = await create(DEF);
            made.c.onRows([{ targetColumn: 'AMOUNT', sourceExpression: 'amount_cents', transformType: 'DIRECT' }]);
            made.c.onImported(importOf(made.c.rows()));
            await made.c.onSample(fileEvent('sample.csv', sample));
            return made;
        }

        it('previews old and new output rows over the same sample', async () => {
            const { c } = await withSample('amt,amount_cents\n150,15000\n');
            // the pre-import rules read `amt`; the imported ones read `amount_cents` — same input row
            expect(c.previewBefore()).toEqual([{ AMOUNT: '150', DAY: null }]);
            expect(c.previewAfter()).toEqual([{ AMOUNT: '15000' }]);
            expect(c.sampleName()).toBe('sample.csv');
        });

        it('previews through a throwaway candidate graph, never a stored pipeline', async () => {
            const { c, pipelines } = await withSample();
            expect(pipelines.dryRunAuthored).toHaveBeenCalledTimes(2);
            const [, sample, candidate] = pipelines.dryRunAuthored.mock.calls[1];
            expect(sample).toEqual([{ amt: '150', ts: '2026-06-24' }]);
            // the draft rules travel INLINE on the map step — that is what makes an unsaved draft previewable
            expect(candidate?.nodes.find((n) => n.id === 'map')?.config?.['rules'])
                .toEqual([{ targetColumn: 'AMOUNT', sourceExpression: 'amount_cents', transformType: 'DIRECT' }]);
            expect(candidate?.name).toBe('cdr_map');
        });

        it('a sample with no data rows is refused and explains itself', async () => {
            const { c } = await withSample('amt,ts\n');
            expect(c.sampleRows()).toEqual([]);
            expect(c.previewBefore()).toBeNull();
            expect(c.previewError()).toContain('header row');
        });

        it('a dry-run failure degrades to the rule diff rather than blocking the import', async () => {
            const made = await create(DEF);
            made.pipelines.dryRunAuthored.mockReturnValue(throwError(() => ({ status: 400 })));
            made.c.onRows([{ targetColumn: 'AMOUNT', sourceExpression: 'amount_cents', transformType: 'DIRECT' }]);
            made.c.onImported(importOf(made.c.rows()));
            await made.c.onSample(fileEvent('sample.csv', 'amt\n150\n'));
            expect(made.c.previewBefore()).toBeNull();
            expect(made.c.previewError()).toBeTruthy();
            expect(made.c.diff().length).toBeGreaterThan(0);   // the rule-level diff still stands
        });

        it('renders both tables and stays accessible', async () => {
            const { fixture } = await withSample('amt,amount_cents\n150,15000\n');
            fixture.detectChanges();
            const headings = Array.from(fixture.nativeElement.querySelectorAll('h4')).map((h) => (h as HTMLElement).textContent);
            expect(headings.some((t) => t?.includes('Before'))).toBe(true);
            expect(headings.some((t) => t?.includes('After'))).toBe(true);
            await expectNoA11yViolations(fixture.nativeElement);
        });

        it('no sample means no dry-run at all — the rule diff is the whole review', async () => {
            const { c, pipelines } = await create(DEF);
            c.onRows([{ targetColumn: 'AMOUNT', sourceExpression: 'x', transformType: 'DIRECT' }]);
            c.onImported(importOf(c.rows()));
            expect(pipelines.dryRunAuthored).not.toHaveBeenCalled();
            expect(c.previewBefore()).toBeNull();
        });
    });
});

describe('previewGraph', () => {
    it('is a seed → map → sink chain carrying the rules inline', () => {
        const g = previewGraph('cdr_map', [{ targetColumn: 'A', sourceExpression: 'a', transformType: 'DIRECT' }]);
        expect(g.active).toBe(false);
        expect(g.nodes.map((n) => n.type)).toEqual(['acquisition', 'transform.map', 'sink.persistent']);
        expect(g.edges).toEqual([
            { from: 'seed', rel: 'data', to: 'map' },
            { from: 'map', rel: 'data', to: 'sink' },
        ]);
        // no `use:` anywhere — a reference would resolve the STORED mapping and miss the draft entirely
        expect(g.nodes.every((n) => !n.use)).toBe(true);
    });
});

describe('diffRules', () => {
    it('is keyed by target column and ignores rows without one', () => {
        expect(diffRules(
            [{ targetColumn: '', sourceExpression: 'x', transformType: '' }],
            [{ targetColumn: '', sourceExpression: 'y', transformType: '' }],
        )).toEqual([]);
    });

    it('treats a blank transform type as DIRECT, so it is not a change', () => {
        expect(diffRules(
            [{ targetColumn: 'A', sourceExpression: 'a', transformType: 'DIRECT' }],
            [{ targetColumn: 'A', sourceExpression: 'a', transformType: '' }],
        )).toEqual([]);
    });
});
