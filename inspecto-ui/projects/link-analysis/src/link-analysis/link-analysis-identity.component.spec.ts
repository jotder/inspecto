import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { IdentityGroupIndex, IdentityImportResult, InvService } from '@inspecto/link-analysis/api/inv.service';
import { LensService } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { DatasetRowsService } from '@inspecto/core/viz/dataset-rows.service';
import { LA_DATASETS } from '@inspecto/link-analysis/la-host';
import { importFieldNames, LinkAnalysisIdentityComponent } from './link-analysis-identity.component';

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

function create(overrides: Partial<Record<keyof InvService, unknown>> = {}, canManage = true) {
    const inv = {
        listIdentityGroups: vi.fn(() => of(HEAD)),
        importIdentities: vi.fn(() => of(IMPORTED)),
        ...overrides,
    };
    TestBed.configureTestingModule({
        providers: [
            provideNoopAnimations(),
            { provide: InvService, useValue: inv },
            { provide: LensService, useValue: { canManageIncidents: signal(canManage) } },
            {
                provide: LA_DATASETS,
                useValue: { list: () => of([{ id: 'map', name: 'SIM map', sourceName: 'map' }]), get: () => of({}) },
            },
            { provide: DatasetRowsService, useValue: { columns: vi.fn(async () => [{ name: 'msisdn' }, { name: 'imsi' }]) } },
        ],
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

    it('without the Incident management capability there is no import form, no read, and the reason shows', async () => {
        const { f, el, inv } = create({}, false);
        await f.whenStable();
        f.detectChanges();
        expect(inv.listIdentityGroups).not.toHaveBeenCalled();
        expect(el.querySelector('form')).toBeNull();
        expect(el.querySelector('[data-testid="identity-read-only"]')?.textContent).toContain(
            'Incident management capability',
        );
        await expectNoA11yViolations(el);
    });

    it('with the capability the import form is present and the log is read', async () => {
        const { f, el, inv } = create({}, true);
        await f.whenStable();
        f.detectChanges();
        expect(inv.listIdentityGroups).toHaveBeenCalled();
        expect(el.querySelector('form')).not.toBeNull();
        expect(el.querySelector('[data-testid="identity-read-only"]')).toBeNull();
    });

    it('says the chain did NOT verify on a 500 and shows no stale head hash', async () => {
        const { f, c, el, inv } = create();
        await f.whenStable();
        (inv.listIdentityGroups as ReturnType<typeof vi.fn>).mockReturnValue(
            throwError(
                () => new HttpErrorResponse({ status: 500, error: { error: 'identity fact log is broken at seq 3' } }),
            ),
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

    it('the head the panel shows moves to the new head after a successful import, without Refresh', async () => {
        const moved: IdentityGroupIndex = { groups: [], atSeq: 7, headSeq: 7, headHash: 'sha256:def456' };
        const list = vi.fn().mockReturnValueOnce(of(HEAD)).mockReturnValue(of(moved));
        const { f, c, el } = create({ listIdentityGroups: list });
        await f.whenStable();
        f.detectChanges();
        expect(el.querySelector('[aria-label="Identity Fact log head"]')!.textContent).toContain('sha256:abc123');
        c.dataset.setValue('map');
        c.aCol.setValue('msisdn');
        c.bCol.setValue('imsi');
        c.reason.setValue('SIM swap file');
        await c.import();
        f.detectChanges();
        const head = el.querySelector('[aria-label="Identity Fact log head"]')!.textContent;
        expect(head).toContain('sha256:def456');
        expect(head).not.toContain('sha256:abc123');
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

    it('names a missing mapping Dataset (404) without calling it an Entity List', async () => {
        const { f, c, el } = create({
            importIdentities: vi.fn(() =>
                throwError(() => new HttpErrorResponse({ status: 404, error: { error: "no dataset 'nope'" } })),
            ),
        });
        c.dataset.setValue('nope');
        c.aCol.setValue('x');
        c.bCol.setValue('y');
        c.reason.setValue('r');
        await c.import();
        f.detectChanges();
        expect(el.textContent).toContain("no dataset 'nope'");
        expect(el.textContent).not.toContain('Entity List');
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
        expect(el.textContent).toContain('"Column A" must be a column');
        expect(el.textContent).not.toContain('aCol');
    });

    it('offers the Datasets and the picked one’s columns as pickers, clearing stale column picks', async () => {
        const { f, c, el } = create();
        await f.whenStable();
        f.detectChanges();
        expect(el.querySelectorAll('mat-select').length).toBe(3);
        expect(c.datasets().map((d) => d.id)).toEqual(['map']);
        c.dataset.setValue('map');
        await f.whenStable();
        expect(c.columns()).toEqual(['msisdn', 'imsi']);
        c.aCol.setValue('msisdn');
        c.dataset.setValue('other');
        await f.whenStable();
        expect(c.aCol.value).toBe('');
        expect(c.columns()).toEqual([]);
    });

    it('names the form fields, not the wire names, in a refusal', () => {
        expect(importFieldNames("'bCol' and aType are unknown")).toBe('"Column B" and "Type A" are unknown');
    });
});
