import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { catchError, Observable, of, tap } from 'rxjs';

import {
    AccessService,
    apiErrorMessage,
    ExplainResult,
    isStaleVersionError,
    LensService,
    PoliciesDoc,
    PolicyDef,
    PolicyWarning,
    STALE_WRITE_MESSAGE,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { InspectoOptionPickerComponent, pickerOptions } from 'app/inspecto/components/option-picker.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { PolicyFormData, PolicyFormDialog } from './policy-form.dialog';

/**
 * Settings ▸ Access ▸ Policies: the effective Access Policies (ABAC A2/A3) with authoring, plus a
 * "Why denied?" explainer — ONE surface, extended rather than duplicated (policy-authoring-ux-design.md
 * S3):
 *
 * <ul>
 *   <li><b>Seed visibility:</b> the engine-resident A4 space-isolation denies are never in the authored
 *       doc, so the table surfaces them tagged <code>built-in</code> beside the authored rows.</li>
 *   <li><b>Authoring</b> (gated on <code>canConfigureAccess</code>): new / edit / delete an authored
 *       policy, and "override" a built-in behind a confirm naming what the replacement loses (D8). Every
 *       save is a full replace of the authored list with <code>If-Match</code>; the server refuses the
 *       nine failure modes (422, incl. <code>would-lock-out</code>) or returns warnings, which render
 *       inline per row — for hand-edited docs too.</li>
 *   <li><b>Why denied:</b> <code>GET /access/explain</code> for the caller's own session.</li>
 * </ul>
 *
 * Enforcement is Enterprise-only — on Personal/Professional the explainer and the impact preview say so.
 */
@Component({
    selector: 'app-access-policies',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        InspectoOptionPickerComponent,
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatTooltipModule,
        ChipComponent,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        InspectoSkeletonComponent,
    ],
    templateUrl: './access-policies.component.html',
})
export class AccessPoliciesComponent {
    private readonly api = inject(AccessService);
    private readonly lens = inject(LensService);
    private readonly dialog = inject(MatDialog);
    private readonly confirm = inject(InspectoConfirmService);
    private readonly fb = inject(FormBuilder);
    private readonly toastr = inject(ToastrService);

    readonly methods = pickerOptions(['GET', 'POST', 'PUT', 'PATCH', 'DELETE']);

    readonly loading = signal(true);
    readonly saving = signal(false);
    readonly rows = signal<PolicyDef[]>([]);
    /** Set ⇔ the authored access-policies.toon is unreadable (engine denies, fail-closed). */
    readonly docError = signal<string | null>(null);
    readonly warnings = signal<PolicyWarning[]>([]);
    private readonly resourceKinds = signal<string[]>([]);
    /** The GET's ETag — echoed as If-Match so a concurrent save is a 409, never a silent overwrite. */
    private etag: string | undefined;

    readonly explaining = signal(false);
    readonly result = signal<ExplainResult | null>(null);

    readonly hasPolicies = computed(() => this.rows().length > 0);
    readonly canEdit = computed(() => this.lens.canConfigureAccess());

    readonly form = this.fb.group({
        route: ['', Validators.required],
        method: ['GET'],
        resourceKind: [''],
    });

    constructor() {
        this.load();
    }

    private load(): void {
        this.api
            .policies()
            .pipe(catchError(() => of<PoliciesDoc>({ policies: [] })))
            .subscribe((doc) => {
                this.apply(doc);
                this.loading.set(false);
            });
    }

    private apply(doc: PoliciesDoc): void {
        this.docError.set(doc.error ?? null);
        this.rows.set(doc.policies ?? []);
        this.warnings.set(doc.warnings ?? []);
        this.resourceKinds.set(doc.resourceKinds ?? []);
        this.etag = doc.etag;
    }

    /** The server's warnings for one policy — rendered under its row. */
    warningsFor(name: string): PolicyWarning[] {
        return this.warnings().filter((w) => w.policy === name);
    }

    newPolicy(): void {
        this.openForm({});
    }

    edit(p: PolicyDef): void {
        this.openForm({ policy: p });
    }

    /** D8 (taken on recommendation): a built-in may be replaced from here, but only behind a confirm
     *  that quotes the condition — and so the exemption — the replacement drops. */
    async override(seed: PolicyDef): Promise<void> {
        if (!this.canEdit() || this.saving()) return;
        const ok = await this.confirm.confirm(
            `Authoring a policy named '${seed.name}' replaces the built-in one wholesale. Its condition is: ` +
                `${seed.when ?? '(none)'} — including any exemption in it. Keep that clause in your ` +
                `replacement if it should still apply.`,
            'Override a built-in policy?',
        );
        if (!ok) return;
        this.openForm({
            policy: { name: seed.name, effect: seed.effect, target: seed.target, when: seed.when },
            override: true,
        });
    }

    async remove(p: PolicyDef): Promise<void> {
        if (!this.canEdit() || this.saving()) return;
        const ok = await this.confirm.confirmDestructive(
            `Delete the policy '${p.name}'? It stops applying on the next request.`,
            { title: 'Delete policy?' },
        );
        if (!ok) return;
        this.persist(this.authored().filter((r) => r.name !== p.name)).subscribe({
            next: () => this.toastr.success(`Policy '${p.name}' deleted`),
            error: (err) => this.toastr.error(this.saveError(err)),
        });
    }

    private openForm(data: Pick<PolicyFormData, 'policy' | 'override'>): void {
        if (!this.canEdit() || this.saving()) return;
        const name = data.policy?.name;
        this.dialog
            .open<PolicyFormDialog, PolicyFormData, PolicyDef | undefined>(PolicyFormDialog, {
                width: '760px',
                data: {
                    ...data,
                    existingNames: this.rows().map((r) => r.name),
                    resourceKinds: this.resourceKinds(),
                    others: this.authored().filter((r) => r.name !== name),
                    warnings: name ? this.warningsFor(name) : [],
                    save: (authored) => this.persist(authored),
                },
            })
            .afterClosed()
            .subscribe((saved) => {
                if (saved) this.toastr.success(`Policy '${saved.name}' saved — it applies on the next request`);
            });
    }

    /** The authored list — exactly the rows `PUT /access/policies` replaces. */
    private authored(): PolicyDef[] {
        return this.rows().filter((r) => r.source === 'authored');
    }

    private persist(authored: PolicyDef[]): Observable<PoliciesDoc> {
        this.saving.set(true);
        return this.api.savePolicies(authored, this.etag).pipe(
            tap({
                next: (doc) => {
                    this.apply(doc);
                    this.saving.set(false);
                },
                error: () => this.saving.set(false),
            }),
        );
    }

    private saveError(err: unknown): string {
        return isStaleVersionError(err) ? STALE_WRITE_MESSAGE : apiErrorMessage(err, 'Could not save the policies');
    }

    /** Run the dry-run for the caller's own session against the form's route/method/resourceKind. */
    explain(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const v = this.form.getRawValue();
        this.explaining.set(true);
        this.api
            .explain({
                route: (v.route ?? '').trim(),
                method: v.method ?? 'GET',
                resourceKind: v.resourceKind?.trim() || undefined,
            })
            .subscribe({
                next: (r) => {
                    this.result.set(r);
                    this.explaining.set(false);
                },
                error: (err) => {
                    this.explaining.set(false);
                    this.result.set(null);
                    this.toastr.error(apiErrorMessage(err, 'Could not evaluate access'));
                },
            });
    }

    /** DENY → error, ALLOW → success, ABSTAIN/other → info — the inline result banner's variant. */
    decisionVariant(d: string | undefined): 'success' | 'error' | 'info' {
        return d === 'DENY' ? 'error' : d === 'ALLOW' ? 'success' : 'info';
    }

    /** A human summary of a policy's target dimensions ("any" when unconstrained). */
    target(p: PolicyDef): string {
        const t = p.target;
        const parts: string[] = [];
        if (t?.actions?.length) parts.push(t.actions.join(', '));
        if (t?.resourceKinds?.length) parts.push(t.resourceKinds.join(', '));
        return parts.length ? parts.join(' · ') : 'any';
    }
}
