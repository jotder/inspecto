import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { InvestigationReferences, InvService } from '@inspecto/link-analysis/api/inv.service';
import { LensService } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { LinkAnalysisReferencesComponent } from './link-analysis-references.component';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisReferencesComponent],
    template: `<inspecto-link-analysis-references investigationId="inv-1"></inspecto-link-analysis-references>`,
})
class Host {}

const ONE: InvestigationReferences = {
    investigationId: 'inv-1',
    count: 1,
    max: 200,
    note: 'pointers only — never fetched, never trusted, and they grant no access',
    references: [
        {
            seq: 1,
            key: 'k',
            system: 'jira',
            type: 'ticket',
            id: 'FRAUD-7',
            url: 'https://jira.example/FRAUD-7',
            label: 'Ring',
            addedBy: 'alice',
            trusted: false,
        },
    ],
};

function create(overrides: Partial<Record<keyof InvService, unknown>> = {}, canManage = true, role = 'lead') {
    const inv = {
        investigationMembers: vi.fn(() =>
            of({ investigationId: 'inv-1', owner: null, members: [], history: [], you: role }),
        ),
        investigationReferences: vi.fn(() => of(ONE)),
        addInvestigationReference: vi.fn(() => of(ONE)),
        ...overrides,
    };
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            provideNoopAnimations(),
            { provide: InvService, useValue: inv },
            { provide: LensService, useValue: { canManageIncidents: signal(canManage) } },
        ],
    });
    const f = TestBed.createComponent(Host);
    f.detectChanges();
    const c = f.debugElement.children[0].componentInstance as LinkAnalysisReferencesComponent;
    return { f, c, el: f.nativeElement as HTMLElement, inv };
}

describe('LinkAnalysisReferencesComponent', () => {
    it('lists the references with the never-trusted note, the url as TEXT not a link', async () => {
        const { f, c, el, inv } = create();
        await c.load();
        f.detectChanges();
        expect(inv.investigationReferences).toHaveBeenCalledWith('inv-1');
        const list = el.querySelector('[aria-label="References of this Investigation"]')!;
        expect(list.textContent).toContain('jira/ticket');
        expect(list.textContent).toContain('FRAUD-7');
        expect(list.textContent).toContain('https://jira.example/FRAUD-7');
        expect(list.querySelector('a')).toBeNull();
        expect(el.textContent).toContain('never trusted');
        await expectNoA11yViolations(el);
    });

    const addButton = (el: HTMLElement) =>
        Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Add reference')!;

    it('without the Incident management capability Add reference is disabled with the reason; with it, enabled', async () => {
        const ro = create({}, false);
        await ro.c.load();
        ro.c.system.setValue('jira');
        ro.c.type.setValue('ticket');
        ro.c.refId.setValue('X-1');
        ro.f.detectChanges();
        expect(addButton(ro.el).disabled).toBe(true);
        expect(ro.el.querySelector('[data-testid="references-read-only"]')?.textContent).toContain(
            'needs Incident management',
        );
        expect(addButton(ro.el).getAttribute('aria-describedby')).toBe('refs-read-only');
        await ro.c.add();
        expect(ro.inv.addInvestigationReference).not.toHaveBeenCalled();
        await expectNoA11yViolations(ro.el);
        TestBed.resetTestingModule();
        const rw = create({}, true);
        await rw.c.load();
        rw.c.system.setValue('jira');
        rw.c.type.setValue('ticket');
        rw.c.refId.setValue('X-1');
        rw.f.detectChanges();
        expect(addButton(rw.el).disabled).toBe(false);
        expect(rw.el.querySelector('[data-testid="references-read-only"]')).toBeNull();
    });

    it('adds a reference, sending only the filled optional fields, and shows the answer', async () => {
        const { f, c, el, inv } = create();
        await c.load();
        c.system.setValue(' jira ');
        c.type.setValue('ticket');
        c.refId.setValue('FRAUD-8');
        await c.add();
        f.detectChanges();
        expect(inv.addInvestigationReference).toHaveBeenCalledWith('inv-1', {
            system: 'jira',
            type: 'ticket',
            id: 'FRAUD-8',
        });
        expect(c.refId.value).toBe('');
        expect(el.textContent).toContain('FRAUD-7');
    });

    it('will not add without system, type and id', async () => {
        const { c, inv } = create();
        await c.load();
        await c.add();
        expect(inv.addInvestigationReference).not.toHaveBeenCalled();
    });

    it('explains a duplicate (409) in place', async () => {
        const { f, c, el } = create({
            addInvestigationReference: vi.fn(() =>
                throwError(() => new HttpErrorResponse({ status: 409, error: { error: 'already recorded' } })),
            ),
        });
        await c.load();
        c.system.setValue('jira');
        c.type.setValue('ticket');
        c.refId.setValue('FRAUD-7');
        await c.add();
        f.detectChanges();
        expect(el.textContent).toContain('Refused');
        expect(el.textContent).toContain('already recorded');
        await expectNoA11yViolations(el); // axe, LA-A11Y-AUDIT-1
    });

    it('LA-UI-REFERENCE-ROLE-NOTE-1: a reviewer with the capability still cannot add, and is told why', async () => {
        const r = create({}, true, 'reviewer');
        await r.c.load();
        await r.f.whenStable();
        r.f.detectChanges();
        expect(r.el.querySelector('[data-testid="references-read-only"]')?.textContent).toContain('reviewer');
        const add = Array.from(r.el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Add reference');
        expect(add?.disabled).toBe(true);
    });

    it('LA-UI-REFERENCE-ROLE-NOTE-1: an analyst with the capability sees no role note', async () => {
        const r = create({}, true, 'analyst');
        await r.c.load();
        await r.f.whenStable();
        r.f.detectChanges();
        expect(r.el.querySelector('[data-testid="references-read-only"]')).toBeNull();
    });
});
