import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { AccessService, PolicyDef, PolicyPreview } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { PolicyFormData, PolicyFormDialog } from './policy-form.dialog';

const OTHER: PolicyDef = { name: 'b', effect: 'allow' };

function create(data: Partial<PolicyFormData> = {}, preview?: () => Observable<PolicyPreview>) {
    const ref = { close: vi.fn(), disableClose: false };
    const save = vi.fn(data.save ?? (() => of({})));
    const previewPolicies = vi.fn(preview ?? (() => of<PolicyPreview>({ enabled: false, reason: 'no engine' })));
    TestBed.configureTestingModule({
        imports: [PolicyFormDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: ref },
            { provide: AccessService, useValue: { previewPolicies } },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: vi.fn(() => Promise.resolve(true)) } },
            {
                provide: MAT_DIALOG_DATA,
                useValue: {
                    existingNames: ['b', 'space-isolation'],
                    resourceKinds: ['incident', 'investigation'],
                    others: [OTHER],
                    warnings: [],
                    ...data,
                    save,
                } satisfies PolicyFormData,
            },
        ],
    });
    const fixture = TestBed.createComponent(PolicyFormDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref, save, previewPolicies };
}

describe('PolicyFormDialog', () => {
    it('creates a policy: lower-cased name, ordered target, trimmed condition — saved with the rest of the list', async () => {
        const { fixture, c, ref, save } = create();
        c.form.controls.name.setValue('Contractor-Freeze');
        c.toggleAction('operate', true);
        c.toggleAction('write', true);
        c.toggleKind('incident', true);
        c.form.controls.when.setValue("  subject.roles contains 'contractor' ");
        c.save();
        const draft: PolicyDef = {
            name: 'contractor-freeze',
            effect: 'deny',
            target: { actions: ['write', 'operate'], resourceKinds: ['incident'] },
            when: "subject.roles contains 'contractor'",
        };
        expect(save).toHaveBeenCalledWith([OTHER, draft]);
        expect(ref.close).toHaveBeenCalledWith(draft);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('rejects a duplicate name inline and does not save', () => {
        const { c, save } = create();
        c.form.controls.name.setValue('B');
        c.save();
        expect(c.form.controls.name.hasError('duplicate')).toBe(true);
        expect(save).not.toHaveBeenCalled();
    });

    it("edit keeps the name immutable and the stored target, and shows the server's warnings", async () => {
        const { fixture, c, save } = create({
            policy: { name: 'p', effect: 'deny', target: { actions: ['write'] }, when: "'x' in subject.roles" },
            warnings: [{ policy: 'p', code: 'unknown-role', message: "'x' is not a role in this Space" }],
        });
        expect(fixture.nativeElement.querySelector('input[formcontrolname="name"]')).toBeNull();
        expect(fixture.nativeElement.textContent).toContain("'x' is not a role in this Space");
        c.save();
        expect((save.mock.calls[0] as unknown as [PolicyDef[]])[0][1]).toEqual({
            name: 'p',
            effect: 'deny',
            target: { actions: ['write'] },
            when: "'x' in subject.roles",
        });
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('stays open with the server refusal on a 422 — the would-lock-out message included', () => {
        const refusal = new HttpErrorResponse({
            status: 422,
            error: {
                error: {
                    errorCode: 'CONFIG_VALIDATION_FAILED',
                    message: "policy 'p' [would-lock-out]: it would deny your own next PUT /access/policies",
                },
            },
        });
        const { fixture, c, ref } = create({ save: () => throwError(() => refusal) });
        c.form.controls.name.setValue('p');
        c.save();
        fixture.detectChanges();
        expect(ref.close).not.toHaveBeenCalled();
        expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('would-lock-out');
    });

    it('previews the draft server-side and marks the flipped cells', async () => {
        const preview: PolicyPreview = {
            enabled: true,
            route: '/',
            roles: ['operations', 'admin'],
            actions: ['read', 'write', 'operate'],
            kinds: [],
            cells: ['operations', 'admin'].flatMap((role) =>
                (['read', 'write', 'operate'] as const).map((action) => ({
                    role,
                    action,
                    resourceKind: null,
                    before: 'ABSTAIN' as const,
                    beforePolicy: null,
                    after: role === 'operations' && action === 'write' ? ('DENY' as const) : ('ABSTAIN' as const),
                    afterPolicy: role === 'operations' && action === 'write' ? 'p' : null,
                    changed: role === 'operations' && action === 'write',
                })),
            ),
            warnings: [{ policy: 'p', code: 'unknown-role', message: 'typo' }],
        };
        const { fixture, c, previewPolicies } = create({}, () => of(preview));
        c.form.controls.name.setValue('p');
        c.toggleAction('write', true);
        c.runPreview();
        fixture.detectChanges();
        expect(previewPolicies).toHaveBeenCalledWith([
            OTHER,
            { name: 'p', effect: 'deny', target: { actions: ['write'] } },
        ]);
        const text = fixture.nativeElement.textContent as string;
        expect(text).toContain('1 of 6 decisions change');
        expect(text).toContain('ABSTAIN → DENY');
        expect(text).toContain('typo'); // the draft's warnings refresh from the preview
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
