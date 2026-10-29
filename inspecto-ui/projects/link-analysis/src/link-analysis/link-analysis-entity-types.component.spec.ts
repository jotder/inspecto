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

function make(opts: { canEdit?: boolean; confirm?: boolean; save?: ReturnType<typeof vi.fn> } = {}) {
    const save = opts.save ?? vi.fn((b: unknown) => of(b as LinkAnalysisLimits));
    TestBed.configureTestingModule({
        imports: [LinkAnalysisEntityTypesComponent],
        providers: [
            provideNoopAnimations(),
            {
                provide: LinkAnalysisSettingsService,
                useValue: { limits: signal(SERVED), get: vi.fn(() => of(SERVED)), save },
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
    return { fixture, el: fixture.nativeElement as HTMLElement, save };
}

describe('LinkAnalysisEntityTypesComponent (⚙ Settings → Entity Types, sweep S3)', () => {
    it('lists the types in force and retires one by stating the rest', async () => {
        const { fixture, el, save } = make();
        expect(el.textContent).toContain('ACCOUNT');
        expect(el.textContent).toContain('DEVICE');
        await fixture.componentInstance.retire('device', 'DEVICE');
        expect(save).toHaveBeenCalledTimes(1);
        const body = save.mock.calls[0][0] as { entityTypes: EntityTypeConfig[] };
        expect(body.entityTypes.map((t) => t.id)).toEqual(['account']);
        await expectNoA11yViolations(el);
    });

    it('a declined confirm writes nothing; a refused write says why', async () => {
        const declined = make({ confirm: false });
        await declined.fixture.componentInstance.retire('device', 'DEVICE');
        expect(declined.save).not.toHaveBeenCalled();
        TestBed.resetTestingModule();
        const refused = make({ save: vi.fn(() => throwError(() => new Error('in use'))) });
        await refused.fixture.componentInstance.retire('account', 'ACCOUNT');
        refused.fixture.detectChanges();
        expect(refused.fixture.componentInstance.error()).not.toBe('');
        expect(refused.el.querySelector('inspecto-alert')).not.toBeNull();
    });

    it('says how values are compared in plain words, never the normaliser id (T-190)', () => {
        const { el } = make();
        expect(el.textContent).toContain('Upper case, spaces trimmed');
        expect(el.textContent).not.toContain('upper-trim');
    });

    it('without the right to change settings there is no New or Retire', () => {
        const { el } = make({ canEdit: false });
        expect(el.querySelector('[data-testid="la-entity-type-new"]')).toBeNull();
        expect(el.querySelector('[data-testid^="la-entity-type-retire-"]')).toBeNull();
    });
});
