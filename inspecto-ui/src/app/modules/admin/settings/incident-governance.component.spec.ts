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

function setup(opts: { canAdminister?: boolean; createFails?: boolean; opsMissing?: boolean } = {}) {
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
    const unavailable = new HttpErrorResponse({
        status: 503,
        error: { error: { errorCode: 'CAPABILITY_UNAVAILABLE', message: 'Operational objects are not installed' } },
    });
    const objects = {
        workflow: vi.fn(() => (opts.opsMissing ? throwError(() => unavailable) : of(WORKFLOW))),
    };
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

    it('draws the state diagram from the table and follows edits', () => {
        const { c, fixture, el } = setup();
        expect(el.querySelectorAll('svg[role="img"] rect')).toHaveLength(3);
        c.transitions.at(0).patchValue({ to: 'LIMBO' });
        fixture.detectChanges();
        expect(el.querySelector('svg[role="img"]')?.getAttribute('aria-label')).toContain('IDENTIFIED to LIMBO');
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

    it('says the governance module is not installed instead of offering an empty editor (no a11y violations)', async () => {
        const { el, button } = setup({ opsMissing: true });
        expect(el.textContent).toContain('Not available in this edition');
        expect(el.textContent).toContain('Operational objects are not installed');
        expect(button('Save workflow')).toBeUndefined();
        expect(button('Save SLA policy')).toBeUndefined();
        expect(button('Add Escalation Rule')).toBeUndefined();
        await expectNoA11yViolations(el);
    });

    describe('Escalation Rule advanced match (when)', () => {
        const toggle = (el: HTMLElement) => el.querySelector<HTMLButtonElement>('[data-testid="when-toggle"]')!;

        it('is a disclosure button wired to its region, collapsed by default, with no a11y violations', async () => {
            const { fixture, el } = setup();
            expect(toggle(el).tagName).toBe('BUTTON');
            expect(toggle(el).getAttribute('aria-expanded')).toBe('false');
            const region = el.querySelector('#' + toggle(el).getAttribute('aria-controls'))!;
            expect(region.hasAttribute('hidden')).toBe(true);
            toggle(el).click();
            fixture.detectChanges();
            expect(toggle(el).getAttribute('aria-expanded')).toBe('true');
            expect(region.hasAttribute('hidden')).toBe(false);
            expect(el.querySelector('inspecto-query-condition-group')).toBeTruthy();
            await expectNoA11yViolations(el);
        });

        it('offers the match context columns and saves the authored tree with the rule', () => {
            const { c, fixture, el, components } = setup();
            toggle(el).click();
            fixture.detectChanges();
            expect(c.contextColumns.map((x) => x.name)).toEqual([
                'type',
                'status',
                'priority',
                'severity',
                'category',
                'assignee',
                'ageMinutes',
                'minutesToDue',
                'resolutionBreached',
                'responseBreached',
                'escalated',
            ]);
            c.whenGroup.items.push({ kind: 'condition', field: 'minutesToDue', operator: '<=', value: '10' });
            c.whenChanged();
            c.rule.patchValue({ id: 'soon' });
            c.addRule();
            const body = components.create.mock.calls.at(-1) as unknown as [string, Record<string, unknown>];
            expect(body[0]).toBe('escalation-rule');
            expect(body[1]['when']).toEqual({
                kind: 'group',
                op: 'AND',
                items: [{ kind: 'condition', field: 'minutesToDue', operator: '<=', value: '10' }],
            });
        });

        it('negative probe: a rule with no tree is saved without a when', () => {
            const { c, components } = setup();
            c.rule.patchValue({ id: 'plain' });
            c.addRule();
            const body = components.create.mock.calls.at(-1) as unknown as [string, Record<string, unknown>];
            expect(body[1]).not.toHaveProperty('when');
        });

        it('refuses a bad condition, announces it, and opens the collapsed section', () => {
            const { c, fixture, el, components } = setup();
            c.whenGroup.items.push({ kind: 'condition', field: 'status', operator: 'matches', value: '(?=x)' });
            c.rule.patchValue({ id: 'bad' });
            expect(toggle(el).getAttribute('aria-expanded')).toBe('false');
            c.addRule();
            fixture.detectChanges();
            expect(components.create).not.toHaveBeenCalled();
            expect(toggle(el).getAttribute('aria-expanded')).toBe('true');
            const err = el.querySelector<HTMLElement>('[data-testid="when-error"]')!;
            expect(err.getAttribute('role')).toBe('alert');
            expect(err.textContent).toContain('lookahead');
            expect(toggle(el).getAttribute('aria-describedby')).toBe(err.id);
        });
    });

    describe('Escalation Rule edit', () => {
        const STORED = {
            type: 'escalation-rule',
            name: 'age-page',
            ref: 'escalation-rule/age-page',
            contentHash: 'h-1',
            content: {
                id: 'age-page',
                objectType: 'CASE',
                on: 'age',
                afterMinutes: 30,
                priority: 'P1',
                raisePriority: true,
                'x-note': 'kept',
                when: {
                    kind: 'group',
                    op: 'AND',
                    items: [{ kind: 'condition', field: 'status', operator: '=', value: 'OPEN' }],
                },
            },
        };
        const editButton = (el: HTMLElement, name: string) =>
            el.querySelector<HTMLButtonElement>(`button[aria-label="Edit Escalation Rule ${name}"]`)!;

        function editing() {
            const s = setup();
            s.components.list.mockReturnValue(of([RULE, STORED] as never));
            s.c.load();
            s.fixture.detectChanges();
            editButton(s.el, 'age-page').click();
            s.fixture.detectChanges();
            return s;
        }

        it('loads the stored rule into the form with its id fixed and says which rule is edited (no a11y violations)', async () => {
            const { c, el, fixture, button } = editing();
            expect(c.rule.controls.id.disabled).toBe(true);
            expect(c.rule.getRawValue()).toMatchObject({
                id: 'age-page',
                on: 'age',
                afterMinutes: 30,
                priority: 'P1',
                raisePriority: true,
                notify: false,
            });
            expect(el.querySelector('[data-testid="rule-editing"]')?.textContent).toContain('age-page');
            expect(button('Save Escalation Rule')).toBeTruthy();
            expect(button('Add Escalation Rule')).toBeUndefined();
            expect(c.whenGroup.items.length).toBe(1);
            expect(c.whenGroup).not.toBe(STORED.content.when);
            await fixture.whenStable();
            await expectNoA11yViolations(el);
        });

        it('saves with PUT under the stored name and hash, keeps unmodelled keys and objectType, then leaves edit mode', () => {
            const { c, fixture, components } = editing();
            c.rule.patchValue({ afterMinutes: 45 });
            c.addRule();
            fixture.detectChanges();
            expect(components.create).not.toHaveBeenCalled();
            const [kind, id, body, opts] = components.update.mock.calls.at(-1) as unknown as [
                string,
                string,
                Record<string, unknown>,
                { ifMatch?: string },
            ];
            expect([kind, id, opts.ifMatch]).toEqual(['escalation-rule', 'age-page', 'h-1']);
            expect(body).toMatchObject({
                id: 'age-page',
                objectType: 'CASE',
                on: 'age',
                afterMinutes: 45,
                'x-note': 'kept',
            });
            expect(body['when']).toEqual(STORED.content.when);
            expect(c.editing()).toBeNull();
            expect(c.rule.controls.id.enabled).toBe(true);
        });

        it("negative probe: a refused save stays in edit mode with the author's changes", () => {
            const { c, fixture, el, components } = editing();
            components.update.mockReturnValueOnce(
                throwError(
                    () => new HttpErrorResponse({ status: 409, error: { error: 'changed since you opened it' } }),
                ) as never,
            );
            c.rule.patchValue({ afterMinutes: 90 });
            c.addRule();
            fixture.detectChanges();
            expect(c.editing()?.name).toBe('age-page');
            expect(c.rule.getRawValue().afterMinutes).toBe(90);
            expect(el.textContent).toContain('changed since you opened it');
        });

        it('a saved edit leaves a clean blank form: no required-id error from the submit that saved it', () => {
            const { fixture, el, button } = editing();
            button('Save Escalation Rule')!.click();
            fixture.detectChanges();
            expect(button('Add Escalation Rule')).toBeTruthy();
            expect(el.querySelector('mat-error')).toBeNull();
        });

        it('a created rule clears the add form without flagging the blank id', () => {
            const { c, fixture, el, button, components } = setup();
            c.rule.patchValue({ id: 'second' });
            button('Add Escalation Rule')!.click();
            fixture.detectChanges();
            expect(components.create).toHaveBeenCalled();
            expect(c.rule.getRawValue().id).toBe('');
            expect(el.querySelector('mat-error')).toBeNull();
        });

        it('cancel returns the form to adding a new rule', () => {
            const { c, fixture, button } = editing();
            button('Cancel')!.click();
            fixture.detectChanges();
            expect(c.editing()).toBeNull();
            expect(c.rule.controls.id.enabled).toBe(true);
            expect(c.rule.getRawValue().id).toBe('');
            expect(c.whenGroup.items.length).toBe(0);
            expect(button('Add Escalation Rule')).toBeTruthy();
        });
    });
});
