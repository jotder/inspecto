import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';

import {
    AccessService,
    ExplainResult,
    LensService,
    PoliciesDoc,
    PolicyDef,
    PolicyWarning,
    STALE_WRITE_MESSAGE,
} from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { AccessPoliciesComponent } from './access-policies.component';
import { PolicyFormData } from './policy-form.dialog';

const AUTHORED: PolicyDef = {
    name: 'freeze-contractor',
    effect: 'deny',
    target: { actions: ['write', 'operate'] },
    when: "subject.employment == 'contractor'",
    source: 'authored',
};
const SEED: PolicyDef = { name: 'space-isolation', effect: 'deny', when: 'env.space != subject.space', source: 'seed' };

function create(
    opts: {
        policies?: PolicyDef[];
        error?: string;
        warnings?: PolicyWarning[];
        explain?: ExplainResult;
        canEdit?: boolean;
        save?: () => Observable<PoliciesDoc>;
    } = {},
) {
    const explain = vi.fn(() => of(opts.explain ?? { enabled: false, reason: 'no engine' }));
    const savePolicies = vi.fn(opts.save ?? (() => of<PoliciesDoc>({ policies: [], etag: '"sha256:new"' })));
    const api = {
        policies: vi.fn(() =>
            of<PoliciesDoc>({
                policies: opts.policies ?? [],
                error: opts.error,
                warnings: opts.warnings ?? [],
                resourceKinds: ['incident'],
                etag: '"sha256:abc"',
            }),
        ),
        savePolicies,
        explain,
    };
    const toastr = { success: vi.fn(), error: vi.fn() };
    const confirm = {
        confirm: vi.fn(() => Promise.resolve(true)),
        confirmDestructive: vi.fn(() => Promise.resolve(true)),
    };
    const dialog = { open: vi.fn(() => ({ afterClosed: () => of(undefined) })) };
    TestBed.configureTestingModule({
        imports: [AccessPoliciesComponent],
        providers: [
            provideNoopAnimations(),
            { provide: AccessService, useValue: api },
            { provide: ToastrService, useValue: toastr },
            { provide: LensService, useValue: { canConfigureAccess: () => opts.canEdit ?? true } },
            { provide: InspectoConfirmService, useValue: confirm },
            { provide: MatDialog, useValue: dialog },
        ],
    });
    const fixture = TestBed.createComponent(AccessPoliciesComponent);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, explain, toastr, savePolicies, confirm, dialog };
}

describe('AccessPoliciesComponent', () => {
    it('lists effective policies with authored + built-in source badges', async () => {
        const { fixture } = create({ policies: [AUTHORED, SEED] });
        const text = fixture.nativeElement.textContent as string;
        expect(text).toContain('freeze-contractor');
        expect(text).toContain('space-isolation');
        expect(text).toContain('authored');
        expect(text).toContain('built-in');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('shows an empty state when nothing is authored and no seeds are in force', async () => {
        const { fixture } = create({ policies: [] });
        expect(fixture.nativeElement.textContent).toContain('No access policies');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('surfaces an unreadable authored document, fail-closed', () => {
        const { fixture } = create({ error: 'access-policies.toon is unreadable' });
        expect(fixture.nativeElement.textContent).toContain('unreadable');
    });

    it('requires a route before explaining', () => {
        const { c, explain } = create();
        c.explain();
        expect(explain).not.toHaveBeenCalled();
        expect(c.form.controls.route.touched).toBe(true);
    });

    it('reports a disabled engine without a trace', async () => {
        const { fixture, c } = create({
            explain: { enabled: false, reason: 'no access policy engine on this edition' },
        });
        c.form.setValue({ route: '/access/roles', method: 'PUT', resourceKind: '' });
        c.explain();
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('not active');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('renders the decision, matched policy, and per-policy trace when enabled', async () => {
        const result: ExplainResult = {
            enabled: true,
            subject: 'carl',
            action: 'write',
            route: '/access/roles',
            decision: 'DENY',
            matchedPolicy: 'freeze-contractor',
            trace: [
                { name: 'space-isolation', effect: 'deny', source: 'seed', targeted: false, conditionHeld: false },
                { name: 'freeze-contractor', effect: 'deny', source: 'authored', targeted: true, conditionHeld: true },
            ],
        };
        const { fixture, c, explain } = create({ explain: result });
        c.form.setValue({ route: '/access/roles', method: 'PUT', resourceKind: 'incident' });
        c.explain();
        fixture.detectChanges();

        expect(explain).toHaveBeenCalledWith({ route: '/access/roles', method: 'PUT', resourceKind: 'incident' });
        const text = fixture.nativeElement.textContent as string;
        expect(text).toContain('Decision: DENY');
        expect(text).toContain('freeze-contractor');
        expect(text).toContain('space-isolation');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    // ── authoring (policy-authoring S3) ────────────────────────────────────────────────

    it('renders the server warnings under the policy they name — hand-edited docs included', async () => {
        const { fixture } = create({
            policies: [AUTHORED],
            warnings: [{ policy: 'freeze-contractor', code: 'unknown-role', message: "'contractr' is not a role" }],
        });
        const warn = fixture.nativeElement.querySelector('[aria-label="Warnings for freeze-contractor"]');
        expect(warn?.textContent).toContain("'contractr' is not a role");
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('is read-only for a subject without canConfigureAccess', () => {
        const { fixture } = create({ policies: [AUTHORED, SEED], canEdit: false });
        const text = fixture.nativeElement.textContent as string;
        expect(text).not.toContain('New policy');
        expect(text).not.toContain('Override');
        expect(fixture.nativeElement.querySelector('[aria-label="Edit policy freeze-contractor"]')).toBeNull();
    });

    it('opens the form with the rest of the authored list and saves through If-Match', () => {
        const other: PolicyDef = { name: 'b', effect: 'allow', source: 'authored' };
        const { c, dialog, savePolicies } = create({ policies: [AUTHORED, other, SEED] });
        c.edit(AUTHORED);
        const data = (dialog.open.mock.calls[0] as unknown as [unknown, { data: PolicyFormData }])[1].data;
        expect(data.others.map((p) => p.name)).toEqual(['b']); // the edited one and the seed are not in it
        expect(data.resourceKinds).toEqual(['incident']); // served, never mirrored
        data.save([other]).subscribe();
        expect(savePolicies).toHaveBeenCalledWith([other], '"sha256:abc"');
    });

    it('deletes an authored policy with a full replace of the rest', async () => {
        const other: PolicyDef = { name: 'b', effect: 'allow', source: 'authored' };
        const { c, savePolicies, confirm } = create({ policies: [AUTHORED, other, SEED] });
        await c.remove(AUTHORED);
        expect(confirm.confirmDestructive).toHaveBeenCalled();
        expect(savePolicies).toHaveBeenCalledWith([other], '"sha256:abc"');
    });

    it('reports a stale save as a concurrent edit, not a generic failure', async () => {
        const stale = new HttpErrorResponse({
            status: 409,
            error: { error: { errorCode: 'CONFLICT_STALE_VERSION', message: 'stale' } },
        });
        const { c, toastr } = create({ policies: [AUTHORED], save: () => throwError(() => stale) });
        await c.remove(AUTHORED);
        expect(toastr.error).toHaveBeenCalledWith(STALE_WRITE_MESSAGE);
    });

    it('overrides a built-in only after a confirm that quotes the condition it replaces (D8)', async () => {
        const { c, confirm, dialog } = create({ policies: [SEED] });
        confirm.confirm.mockResolvedValueOnce(false);
        await c.override(SEED);
        expect(dialog.open).not.toHaveBeenCalled();
        expect((confirm.confirm.mock.calls[0] as unknown as [string])[0]).toContain(SEED.when);
        await c.override(SEED);
        const data = (dialog.open.mock.calls[0] as unknown as [unknown, { data: PolicyFormData }])[1].data;
        expect(data.override).toBe(true);
        expect(data.policy?.name).toBe('space-isolation');
    });
});
