import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { LensService } from 'app/inspecto/api';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { Query } from './query-types';
import { QueriesService } from './queries.service';
import { QueriesComponent } from './queries.component';
import { DatasetRowsService } from 'app/inspecto/viz/dataset-rows.service';

const DS: Dataset = {
    id: 'cdr_sample',
    name: 'cdr_sample',
    kind: 'virtual',
    sourceName: 'cdr',
    columns: [{ name: 'cost_usd', type: 'number', role: 'measure' }],
    measures: [],
    calculated: [],
};
const Q: Query = {
    id: 'recent',
    name: 'recent',
    type: 'sql',
    datasetId: 'cdr_sample',
    sourceName: 'cdr',
    text: 'SELECT * FROM cdr',
    parameters: [],
};

/** Records what the rows seam was asked to run, and answers with one page. */
function rowsSeam() {
    const rows = vi.fn(() => Promise.resolve({ rows: [{ cost_usd: 4 }], columns: [], truncated: false }));
    const sql = vi.fn(() => Promise.resolve({ rows: [{ cost_usd: 4 }], columns: [], truncated: true }));
    return { rows, sql };
}

/** One server-run result in the shape `POST /queries/{id}/run` returns. */
const RUN_RESULT = {
    resultSet: { columns: [{ name: 'cost_usd', type: 'number' }], rowCount: 2 },
    rows: [{ cost_usd: 4 }, { cost_usd: 9 }],
    statistics: { rowCount: 2, elapsedMs: 7, truncated: false },
};

function create(
    queries: Query[] = [Q],
    dialogResult: unknown = true,
    seam = rowsSeam(),
    opts: { run?: ReturnType<typeof vi.fn>; canAuthor?: boolean } = {},
) {
    const save = vi.fn((q: Query) => of(q));
    const remove = vi.fn(() => of(null));
    const list = vi.fn(() => of(queries));
    const run = opts.run ?? vi.fn(() => of(RUN_RESULT));
    const dialogOpen = vi.fn(() => ({ afterClosed: () => of(dialogResult) }));
    TestBed.configureTestingModule({
        imports: [QueriesComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: QueriesService, useValue: { list, get: () => of(Q), save, remove, run } },
            { provide: DatasetsService, useValue: { list: () => of([DS]) } },
            { provide: DatasetRowsService, useValue: seam },
            {
                provide: ToastrService,
                useValue: { warning: () => undefined, success: () => undefined, error: () => undefined },
            },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: () => Promise.resolve(true) } },
            // The server-run result mounts a real <inspecto-data-table>, whose theme service walks up to
            // GAMMA_APP_CONFIG — absent in a TestBed. Stub the theme, as the data-table's own spec does.
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
            // Only stubbed when a test cares: the component reads `canAuthorWorkbench` as a signal, so a
            // plain callable is enough and avoids pulling SessionService's authMode/capabilities in.
            ...(opts.canAuthor === undefined
                ? []
                : [{ provide: LensService, useValue: { canAuthorWorkbench: () => opts.canAuthor } }]),
        ],
    });
    // The embedded `<inspecto-query-panel>` mounts `<inspecto-data-table>`, which injects the real
    // MatDialog — a plain `{provide: MatDialog, ...}` above is silently ignored, so it must be overridden.
    TestBed.overrideProvider(MatDialog, { useValue: { open: dialogOpen } });
    return { fixture: TestBed.createComponent(QueriesComponent), save, remove, list, dialogOpen, seam, run };
}

describe('QueriesComponent (R3)', () => {
    it('loads queries and datasets on init', () => {
        const { fixture } = create();
        fixture.detectChanges();
        expect(fixture.componentInstance.queries().length).toBe(1);
        expect(fixture.componentInstance.datasets()[0].id).toBe('cdr_sample');
    });

    it('opens a blank editor on New, and closes on Cancel', () => {
        const { fixture } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.newQuery();
        expect(c.editing()).toBe(true);
        expect(c.editingExisting()).toBe(false);
        c.cancel();
        expect(c.editing()).toBe(false);
    });

    it('detects user-declared $params from the SQL (built-ins excluded)', () => {
        const { fixture } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.newQuery();
        c.form.controls.text.setValue('WHERE t >= $day(-7) AND region = $region');
        expect(c.userParamNames()).toEqual(['region']);
        expect(c.builtinTokens()).toEqual(['$day(-7)']);
    });

    it('editing an existing query disables the name and seeds its param defaults', () => {
        const { fixture } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.editQuery({ ...Q, parameters: [{ name: 'region', type: 'string', default: 'APAC' }] });
        expect(c.editingExisting()).toBe(true);
        expect(c.form.controls.name.disabled).toBe(true);
        expect(c.paramDefaults()['region']).toBe('APAC');
    });

    it('saves a query built from the form', () => {
        const { fixture, save } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.newQuery();
        c.form.patchValue({ name: 'new_q', datasetId: 'cdr_sample', text: 'SELECT 1 FROM cdr' });
        c.save();
        expect(save).toHaveBeenCalled();
        expect(save.mock.calls[0][0]).toMatchObject({ id: 'new_q', datasetId: 'cdr_sample', type: 'sql' });
    });

    it('deletes after confirmation', async () => {
        const { fixture, remove } = create();
        fixture.detectChanges();
        await fixture.componentInstance.remove(Q);
        expect(remove).toHaveBeenCalledWith('recent');
    });

    it('a history restore reloads the list and closes a stale open editor for that query (MET-5)', () => {
        const { fixture, list, dialogOpen } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.editQuery(Q); // the restored query is open in the edit form
        c.history(Q); // dialog closes with `true` (restored)
        expect(dialogOpen).toHaveBeenCalled();
        expect(c.editing()).toBe(false); // stale form closed — saving it would overwrite the restore
        expect(list).toHaveBeenCalledTimes(2); // init + post-restore reload
    });

    it('a dismissed history dialog changes nothing', () => {
        // `null`, not `undefined` — an explicit undefined would trigger create()'s `= true` default.
        const { fixture, list } = create([Q], null); // dialog dismissed (no restore)
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.editQuery(Q);
        c.history(Q);
        expect(c.editing()).toBe(true); // editor untouched
        expect(list).toHaveBeenCalledTimes(1); // no reload
    });

    it('renders the library with no a11y violations', async () => {
        const { fixture } = create([]);
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('defaults a new query to type sql', () => {
        const { fixture } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.newQuery();
        expect(c.form.controls.type.value).toBe('sql');
    });

    it('run() in structured mode runs the model against the store and populates the preview', async () => {
        const { fixture, seam } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.newQuery();
        c.form.patchValue({ datasetId: 'cdr_sample', type: 'structured' });
        c.onStructuredChange({
            model: { projection: '*', where: { kind: 'group', op: 'AND', items: [] }, sqlOverride: null },
            sql: 'SELECT * FROM cdr',
        });
        await c.run();
        expect(c.preview()?.resolvedSql).toBe('SELECT * FROM cdr');
        expect(c.preview()?.error).toBeUndefined();
        // The model went to the store as the dataset's own query — not evaluated over a page held here.
        expect(seam.rows).toHaveBeenCalledWith(expect.objectContaining({ sourceName: 'cdr' }));
        expect(c.preview()?.resultSet?.rowCount).toBe(1);
    });

    it('run() in SQL mode sends the resolved SQL to the store and reports a truncated page', async () => {
        const { fixture, seam } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.newQuery();
        c.form.patchValue({ datasetId: 'cdr_sample', type: 'sql', text: 'SELECT * FROM cdr' });
        await c.run();
        expect(seam.sql).toHaveBeenCalledWith('cdr', 'SELECT * FROM cdr');
        expect(c.preview()?.truncated).toBe(true);
        expect(c.preview()?.error).toBeUndefined();
    });

    it('save() on a structured query persists the model and omits text/parameters', () => {
        const { fixture, save } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        const model = {
            projection: ['cost_usd'],
            where: { kind: 'group' as const, op: 'AND' as const, items: [] },
            sqlOverride: null,
        };
        c.newQuery();
        c.form.patchValue({ name: 'structured_q', datasetId: 'cdr_sample', type: 'structured' });
        c.onStructuredChange({ model, sql: 'SELECT cost_usd FROM cdr' });
        c.save();
        expect(save).toHaveBeenCalled();
        expect(save.mock.calls[0][0]).toMatchObject({
            id: 'structured_q',
            type: 'structured',
            model,
            text: null,
            parameters: [],
        });
    });

    it('editing an existing structured query round-trips its model into the panel', () => {
        const { fixture } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        const model = {
            projection: '*' as const,
            where: { kind: 'group' as const, op: 'AND' as const, items: [] },
            sqlOverride: null,
        };
        c.editQuery({
            ...Q,
            id: 'structured_q',
            name: 'structured_q',
            type: 'structured',
            text: null,
            model,
            parameters: [],
        });
        expect(c.form.controls.type.value).toBe('structured');
        expect(c.structuredModel()).toEqual(model);
    });

    // ─── AGT-6a A2/A3: drafting SQL from the structured condition tree ───

    it('passes the dataset and the condition tree through as the tool args (A3)', () => {
        const { fixture } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.newQuery();
        c.form.controls.datasetId.setValue('cdr_sample');
        c.form.controls.name.setValue('costly');

        const args = c.aiQueryArgs();
        expect(args['dataset']).toBe('cdr_sample');
        expect(args['name']).toBe('costly');
        // The structured tree, never SQL text — the server renders the predicate.
        expect(args['when']).toEqual(c.structuredModel().where);
    });

    it('applying drafted SQL switches the editor to sql so the draft is not discarded on save', () => {
        const { fixture, save } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.newQuery();
        c.form.controls.datasetId.setValue('cdr_sample');
        c.form.controls.type.setValue('structured');

        c.applyQueryDraft({
            label: 'query',
            clean: true,
            findings: [],
            config: { type: 'sql', text: 'SELECT * FROM cdr WHERE cost_usd > 100', datasetId: 'cdr_sample' },
        });

        // Left on 'structured', buildQuery persists the MODEL and drops the text entirely.
        expect(c.form.controls.type.value).toBe('sql');
        expect(c.form.controls.text.value).toContain('cost_usd > 100');
        // Nothing saved — the operator still presses the existing Save (D2).
        expect(save).not.toHaveBeenCalled();
    });

    it('runs a SAVED query through the server route and renders its rows', async () => {
        const { fixture, run } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;

        c.runSaved(Q);
        fixture.detectChanges();

        // The id is what addresses the stored query — the draft preview seam must not be involved.
        expect(run).toHaveBeenCalledWith('recent');
        expect(c.savedRun()?.rowCount).toBe(2);
        expect(c.savedRun()?.elapsedMs).toBe(7);
        expect(c.runningSaved()).toBeNull();
        expect(fixture.nativeElement.querySelector('inspecto-data-table')).toBeTruthy();
        // The result panel is a whole new region on the page — cover it, not just the empty list.
        await expectNoA11yViolations(fixture.nativeElement);
    });

    /**
     * ⚠ A 422 here is a REFUSAL with a reason — a non-sql query, a failed SQL safety check, an
     * unresolvable parameter — and the operator has to read it. A toast that scrolls away, or a silent
     * empty grid, both lose the one thing the server took the trouble to say.
     */
    it('renders a refusal in place instead of an empty result', () => {
        // ⚠ A real HttpErrorResponse — `apiErrorMessage` returns the fallback for a plain object, so a
        // hand-rolled stub silently asserts the wrong string (it did, first time round).
        // ⚠ And the v1 envelope this route ACTUALLY sends, captured from the live backend on
        // 2026-09-14: `{error: {errorCode, message, recoverable, correlationId}}`. ⛔ Not the legacy
        // `{error: '<message>'}` that `ControlApi`'s raw error boundary constructs — `apiErrorMessage`
        // happens to read both, so pinning the wrong one passes while describing a response nobody
        // sends.
        const err = new HttpErrorResponse({
            status: 422,
            error: {
                error: {
                    errorCode: 'CONFIG_VALIDATION_FAILED',
                    message: "only type:sql queries run server-side today (got 'structured')",
                },
            },
        });
        const { fixture } = create([Q], true, rowsSeam(), { run: vi.fn(() => throwError(() => err)) });
        fixture.detectChanges();
        const c = fixture.componentInstance;

        c.runSaved(Q);
        fixture.detectChanges();

        expect(c.savedRun()?.error).toContain('only type:sql');
        expect(c.savedRun()?.rows).toEqual([]);
        expect(c.runningSaved()).toBeNull();
        // The reason is on screen, not just in the signal.
        expect(fixture.nativeElement.querySelector('inspecto-alert')?.textContent).toContain('only type:sql');
        expect(fixture.nativeElement.querySelector('inspecto-data-table')).toBeFalsy();
    });

    /**
     * 🔴 Running a stored, read-only query is an OPERATIONAL action, so it must survive the authoring
     * gate — the skill's rule is to gate config authoring only. ⛔ This is the assertion that fails if
     * someone "tidies" the Run button inside the neighbouring `@if (canAuthor())` block, which is where
     * every other action on the row lives.
     */
    it('offers Run without authoring capability, while Edit and Delete disappear', () => {
        const { fixture } = create([Q], true, rowsSeam(), { canAuthor: false });
        fixture.detectChanges();

        const labels = Array.from(fixture.nativeElement.querySelectorAll('button[aria-label]')).map((b) =>
            (b as HTMLElement).getAttribute('aria-label'),
        );
        expect(labels).toContain('Run query recent on the server');
        expect(labels).not.toContain('Edit query');
        expect(labels).not.toContain('Delete query');
    });

    it('ignores a draft with no SQL text', () => {
        const { fixture } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.newQuery();
        c.form.controls.text.setValue('SELECT 1');

        c.applyQueryDraft({ label: 'query', clean: true, findings: [], config: { type: 'sql', text: '  ' } });
        expect(c.form.controls.text.value).toBe('SELECT 1');
    });
});
