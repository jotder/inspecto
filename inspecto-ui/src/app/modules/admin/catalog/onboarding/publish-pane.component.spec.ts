import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { ComponentDef, ComponentsService, ConfigService, InboxStatus, RunsService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { OnboardingPublishPaneComponent } from './publish-pane.component';
import { OnboardingStateService } from './onboarding-state.service';

const TOASTR = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };
const WRITE_OK = {
    type: 'pipeline',
    written: true,
    path: 'x.toon',
    name: 'x',
    bytes: 1,
    overwritten: false,
    findings: [],
};
const PENDING: InboxStatus = { pipeline: 'x', inbox: 'spaces/demo/data/inbox/orders_feed', pending: 2, running: false };

const READY_CONFIG = {
    name: 'orders_feed',
    active: false,
    collector: { connector: 'local' },
    parsing: { frontend: 'delimited' },
    processing: { schema_file: 'x_schema.toon' },
};

function create(
    config: Record<string, unknown>,
    opts: {
        api?: Partial<ConfigService>;
        confirm?: boolean;
        runsApi?: Partial<RunsService>;
        datasets?: ComponentDef[];
        components?: Partial<ComponentsService>;
    } = {},
) {
    // Stage saves go through POST /config/patch — the mock's third arg is the block patch itself.
    const patch = vi.fn((_type: string, _name: string, _patch: Record<string, unknown>) => of(WRITE_OK));
    const confirmFn = vi.fn(() => Promise.resolve(opts.confirm ?? true));
    const components = {
        list: vi.fn(() => of(opts.datasets ?? [])),
        create: vi.fn((_t: string, c: Record<string, unknown>) =>
            of({ type: 'dataset', name: String(c['id']), ref: `dataset/${c['id']}`, content: c } as ComponentDef),
        ),
        ...opts.components,
    };
    TestBed.configureTestingModule({
        imports: [OnboardingPublishPaneComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            OnboardingStateService,
            { provide: ConfigService, useValue: { patch, ...opts.api } },
            { provide: RunsService, useValue: { pending: vi.fn(() => of(PENDING)), ...opts.runsApi } },
            { provide: ComponentsService, useValue: components },
            {
                provide: InspectoConfirmService,
                useValue: { confirm: confirmFn, confirmDestructive: vi.fn(() => Promise.resolve(true)) },
            },
            { provide: ToastrService, useValue: TOASTR },
        ],
    });
    const state = TestBed.inject(OnboardingStateService);
    state.config.set(config);
    state.name.set(String(config['name'] ?? '')); // the shell sets this from the route param
    const fixture = TestBed.createComponent(OnboardingPublishPaneComponent);
    fixture.detectChanges();
    return { fixture, state, patch, confirmFn, components };
}

describe('OnboardingPublishPaneComponent', () => {
    beforeEach(() => {
        localStorage.removeItem('inspecto.currentLens');
        Object.values(TOASTR).forEach((f) => f.mockClear());
    });

    it('save writes the output block with the schema-form defaults', () => {
        const { fixture, patch } = create({ name: 'x' });
        fixture.componentInstance.save();
        const [type, , blockPatch] = patch.mock.calls[0] as [string, string, Record<string, unknown>];
        expect(type).toBe('pipeline');
        // W4a: the Parquet suggestion now rides `initial` (new drafts only); the shared
        // OUTPUT_ATTRIBUTES table's own default is the engine truth (CSV).
        expect((blockPatch['output'] as Record<string, unknown>)['format']).toBe('PARQUET');
    });

    it('a resumed output block renders verbatim — the Parquet suggestion is for new drafts only', () => {
        const { fixture, patch } = create({ name: 'x', output: { format: 'CSV' } });
        fixture.componentInstance.save();
        const [, , blockPatch] = patch.mock.calls[0] as [string, string, Record<string, unknown>];
        expect((blockPatch['output'] as Record<string, unknown>)['format']).toBe('CSV');
    });

    it('names the other incomplete required stages when far from ready', () => {
        const { fixture } = create({ name: 'x' });
        expect(fixture.componentInstance.blockedOn()).toEqual(['Collection', 'Parsing', 'Schema & Mapping']);
        expect(fixture.nativeElement.textContent).toContain('Collection, Parsing, Schema & Mapping');
    });

    it('asks to save output (not the other stages) when only the output block is missing', () => {
        const { fixture, state } = create(READY_CONFIG);
        expect(fixture.componentInstance.blockedOn()).toEqual([]);
        expect(state.lifecycle()).toBe('Draft');
        expect(fixture.nativeElement.textContent).toContain('Save the output settings above');
    });

    it('activate confirms, flips active, and refreshes the activity glance', async () => {
        const { fixture, state, patch, confirmFn } = create({ ...READY_CONFIG, output: { format: 'PARQUET' } });
        expect(state.lifecycle()).toBe('Ready');
        await fixture.componentInstance.activate();
        expect(confirmFn).toHaveBeenCalled();
        const [, , blockPatch] = patch.mock.calls[0] as [string, string, Record<string, unknown>];
        expect(blockPatch['active']).toBe(true);
    });

    /** A go-live-ready config (all required stages + saved output), optionally reference-kind. */
    const readyConfig = (extra: Record<string, unknown> = {}) => ({
        ...READY_CONFIG,
        output: { format: 'PARQUET' },
        ...extra,
    });

    it('going live on a Stream registers the Dataset over its store (split S1)', async () => {
        const { fixture, components } = create(readyConfig());
        await fixture.componentInstance.activate();
        expect(components.create).toHaveBeenCalledWith('dataset', {
            id: 'orders_feed',
            name: 'orders_feed',
            kind: 'physical',
            // Names its own store. Omitting this let DatasetsService fall through to a source name
            // that resolves to no rows, so every consumer read the live dataset as empty.
            sourceName: 'orders_feed',
            physicalRef: 'orders_feed',
            description: expect.stringContaining('orders_feed'),
        });
        expect(TOASTR.success).toHaveBeenCalledWith(expect.stringContaining('Dataset "orders_feed" registered'));
    });

    it('skips registration when a dataset already points at the store, whatever its id', async () => {
        const existing: ComponentDef = {
            type: 'dataset',
            name: 'orders_gold',
            ref: 'dataset/orders_gold',
            content: { name: 'Orders (gold)', physicalRef: 'orders_feed' },
        };
        const { fixture, components } = create(readyConfig(), { datasets: [existing] });
        await fixture.componentInstance.activate();
        expect(components.create).not.toHaveBeenCalled();
    });

    it('a registration failure downgrades to a warning — activation already succeeded', async () => {
        const { fixture } = create(readyConfig(), {
            components: { create: vi.fn(() => throwError(() => new Error('409'))) },
        });
        await fixture.componentInstance.activate();
        expect(TOASTR.success).toHaveBeenCalledWith('"orders_feed" is live');
        expect(TOASTR.warning).toHaveBeenCalledWith(expect.stringContaining('could not be registered'));
    });

    it('a Reference go-live registers no Dataset — its store is consumed by name', async () => {
        const { fixture, components, patch } = create(readyConfig({ produces: 'reference' }));
        await fixture.componentInstance.activate();
        const [, , blockPatch] = patch.mock.calls[0] as [string, string, Record<string, unknown>];
        expect(blockPatch['active']).toBe(true); // activation DID run — the skip is kind-scoped, not vacuous
        expect(components.create).not.toHaveBeenCalled();
    });

    it('declining the confirm leaves the draft inactive', async () => {
        const { fixture, patch } = create({ ...READY_CONFIG, output: { format: 'PARQUET' } }, { confirm: false });
        await fixture.componentInstance.activate();
        expect(patch).not.toHaveBeenCalled();
    });

    it('shows the inbox activity glance once live', () => {
        const { fixture } = create({ ...READY_CONFIG, active: true, output: { format: 'PARQUET' } });
        expect(fixture.componentInstance.activity()).toEqual(PENDING);
        expect(fixture.nativeElement.textContent).toContain('pending in spaces/demo');
    });

    it('take-offline confirms and writes active:false — the only way off a live pipeline', async () => {
        const { fixture, patch, confirmFn } = create({ ...READY_CONFIG, active: true, output: { format: 'PARQUET' } });
        await fixture.componentInstance.deactivate();
        expect(confirmFn).toHaveBeenCalled();
        const [, , blockPatch] = patch.mock.calls[0] as [string, string, Record<string, unknown>];
        expect(blockPatch['active']).toBe(false);
        expect(fixture.componentInstance.activity()).toBeNull(); // the glance is meaningless once offline
    });

    it('declining the take-offline confirm leaves it live', async () => {
        const { fixture, patch, state } = create(
            { ...READY_CONFIG, active: true, output: { format: 'PARQUET' } },
            { confirm: false },
        );
        await fixture.componentInstance.deactivate();
        expect(patch).not.toHaveBeenCalled();
        expect(state.active()).toBe(true);
    });

    it('offers Take offline while live', () => {
        const { fixture } = create({ ...READY_CONFIG, active: true, output: { format: 'PARQUET' } });
        expect(fixture.nativeElement.textContent).toContain('Take offline');
    });

    // Pairs with the test above: ready-but-not-live must offer Go live INSTEAD, or that one is vacuous.
    it('offers Go live, not Take offline, before activation', () => {
        const { fixture } = create({ ...READY_CONFIG, output: { format: 'PARQUET' } });
        expect(fixture.nativeElement.textContent).not.toContain('Take offline');
        expect(fixture.nativeElement.textContent).toContain('Go live');
    });

    it('warns a live author that stage edits take effect immediately', () => {
        const { fixture } = create({ ...READY_CONFIG, active: true, output: { format: 'PARQUET' } });
        expect(fixture.nativeElement.textContent).toContain('every stage edit takes effect immediately');
    });

    it('tells a Reference author the dataset becomes bindable by name', () => {
        const { fixture, state } = create({ ...READY_CONFIG, produces: 'reference' });
        expect(state.kind()).toBe('reference');
        expect(fixture.nativeElement.textContent).toContain('Reference Dataset');
        expect(fixture.nativeElement.textContent).toContain('ref: orders_feed');
    });

    it('has no a11y violations', async () => {
        const { fixture } = create({ name: 'x' });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
