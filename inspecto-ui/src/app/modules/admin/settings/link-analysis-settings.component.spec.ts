import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ToastrService } from 'ngx-toastr';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { LensService } from 'app/inspecto/api';
import { GraphRunsService } from '@inspecto/link-analysis/api/graph-runs.service';
import {
    LinkAnalysisLimits,
    LinkAnalysisSettingsService,
} from '@inspecto/link-analysis/link-analysis/link-analysis-settings.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { LinkAnalysisSettingsComponent } from './link-analysis-settings.component';

const SERVED: LinkAnalysisLimits = {
    projectionNodeCap: 800,
    analysisNodeCap: null,
    suspicionNodeCap: null,
    maskingMode: 'typed',
    fourEyesBudgetAbove: 50,
    fourEyesFanOutAbove: null,
    entityTypes: null,
    entityTypesInForce: [],
    mergedDistinctCap: null,
    mergedDistinctCapInForce: 20000,
};

function setup(opts: { served?: Partial<LinkAnalysisLimits>; save?: () => unknown; canEdit?: boolean; catalogueFails?: boolean } = {}) {
    const served = { ...SERVED, ...opts.served };
    const api = {
        get: vi.fn(() => of(served)),
        save: vi.fn(opts.save ?? ((b: LinkAnalysisLimits) => of({ ...b, mergedDistinctCapInForce: 20000 }))),
    };
    const runs = {
        algorithms: vi.fn(() =>
            opts.catalogueFails
                ? throwError(() => new HttpErrorResponse({ status: 503 }))
                : of({
                      defaults: { maxNodes: 50000, maxEdges: 500000, timeoutMs: 30000, clamped: false },
                      ceilings: { maxNodes: 500000, maxEdges: 5000000, timeoutMs: 300000 },
                  }),
        ),
    };
    const toastr = { success: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
        imports: [LinkAnalysisSettingsComponent],
        providers: [
            provideNoopAnimations(),
            { provide: LinkAnalysisSettingsService, useValue: api },
            { provide: GraphRunsService, useValue: runs },
            { provide: ToastrService, useValue: toastr },
            { provide: LensService, useValue: { canAuthorWorkbench: () => opts.canEdit !== false } },
        ],
    });
    const fixture = TestBed.createComponent(LinkAnalysisSettingsComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const submit = () => {
        el.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
        fixture.detectChanges();
    };
    return { fixture, el, c: fixture.componentInstance, api, toastr, submit };
}

describe('LinkAnalysisSettingsComponent', () => {
    it('shows the four-eyes thresholds and the merged cap in force; hides seedBy when not served (a11y)', async () => {
        const { el, c } = setup();
        expect(c.form.controls.fourEyesBudgetAbove.value).toBe(50);
        expect(el.textContent).toContain('Four-eyes: expand budget above');
        expect(el.textContent).toContain('Four-eyes: fan-out above');
        expect(el.textContent).toContain('In force: 20000');
        expect(el.textContent).not.toContain('Seed by Entity List');
        await expectNoA11yViolations(el);
    });

    it('shows seedByDistinctCap only when the server reports it', () => {
        const { el } = setup({ served: { seedByDistinctCap: null, seedByDistinctCapInForce: 5000 } });
        expect(el.textContent).toContain('Seed by Entity List');
        expect(el.textContent).toContain('In force: 5000');
    });

    it('saves the edited keys and sends every other stated key back unchanged (the PUT replaces)', () => {
        const { c, api, toastr, submit } = setup();
        c.form.controls.fourEyesFanOutAbove.setValue(200);
        c.form.controls.mergedDistinctCap.setValue(15000);
        submit();
        expect(api.save).toHaveBeenCalledWith({
            projectionNodeCap: 800,
            analysisNodeCap: null,
            suspicionNodeCap: null,
            maskingMode: 'typed',
            fourEyesBudgetAbove: 50,
            fourEyesFanOutAbove: 200,
            entityTypes: null,
            mergedDistinctCap: 15000,
        });
        expect(toastr.success).toHaveBeenCalled();
    });

    it('sends the graph-run knobs back untouched: the PUT replaces, so a save must not drop what it has no field for', () => {
        const graphRun = { maxNodes: 900, maxEdges: null, timeoutMs: 4000, threads: 3, queue: null };
        const { api, submit } = setup({ served: { graphRun } });
        submit();
        expect(api.save).toHaveBeenCalledWith(expect.objectContaining({ graphRun }));
    });

    it('sends the index knobs back untouched, an explicit enabled=false included: the PUT replaces', () => {
        const index = { enabled: false, maxDiskBytes: 5000000000, keepVersions: 3, threads: null, queue: null };
        const { api, submit } = setup({ served: { index } });
        submit();
        expect(api.save).toHaveBeenCalledWith(expect.objectContaining({ index }));
    });

    it("shows a 422 inline in the server's words and a 503 as a writes-disabled notice", () => {
        const { el, api, submit } = setup({
            save: () =>
                throwError(
                    () =>
                        new HttpErrorResponse({
                            status: 422,
                            error: { error: { message: 'mergedDistinctCap must be 1..100000, got 0' } },
                        }),
                ),
        });
        submit();
        expect(el.textContent).toContain('mergedDistinctCap must be 1..100000, got 0');
        api.save.mockImplementation(() =>
            throwError(() => new HttpErrorResponse({ status: 503, error: { error: { message: 'read-only' } } })),
        );
        submit();
        expect(el.textContent).toContain('Changes cannot be saved here');
    });

    it('refuses an out-of-range value inline without saving', () => {
        const { c, api, submit } = setup();
        c.form.controls.mergedDistinctCap.setValue(0);
        submit();
        expect(api.save).not.toHaveBeenCalled();
        expect(c.form.controls.mergedDistinctCap.touched).toBe(true);
    });

    it('shows the Drafts fields with the values in force and round-trips stated keys', () => {
        const { el, c, api, submit } = setup({
            served: {
                drafts: { maxOpen: 10 },
                draftsInForce: { maxOpen: 10, hibernateAfterMinutes: 60, expireAfterDays: 30 },
            },
        });
        expect(el.textContent).toContain('Drafts: open per Space');
        expect(el.textContent).toContain('In force: 60');
        expect(c.form.controls.drafts.controls.maxOpen.value).toBe(10);
        c.form.controls.drafts.controls.expireAfterDays.setValue(45);
        submit();
        expect(api.save).toHaveBeenCalledWith(
            expect.objectContaining({ drafts: { maxOpen: 10, expireAfterDays: 45 } }),
        );
    });

    it('omits the drafts block when every key is blank', () => {
        const { api, submit } = setup();
        submit();
        expect(api.save.mock.calls[0][0]).not.toHaveProperty('drafts');
    });

    it('refuses an out-of-range Drafts value and an expiry not longer than hibernation, client-side', () => {
        const { c, el, api, fixture, submit } = setup();
        c.form.controls.drafts.controls.maxOpen.setValue(1001);
        submit();
        expect(api.save).not.toHaveBeenCalled();
        c.form.controls.drafts.controls.maxOpen.setValue(null);
        c.form.controls.drafts.controls.hibernateAfterMinutes.setValue(10080);
        c.form.controls.drafts.controls.expireAfterDays.setValue(7);
        fixture.detectChanges();
        expect(el.querySelector('[role="alert"]')?.textContent).toContain('longer than the hibernation');
        submit();
        expect(api.save).not.toHaveBeenCalled();
    });

    it('shows a Drafts 422 in the server words', () => {
        const { el, submit } = setup({
            save: () =>
                throwError(
                    () =>
                        new HttpErrorResponse({
                            status: 422,
                            error: { error: { message: 'drafts.expireAfterDays must be longer than hibernation' } },
                        }),
                ),
        });
        submit();
        expect(el.textContent).toContain('drafts.expireAfterDays must be longer than hibernation');
    });

    describe('Working Set size limit (maxSetBytes)', () => {
        const SET = { maxSetBytes: null, maxSetBytesInForce: 67108864 };

        it('is hidden when the server does not report the key', () => {
            expect(setup().el.textContent).not.toContain('Working Set size limit');
        });

        it('shows the value in force in MiB with the 413 hint (a11y)', async () => {
            const { el } = setup({ served: SET });
            expect(el.textContent).toContain('Working Set size limit (bytes)');
            expect(el.textContent).toContain('In force: 64 MiB (67108864 bytes)');
            expect(el.textContent).toContain('refused with 413');
            await expectNoA11yViolations(el);
        });

        it('saves an edited limit and a blank one (null = inherit), and a save never erases a stated one', () => {
            const { c, api, submit } = setup({ served: { maxSetBytes: 1048576, maxSetBytesInForce: 1048576 } });
            expect(c.form.controls.maxSetBytes.value).toBe(1048576);
            submit();
            expect(api.save).toHaveBeenLastCalledWith(expect.objectContaining({ maxSetBytes: 1048576 }));
            c.form.controls.maxSetBytes.setValue(2048);
            submit();
            expect(api.save).toHaveBeenLastCalledWith(expect.objectContaining({ maxSetBytes: 2048 }));
            c.form.controls.maxSetBytes.setValue(null);
            submit();
            expect(api.save).toHaveBeenLastCalledWith(expect.objectContaining({ maxSetBytes: null }));
        });

        it('does not send the key when the server did not report it', () => {
            const { api, submit } = setup();
            submit();
            expect(api.save.mock.calls[0][0]).not.toHaveProperty('maxSetBytes');
        });

        it.each([1023, 1073741825, 1.5])('refuses %s client-side, with the error tied to the field, and does not save', (bad) => {
            const { c, el, api, fixture, submit } = setup({ served: SET });
            c.form.controls.maxSetBytes.setValue(bad);
            submit();
            fixture.detectChanges();
            expect(api.save).not.toHaveBeenCalled();
            const input = el.querySelector<HTMLInputElement>('input[formcontrolname="maxSetBytes"]')!;
            const described = (input.getAttribute('aria-describedby') ?? '').split(' ');
            const error = el.querySelector('mat-error');
            expect(error?.textContent).toContain('from 1024 (1 KiB) to 1073741824 (1 GiB)');
            expect(described).toContain(error!.id);
        });

        it('accepts the bounds 1024 and 1073741824', () => {
            const { c, api, submit } = setup({ served: SET });
            c.form.controls.maxSetBytes.setValue(1024);
            submit();
            c.form.controls.maxSetBytes.setValue(1073741824);
            submit();
            expect(api.save).toHaveBeenCalledTimes(2);
        });

        it("shows the server's 422 in its words", () => {
            const msg = 'maxSetBytes must be 1024..1073741824 (1 KiB..1 GiB), got 5';
            const { el, submit } = setup({
                served: SET,
                save: () =>
                    throwError(
                        () => new HttpErrorResponse({ status: 422, error: { error: { message: msg } } }),
                    ),
            });
            submit();
            expect(el.textContent).toContain(msg);
        });
    });

    describe('Investigation total size budget (maxInvestigationBytes)', () => {
        const INV = { maxInvestigationBytes: null, maxInvestigationBytesInForce: 4294967296 };

        it('is hidden when the server does not report the key', () => {
            expect(setup().el.textContent).not.toContain('Investigation total size budget');
        });

        it('shows the value in force in GiB with the 413 hint (a11y)', async () => {
            const { el } = setup({ served: INV });
            expect(el.textContent).toContain('Investigation total size budget (bytes)');
            expect(el.textContent).toContain('In force: 4 GiB (4294967296 bytes)');
            expect(el.textContent).toContain('refused with 413');
            await expectNoA11yViolations(el);
        });

        it('saves an edited budget and a blank one (null = inherit), and a save never erases a stated one', () => {
            const { c, api, submit } = setup({ served: { maxInvestigationBytes: 8589934592, maxInvestigationBytesInForce: 8589934592 } });
            expect(c.form.controls.maxInvestigationBytes.value).toBe(8589934592);
            submit();
            expect(api.save).toHaveBeenLastCalledWith(expect.objectContaining({ maxInvestigationBytes: 8589934592 }));
            c.form.controls.maxInvestigationBytes.setValue(null);
            submit();
            expect(api.save).toHaveBeenLastCalledWith(expect.objectContaining({ maxInvestigationBytes: null }));
        });

        it('does not send the key when the server did not report it', () => {
            const { api, submit } = setup();
            submit();
            expect(api.save.mock.calls[0][0]).not.toHaveProperty('maxInvestigationBytes');
        });

        it.each([1048575, 1099511627777, 1.5])('refuses %s client-side and does not save', (bad) => {
            const { c, el, api, fixture, submit } = setup({ served: INV });
            c.form.controls.maxInvestigationBytes.setValue(bad);
            submit();
            fixture.detectChanges();
            expect(api.save).not.toHaveBeenCalled();
            expect(el.querySelector('mat-error')?.textContent).toContain('from 1048576 (1 MiB) to 1099511627776 (1 TiB)');
        });

        it('accepts the bounds 1048576 and 1099511627776', () => {
            const { c, api, submit } = setup({ served: INV });
            c.form.controls.maxInvestigationBytes.setValue(1048576);
            submit();
            c.form.controls.maxInvestigationBytes.setValue(1099511627776);
            submit();
            expect(api.save).toHaveBeenCalledTimes(2);
        });
    });

    describe('DR-U3 controls', () => {
        const ET = [{ id: 'msisdn', label: 'Subscriber', normaliser: 'msisdn' as never, masked: true, classifications: ['PII'] }];
        const FULL: Partial<LinkAnalysisLimits> = {
            maskingMode: 'all',
            maskingModeInForce: 'all',
            entityTypesInForce: ET,
            index: { enabled: true, maxDiskBytes: 1000, keepVersions: 5, threads: 2, queue: 8 },
            indexInForce: { enabled: true, maxDiskBytes: 1000, keepVersions: 5, threads: 2, queue: 8 },
            graphRun: { maxNodes: 900, maxEdges: null, timeoutMs: 4000, threads: 3, queue: 7, maxResultItems: 55 },
            investigationStoreInForce: 'db',
        };

        it('shows masking in force with each mode explained, the Entity Types, the store and the budgets with ceilings (a11y)', async () => {
            const { el, c } = setup({ served: FULL });
            expect(c.form.controls.maskingMode.value).toBe('all');
            expect(el.textContent).toContain('In force: all.');
            expect(el.textContent).toContain('raw value stays on the server');
            expect(el.textContent).toContain('Subscriber');
            expect(el.textContent).toContain('Investigation store: PostgreSQL');
            expect(el.textContent).toContain('In force: 50000; server ceiling: 500000');
            expect(el.textContent).toContain('Serve reads from the link index when a fresh one exists');
            await expectNoA11yViolations(el);
        });

        it('says filesystem for fs and falls back to dashes when the catalogue route does not answer', () => {
            const { el } = setup({ served: { investigationStoreInForce: 'fs' }, catalogueFails: true });
            expect(el.textContent).toContain('Investigation store: filesystem');
            expect(el.textContent).toContain('In force: -; server ceiling: -');
        });

        it('edits masking, index and graph-run keys and keeps the keys the form does not show', () => {
            const { c, api, submit } = setup({ served: FULL });
            c.form.controls.maskingMode.setValue('none');
            c.form.controls.index.controls.enabled.setValue(false);
            c.form.controls.index.controls.queue.setValue(null);
            c.form.controls.graphRun.controls.maxEdges.setValue(1234);
            submit();
            const body = api.save.mock.calls[0][0] as unknown as Record<string, unknown>;
            expect(body['maskingMode']).toBe('none');
            expect(body['index']).toEqual({ enabled: false, maxDiskBytes: 1000, keepVersions: 5, threads: 2, queue: null });
            expect(body['graphRun']).toEqual({ maxNodes: 900, maxEdges: 1234, timeoutMs: 4000, threads: 3, queue: 7, maxResultItems: 55 });
            expect(body['entityTypesInForce']).toBeUndefined();
            expect(body['indexInForce']).toBeUndefined();
            expect(body['investigationStoreInForce']).toBeUndefined();
        });

        it('blank masking sends null (inherit) and untouched blank blocks are omitted', () => {
            const { c, api, submit } = setup({ served: { maskingMode: 'all' } });
            c.form.controls.maskingMode.setValue(null);
            submit();
            const body = api.save.mock.calls[0][0] as unknown as Record<string, unknown>;
            expect(body['maskingMode']).toBeNull();
            expect(body).not.toHaveProperty('index');
            expect(body).not.toHaveProperty('graphRun');
        });

        it.each([
            ['index', 'threads', 65],
            ['index', 'queue', 1001],
            ['index', 'maxDiskBytes', -1],
            ['graphRun', 'maxNodes', 10000001],
            ['graphRun', 'timeoutMs', 0],
        ])('refuses %s.%s = %s client-side and does not save', (group, key, bad) => {
            const { c, api, submit } = setup();
            const g = c.form.controls[group as 'index' | 'graphRun'].controls as Record<string, { setValue(v: number): void }>;
            g[key].setValue(bad);
            submit();
            expect(api.save).not.toHaveBeenCalled();
        });

        it('accepts the index and graph-run bounds', () => {
            const { c, api, submit } = setup();
            c.form.controls.index.patchValue({ threads: 64, queue: 1000, maxDiskBytes: 0 });
            c.form.controls.graphRun.patchValue({ maxNodes: 10000000 });
            submit();
            expect(api.save).toHaveBeenCalledTimes(1);
        });

        it('disables the selects and shows no save button without the authoring capability', () => {
            const { c } = setup({ canEdit: false, served: FULL });
            expect(c.form.controls.maskingMode.disabled).toBe(true);
            expect(c.form.controls.index.controls.enabled.disabled).toBe(true);
        });
    });

    it('is read only without the authoring capability', () => {
        const { el } = setup({ canEdit: false });
        expect(el.querySelector('button[type="submit"]')).toBeNull();
        expect(el.textContent).toContain('Read only');
    });
});
