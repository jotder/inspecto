import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { InvService, InvestigationCaseLink, ObjectsService, SessionService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { provideLaHostServices } from 'app/modules/admin/studio/la-host.providers';
import { LinkAnalysisInvestigationCaseComponent } from './link-analysis-investigation-case.component';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisInvestigationCaseComponent],
    template: `<inspecto-link-analysis-investigation-case
        investigationId="inv-1"
    ></inspecto-link-analysis-investigation-case>`,
})
class Host {}

const UNLINKED: InvestigationCaseLink = {
    investigationId: 'inv-1',
    caseRef: null,
    linkedBy: null,
    linkedAt: null,
    access: 'owner',
    readOnly: false,
    sharing: false,
    reason: 'not linked to a Case — owner-only',
};
const LINKED: InvestigationCaseLink = { ...UNLINKED, caseRef: 'CASE-1', sharing: true, reason: 'members read' };

async function create(link: InvestigationCaseLink, opsEnabled: boolean) {
    const inv = {
        investigationCase: vi.fn(() => of(link)),
        linkInvestigationCase: vi.fn(() => of(LINKED)),
        unlinkInvestigationCase: vi.fn(() => of(UNLINKED)),
    };
    const objects = { list: vi.fn(() => of([{ id: 'CASE-1', title: 'Fraud ring' }])) };
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            ...provideLaHostServices(),
            provideNoopAnimations(),
            { provide: InvService, useValue: inv },
            { provide: ObjectsService, useValue: objects },
            { provide: SessionService, useValue: { opsEnabled: signal(opsEnabled) } },
        ],
    });
    const f = TestBed.createComponent(Host);
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
    const c = f.debugElement.children[0].componentInstance as LinkAnalysisInvestigationCaseComponent;
    return { f, c, el: f.nativeElement as HTMLElement, inv, objects };
}

describe('LinkAnalysisInvestigationCaseComponent (LA-24)', () => {
    it('offers real Cases to the owner and links the picked one', async () => {
        const { f, c, el, inv, objects } = await create(UNLINKED, true);
        expect(objects.list).toHaveBeenCalledWith({ type: 'CASE' });
        expect(c.cases()).toEqual([{ value: 'CASE-1', label: 'CASE-1 · Fraud ring' }]);
        c.picked.set('CASE-1');
        await c.linkTo();
        f.detectChanges();
        expect(inv.linkInvestigationCase).toHaveBeenCalledWith('inv-1', 'CASE-1');
        expect(el.textContent).toContain('Linked to Case');
        await expectNoA11yViolations(el);
    });

    it('offers no Case without Case management and says why', async () => {
        const { el, objects } = await create(UNLINKED, false);
        expect(objects.list).not.toHaveBeenCalled();
        expect(el.textContent).toContain('needs Case management');
        expect(el.textContent).not.toContain('Link to Case');
    });

    it('shows a read-only notice, and no controls, to a Case member', async () => {
        const { el, objects } = await create({ ...LINKED, access: 'case-member', readOnly: true }, true);
        expect(el.textContent).toContain('Read-only');
        expect(el.textContent).toContain('CASE-1');
        expect(el.querySelector('button')).toBeNull();
        expect(objects.list).not.toHaveBeenCalled();
    });

    it('unlinks for the owner', async () => {
        const { f, c, el, inv } = await create(LINKED, true);
        await c.unlink();
        f.detectChanges();
        expect(inv.unlinkInvestigationCase).toHaveBeenCalledWith('inv-1');
        expect(el.textContent).not.toContain('Linked to Case');
    });
});
