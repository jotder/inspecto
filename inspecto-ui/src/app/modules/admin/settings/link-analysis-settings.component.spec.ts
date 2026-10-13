import { AbstractControl, FormGroup } from '@angular/forms';
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

    it('hub threshold: shows the value in force, saves a whole number >= 1, refuses 0 (telecom S3, a11y)', async () => {
        const { el, c, api, submit } = setup({ served: { hubThreshold: null, hubThresholdInForce: 500 } });
        expect(el.textContent).toContain('Hub threshold (distinct contacts)');
        expect(el.textContent).toContain('In force: 500');
        await expectNoA11yViolations(el);

        c.form.controls.hubThreshold.setValue(0);
        submit();
        expect(api.save).not.toHaveBeenCalled();
        expect(el.textContent).toContain('A whole number from 1 to');

        c.form.controls.hubThreshold.setValue(250);
        submit();
        expect(api.save).toHaveBeenCalledWith(expect.objectContaining({ hubThreshold: 250 }));
        expect(api.save.mock.calls[0][0]).not.toHaveProperty('hubThresholdInForce');
    });

    it('hub threshold is read only without the authoring capability', () => {
        const { el } = setup({ served: { hubThreshold: 300, hubThresholdInForce: 300 }, canEdit: false });
        const field = Array.from(el.querySelectorAll('mat-form-field')).find((f) =>
            f.textContent?.includes('Hub threshold'),
        );
        expect(field?.querySelector('input')?.readOnly).toBe(true);
        expect(el.textContent).toContain('needs the Workbench authoring capability');
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
        expect(api.save).toHaveBeenCalledWith(expect.objectContaining({ graphRun: { ...graphRun, maxResultItems: null, propagatedRiskWeights: null } }));
    });

    it('sends the index knobs back untouched, an explicit enabled=false included: the PUT replaces', () => {
        const index = { enabled: false, maxDiskBytes: 5000000000, keepVersions: 3, threads: null, queue: null, maxAgeHours: null };
        const { api, submit } = setup({ served: { index } });
        submit();
        expect(api.save).toHaveBeenCalledWith(expect.objectContaining({ index }));
    });

    it("shows a 422 inline in the server's words and a 503 as a writes-disabled notice", async () => {
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
        await expectNoA11yViolations(el); // axe, LA-A11Y-AUDIT-1: the writes-disabled notice
    });

    it('the 422 shown inline is announced and axe-clean (LA-A11Y-AUDIT-1)', async () => {
        const { el, submit } = setup({
            save: () =>
                throwError(
                    () => new HttpErrorResponse({ status: 422, error: { error: { message: 'mergedDistinctCap must be 1..100000, got 0' } } }),
                ),
        });
        submit();
        expect(el.querySelector('[role="alert"]')?.textContent).toContain('mergedDistinctCap must be 1..100000');
        await expectNoA11yViolations(el);
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
            indexInForce: { enabled: true, maxDiskBytes: 1000, keepVersions: 5, threads: 2, queue: 8, maxAgeHours: 24 },
            graphRun: { maxNodes: 900, maxEdges: null, timeoutMs: 4000, threads: 3, queue: 7, maxResultItems: 55, propagatedRiskWeights: [1, 0.5] },
            propagatedRiskWeightsInForce: [1, 0.5],
            graphRunInForce: { threads: 3, queue: 7, maxResultItems: 55 },
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
            c.form.controls.index.controls.maxAgeHours.setValue(48);
            c.form.controls.graphRun.controls.maxEdges.setValue(1234);
            c.form.controls.graphRun.controls.threads.setValue(8);
            c.form.controls.graphRun.controls.maxResultItems.setValue(null);
            submit();
            const body = api.save.mock.calls[0][0] as unknown as Record<string, unknown>;
            expect(body['maskingMode']).toBe('none');
            expect(body['index']).toEqual({ enabled: false, maxDiskBytes: 1000, keepVersions: 5, threads: 2, queue: null, maxAgeHours: 48 });
            expect(body['graphRun']).toEqual({ maxNodes: 900, maxEdges: 1234, timeoutMs: 4000, threads: 8, queue: 7, maxResultItems: null, propagatedRiskWeights: [1, 0.5] });
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

        it('edits keepVersions and the risk weights, sends the full documents, and shows the in-force run values', () => {
            const { el, c, api, submit } = setup({ served: FULL });
            expect(c.form.controls.index.controls.keepVersions.value).toBe(5);
            expect(c.form.controls.graphRun.controls.propagatedRiskWeights.value).toBe('1, 0.5');
            expect(el.textContent).toContain('In force: 3. Runs executing at once');
            expect(el.textContent).toContain('In force: 55. Each list');
            c.form.controls.index.controls.keepVersions.setValue(7);
            c.form.controls.graphRun.controls.propagatedRiskWeights.setValue('1, 0.8, 0.2');
            submit();
            const body = api.save.mock.calls[0][0] as unknown as Record<string, Record<string, unknown>>;
            expect(body['index']['keepVersions']).toBe(7);
            expect(body['graphRun']['propagatedRiskWeights']).toEqual([1, 0.8, 0.2]);
        });

        it('a blank risk-weights field inherits (null)', () => {
            const { c, api, submit } = setup({ served: FULL });
            c.form.controls.graphRun.controls.propagatedRiskWeights.setValue('');
            submit();
            const body = api.save.mock.calls[0][0] as unknown as Record<string, Record<string, unknown>>;
            expect(body['graphRun']['propagatedRiskWeights']).toBeNull();
        });

        it.each(['1.5', '-0.1', 'a', '1,2', '0.1,0.2,0.3,0.4,0.5,0.6,0.7'])('refuses risk weights "%s" client-side', (bad) => {
            const { c, api, submit } = setup();
            c.form.controls.graphRun.controls.propagatedRiskWeights.setValue(bad);
            submit();
            expect(api.save).not.toHaveBeenCalled();
        });

        it.each([0, 101])('refuses index.keepVersions = %s client-side', (bad) => {
            const { c, api, submit } = setup();
            c.form.controls.index.controls.keepVersions.setValue(bad);
            submit();
            expect(api.save).not.toHaveBeenCalled();
        });

        it.each([
            ['index', 'threads', 65],
            ['index', 'queue', 1001],
            ['index', 'maxDiskBytes', -1],
            ['index', 'maxAgeHours', 0],
            ['index', 'maxAgeHours', 8761],
            ['graphRun', 'maxNodes', 10000001],
            ['graphRun', 'timeoutMs', 0],
            ['graphRun', 'threads', 0],
            ['graphRun', 'queue', 10000001],
            ['graphRun', 'maxResultItems', 0],
        ])('refuses %s.%s = %s client-side and does not save', (group, key, bad) => {
            const { c, api, submit } = setup();
            const g = c.form.controls[group as 'index' | 'graphRun'].controls as unknown as Record<string, { setValue(v: number): void }>;
            g[key].setValue(bad);
            submit();
            expect(api.save).not.toHaveBeenCalled();
        });

        it('accepts the index and graph-run bounds', () => {
            const { c, api, submit } = setup();
            c.form.controls.index.patchValue({ threads: 64, queue: 1000, maxDiskBytes: 0 });
            c.form.controls.graphRun.patchValue({ maxNodes: 10000000, threads: 1, queue: 10000000, maxResultItems: 1000000 });
            submit();
            expect(api.save).toHaveBeenCalledTimes(1);
        });

        it('disables the selects and shows no save button without the authoring capability', () => {
            const { c } = setup({ canEdit: false, served: FULL });
            expect(c.form.controls.maskingMode.disabled).toBe(true);
            expect(c.form.controls.index.controls.enabled.disabled).toBe(true);
        });
    });

    describe('embed links (LA-EMBED-VIEW-1)', () => {
        const EMBED: Partial<LinkAnalysisLimits> = {
            embed: { defaultTtlHours: 48, maxTtlHours: 100, refuseClassifications: ['PII', 'SECRET'] },
            embedInForce: { defaultTtlHours: 48, maxTtlHours: 100, refuseClassifications: ['PII', 'SECRET'] },
        };

        it('shows the values in force, says the feature is off unless the host enables it, and fills the form (a11y)', async () => {
            const { el, c } = setup({ served: EMBED });
            expect(el.textContent).toContain('OFF unless the host runs with');
            expect(el.textContent).toContain('-Dla.embed.enabled=true');
            expect(el.textContent).toContain('In force: 48 hours');
            expect(el.textContent).toContain('In force: 100 hours');
            expect(el.textContent).toContain('In force: PII, SECRET.');
            expect(c.form.controls.embed.controls.refuseClassifications.value).toBe('PII, SECRET');
            await expectNoA11yViolations(el);
        });

        it('saves the three knobs, the classes as a list, and omits the block when all are blank', () => {
            const { c, api, submit } = setup({ served: EMBED });
            c.form.controls.embed.patchValue({ defaultTtlHours: 24, maxTtlHours: 8760, refuseClassifications: ' pii ,, Secret ' });
            submit();
            expect((api.save.mock.calls[0][0] as unknown as Record<string, unknown>)['embed']).toEqual({
                defaultTtlHours: 24, maxTtlHours: 8760, refuseClassifications: ['pii', 'Secret'], frameAncestors: null,
            });
            c.form.controls.embed.patchValue({ defaultTtlHours: null, maxTtlHours: null, refuseClassifications: '' });
            submit();
            const body = api.save.mock.calls[1][0] as unknown as Record<string, unknown>;
            expect(body).not.toHaveProperty('embed');
            expect(body).not.toHaveProperty('embedInForce');
        });

        it('sends the sites allowed to frame the viewer as a list, and refuses anything that is not an https origin', () => {
            const { c, api, submit } = setup({ served: EMBED });
            c.form.controls.embed.patchValue({ frameAncestors: ' https://portal.example.com , https://b.example:8443 ' });
            submit();
            expect((api.save.mock.calls[0][0] as unknown as { embed: { frameAncestors: string[] } }).embed.frameAncestors).toEqual([
                'https://portal.example.com',
                'https://b.example:8443',
            ]);
            for (const bad of ['*', 'http://portal.example.com', 'https://a.example/path', 'https://*.example.com'])
                {
                    c.form.controls.embed.patchValue({ frameAncestors: bad });
                    expect(c.form.controls.embed.controls.frameAncestors.invalid, bad).toBe(true);
                }
            c.form.controls.embed.patchValue({ frameAncestors: 'http://localhost:4200' });
            expect(c.form.controls.embed.controls.frameAncestors.invalid).toBe(false);
        });

        it.each([
            ['defaultTtlHours', 0],
            ['defaultTtlHours', 8761],
            ['maxTtlHours', 0],
            ['maxTtlHours', 8761],
        ])('refuses embed.%s = %s client-side and does not save', (key, bad) => {
            const { c, api, submit } = setup();
            c.form.controls.embed.patchValue({ [key]: bad });
            submit();
            expect(api.save).not.toHaveBeenCalled();
        });

        it('refuses a default above the longest lifetime (a blank longest is the shipped 720), and accepts equal', () => {
            const { c, api, fixture, el, submit } = setup();
            c.form.controls.embed.patchValue({ defaultTtlHours: 721 });
            fixture.detectChanges();
            expect(el.textContent).toContain('must not exceed the longest lifetime');
            submit();
            expect(api.save).not.toHaveBeenCalled();
            c.form.controls.embed.patchValue({ defaultTtlHours: 100, maxTtlHours: 99 });
            submit();
            expect(api.save).not.toHaveBeenCalled();
            c.form.controls.embed.patchValue({ defaultTtlHours: 99 });
            submit();
            expect(api.save).toHaveBeenCalledTimes(1);
        });

        it('refuses more than 20 classifications or a name over 64 characters', () => {
            const { c, api, submit } = setup();
            c.form.controls.embed.patchValue({ refuseClassifications: Array.from({ length: 21 }, (_, i) => 'C' + i).join(',') });
            submit();
            c.form.controls.embed.patchValue({ refuseClassifications: 'X'.repeat(65) });
            submit();
            expect(api.save).not.toHaveBeenCalled();
        });
    });

    it('is read only without the authoring capability', () => {
        const { el } = setup({ canEdit: false });
        expect(el.querySelector('button[type="submit"]')).toBeNull();
        expect(el.textContent).toContain('Read only');
    });

    it('read only: no save button, and axe is clean with the embed block showing (LA-A11Y-AUDIT-1)', async () => {
        const { el } = setup({
            canEdit: false,
            served: {
                embed: { defaultTtlHours: 48, maxTtlHours: 100, refuseClassifications: ['PII'] },
                embedInForce: { defaultTtlHours: 48, maxTtlHours: 100, refuseClassifications: ['PII'] },
            },
        });
        // Finding (LA-A11Y-AUDIT-1): the knobs of the older blocks (e.g. hub threshold, threads, queue) stay ENABLED
        // read-only; only the newer selects and the save button are withheld. Not an axe rule; recorded in the audit.
        expect(el.querySelector('button[type="submit"]')).toBeNull();
        await expectNoA11yViolations(el);
    });
    /** Every leaf control of the form: a control added without the read-only gate changes this count and fails. */
    const leaves = (c: LinkAnalysisSettingsComponent) => {
        const out: { path: string; enabled: boolean }[] = [];
        const walk = (g: FormGroup, path: string) => {
            for (const [k, ctl] of Object.entries<AbstractControl>(g.controls)) {
                if (ctl instanceof FormGroup) walk(ctl, path + k + '.');
                else out.push({ path: path + k, enabled: ctl.enabled });
            }
        };
        walk(c.form, '');
        return out;
    };
    const CONTROL_COUNT = 28;

    it('read only: EVERY control is disabled, Save is gone, the reason is described, and save() cannot fire (a11y)', async () => {
        const { el, c, api, fixture } = setup({ canEdit: false });
        const all = leaves(c);
        expect(all.length).toBe(CONTROL_COUNT);
        expect(all.filter((x) => x.enabled).map((x) => x.path)).toEqual([]);
        const dom = Array.from(el.querySelectorAll<HTMLElement>('input, textarea, select, mat-select'));
        expect(dom.length).toBeGreaterThan(0);
        for (const d of dom) {
            const off = (d as HTMLInputElement).disabled || d.getAttribute('aria-disabled') === 'true';
            expect(off, d.outerHTML.slice(0, 80)).toBe(true);
        }
        expect(el.querySelector('button[type="submit"]')).toBeNull();
        const form = el.querySelector('form')!;
        const reason = form.getAttribute('aria-describedby')!;
        expect(el.querySelector('#' + reason)!.textContent).toContain('Workbench authoring capability');
        c.save();
        expect(api.save).not.toHaveBeenCalled();
        fixture.detectChanges();
        await expectNoA11yViolations(el);
    });

    it('writable: every control is enabled and Save is present', () => {
        const { el, c } = setup({ canEdit: true });
        const all = leaves(c);
        expect(all.length).toBe(CONTROL_COUNT);
        expect(all.filter((x) => !x.enabled)).toEqual([]);
        expect(el.querySelector('button[type="submit"]')).not.toBeNull();
    });
});
