import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { IdentityGroupIndex, IdentityImportResult, InvService } from '@inspecto/link-analysis/api/inv.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { LinkAnalysisIdentityComponent } from './link-analysis-identity.component';

const HEAD: IdentityGroupIndex = { groups: [], atSeq: 4, headSeq: 4, headHash: 'sha256:abc123' };
const IMPORTED: IdentityImportResult = {
    imported: 3,
    skipped: { alreadyAsserted: 1, duplicate: 0, empty: 2, self: 0 },
    rowsRead: 6,
    truncated: false,
    dataset: 'map',
    fingerprint: 'f',
    atSeq: 7,
    headHash: 'sha256:def456',
};

function create(overrides: Partial<Record<keyof InvService, unknown>> = {}) {
    const inv = {
        listIdentityGroups: vi.fn(() => of(HEAD)),
        importIdentities: vi.fn(() => of(IMPORTED)),
        ...overrides,
    };
    TestBed.configureTestingModule({
        providers: [provideNoopAnimations(), { provide: InvService, useValue: inv }],
    });
    const f = TestBed.createComponent(LinkAnalysisIdentityComponent);
    f.detectChanges();
    return { f, c: f.componentInstance, el: f.nativeElement as HTMLElement, inv };
}

describe('LinkAnalysisIdentityComponent (DR-U8)', () => {
    it('shows the head hash and "Chain verified" when the head read succeeds', async () => {
        const { f, el, inv } = create();
        await f.whenStable();
        f.detectChanges();
        expect(inv.listIdentityGroups).toHaveBeenCalled();
        expect(el.textContent).toContain('Chain verified');
        expect(el.querySelector('[aria-label="Identity Fact log head"]')!.textContent).toContain('sha256:abc123');
        await expectNoA11yViolations(el);
    });

    it('says the chain did NOT verify on a 500 and shows no stale head hash', async () => {
        const { f, c, el, inv } = create();
        await f.whenStable();
        (inv.listIdentityGroups as ReturnType<typeof vi.fn>).mockReturnValue(
            throwError(() => new HttpErrorResponse({ status: 500, error: { error: 'identity fact log is broken at seq 3' } })),
        );
        await c.load();
        f.detectChanges();
        expect(el.textContent).toContain('Chain NOT verified');
        expect(el.textContent).toContain('broken at seq 3');
        expect(el.textContent).not.toContain('Chain verified');
        expect(el.textContent).not.toContain('sha256:abc123');
    });

    it('explains a 403 as a capability, not a broken chain', async () => {
        const { f, el } = create({
            listIdentityGroups: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 403, error: {} }))),
        });
        await f.whenStable();
        f.detectChanges();
        expect(el.textContent).toContain('Incident-management capability');
        expect(el.textContent).not.toContain('Chain NOT verified');
    });

    it('imports with only the filled optional fields, reports counts, then re-reads the head', async () => {
        const { f, c, el, inv } = create();
        await f.whenStable();
        c.dataset.setValue('map');
        c.aCol.setValue('msisdn');
        c.bCol.setValue('imsi');
        c.aType.setValue('msisdn');
        c.reason.setValue('SIM swap file');
        await c.import();
        f.detectChanges();
        expect(inv.importIdentities).toHaveBeenCalledWith({
            dataset: 'map',
            aCol: 'msisdn',
            bCol: 'imsi',
            aType: 'msisdn',
            reason: 'SIM swap file',
        });
        expect(inv.listIdentityGroups).toHaveBeenCalledTimes(2);
        expect(el.textContent).toContain('3 assertion(s) added from 6 row(s)');
        expect(el.textContent).toContain('1 already asserted');
    });

    it('will not import without a Dataset, both columns and a reason', async () => {
        const { c, inv } = create();
        await c.import();
        expect(inv.importIdentities).not.toHaveBeenCalled();
    });

    it('shows a refused import (422) in place', async () => {
        const { f, c, el } = create({
            importIdentities: vi.fn(() =>
                throwError(() => new HttpErrorResponse({ status: 422, error: { error: "'aCol' must be a column" } })),
            ),
        });
        c.dataset.setValue('map');
        c.aCol.setValue('x');
        c.bCol.setValue('y');
        c.reason.setValue('r');
        await c.import();
        f.detectChanges();
        expect(el.textContent).toContain("'aCol' must be a column");
    });
});
