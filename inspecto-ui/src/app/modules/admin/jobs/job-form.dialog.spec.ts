import { provideHttpClient, withXhr } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { JobExpressionDecl, JobsService, JobTypeDescriptor } from 'app/inspecto/api';
import { ToastrService } from 'ngx-toastr';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { JobFormData, JobFormDialog } from './job-form.dialog';

const noParams = () =>
    of({
        id: 'x',
        title: '',
        description: '',
        parameters: [],
        emits: [],
        artifacts: [],
        requires: [],
    });

function create(
    data: JobFormData,
    save = vi.fn(() => of({ name: 'x' })),
    describeType = vi.fn(noParams),
    types: () => Observable<JobTypeDescriptor[]> = () => of([]),
    expressions: () => Observable<JobExpressionDecl[]> = () => of([]),
) {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [JobFormDialog],
        providers: [
            provideNoopAnimations(),
            provideHttpClient(withXhr()), // the autocomplete option loaders inject root HTTP services
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: MatDialogRef, useValue: ref },
            {
                provide: JobsService,
                useValue: {
                    create: save,
                    update: save,
                    describeType,
                    types,
                    expressions,
                },
            },
            { provide: ToastrService, useValue: { error: vi.fn() } },
        ],
    });
    const fixture = TestBed.createComponent(JobFormDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref, save };
}

describe('JobFormDialog', () => {
    it('seeds the form from an existing job (edit) and stays on the config step (id is immutable)', () => {
        const { c } = create({
            job: {
                name: 'j1',
                type: 'report',
                cron: '0 0 6 * * *',
                onPipeline: null,
                enabled: true,
                params: { report: 'x' },
            },
        });
        expect(c.isEdit).toBe(true);
        expect(c.step()).toBe('config');
        expect(c.schemaForm.form.get('scheduleMode')?.value).toBe('cron');
        expect(c.paramsArray.length).toBe(1);
    });

    it('blocks save on an invalid cron, advances to the save step once valid, then creates with the entered id', () => {
        const { c, ref, save } = create({});
        c.schemaForm.form.patchValue({ scheduleMode: 'cron', cron: 'nonsense' });
        c.save();
        expect(c.step()).toBe('config');
        expect(save).not.toHaveBeenCalled();

        c.schemaForm.form.patchValue({ cron: '0 0 6 * * *' });
        c.save(); // config valid ⇒ advances to the save step, doesn't call the API yet
        expect(c.step()).toBe('save');
        expect(save).not.toHaveBeenCalled();

        c.saveForm.patchValue({ name: 'new_job' });
        c.save();
        expect(save).toHaveBeenCalledWith(
            expect.objectContaining({
                name: 'new_job',
                cron: '0 0 6 * * *',
                onPipeline: null,
            }),
        );
        expect(ref.close).toHaveBeenCalled();
    });

    it('blocks save on a duplicate id (case-insensitive) at the save step, then creates once unique', () => {
        const { c, save } = create({ existingNames: ['cdr_ingest'] });
        c.schemaForm.form.patchValue({ scheduleMode: 'manual' });
        c.save();
        expect(c.step()).toBe('save');

        c.saveForm.patchValue({ name: 'CDR_Ingest' });
        c.save();
        expect(save).not.toHaveBeenCalled();
        expect(c.saveForm.get('name')?.hasError('duplicate')).toBe(true);

        c.saveForm.patchValue({ name: 'cdr_ingest_2' });
        c.save();
        expect(save).toHaveBeenCalledWith(expect.objectContaining({ name: 'cdr_ingest_2' }));
    });

    it('lets Back return to the config step without losing the entered id', () => {
        const { c } = create({});
        c.schemaForm.form.patchValue({ scheduleMode: 'manual' });
        c.save();
        expect(c.step()).toBe('save');
        c.saveForm.patchValue({ name: 'kept_id' });
        c.backToConfig();
        expect(c.step()).toBe('config');
        expect(c.saveForm.get('name')?.value).toBe('kept_id');
    });

    it("renders the selected type's declared parameters and includes them on save", async () => {
        const describeType = vi.fn(() =>
            of({
                id: 'sql.template',
                title: 'Templated SQL',
                description: '',
                parameters: [
                    {
                        name: 'sink_dataset',
                        type: 'STRING',
                        required: true,
                        deduce: '',
                        default: '',
                        description: 'Output Dataset',
                    },
                ],
                emits: [],
                artifacts: [],
                requires: [],
            }),
        );
        const { c, fixture, save } = create({}, undefined, describeType);
        await Promise.resolve(); // flush the queued loadParams microtask
        fixture.detectChanges();

        expect(c.paramSpecs().map((s) => s.key)).toContain('sink_dataset');
        c.schemaForm.form.patchValue({ scheduleMode: 'manual' });
        c.paramForm!.form.patchValue({ sink_dataset: 'txn_rollup' });
        c.save(); // → save step
        c.saveForm.patchValue({ name: 'rollup' });
        c.save();
        expect(save).toHaveBeenCalledWith(
            expect.objectContaining({
                params: expect.objectContaining({ sink_dataset: 'txn_rollup' }),
            }),
        );
    });

    it("shows the type's declared Platform Service grants as chips (requires:, S1-2)", async () => {
        const describeType = vi.fn(() =>
            of({
                id: 'acme.notify',
                title: 'Acme Notify',
                description: '',
                parameters: [],
                emits: [],
                artifacts: [],
                requires: ['notifications', 'schema'],
            }),
        );
        const { c, fixture } = create({}, undefined, describeType);
        await Promise.resolve(); // flush the queued loadParams microtask
        fixture.detectChanges();

        expect(c.typeRequires()).toEqual(['notifications', 'schema']);
        const chips = Array.from(fixture.nativeElement.querySelectorAll('inspecto-chip')).map((el) =>
            (el as HTMLElement).textContent?.trim(),
        );
        expect(chips).toContain('notifications');
        expect(chips).toContain('schema');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('authors a signal trigger: the id, the optional guard, and a suggested name from both', () => {
        const { c, save } = create({});
        c.schemaForm.form.patchValue({
            type: 'maintenance',
            scheduleMode: 'signal',
            onSignal: 'dataset.write',
            when: "$signal.dataset == 'premium_cdr_view'",
        });
        c.save(); // config valid ⇒ save step, with the id pre-filled from the trigger
        expect(c.step()).toBe('save');
        expect(c.saveForm.get('name')?.value).toBe('maintenance_dataset.write');

        c.save();
        expect(save).toHaveBeenCalledWith(
            expect.objectContaining({
                onSignal: 'dataset.write',
                when: "$signal.dataset == 'premium_cdr_view'",
                cron: null,
                onPipeline: null,
            }),
        );
    });

    it('sends the guard only with the trigger it narrows, and clears the trigger when the mode changes', () => {
        const { c, save } = create({});
        c.schemaForm.form.patchValue({
            scheduleMode: 'signal',
            onSignal: 'dataset.write',
            when: '$signal.rows > 0',
        });
        c.schemaForm.form.patchValue({ scheduleMode: 'manual' }); // author changes their mind
        c.save();
        c.saveForm.patchValue({ name: 'manual_job' });
        c.save();
        expect(save).toHaveBeenCalledWith(expect.objectContaining({ onSignal: null, when: null }));
    });

    it('seeds the signal trigger back from a saved job (edit)', () => {
        const { c } = create({
            job: {
                name: 'j2',
                type: 'maintenance',
                cron: null,
                onPipeline: null,
                onSignal: 'dataset.*',
                when: '$signal.rows > 0',
                enabled: true,
            },
        });
        expect(c.schemaForm.form.get('scheduleMode')?.value).toBe('signal');
        expect(c.schemaForm.form.get('onSignal')?.value).toBe('dataset.*');
        expect(c.schemaForm.form.get('when')?.value).toBe('$signal.rows > 0');
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create({});
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('renders the signal trigger fields with no a11y violations', async () => {
        const { c, fixture } = create({});
        c.schemaForm.form.patchValue({
            scheduleMode: 'signal',
            onSignal: 'dataset.write',
        });
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    /** Step 12: the picker and the "what this does" panel come from the server's registry. */
    describe('type catalog', () => {
        const CATALOG = [
            {
                id: 'report',
                title: 'Report',
                description: 'Computes a report.',
                parameters: [],
                emits: [],
                artifacts: [],
                requires: [],
            },
            {
                id: 'acme.thing',
                title: 'Acme Thing',
                description: 'From a pack.',
                parameters: [],
                emits: ['acme.done'],
                artifacts: [{ name: 'out', kind: 'dataset' }],
                requires: [],
                implClass: 'com.acme.ThingType',
                source: 'pack:acme',
                version: '2.1.0',
            },
        ];
        const typeOptions = (c: JobFormDialog) =>
            (c.attributes().find((s) => s.key === 'type')?.options ?? []).map((o) => o.value);

        it('builds the type options from GET /jobs/types, not the hardcoded list', () => {
            const { c } = create({}, undefined, undefined, () => of(CATALOG));
            // A packaged type the UI has never heard of must be selectable — that is the whole claim.
            expect(typeOptions(c)).toEqual(['report', 'acme.thing']);
        });

        it('keeps the declared options when the catalog is empty, so the picker is never blank', () => {
            const { c } = create({}, undefined, undefined, () => of([]));
            expect(typeOptions(c).length).toBeGreaterThan(0);
        });

        it("shows the selected type's provenance so a packaged type is identifiable", async () => {
            const describe2 = vi.fn(() => of(CATALOG[1]));
            const { c, fixture } = create({}, undefined, describe2, () => of(CATALOG));
            c.schemaForm.form.patchValue({ type: 'acme.thing' });
            await Promise.resolve();
            fixture.detectChanges();

            const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
            expect(text).toContain('pack:acme');
            expect(text).toContain('2.1.0');
            expect(text).toContain('com.acme.ThingType');
            // Zero-arg: the summaries are computeds over `selectedType()`, not helpers taking a descriptor.
            expect(c.artifactSummary()).toBe('out (dataset)');
            expect(c.emitsSummary()).toBe('acme.done');
        });
    });

    /** Step 14: the free key/value editor is the descriptor-missing escape hatch, not a normal path. */
    describe('free key/value fallback', () => {
        it('is hidden when the type publishes a contract and no untyped params exist', async () => {
            const { c, fixture } = create({});
            await Promise.resolve();
            fixture.detectChanges();

            expect(c.descriptorMissing()).toBe(false);
            expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Additional parameters');
        });

        it('appears WITH a warning when the type publishes no descriptor', async () => {
            const failing = vi.fn(() => throwError(() => new Error('404')));
            const { c, fixture } = create({}, undefined, failing);
            await Promise.resolve();
            fixture.detectChanges();

            expect(c.descriptorMissing()).toBe(true);
            const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
            expect(text).toContain('publishes no parameter contract');
            expect(text).toContain('Additional parameters');
        });

        it('stays visible for a job that already carries untyped params, so editing cannot strand them', async () => {
            const { c, fixture } = create({
                job: {
                    name: 'j1',
                    type: 'report',
                    cron: '0 0 6 * * *',
                    onPipeline: null,
                    enabled: true,
                    params: { legacy_key: 'v' },
                },
            });
            await Promise.resolve();
            fixture.detectChanges();

            expect(c.descriptorMissing()).toBe(false);
            expect(c.paramsArray.length).toBe(1);
            expect((fixture.nativeElement as HTMLElement).textContent).toContain('Additional parameters');
        });
    });

    /**
     * The token picker's wiring (step 13). The filtering itself is pinned in `job-parameter-specs.spec.ts`;
     * what only the dialog can prove is that the offer FOLLOWS the author — the trigger they pick and the
     * type they switch to — rather than being computed once at open.
     */
    describe('expression token picker', () => {
        const CATALOG: JobExpressionDecl[] = [
            {
                token: '$today',
                form: 'LITERAL',
                yields: 'DATE',
                description: 'The fire date',
                example: '2026-08-07',
                availableIn: ['cron', 'manual', 'on_pipeline', 'on_signal'],
                contextFree: true,
                preview: '2026-08-10',
            },
            {
                token: '$signal.',
                form: 'PREFIX',
                yields: 'STRING',
                description: 'A Signal field',
                example: '$signal.dataset',
                availableIn: ['on_signal'],
                contextFree: false,
                preview: '$signal.dataset',
            },
        ];
        const withParams = (
            params: {
                name: string;
                type: string;
                required?: boolean;
                expressions?: boolean;
            }[],
        ) =>
            vi.fn(() =>
                of({
                    id: 'report',
                    title: 'Report',
                    description: '',
                    emits: [],
                    artifacts: [],
                    requires: [],
                    parameters: params.map((p) => ({
                        required: false,
                        deduce: '',
                        default: '',
                        description: '',
                        ...p,
                    })),
                }),
            );

        it('offers the tokens a declared parameter can hold, and none for one that opted out', async () => {
            const { c, fixture } = create(
                {},
                undefined,
                withParams([
                    { name: 'day', type: 'DATE' },
                    { name: 'sql', type: 'TEXT', expressions: false },
                ]),
                () => of([]),
                () => of(CATALOG),
            );
            await Promise.resolve();
            fixture.detectChanges();

            expect(Object.keys(c.paramTokenMap())).toEqual(['day']);
            expect(c.paramTokenMap()['day'].map((t) => t.token)).toEqual(['$today']);
        });

        it('withdraws $signal.* when the author switches the trigger away from a signal', async () => {
            const { c, fixture } = create(
                {},
                undefined,
                withParams([{ name: 'note', type: 'STRING' }]),
                () => of([]),
                () => of(CATALOG),
            );
            await Promise.resolve();
            fixture.detectChanges();

            c.schemaForm.form.get('scheduleMode')!.setValue('signal');
            expect(c.paramTokenMap()['note'].map((t) => t.token)).toEqual(['$today', '$signal.dataset']);

            // A cron fire has no Signal payload, so leaving the token offerable would author a value that
            // resolves to nothing.
            c.schemaForm.form.get('scheduleMode')!.setValue('cron');
            expect(c.paramTokenMap()['note'].map((t) => t.token)).toEqual(['$today']);
        });

        it('renders the picker affordance on the parameter field', async () => {
            // `required` matters to this assertion, not just to validation: an optional-tier field sits
            // inside the collapsed "Optional settings" disclosure and is not in the DOM at all until it is
            // expanded, so a DOM query for its picker finds nothing for a reason that has nothing to do
            // with tokens.
            const { fixture } = create(
                {},
                undefined,
                withParams([{ name: 'day', type: 'DATE', required: true }]),
                () => of([]),
                () => of(CATALOG),
            );
            await Promise.resolve();
            fixture.detectChanges();

            const picker = (fixture.nativeElement as HTMLElement).querySelector(
                'button[aria-label="Insert a runtime token into Day"]',
            );
            expect(picker).not.toBeNull();
            expect(picker!.closest('.mat-mdc-form-field-icon-suffix')).not.toBeNull();
        });

        it('degrades to no pickers when the catalog call fails, leaving the fields typeable', async () => {
            // A server predating step 6 404s here. The picker is an accelerator over a field the author can
            // still fill in by hand, so it must cost the pickers and nothing else.
            const { c, fixture } = create(
                {},
                undefined,
                withParams([{ name: 'day', type: 'DATE' }]),
                () => of([]),
                () => throwError(() => ({ status: 404 })),
            );
            await Promise.resolve();
            fixture.detectChanges();

            expect(c.paramTokenMap()).toEqual({});
            expect(c.paramSpecs().map((s) => s.key)).toEqual(['day']);
        });
    });
});
