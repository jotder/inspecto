import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { CatalogService, ConfigService, DbBrowserService, MetadataNode, SpacesService } from 'app/inspecto/api';
import { EnrichmentEditorComponent } from 'app/inspecto/enrichment/enrichment-editor.component';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { OnboardingEnrichmentPaneComponent } from './enrichment-pane.component';
import { OnboardingStateService } from './onboarding-state.service';

const TOASTR = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };
const WRITE_OK = (type: string, name: string) => ({
    // Mirrors the real fileBase convention: suffix once, never double.
    type, written: true, path: name.endsWith('_enrich') ? `${name}.toon` : `${name}_enrich.toon`,
    name, bytes: 1, overwritten: false, findings: [],
});
const REGISTER_OK = { registered: true, name: 'orders_feed_enrich', path: 'orders_feed_enrich.toon', findings: [] };

const PRODUCED_REF: MetadataNode = {
    id: 'ref:region_dim', kind: 'REFERENCE_DATASET', label: 'REGION_DIM',
    attrs: { pipeline: 'region_dim', active: true },
} as MetadataNode;
const PATH_REF: MetadataNode = {
    id: 'ref:daily/zones', kind: 'REFERENCE_DATASET', label: 'zones',
    attrs: { path: 'data/zones.csv', format: 'CSV' },
} as MetadataNode;

async function create(
    config: Record<string, unknown>,
    opts: {
        api?: Partial<ConfigService>;
        refs?: MetadataNode[];
        enrichment?: Record<string, unknown> | null;
        sample?: Record<string, unknown>[] | 'error';
    } = {},
) {
    const table = vi.fn(() =>
        opts.sample === 'error'
            ? throwError(() => ({ status: 404 }))
            : of({ columns: [], rows: opts.sample ?? [], statistics: { rowCount: 0, elapsedMs: 0, truncated: false } }));
    TestBed.configureTestingModule({
        imports: [OnboardingEnrichmentPaneComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            OnboardingStateService,
            {
                provide: ConfigService,
                useValue: {
                    write: vi.fn((type: string, cfg: Record<string, unknown>, _opts?: unknown) =>
                        of(WRITE_OK(type, String(cfg['name'] ?? 'x')))),
                    read: vi.fn(() => throwError(() => ({ status: 404 }))),
                    registerEnrichment: vi.fn((_path: string) => of(REGISTER_OK)),
                    previewEnrichment: vi.fn(() => of({ columns: ['ID'], rows: [{ ID: 'x' }], truncated: false })),
                    ...opts.api,
                },
            },
            {
                provide: CatalogService,
                useValue: { references: vi.fn(() => of(opts.refs ?? [PRODUCED_REF, PATH_REF])) },
            },
            { provide: DbBrowserService, useValue: { table } },
            { provide: SpacesService, useValue: { currentSpaceId: () => 'demo' } },
            { provide: ToastrService, useValue: TOASTR },
        ],
    });
    const state = TestBed.inject(OnboardingStateService);
    state.name.set(String(config['name'] ?? ''));
    state.config.set(config);
    if (opts.enrichment !== undefined) state.enrichmentConfig.set(opts.enrichment);
    await TestBed.compileComponents(); // the shared data-table pulls in @defer-loaded blocks
    const fixture = TestBed.createComponent(OnboardingEnrichmentPaneComponent);
    fixture.detectChanges();
    fixture.detectChanges(); // second pass: the shared editor mounts, then the hydrate effect sees it
    return { fixture, state, api: TestBed.inject(ConfigService), table };
}

/** The shared editor hosted by the pane (W4b) — starts the stage first when needed. */
function editor(fixture: ComponentFixture<OnboardingEnrichmentPaneComponent>): EnrichmentEditorComponent {
    if (!fixture.componentInstance.started()) {
        fixture.componentInstance.start();
        fixture.detectChanges();
    }
    return fixture.debugElement.query(By.directive(EnrichmentEditorComponent)).componentInstance;
}

const PIPELINE = { name: 'orders_feed', dirs: { poll: 'in', database: 'spaces/demo/data/orders_feed/database' } };

describe('OnboardingEnrichmentPaneComponent', () => {
    beforeEach(() => {
        localStorage.removeItem('inspecto.currentLens');
        TOASTR.success.mockClear();
        TOASTR.warning.mockClear();
        TOASTR.error.mockClear();
    });

    it('opens as an opt-in empty state (the stage is optional) and starts on demand', async () => {
        const { fixture } = await create(PIPELINE);
        const c = fixture.componentInstance;
        expect(c.started()).toBe(false);
        expect(fixture.nativeElement.textContent).toContain('Optional');
        c.start();
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('Transform');
    });

    it('offers only pipeline-produced references, excluding this pipeline itself', async () => {
        const self: MetadataNode = {
            id: 'ref:orders_feed', kind: 'REFERENCE_DATASET', label: 'self',
            attrs: { pipeline: 'orders_feed' },
        } as MetadataNode;
        const { fixture } = await create(PIPELINE, { refs: [PRODUCED_REF, PATH_REF, self] });
        expect(fixture.componentInstance.referenceOptions()).toEqual([
            { id: 'region_dim', label: 'REGION_DIM' },
        ]);
    });

    it('hydrates the shared editor from an existing companion config, pristine', async () => {
        const { fixture } = await create(PIPELINE, {
            enrichment: {
                name: 'orders_feed_enrich',
                references: { region_dim: { ref: 'region_dim' }, zones: { path: 'data/zones.csv', format: 'CSV' } },
                transform: 'SELECT * FROM input i LEFT JOIN region_dim r ON i.REGION = r.region',
            },
        });
        expect(fixture.componentInstance.started()).toBe(true);
        const ed = editor(fixture);
        expect(ed.referenceRows.length).toBe(2);
        expect(ed.referenceRows.at(0).get('mode')?.value).toBe('ref');
        expect(ed.referenceRows.at(1).get('mode')?.value).toBe('path');
        expect(ed.sql()).toContain('LEFT JOIN region_dim');
        expect(ed.isDirty()).toBe(false);
    });

    it('hydrates when the companion read lands after the pane mounted', async () => {
        const { fixture, state } = await create(PIPELINE);
        expect(fixture.componentInstance.started()).toBe(false);
        state.enrichmentConfig.set({ name: 'orders_feed_enrich', transform: 'SELECT 1 FROM input' });
        fixture.detectChanges(); // started flips, the editor mounts
        fixture.detectChanges(); // the hydrate effect sees the mounted editor
        expect(fixture.componentInstance.started()).toBe(true);
        expect(editor(fixture).sql()).toBe('SELECT 1 FROM input');
    });

    it('save writes the companion with derived wiring, then hot-registers it', async () => {
        const write = vi.fn((type: string, cfg: Record<string, unknown>, _opts?: unknown) =>
            of(WRITE_OK(type, String(cfg['name'] ?? 'x'))));
        const registerEnrichment = vi.fn((_path: string) => of(REGISTER_OK));
        const { fixture, state } = await create(PIPELINE, { api: { write, registerEnrichment } });
        const c = fixture.componentInstance;
        const ed = editor(fixture);
        ed.addReference();
        ed.referenceRows.at(0).get('name')?.setValue('region_dim');
        ed.referenceRows.at(0).get('ref')?.setValue('region_dim');
        ed.onSqlChange('SELECT i.*, r.zone FROM input i LEFT JOIN region_dim r ON i.REGION = r.region');
        c.save();

        const [type, draft] = write.mock.calls[0] as [string, Record<string, unknown>];
        expect(type).toBe('enrichment');
        expect(draft['name']).toBe('orders_feed_enrich');
        expect(draft['references']).toEqual({ region_dim: { ref: 'region_dim' } });
        expect((draft['triggers'] as Record<string, unknown>)['on_pipeline']).toBe('orders_feed');
        expect((draft['input'] as Record<string, unknown>)['database'])
            .toBe('spaces/demo/data/orders_feed/database');
        expect((draft['output'] as Record<string, unknown>)['database'])
            .toBe('spaces/demo/data/enriched/orders_feed_enrich');
        expect(registerEnrichment).toHaveBeenCalledWith('orders_feed_enrich.toon');
        expect(state.enrichmentConfig()).toEqual(draft);
        expect(TOASTR.success).toHaveBeenCalled();
    });

    it('keeps the save but warns when registration fails', async () => {
        const registerEnrichment = vi.fn((_path: string) => throwError(() => ({ status: 503 })));
        const { fixture, state } = await create(PIPELINE, { api: { registerEnrichment } });
        editor(fixture).onSqlChange('SELECT * FROM input');
        fixture.componentInstance.save();
        expect(state.enrichmentConfig()).not.toBeNull();
        expect(TOASTR.warning).toHaveBeenCalled();
    });

    it('blocks save on a duplicate reference alias', async () => {
        const write = vi.fn((type: string, cfg: Record<string, unknown>, _opts?: unknown) =>
            of(WRITE_OK(type, String(cfg['name'] ?? 'x'))));
        const { fixture } = await create(PIPELINE, { api: { write } });
        const ed = editor(fixture);
        ed.addReference();
        ed.addReference();
        ed.referenceRows.at(0).get('name')?.setValue('dupe');
        ed.referenceRows.at(0).get('ref')?.setValue('a');
        ed.referenceRows.at(1).get('name')?.setValue('dupe');
        ed.referenceRows.at(1).get('ref')?.setValue('b');
        fixture.componentInstance.save();
        expect(write).not.toHaveBeenCalled();
        expect(TOASTR.warning).toHaveBeenCalled();
    });

    it('preview samples the stream output and runs the draft transform over it', async () => {
        const previewEnrichment = vi.fn((_cfg: Record<string, unknown>, _rows: Record<string, unknown>[]) =>
            of({ columns: ['ID', 'ZONE'], rows: [{ ID: '1', ZONE: 'north' }], truncated: true }));
        const { fixture, table } = await create(PIPELINE, {
            api: { previewEnrichment }, sample: [{ ID: '1', REGION: 'r1' }],
        });
        const c = fixture.componentInstance;
        editor(fixture).onSqlChange('SELECT * FROM input');
        c.preview();

        expect(table).toHaveBeenCalledWith(expect.objectContaining({ name: 'orders_feed', limit: 200 }));
        const [draft, sample] = previewEnrichment.mock.calls[0] as [Record<string, unknown>, unknown[]];
        expect(draft['transform']).toBe('SELECT * FROM input');
        expect(sample).toEqual([{ ID: '1', REGION: 'r1' }]);
        expect(c.previewResult()?.rows).toEqual([{ ID: '1', ZONE: 'north' }]);
        expect(c.previewResult()?.truncated).toBe(true);
        expect(c.previewError()).toBeNull();
    });

    it('preview warns and skips the call when the stream has no data yet', async () => {
        const previewEnrichment = vi.fn();
        const { fixture } = await create(PIPELINE, { api: { previewEnrichment }, sample: [] });
        const c = fixture.componentInstance;
        editor(fixture).onSqlChange('SELECT * FROM input');
        c.preview();
        expect(previewEnrichment).not.toHaveBeenCalled();
        expect(c.previewResult()).toBeNull();
        expect(TOASTR.warning).toHaveBeenCalledWith(expect.stringContaining('No data'));
    });

    it('preview falls back to an empty sample (and warns) when the store is not browsable', async () => {
        const previewEnrichment = vi.fn();
        const { fixture } = await create(PIPELINE, { api: { previewEnrichment }, sample: 'error' });
        editor(fixture).onSqlChange('SELECT * FROM input');
        fixture.componentInstance.preview();
        expect(previewEnrichment).not.toHaveBeenCalled();
        expect(TOASTR.warning).toHaveBeenCalledWith(expect.stringContaining('No data'));
    });

    it('preview surfaces a transform error from the sample', async () => {
        const previewEnrichment = vi.fn(() => throwError(() =>
            new HttpErrorResponse({ status: 422, error: { error: { message: 'no such column: BOGUS' } } })));
        const { fixture } = await create(PIPELINE, {
            api: { previewEnrichment }, sample: [{ ID: '1' }],
        });
        const c = fixture.componentInstance;
        editor(fixture).onSqlChange('SELECT BOGUS FROM input');
        c.preview();
        expect(c.previewResult()).toBeNull();
        expect(c.previewError()).toContain('BOGUS');
    });

    it('has no a11y violations in both the opt-in and form states', async () => {
        const { fixture } = await create(PIPELINE);
        await expectNoA11yViolations(fixture.nativeElement);
        editor(fixture).addReference();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
