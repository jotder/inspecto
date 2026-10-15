import { AbstractControl, FormGroup } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ToastrService } from 'ngx-toastr';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { LensService } from 'app/inspecto/api';
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

/** Every numeric LIMIT of the old document: edited ONLY in the limits sections now (LA-CONFIG-LIMITS-1 CL-17). */
const RETIRED_TOP = [
    'projectionNodeCap',
    'analysisNodeCap',
    'suspicionNodeCap',
    'fourEyesBudgetAbove',
    'fourEyesFanOutAbove',
    'mergedDistinctCap',
    'seedByDistinctCap',
    'maxSetBytes',
    'maxInvestigationBytes',
    'hubThreshold',
    'drafts',
];

function setup(opts: { served?: Partial<LinkAnalysisLimits>; save?: () => unknown; canEdit?: boolean } = {}) {
    const served = { ...SERVED, ...opts.served };
    const api = {
        get: vi.fn(() => of(served)),
        save: vi.fn(opts.save ?? ((b: LinkAnalysisLimits) => of({ ...served, ...b }))),
    };
    const toastr = { success: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
        imports: [LinkAnalysisSettingsComponent],
        providers: [
            provideNoopAnimations(),
            { provide: LinkAnalysisSettingsService, useValue: api },
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
    const body = (call = 0) => api.save.mock.calls[call][0] as unknown as Record<string, unknown>;
    return { fixture, el, c: fixture.componentInstance, api, toastr, submit, body };
}

describe('LinkAnalysisSettingsComponent', () => {
    const ET = [{ id: 'msisdn', label: 'Subscriber', normaliser: 'msisdn' as never, masked: true, classifications: ['PII'] }];
    /** A document with EVERY limit stated, beside the non-limit keys this form edits. */
    const FULL: Partial<LinkAnalysisLimits> = {
        maskingMode: 'all',
        maskingModeInForce: 'all',
        entityTypes: ET,
        entityTypesInForce: ET,
        hubThreshold: 250,
        hubThresholdInForce: 250,
        seedByDistinctCap: 700,
        maxSetBytes: 1048576,
        maxInvestigationBytes: 1073741824,
        index: { enabled: true, maxDiskBytes: 1000, keepVersions: 5, maxAgeHours: 48 },
        indexInForce: { enabled: true, maxDiskBytes: 1000, keepVersions: 5, maxAgeHours: 48 },
        graphRun: { maxNodes: 900, maxEdges: null, timeoutMs: 4000, maxResultItems: 55, propagatedRiskWeights: [1, 0.5] },
        propagatedRiskWeightsInForce: [1, 0.5],
        graphRunInForce: { maxResultItems: 55 },
        drafts: { maxOpen: 9, hibernateAfterMinutes: 30, expireAfterDays: 14 },
        embed: { defaultTtlHours: 48, maxTtlHours: 100, refuseClassifications: ['PII', 'SECRET'] },
        embedInForce: { defaultTtlHours: 48, maxTtlHours: 100, refuseClassifications: ['PII', 'SECRET'] },
        investigationStoreInForce: 'db',
    };

    it('shows masking in force with each mode explained, the Entity Types, the store and the link index switch (a11y)', async () => {
        const { el, c } = setup({ served: FULL });
        expect(c.form.controls.maskingMode.value).toBe('all');
        expect(el.textContent).toContain('In force: all.');
        expect(el.textContent).toContain('raw value stays on the server');
        expect(el.textContent).toContain('Subscriber');
        expect(el.textContent).toContain('Investigation store: PostgreSQL');
        expect(el.textContent).toContain('Serve reads from the link index when a fresh one exists');
        expect(c.form.controls.graphRun.controls.propagatedRiskWeights.value).toBe('1, 0.5');
        await expectNoA11yViolations(el);
    });

    it('says filesystem for fs', () => {
        const { el } = setup({ served: { investigationStoreInForce: 'fs' } });
        expect(el.textContent).toContain('Investigation store: filesystem');
    });

    it('has no field for any numeric limit: each is edited in ONE place, the limits sections (CL-17)', () => {
        const { el } = setup({ served: FULL });
        const form = el.querySelector('form')!;
        expect(form.querySelectorAll('input[type="number"]').length).toBe(0);
        for (const gone of [
            'Four-eyes',
            'Hub threshold',
            'Merged expand',
            'Seed by Entity List',
            'Working Set size limit',
            'Investigation total size budget',
            'Index build workers',
            'Index versions kept',
            'Index disk budget',
            'default node budget',
            'worker threads',
            'items per result list',
            'Drafts',
            'link lifetime',
        ])
            expect(form.textContent, gone).not.toContain(gone);
    });

    it('a save sends the non-limit keys only - never a limit, even one the server states (CL-17)', () => {
        const { api, toastr, submit, body } = setup({ served: FULL });
        submit();
        expect(api.save).toHaveBeenCalledTimes(1);
        expect(body()).toEqual({
            maskingMode: 'all',
            entityTypes: ET,
            index: { enabled: true },
            graphRun: { propagatedRiskWeights: [1, 0.5] },
            embed: { refuseClassifications: ['PII', 'SECRET'], frameAncestors: null },
        });
        for (const k of RETIRED_TOP) expect(body(), k).not.toHaveProperty(k);
        expect(toastr.success).toHaveBeenCalled();
    });

    it('edits masking, the index switch and the risk weights', () => {
        const { c, submit, body } = setup({ served: FULL });
        c.form.controls.maskingMode.setValue('none');
        c.form.controls.index.controls.enabled.setValue(false);
        c.form.controls.graphRun.controls.propagatedRiskWeights.setValue('1, 0.8, 0.2');
        submit();
        expect(body()['maskingMode']).toBe('none');
        expect(body()['index']).toEqual({ enabled: false });
        expect(body()['graphRun']).toEqual({ propagatedRiskWeights: [1, 0.8, 0.2] });
    });

    it('blank masking sends null (inherit) and blocks with nothing stated are left out', () => {
        const { c, submit, body } = setup({ served: { maskingMode: 'all' } });
        c.form.controls.maskingMode.setValue(null);
        submit();
        expect(body()['maskingMode']).toBeNull();
        expect(body()).not.toHaveProperty('index');
        expect(body()).not.toHaveProperty('graphRun');
        expect(body()).not.toHaveProperty('embed');
    });

    it.each(['1.5', '-0.1', 'a', '1,2', '0.1,0.2,0.3,0.4,0.5,0.6,0.7'])('refuses risk weights "%s" client-side', (bad) => {
        const { c, api, submit } = setup();
        c.form.controls.graphRun.controls.propagatedRiskWeights.setValue(bad);
        submit();
        expect(api.save).not.toHaveBeenCalled();
    });

    it("shows a 422 inline in the server's words and a 503 as a writes-disabled notice", async () => {
        const { el, api, submit } = setup({
            save: () =>
                throwError(
                    () => new HttpErrorResponse({ status: 422, error: { error: { message: "masking_mode must be one of typed, all, none" } } }),
                ),
        });
        submit();
        expect(el.textContent).toContain('masking_mode must be one of typed, all, none');
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
                throwError(() => new HttpErrorResponse({ status: 422, error: { error: { message: 'graphRun.propagatedRiskWeights must be a list' } } })),
        });
        submit();
        expect(el.querySelector('[role="alert"]')?.textContent).toContain('graphRun.propagatedRiskWeights must be a list');
        await expectNoA11yViolations(el);
    });

    describe('embed links (LA-EMBED-VIEW-1)', () => {
        const EMBED: Partial<LinkAnalysisLimits> = {
            embed: { defaultTtlHours: 48, maxTtlHours: 100, refuseClassifications: ['PII', 'SECRET'] },
            embedInForce: { defaultTtlHours: 48, maxTtlHours: 100, refuseClassifications: ['PII', 'SECRET'] },
        };

        it('shows the classifications in force, says the feature is off unless the host enables it, and fills the form (a11y)', async () => {
            const { el, c } = setup({ served: EMBED });
            expect(el.textContent).toContain('OFF unless the host runs with');
            expect(el.textContent).toContain('-Dla.embed.enabled=true');
            expect(el.textContent).toContain('In force: PII, SECRET.');
            expect(c.form.controls.embed.controls.refuseClassifications.value).toBe('PII, SECRET');
            await expectNoA11yViolations(el);
        });

        it('saves the classes as a list without the lifetimes, and leaves the block out when both lists are blank', () => {
            const { c, submit, body } = setup({ served: EMBED });
            c.form.controls.embed.patchValue({ refuseClassifications: ' pii ,, Secret ' });
            submit();
            expect(body()['embed']).toEqual({ refuseClassifications: ['pii', 'Secret'], frameAncestors: null });
            c.form.controls.embed.patchValue({ refuseClassifications: '' });
            submit();
            expect(body(1)).not.toHaveProperty('embed');
        });

        it('sends the sites allowed to frame the viewer as a list, and refuses anything that is not an https origin', () => {
            const { c, submit, body } = setup({ served: EMBED });
            c.form.controls.embed.patchValue({ frameAncestors: ' https://portal.example.com , https://b.example:8443 ' });
            submit();
            expect((body()['embed'] as { frameAncestors: string[] }).frameAncestors).toEqual([
                'https://portal.example.com',
                'https://b.example:8443',
            ]);
            for (const bad of ['*', 'http://portal.example.com', 'https://a.example/path', 'https://*.example.com']) {
                c.form.controls.embed.patchValue({ frameAncestors: bad });
                expect(c.form.controls.embed.controls.frameAncestors.invalid, bad).toBe(true);
            }
            c.form.controls.embed.patchValue({ frameAncestors: 'http://localhost:4200' });
            expect(c.form.controls.embed.controls.frameAncestors.invalid).toBe(false);
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
    /** maskingMode, index.enabled, embed.refuseClassifications, embed.frameAncestors, graphRun.propagatedRiskWeights. */
    const CONTROL_COUNT = 5;

    it('read only: EVERY control is disabled, Save is gone, the reason is described, and save() cannot fire (a11y)', async () => {
        const { el, c, api, fixture } = setup({ canEdit: false, served: FULL });
        const all = leaves(c);
        expect(all.length).toBe(CONTROL_COUNT);
        expect(all.filter((x) => x.enabled).map((x) => x.path)).toEqual([]);
        const dom = Array.from(el.querySelector('form')!.querySelectorAll<HTMLElement>('input, textarea, select, mat-select'));
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
