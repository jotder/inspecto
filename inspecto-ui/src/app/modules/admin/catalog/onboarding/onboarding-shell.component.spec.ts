import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { ConfigService, ConnectionsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { STREAM_BUNDLE_FORMAT, StreamBundle } from 'app/inspecto/transfer/stream-bundle';
import { StreamTransferService } from 'app/inspecto/transfer/stream-transfer.service';
import { OnboardingShellComponent } from './onboarding-shell.component';
import { OnboardingStateService } from './onboarding-state.service';

const TOASTR = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };

const exportedBundle = (name: string): StreamBundle => ({
    format: STREAM_BUNDLE_FORMAT,
    version: 1,
    exportedAt: '2026-07-31T10:00:00.000Z',
    source: { space: 'demo', name, contentHash: 'abc' },
    kind: 'stream',
    pipeline: { name },
    requires: [],
});

function create(
    params: Record<string, string>,
    api: Partial<ConfigService> = {},
    transfer: Partial<StreamTransferService> = {},
) {
    TestBed.configureTestingModule({
        imports: [OnboardingShellComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap(params)) } },
            {
                provide: ConfigService,
                useValue: {
                    read: () =>
                        of({ type: 'pipeline', name: params['name'], path: 'p', config: { name: params['name'] } }),
                    ...api,
                },
            },
            { provide: ConnectionsService, useValue: { list: () => of([]), test: () => of({}) } },
            { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(undefined) }) } },
            { provide: ToastrService, useValue: TOASTR },
            {
                provide: StreamTransferService,
                useValue: {
                    buildExport: vi.fn(() => of({ bundle: exportedBundle(params['name']), missing: [] as string[] })),
                    download: vi.fn(),
                    ...transfer,
                },
            },
        ],
    });
    const fixture = TestBed.createComponent(OnboardingShellComponent);
    fixture.detectChanges();
    return fixture;
}

describe('OnboardingShellComponent', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('loads the draft from the route and renders the stream stage rail', () => {
        const fixture = create({ name: 'orders_feed' });
        const text = fixture.nativeElement.textContent as string;
        expect(text).toContain('orders_feed');
        expect(text).toContain('Collection');
        expect(text).toContain('Parsing');
        expect(text).toContain('Dataset & Go-live');
        expect(text).toContain('Draft');
    });

    it('lands on the first incomplete stage when no :stage is in the URL', () => {
        const fixture = create(
            { name: 'x' },
            {
                read: () =>
                    of({
                        type: 'pipeline',
                        name: 'x',
                        path: 'p',
                        config: { name: 'x', collector: { connector: 'local' } },
                    }),
            },
        );
        expect(fixture.componentInstance.activeStage().id).toBe('parsing');
    });

    it('honours an explicit :stage URL param', () => {
        const fixture = create({ name: 'x', stage: 'parsing' });
        expect(fixture.componentInstance.activeStage().id).toBe('parsing');
    });

    it('does not render the sample panel itself — it belongs to the Parsing stage', () => {
        const fixture = create({ name: 'x' }); // lands on Collection
        expect(fixture.componentInstance.activeStage().id).toBe('collection');
        expect(fixture.nativeElement.querySelector('app-onboarding-sample-panel')).toBeNull();
        expect(fixture.nativeElement.textContent).not.toContain('Capture one representative sample');
    });

    it('shows the not-found state for a 404 draft', () => {
        const fixture = create({ name: 'ghost' }, { read: () => throwError(() => ({ status: 404 })) });
        expect(fixture.nativeElement.textContent).toContain('No pipeline or draft named');
    });

    it('View as graph navigates to the Lineage tab with the stream node id', () => {
        const fixture = create({ name: 'orders_feed' });
        const nav = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        fixture.componentInstance.viewAsGraph();
        expect(nav).toHaveBeenCalledWith(['/catalog'], { queryParams: { tab: 'graph', from: 'stream:orders_feed' } });
    });

    it('View as graph uses the ref: token for a Reference origin', () => {
        const fixture = create(
            { name: 'region_dim' },
            {
                read: () =>
                    of({
                        type: 'pipeline',
                        name: 'region_dim',
                        path: 'p',
                        config: { name: 'region_dim', produces: 'reference' },
                    }),
            },
        );
        const nav = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        fixture.componentInstance.viewAsGraph();
        expect(nav).toHaveBeenCalledWith(['/catalog'], { queryParams: { tab: 'graph', from: 'ref:region_dim' } });
    });

    it('warns that a live pipeline has no separate publish step, and drops the draft-only copy', () => {
        const fixture = create(
            { name: 'orders_feed' },
            {
                read: () =>
                    of({
                        type: 'pipeline',
                        name: 'orders_feed',
                        path: 'p',
                        config: { name: 'orders_feed', active: true },
                    }),
            },
        );
        const text = fixture.nativeElement.textContent as string;
        expect(text).toContain('there is no separate publish step');
        expect(text).not.toContain('It runs only when you go live.');
    });

    // Pairs with the test above — without this, the live assertions could hold for every draft too.
    it('leaves a draft the go-live copy and no live warning', () => {
        const text = create({ name: 'orders_feed' }).nativeElement.textContent as string;
        expect(text).toContain('It runs only when you go live.');
        expect(text).not.toContain('there is no separate publish step');
    });

    it('has no a11y violations', async () => {
        const fixture = create({ name: 'orders_feed' });
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('renders a Blocked chip in the rail when a stage save returns an ERROR finding', async () => {
        const fixture = create(
            { name: 'x', stage: 'parsing' },
            {
                read: () =>
                    of({
                        type: 'pipeline',
                        name: 'x',
                        path: 'p',
                        config: { name: 'x', parsing: { frontend: 'delimited' } },
                    }),
                patch: () =>
                    of({
                        type: 'pipeline',
                        written: true,
                        path: 'p',
                        name: 'x',
                        bytes: 1,
                        overwritten: true,
                        findings: [{ severity: 'ERROR', fieldPath: 'parsing.frontend', message: 'bad parser' }],
                    }),
            },
        );
        const state = fixture.debugElement.injector.get(OnboardingStateService);
        state.activeStageId.set('parsing');
        state.saveBlock({ parsing: { frontend: 'nope' } }).subscribe();
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('Blocked');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('offers Export config and downloads the assembled bundle', () => {
        const fixture = create({ name: 'orders_feed' });
        expect(fixture.nativeElement.textContent).toContain('Export config');
        const transfer = fixture.debugElement.injector.get(StreamTransferService);
        fixture.componentInstance.exportConfig();
        expect(transfer.buildExport).toHaveBeenCalledWith('orders_feed', 'stream', { name: 'orders_feed' });
        expect(transfer.download).toHaveBeenCalled();
        expect(fixture.componentInstance.exporting()).toBe(false);
    });

    /** An export carries the SERVER-held config, so with unsaved stage edits on screen it would ship
     *  the last saved state under the guise of "export what I'm looking at". Refuse instead. */
    it('refuses to export an in-flight draft rather than shipping the last saved state', () => {
        const fixture = create({ name: 'orders_feed' });
        const state = fixture.debugElement.injector.get(OnboardingStateService);
        state.registerDirtyCheck(() => true);
        const transfer = fixture.debugElement.injector.get(StreamTransferService);
        fixture.componentInstance.exportConfig();

        expect(transfer.buildExport).not.toHaveBeenCalled();
        expect(transfer.download).not.toHaveBeenCalled();
        expect(TOASTR.warning).toHaveBeenCalledWith(expect.stringContaining('Save this stage'));
    });

    it('names an unreadable satellite instead of shipping a silently partial export', () => {
        const fixture = create(
            { name: 'orders_feed' },
            {},
            {
                buildExport: vi.fn(() =>
                    of({
                        bundle: exportedBundle('orders_feed'),
                        missing: ['schema "orders_feed_schema"'],
                    }),
                ),
            },
        );
        const transfer = fixture.debugElement.injector.get(StreamTransferService);
        fixture.componentInstance.exportConfig();
        expect(transfer.download).toHaveBeenCalled(); // still downloads…
        expect(TOASTR.warning).toHaveBeenCalledWith(
            expect.stringContaining('orders_feed_schema'), // …but says what is missing
        );
    });
});
