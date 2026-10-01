import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';

import { ComponentsService, LensService, ObjectsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { IncidentGovernanceComponent } from './incident-governance.component';

const WORKFLOW = {
    type: 'INCIDENT',
    initial: 'IDENTIFIED',
    states: ['IDENTIFIED', 'RESOLVED', 'ARCHIVED'],
    terminal: ['ARCHIVED'],
    transitions: [
        { from: 'IDENTIFIED', to: 'RESOLVED', action: 'resolve' },
        { from: 'RESOLVED', to: 'ARCHIVED', action: 'archive' },
    ],
};
const RULE = {
    type: 'escalation-rule',
    name: 'page-duty',
    ref: 'escalation-rule/page-duty',
    content: { objectType: 'INCIDENT', on: 'breach', reassign: 'duty-manager', notify: true },
};

function setup(opts: { canAdminister?: boolean; createFails?: boolean } = {}) {
    const notFound = new HttpErrorResponse({ status: 404 });
    const components = {
        get: vi.fn(() => throwError(() => notFound)),
        list: vi.fn(() => of([RULE])),
        create: vi.fn(() =>
            opts.createFails
                ? throwError(
                      () => new HttpErrorResponse({ status: 422, error: { error: 'state LIMBO is unreachable' } }),
                  )
                : of({}),
        ),
        update: vi.fn(() => of({})),
        remove: vi.fn(() => of({})),
    };
    const objects = { workflow: vi.fn(() => of(WORKFLOW)) };
    const toastr = { success: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
        imports: [IncidentGovernanceComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ComponentsService, useValue: components },
            { provide: ObjectsService, useValue: objects },
            { provide: ToastrService, useValue: toastr },
            { provide: LensService, useValue: { canAdminister: () => opts.canAdminister !== false } },
        ],
    });
    const fixture = TestBed.createComponent(IncidentGovernanceComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const button = (label: string) =>
        Array.from(el.querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent?.trim() === label);
    return { fixture, c: fixture.componentInstance, el, components, toastr, button };
}

describe('IncidentGovernanceComponent', () => {
    it('renders the effective workflow, the SLA form and the Escalation Rules (no a11y violations)', async () => {
        const { el, c } = setup();
        expect(el.querySelectorAll('h1').length).toBe(1);
        expect(c.workflow.controls.initial.value).toBe('IDENTIFIED');
        expect(c.transitions.length).toBe(2);
        expect(el.textContent).toContain('page-duty');
        expect(el.textContent).toContain('reassign to duty-manager, notify');
        await expectNoA11yViolations(el);
    });

    it('creates the workflow component when none is stored yet', () => {
        const { c, fixture, components, button } = setup();
        c.addTransition();
        fixture.detectChanges();
        button('Save workflow')!.click();
        expect(components.create).toHaveBeenCalledWith(
            'workflow',
            expect.objectContaining({
                id: 'incident',
                initial: 'IDENTIFIED',
                terminal: ['ARCHIVED'],
                transitions: [
                    { from: 'IDENTIFIED', to: 'RESOLVED', action: 'resolve' },
                    { from: 'RESOLVED', to: 'ARCHIVED', action: 'archive' },
                ],
            }),
        );
    });

    it('shows the server refusal inline and asks for an explicit time zone before saving an SLA policy', () => {
        const { c, fixture, el, components, button } = setup({ createFails: true });
        button('Save SLA policy')!.click();
        fixture.detectChanges();
        expect(components.create).not.toHaveBeenCalled();
        expect(el.textContent).toContain('An explicit time zone is required.');
        button('Save workflow')!.click();
        fixture.detectChanges();
        expect(c.lastError()).toContain('LIMBO');
        expect(el.textContent).toContain('Not saved');
    });

    it('is read-only without the administer capability', () => {
        const { el, button } = setup({ canAdminister: false });
        expect(el.textContent).toContain('Read-only');
        expect(button('Save workflow')!.disabled).toBe(true);
        expect(button('Add Escalation Rule')).toBeUndefined();
    });
});
