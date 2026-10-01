import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ToastrService } from 'ngx-toastr';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { LensService } from 'app/inspecto/api';
import { LinkAnalysisLimits, LinkAnalysisSettingsService } from 'app/inspecto/api/link-analysis-settings.service';
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

function setup(opts: { served?: Partial<LinkAnalysisLimits>; save?: () => unknown; canEdit?: boolean } = {}) {
    const served = { ...SERVED, ...opts.served };
    const api = {
        get: vi.fn(() => of(served)),
        save: vi.fn(opts.save ?? ((b: LinkAnalysisLimits) => of({ ...b, mergedDistinctCapInForce: 20000 }))),
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

    it('is read only without the authoring capability', () => {
        const { el } = setup({ canEdit: false });
        expect(el.querySelector('button[type="submit"]')).toBeNull();
        expect(el.textContent).toContain('Read only');
    });
});
