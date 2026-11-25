import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { ApprovalPolicy, LensService, PendingChangesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ApprovalPolicySettingsComponent } from './approval-policy.component';

const POLICY: ApprovalPolicy = {
    approval: {
        pipeline: { required: true, approverCapability: 'canApproveChanges', fourEyes: true },
    },
    expiresAfterHours: 168,
    failedClosed: false,
    governable: ['dataset', 'pipeline'],
    defaultApproverCapability: 'canApproveChanges',
};

function setup(canAdminister = true, overrides: Partial<Record<keyof PendingChangesService, unknown>> = {}) {
    const toastr = { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() };
    const api = {
        policy: vi.fn(() => of(POLICY)),
        savePolicy: vi.fn((body: { approval: Record<string, unknown>; expiresAfterHours?: number }) =>
            of({
                ...POLICY,
                approval: body.approval,
                expiresAfterHours: body.expiresAfterHours ?? 168,
            }),
        ),
        ...overrides,
    };
    TestBed.configureTestingModule({
        imports: [ApprovalPolicySettingsComponent],
        providers: [
            provideNoopAnimations(),
            { provide: PendingChangesService, useValue: api },
            { provide: ToastrService, useValue: toastr },
            { provide: LensService, useValue: { canAdminister: () => canAdminister } },
        ],
    });
    const fixture = TestBed.createComponent(ApprovalPolicySettingsComponent);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, api, toastr };
}

describe('ApprovalPolicySettingsComponent', () => {
    it('loads governable kinds and renders toggles without a11y violations', async () => {
        const { fixture, c } = setup();
        expect(c.kinds()).toEqual(['dataset', 'pipeline']);
        expect(c.draft()['pipeline'].required).toBe(true);
        expect(c.draft()['dataset'].required).toBe(false);
        expect(c.dirty()).toBe(false);
        await expectNoA11yViolations(fixture.nativeElement);
        fixture.destroy();
    });

    it('toggling a kind marks dirty and saves via PUT /settings/approval', () => {
        const { fixture, c, api, toastr } = setup();
        c.setRequired('dataset', true);
        expect(c.dirty()).toBe(true);
        expect(c.hasUnsavedChanges()).toBe(true);

        c.save();

        expect(api.savePolicy).toHaveBeenCalledWith({
            approval: {
                dataset: { required: true, fourEyes: true, approverCapability: 'canApproveChanges' },
                pipeline: { required: true, fourEyes: true, approverCapability: 'canApproveChanges' },
            },
            expiresAfterHours: 168,
        });
        expect(c.dirty()).toBe(false);
        expect(toastr.success).toHaveBeenCalledWith('Approval policy saved.');
        fixture.destroy();
    });

    it('turning all kinds off sends an explicit empty approval map', () => {
        const { fixture, c, api } = setup();
        c.setRequired('pipeline', false);
        c.save();

        expect(api.savePolicy).toHaveBeenCalledWith({
            approval: {},
            expiresAfterHours: 168,
        });
        fixture.destroy();
    });

    it('surfaces 422 validation errors inline', () => {
        const err = new HttpErrorResponse({ status: 422, error: { error: { message: 'bad policy' } } });
        const { fixture, c } = setup(true, {
            savePolicy: vi.fn(() => throwError(() => err)),
        });
        c.setRequired('dataset', true);
        c.save();

        expect(c.saveError()).toBe('bad policy');
        expect(c.dirty()).toBe(true);
        fixture.destroy();
    });

    it('hides save controls when the user lacks canAdminister', () => {
        const { fixture } = setup(false);
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Administer capability required');
        expect((fixture.nativeElement as HTMLElement).querySelector('button')?.textContent).not.toContain('Save policy');
        fixture.destroy();
    });
});
