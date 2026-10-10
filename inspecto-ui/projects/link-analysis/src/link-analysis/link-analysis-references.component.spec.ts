import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { InvestigationReferences, InvService } from '@inspecto/link-analysis/api/inv.service';
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

function create(overrides: Partial<Record<keyof InvService, unknown>> = {}) {
    const inv = {
        investigationReferences: vi.fn(() => of(ONE)),
        addInvestigationReference: vi.fn(() => of(ONE)),
        ...overrides,
    };
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [provideNoopAnimations(), { provide: InvService, useValue: inv }],
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
    });
});
