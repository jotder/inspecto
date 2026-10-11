import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { ConfigService, PipelineSummary, PipelinesService, SpacesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { STREAM_BUNDLE_FORMAT, StreamBundle } from 'app/inspecto/transfer/stream-bundle';
import { OnboardingCreateData, OnboardingCreateDialog } from './onboarding-create.dialog';

const TOASTR = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };

/** `GET /pipelines` rows: one pipeline template among ordinary pipelines. */
const PIPELINES: PipelineSummary[] = [
    { name: 'orders_feed', active: true, nodeCount: 3, edgeCount: 2, produces: [], consumes: [] },
    {
        name: 'orders_tpl',
        displayName: 'Orders template',
        description: 'Daily order drops',
        template: true,
        active: false,
        nodeCount: 3,
        edgeCount: 2,
        produces: [],
        consumes: [],
    },
];

/** The template's config as `save-as-template` wrote it — read by the export half of the pick. */
const TEMPLATE_CONFIG = {
    name: 'Orders template',
    id: 'orders_tpl',
    template: true,
    active: false,
    stream: 'orders_tpl',
    produces: 'reference',
    description: 'Daily order drops',
    collector: { id: 'orders_tpl' },
    dirs: { poll: 'data/templates/orders_tpl/inbox', database: 'data/templates/orders_tpl/database' },
    processing: { threads: 2 },
};

function create(
    data: OnboardingCreateData,
    api: Partial<ConfigService> = {},
    pipelines: PipelineSummary[] = PIPELINES,
) {
    const ref = { close: vi.fn(), disableClose: false };
    TestBed.configureTestingModule({
        imports: [OnboardingCreateDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: ref },
            { provide: MAT_DIALOG_DATA, useValue: data },
            {
                provide: ConfigService,
                useValue: {
                    write: vi.fn(() => of({ path: 'x.toon', name: 'x' })),
                    registerPipeline: vi.fn(() => of({ registered: true })),
                    // Only the template's pipeline config exists; every satellite read 404s (absent).
                    read: vi.fn((type: string, name: string) =>
                        type === 'pipeline' && name === 'orders_tpl'
                            ? of({ config: structuredClone(TEMPLATE_CONFIG) })
                            : throwError(() => ({ status: 404 })),
                    ),
                    ...api,
                },
            },
            { provide: PipelinesService, useValue: { list: vi.fn(() => of(pipelines)) } },
            { provide: SpacesService, useValue: { currentSpaceId: () => 'demo' } },
            { provide: ToastrService, useValue: TOASTR },
        ],
    });
    const fixture = TestBed.createComponent(OnboardingCreateDialog);
    fixture.detectChanges();
    return { fixture, ref, api: TestBed.inject(ConfigService) };
}

describe('OnboardingCreateDialog', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('writes a minimal inactive draft, registers it, and closes with the name', () => {
        const { fixture, ref, api } = create({ kind: 'stream' });
        const c = fixture.componentInstance;
        c.form.controls.name.setValue('orders_feed');
        c.create();
        const [type, config] = (api.write as ReturnType<typeof vi.fn>).mock.calls[0] as [
            string,
            Record<string, unknown>,
        ];
        expect(type).toBe('pipeline');
        expect(config['name']).toBe('orders_feed');
        expect(config['active']).toBe(false);
        expect(config['produces']).toBeUndefined();
        expect(config['dirs']).toEqual({
            poll: 'data/inbox/orders_feed',
            database: 'data/orders_feed/database',
            // Derived silently (never asked): without status_dir the Runs history stays empty.
            backup: 'data/orders_feed/backup',
            temp: 'data/orders_feed/temp',
            errors: 'data/orders_feed/errors',
            quarantine: 'data/orders_feed/quarantine',
            markers: 'data/orders_feed/markers',
            status_dir: 'data/orders_feed/status',
            log_dir: 'data/orders_feed/logs',
        });
        // The LOCAL poll path's real dedup — collector-level `duplicate:` is engine-only.
        // retention_days is deliberately absent — PipelineConfigParser's own default (90) governs;
        // this used to hardcode 30 with no stated reason, silently overriding it.
        expect((config['processing'] as Record<string, unknown>)['duplicate_check']).toEqual({
            enabled: true,
            marker_extension: '.processed',
        });
        expect(api.registerPipeline).toHaveBeenCalledWith('x.toon');
        expect(ref.close).toHaveBeenCalledWith({ name: 'orders_feed' });
    });

    it('a Reference draft declares produces: reference', () => {
        const { fixture, api } = create({ kind: 'reference' });
        const c = fixture.componentInstance;
        c.form.controls.name.setValue('region_dim');
        c.create();
        const config = (api.write as ReturnType<typeof vi.fn>).mock.calls[0][1] as Record<string, unknown>;
        expect(config['produces']).toBe('reference');
    });

    it('rejects a duplicate name inline', () => {
        const { fixture, api } = create({ kind: 'stream', existingNames: ['orders_feed'] });
        const c = fixture.componentInstance;
        c.form.controls.name.setValue('orders_feed');
        c.create();
        expect(c.form.controls.name.hasError('duplicate')).toBe(true);
        expect(api.write).not.toHaveBeenCalled();
    });

    it('shows "A name is required." only after submit — never on open (the error lands in the subscript)', () => {
        const { fixture, api } = create({ kind: 'stream' });
        const el = fixture.nativeElement as HTMLElement;
        // Untouched: no error anywhere. The old nested "@if (…; as c)" wrapper mis-projected the
        // <mat-error> into the form field's DEFAULT slot, so it rendered beside the input on open.
        expect(el.querySelector('mat-error')).toBeNull();
        expect(el.textContent).not.toContain('A name is required');
        fixture.componentInstance.create();
        fixture.detectChanges();
        const err = el.querySelector('mat-error');
        expect(err?.textContent).toContain('A name is required');
        expect(err?.closest('.mat-mdc-form-field-subscript-wrapper')).not.toBeNull();
        expect(api.write).not.toHaveBeenCalled();
    });

    it('a 503 write shows the writes-disabled notice instead of closing', () => {
        const { fixture, ref } = create(
            { kind: 'stream' },
            { write: vi.fn(() => throwError(() => ({ status: 503 }))) },
        );
        const c = fixture.componentInstance;
        c.form.controls.name.setValue('x');
        c.create();
        fixture.detectChanges();
        expect(c.writesDisabled()).toBe(true);
        expect(ref.close).not.toHaveBeenCalled();
        expect(fixture.nativeElement.textContent).toContain('writes are disabled');
    });

    it('has no a11y violations (advanced open)', async () => {
        const { fixture } = create({ kind: 'stream' });
        fixture.componentInstance.advancedOpen.set(true);
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    // ── Import ────────────────────────────────────────────────────────────────
    // `loadBundle` drives the same path `onFilePicked` does after the async file read; the file
    // plumbing itself (FileReader/Blob.text) is jsdom-flaky and carries no logic worth pinning.
    const BUNDLE: StreamBundle = {
        format: STREAM_BUNDLE_FORMAT,
        version: 1,
        exportedAt: '2026-07-31T10:00:00.000Z',
        source: { space: 'staging', name: 'orders_feed', contentHash: 'abc' },
        kind: 'reference' as const,
        pipeline: { description: 'From staging', processing: { threads: 2 }, collector: { connection: 'prod_sftp' } },
        schema: { raw: { name: 'orders_feed_schema', fields: [] } },
        segments: { moCall: { raw: { name: 'orders_feed_moCall' } } },
        enrichment: { joins: [] },
        requires: [{ kind: 'connection' as const, id: 'prod_sftp', reason: 'carries credentials' }],
    };

    function load(fixture: ReturnType<typeof create>['fixture'], bundle: StreamBundle = BUNDLE) {
        const c = fixture.componentInstance;
        const parsed = JSON.parse(JSON.stringify(bundle)) as StreamBundle;
        c.imported.set(parsed);
        c.kind.set(parsed.kind);
        c.form.controls.name.setValue(parsed.source.name);
        fixture.detectChanges();
        return c;
    }

    it('summarizes everything the file carries so Create is not a blind action', () => {
        const { fixture } = create({ kind: 'stream' });
        const c = load(fixture);
        expect(c.importSummary()).toBe('pipeline configuration, schema, 1 segment schema, enrichment');
        expect(fixture.nativeElement.textContent).toContain('orders_feed');
        expect(fixture.nativeElement.textContent).toContain('staging');
    });

    it('states the rewrites and the un-travelled Connection before any write', () => {
        const { fixture } = create({ kind: 'stream' });
        const c = load(fixture);
        const notes = c.importNotes().join(' ');
        expect(notes).toContain('re-derived');
        expect(notes).toContain('inactive draft');
        const text = fixture.nativeElement.textContent as string;
        expect(text).toContain('Must already exist');
        expect(text).toContain('prod_sftp');
    });

    it('takes the kind from the FILE and locks the toggle', () => {
        const { fixture } = create({ kind: 'stream' }); // caller asked for a Stream…
        const c = load(fixture);
        expect(c.kind()).toBe('reference'); // …the file says Reference
        // Assert the behaviour (both toggles unclickable), not a Material-internal class name.
        const buttons = Array.from(
            fixture.nativeElement.querySelectorAll('mat-button-toggle button'),
        ) as HTMLButtonElement[];
        expect(buttons.length).toBe(2);
        expect(buttons.every((b) => b.disabled)).toBe(true);
    });

    it('writes satellites BEFORE the pipeline, rewired to this space and named for the target', () => {
        const { fixture, ref, api } = create({ kind: 'stream' });
        const c = load(fixture);
        c.form.controls.name.setValue('orders_copy');
        c.create();

        const calls = (api.write as ReturnType<typeof vi.fn>).mock.calls as [string, Record<string, unknown>][];
        const types = calls.map((c2) => c2[0]);
        expect(types.indexOf('pipeline')).toBe(types.length - 1); // pipeline is written LAST
        expect(types.filter((t) => t === 'schema')).toHaveLength(2); // main + 1 segment

        const pipeline = calls.find((c2) => c2[0] === 'pipeline')![1];
        expect(pipeline['name']).toBe('orders_copy');
        expect(pipeline['active']).toBe(false);
        expect((pipeline['dirs'] as Record<string, string>)['poll']).toBe('data/inbox/orders_copy');
        // W3: portable bare ref. `dirs` above are Space-relative `data/...` (DATA-DIRS-RESOLVE-AGAINST-CWD-1).
        expect((pipeline['processing'] as Record<string, unknown>)['schema_file']).toBe('orders_copy_schema.toon');
        expect((pipeline['processing'] as Record<string, unknown>)['threads']).toBe(2); // body preserved
        expect(ref.close).toHaveBeenCalledWith({ name: 'orders_copy' });
    });

    it('de-duplicates the suggested name against this instance', () => {
        const { fixture } = create({ kind: 'stream', existingNames: ['orders_feed'] });
        const c = fixture.componentInstance;
        // Mirrors onFilePicked's suggestion step.
        const taken = new Set(['orders_feed']);
        let candidate = 'orders_feed';
        for (let i = 2; taken.has(candidate); i++) candidate = `orders_feed_${i}`;
        expect(candidate).toBe('orders_feed_2');
        c.form.controls.name.setValue(candidate);
        expect(c.form.controls.name.valid).toBe(true);
    });

    it('surfaces a parse error and stays on the blank-create path', () => {
        const { fixture } = create({ kind: 'stream' });
        const c = fixture.componentInstance;
        c.importErrors.set(['Not an Inspecto Stream configuration (format must be "inspecto-stream-config").']);
        fixture.detectChanges();
        expect(c.imported()).toBeNull();
        expect(fixture.nativeElement.textContent).toContain('cannot be imported');
    });

    it('has no a11y violations with an import loaded', async () => {
        const { fixture } = create({ kind: 'stream' });
        load(fixture);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    // ── Start from a template ─────────────────────────────────────────────────

    function openGallery(fixture: ReturnType<typeof create>['fixture']) {
        const el = fixture.nativeElement as HTMLElement;
        const toggle = Array.from(el.querySelectorAll('button')).find((b) =>
            b.textContent?.includes('Start from a template'),
        )!;
        toggle.click();
        fixture.detectChanges();
        return { el, toggle };
    }

    it('lists only the pipeline templates, read once on first open', () => {
        const { fixture } = create({ kind: 'stream' });
        const { el, toggle } = openGallery(fixture);
        expect(toggle.getAttribute('aria-expanded')).toBe('true');
        const gallery = el.querySelector('#onboarding-templates')!;
        expect(gallery.textContent).toContain('Orders template');
        expect(gallery.textContent).toContain('Daily order drops');
        expect(gallery.textContent).not.toContain('orders_feed'); // an ordinary pipeline is not offered
        toggle.click(); // close
        toggle.click(); // reopen — no second read
        expect(TestBed.inject(PipelinesService).list).toHaveBeenCalledTimes(1);
    });

    it('explains how to make a template when the server has none', () => {
        const { fixture } = create({ kind: 'stream' }, {}, [PIPELINES[0]]);
        const { el } = openGallery(fixture);
        expect(el.querySelector('#onboarding-templates')?.textContent).toContain('No pipeline templates yet');
    });

    it('picking a template loads the preview, locks its kind, and leaves the name to the operator', () => {
        const { fixture, api } = create({ kind: 'stream' });
        const c = fixture.componentInstance;
        c.chooseTemplate(PIPELINES[1]);
        fixture.detectChanges();
        expect(api.read).toHaveBeenCalledWith('pipeline', 'orders_tpl');
        expect(api.write).not.toHaveBeenCalled(); // nothing is written until Create
        expect(c.templatesOpen()).toBe(false);
        expect(c.kind()).toBe('reference'); // the template's kind, not the caller's
        expect(c.form.controls.name.value).toBe('');
        expect(c.form.controls.description.value).toBe('Daily order drops');
        const text = fixture.nativeElement.textContent as string;
        expect(text).toContain('Starting from a template');
        expect(text).toContain('From template Orders template');
        expect(text).toContain('Create from template');
        expect(c.importNotes()[0]).toContain('a copy, not a link');
    });

    it('creates a runnable copy: no template flag, its own Stream and collector id', () => {
        const { fixture, ref, api } = create({ kind: 'stream' });
        const c = fixture.componentInstance;
        c.chooseTemplate(PIPELINES[1]);
        c.form.controls.name.setValue('orders_eu');
        c.create();
        const pipeline = (api.write as ReturnType<typeof vi.fn>).mock.calls.find((call) => call[0] === 'pipeline')![1];
        expect(pipeline['template']).toBeUndefined();
        expect(pipeline['active']).toBe(false);
        expect(pipeline['id']).toBe('orders_eu');
        expect(pipeline['stream']).toBe('orders_eu');
        expect(pipeline['collector']).toEqual({ id: 'orders_eu' });
        expect(pipeline['produces']).toBe('reference');
        expect((pipeline['dirs'] as Record<string, string>)['poll']).toBe('data/inbox/orders_eu');
        expect(ref.close).toHaveBeenCalledWith({ name: 'orders_eu' });
        expect(TOASTR.success).toHaveBeenCalledWith(expect.stringContaining('Created "orders_eu"'));
    });

    it('has no a11y violations with the template gallery open', async () => {
        const { fixture } = create({ kind: 'stream' });
        openGallery(fixture);
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
