import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { LensService } from '@inspecto/core/api';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { LinkAnalysisEntityTypesComponent } from './link-analysis-entity-types.component';
import { EntityTypeConfig, LinkAnalysisLimits, LinkAnalysisSettingsService } from './link-analysis-settings.service';

const type = (id: string): EntityTypeConfig => ({
    id,
    label: id.toUpperCase(),
    normaliser: 'upper-trim',
    masked: false,
    classifications: [id.toUpperCase()],
});
const SERVED: LinkAnalysisLimits = {
    projectionNodeCap: null,
    analysisNodeCap: null,
    suspicionNodeCap: null,
    maskingMode: 'none',
    entityTypes: null,
    entityTypesInForce: [type('account'), type('device')],
};

function make(opts: { canEdit?: boolean; confirm?: boolean; del?: ReturnType<typeof vi.fn> } = {}) {
    const del = opts.del ?? vi.fn(() => of(SERVED));
    TestBed.configureTestingModule({
        imports: [LinkAnalysisEntityTypesComponent],
        providers: [
            provideNoopAnimations(),
            {
                provide: LinkAnalysisSettingsService,
                useValue: { limits: signal(SERVED), get: vi.fn(() => of(SERVED)), deleteEntityType: del },
            },
            { provide: LensService, useValue: { canAuthorWorkbench: () => opts.canEdit ?? true } },
            {
                provide: InspectoConfirmService,
                useValue: { confirmDestructive: vi.fn(async () => opts.confirm ?? true) },
            },
        ],
    });
    const fixture = TestBed.createComponent(LinkAnalysisEntityTypesComponent);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, del };
}

describe('LinkAnalysisEntityTypesComponent (⚙ Settings → Entity Types, sweep S3)', () => {
    it('lists the types in force and deletes one through the delete route (LA-ENTITY-TYPE-RETIRE-1)', async () => {
        const { fixture, el, del } = make();
        expect(el.textContent).toContain('ACCOUNT');
        expect(el.textContent).toContain('DEVICE');
        const button = el.querySelector('[data-testid="la-entity-type-delete-device"]') as HTMLButtonElement;
        expect(button.textContent).toContain('Delete');
        expect(button.getAttribute('aria-label')).toBe('Delete the Entity Type DEVICE');
        await fixture.componentInstance.remove('device', 'DEVICE');
        expect(del).toHaveBeenCalledWith('device');
        await expectNoA11yViolations(el);
    });

    it("a declined confirm deletes nothing; a 409 shows the server's reason", async () => {
        const declined = make({ confirm: false });
        await declined.fixture.componentInstance.remove('device', 'DEVICE');
        expect(declined.del).not.toHaveBeenCalled();
        TestBed.resetTestingModule();
        const reason = "Entity Type 'account' is still used by Dataset column calls_ds.acct";
        const refused = make({
            del: vi.fn(() =>
                throwError(
                    () =>
                        new HttpErrorResponse({ status: 409, error: { error: { code: 'CONFLICT', message: reason } } }),
                ),
            ),
        });
        await refused.fixture.componentInstance.remove('account', 'ACCOUNT');
        refused.fixture.detectChanges();
        expect(refused.el.querySelector('inspecto-alert')?.textContent).toContain('calls_ds.acct');
    });

    it('says how values are compared in plain words, never the normaliser id (T-190)', () => {
        const { el } = make();
        expect(el.textContent).toContain('Upper case, spaces trimmed');
        expect(el.textContent).not.toContain('upper-trim');
    });

    it('without the right to change settings there is no New or Delete', () => {
        const { el } = make({ canEdit: false });
        expect(el.querySelector('[data-testid="la-entity-type-new"]')).toBeNull();
        expect(el.querySelector('[data-testid^="la-entity-type-delete-"]')).toBeNull();
    });
});
