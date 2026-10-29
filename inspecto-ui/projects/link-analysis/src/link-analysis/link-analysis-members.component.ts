import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import { Observable, firstValueFrom } from 'rxjs';
import { InvService, InvestigationMembers, InvestigationRole } from '@inspecto/link-analysis/api/inv.service';
import { LensService, apiErrorMessage } from '@inspecto/core/api';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { StatusBadgeComponent } from '@inspecto/core/components/status-badge.component';
import { limitRefusalMessage } from './limit-refusal';
import { DISABLED_REASON, needsPermission } from './disabled-reasons';
import { refusal403 } from './investigation-state';

const ROLES: readonly InvestigationRole[] = ['lead', 'analyst', 'reviewer'];

/** A member change refused by the server, in an analyst's words; the reason only, never the server's raw text (X5). */
export function memberErrorMessage(err: unknown, fallback: string): string {
    const limit = limitRefusalMessage(err);
    if (limit) return limit;
    const status = (err as { status?: number } | null)?.status;
    switch (status) {
        case 403:
            return refusal403(apiErrorMessage(err, fallback));
        case 404:
            return 'Not available: you or that person are not a member of this Investigation.';
        case 422:
            return 'Refused: an Investigation always keeps one lead. Make someone else a lead first.';
        case 409:
            return 'Someone changed the members at the same time. Reload and try again.';
        default:
            return apiErrorMessage(err, fallback);
    }
}

/**
 * **Members** (D7-1) — who may work on the open Investigation and in which role. Any member reads the list; only a
 * lead grants (or changes) a role and revokes a member, so for everyone else the controls are not offered. A caller
 * the server does not count as a member gets an honest "not visible" message: the API answers 404 for absence, it
 * does not say whether the Investigation exists.
 */
@Component({
    selector: 'inspecto-link-analysis-members',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        FormsModule,
        MatButtonModule,
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
        PenFieldComponent,
        StatusBadgeComponent,
    ],
    template: `
        <section
            class="flex flex-col gap-2 rounded-md border p-2 text-sm"
            style="border-color: var(--gamma-border)"
            aria-label="Members"
        >
            <h3 class="m-0 text-base font-semibold">Members</h3>
            @if (error()) {
                <inspecto-alert variant="error" title="Members">{{ error() }}</inspecto-alert>
            }
            @if (hidden()) {
                <p class="text-secondary m-0">
                    Only members of this Investigation can see who else is on it.
                </p>
            } @else if (data(); as d) {
                <ul class="m-0 flex list-none flex-col gap-1 p-0">
                    @for (m of d.members; track m.subject) {
                        <li class="flex items-center gap-2">
                            <code>{{ m.subject }}</code>
                            <inspecto-status-badge [value]="tone(m.role)" [label]="m.role"></inspecto-status-badge>
                            @if (m.subject === d.owner) {
                                <span class="text-secondary">owner</span>
                            }
                            @if (m.subject === d.you) {
                                <span class="text-secondary">you</span>
                            }
                            @if (canChange()) {
                                <button
                                    mat-button
                                    [disabled]="busy()"
                                    [attr.aria-label]="'Revoke ' + m.subject"
                                    (click)="revoke(m.subject)"
                                >
                                    Revoke
                                </button>
                            }
                        </li>
                    }
                </ul>
                @if (canChange()) {
                    <div class="flex items-center gap-2 border-t pt-1" style="border-color: var(--gamma-border)">
                        <inspecto-pen-field
                            class="w-40"
                            label="User"
                            ariaLabel="User"
                            placeholder="User id"
                            [maxlength]="200"
                            [ngModel]="subject()"
                            (ngModelChange)="subject.set('' + $event)"
                        ></inspecto-pen-field>
                        <inspecto-option-picker
                            class="w-32"
                            label="Role"
                            [options]="roleOptions"
                            [ngModel]="role()"
                            (ngModelChange)="role.set($any($event))"
                        />
                        <button
                            mat-stroked-button
                            data-testid="members-grant"
                            [disabled]="busy() || !!grantWhyNot()"
                            [disabledInteractive]="true"
                            [matTooltip]="grantWhyNot()"
                            [attr.aria-describedby]="grantWhyNot() ? 'members-grant-why' : null"
                            (click)="!busy() && !grantWhyNot() && grant()"
                        >
                            Grant
                        </button>
                    </div>
                    @if (grantWhyNot(); as why) {
                        <p class="text-secondary m-0 text-xs" id="members-grant-why" data-testid="members-grant-why">
                            {{ why }}
                        </p>
                    }
                } @else if (!canManage()) {
                    <p class="text-secondary m-0" data-testid="members-read-only">
                        You cannot change members. {{ needsIncidentManagement }}
                    </p>
                } @else {
                    <p class="text-secondary m-0">
                        Only a lead can grant or revoke members.
                    </p>
                }
            }
        </section>
    `,
})
export class LinkAnalysisMembersComponent {
    readonly investigationId = input.required<string>();

    private readonly inv = inject(InvService);
    private readonly lens = inject(LensService);
    private readonly confirm = inject(InspectoConfirmService);
    readonly roleOptions: PickerOption[] = ROLES.map((r) => ({ value: r, label: r }));
    /** Grant and revoke are gated server-side on `canManageIncidents` as well as on being a lead. */
    readonly canManage = computed(() => this.lens.canManageIncidents());

    readonly data = signal<InvestigationMembers | null>(null);
    readonly hidden = signal(false);
    readonly busy = signal(false);
    readonly error = signal('');
    readonly subject = signal('');
    /** Why Grant cannot run yet (X6); '' = it can. */
    readonly grantWhyNot = computed(() => (this.subject().trim() ? '' : DISABLED_REASON.fillRequired));
    protected readonly needsIncidentManagement = needsPermission('Incident management');
    readonly role = signal<InvestigationRole>('analyst');
    /** With no Subject attached (`you` null) nothing is enforced server-side, so the controls are offered. */
    readonly isLead = computed(() => {
        const d = this.data();
        return !!d && (d.you === 'lead' || d.you === null);
    });

    readonly canChange = computed(() => this.isLead() && this.canManage());

    constructor() {
        effect(() => {
            const id = this.investigationId();
            untracked(() => this.load(id));
        });
    }

    tone(role: InvestigationRole): string {
        return role === 'lead' ? 'info' : role === 'analyst' ? 'success' : 'neutral';
    }

    private async load(id: string): Promise<void> {
        this.data.set(null);
        this.hidden.set(false);
        this.error.set('');
        try {
            const d = await firstValueFrom(this.inv.investigationMembers(id));
            if (id === this.investigationId()) this.data.set(d);
        } catch (err) {
            if ((err as { status?: number } | null)?.status === 404) this.hidden.set(true);
            else this.error.set(memberErrorMessage(err, 'Could not read the members.'));
        }
    }

    async grant(): Promise<void> {
        const subject = this.subject().trim();
        if (!subject) return;
        await this.act(
            this.inv.grantInvestigationMember(this.investigationId(), subject, this.role()),
            'Could not grant the role.',
        );
        if (!this.error()) this.subject.set('');
    }

    async revoke(subject: string): Promise<void> {
        const ok = await this.confirm.confirmDestructive(
            `Revoke ${subject} from this Investigation? They lose access at once; a lead can grant it again.`,
            { title: 'Revoke member', confirmText: 'Revoke' },
        );
        if (!ok) return;
        await this.act(
            this.inv.revokeInvestigationMember(this.investigationId(), subject),
            'Could not revoke the member.',
        );
    }

    private async act(call: Observable<InvestigationMembers>, fallback: string): Promise<void> {
        this.busy.set(true);
        this.error.set('');
        try {
            this.data.set(await firstValueFrom(call));
        } catch (err) {
            this.error.set(memberErrorMessage(err, fallback));
        } finally {
            this.busy.set(false);
        }
    }
}
