import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { Observable, firstValueFrom } from 'rxjs';
import { InvService, InvestigationMembers, InvestigationRole } from '@inspecto/link-analysis/api/inv.service';
import { apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { StatusBadgeComponent } from '@inspecto/core/components/status-badge.component';
import { limitRefusalMessage } from './limit-refusal';

const ROLES: readonly InvestigationRole[] = ['lead', 'analyst', 'reviewer'];

/** A member change refused by the server, in an analyst's words; the server's own reason is always kept. */
export function memberErrorMessage(err: unknown, fallback: string): string {
    const limit = limitRefusalMessage(err);
    if (limit) return limit;
    const status = (err as { status?: number } | null)?.status;
    const server = apiErrorMessage(err, fallback);
    switch (status) {
        case 403:
            return 'Only a lead of this Investigation can grant or revoke members. Server: ' + server;
        case 404:
            return (
                'Not available — you are not a member of this Investigation, or the person is not a member. Server: ' +
                server
            );
        case 422:
            return (
                'The change was refused — an Investigation always keeps at least one lead, so make someone else a lead first. Server: ' +
                server
            );
        case 409:
            return 'Someone changed the members at the same moment — reload and try again. Server: ' + server;
        default:
            return server;
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
    imports: [MatButtonModule, InspectoAlertComponent, StatusBadgeComponent],
    template: `
        <section
            class="flex flex-col gap-2 rounded-md border p-2 text-xs"
            style="border-color: var(--gamma-border)"
            aria-label="Members"
        >
            <h3 class="m-0 text-sm font-semibold">Members</h3>
            @if (error()) {
                <inspecto-alert variant="error" title="Members">{{ error() }}</inspecto-alert>
            }
            @if (hidden()) {
                <p class="text-secondary m-0">
                    The member list is not available to you — only members of this Investigation can see who else works
                    on it.
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
                            @if (isLead()) {
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
                @if (isLead()) {
                    <div class="flex items-center gap-2 border-t pt-1" style="border-color: var(--gamma-border)">
                        <input
                            class="rounded border px-1"
                            style="border-color: var(--gamma-border)"
                            maxlength="200"
                            placeholder="Subject id"
                            aria-label="Member subject"
                            [value]="subject()"
                            (input)="subject.set($any($event.target).value)"
                        />
                        <select
                            class="rounded border px-1"
                            style="border-color: var(--gamma-border)"
                            aria-label="Member role"
                            [value]="role()"
                            (change)="role.set($any($event.target).value)"
                        >
                            @for (r of roles; track r) {
                                <option [value]="r">{{ r }}</option>
                            }
                        </select>
                        <button mat-stroked-button [disabled]="busy() || !subject().trim()" (click)="grant()">
                            Grant
                        </button>
                    </div>
                } @else {
                    <p class="text-secondary m-0">
                        You are {{ d.you ?? 'a reader' }} here — only a lead can grant or revoke members.
                    </p>
                }
            }
        </section>
    `,
})
export class LinkAnalysisMembersComponent {
    readonly investigationId = input.required<string>();

    private readonly inv = inject(InvService);
    readonly roles = ROLES;

    readonly data = signal<InvestigationMembers | null>(null);
    readonly hidden = signal(false);
    readonly busy = signal(false);
    readonly error = signal('');
    readonly subject = signal('');
    readonly role = signal<InvestigationRole>('analyst');
    /** With no Subject attached (`you` null) nothing is enforced server-side, so the controls are offered. */
    readonly isLead = computed(() => {
        const d = this.data();
        return !!d && (d.you === 'lead' || d.you === null);
    });

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
