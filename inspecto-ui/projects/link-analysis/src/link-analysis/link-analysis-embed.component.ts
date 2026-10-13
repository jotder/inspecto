import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { LensService } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { StatusBadgeComponent } from '@inspecto/core/components/status-badge.component';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { EmbedGrantList, EmbedGrantSummary, InvService } from '@inspecto/link-analysis/api/inv.service';
import { LinkAnalysisSettingsService } from './link-analysis-settings.service';
import { EMBED_SCOPES, EmbedMintData, LinkAnalysisEmbedMintDialog, embedErrorMessage } from './link-analysis-embed-mint.dialog';

const FLAG_HINT = 'Embed links are disabled on this host; set -Dla.embed.enabled=true to enable them.';

/**
 * **Share (embed link)** (LA-EMBED-SPA-1) - the read-only embeddable view of the open Investigation: the grant list
 * (never a token) with Revoke, and a Mint dialog. Minting needs `canShareInvestigation` AND being a lead of the
 * Investigation; the feature is dark unless the host runs with `-Dla.embed.enabled=true`. Everything is decided by the
 * server; this panel only declines to offer what it would refuse and shows the server's reason in place.
 */
@Component({
    selector: 'inspecto-link-analysis-embed',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, InspectoAlertComponent, StatusBadgeComponent],
    template: `
        <section
            class="flex flex-col gap-2 rounded-md border p-2 text-xs"
            style="border-color: var(--gamma-border)"
            aria-label="Share (embed link)"
        >
            <h3 class="m-0 text-sm font-semibold">Share (embed link)</h3>
            @if (error()) {
                <inspecto-alert variant="error" title="Share">{{ error() }}</inspecto-alert>
            }
            @if (dark()) {
                <inspecto-alert variant="info" title="Share">{{ flagHint }}</inspecto-alert>
            }
            @if (loading()) {
                <p class="text-secondary m-0" role="status">Loading the links...</p>
            } @else if (data(); as d) {
                @if (d.grants.length === 0) {
                    <p class="text-secondary m-0">No embed links yet.</p>
                } @else {
                    <ul class="m-0 flex list-none flex-col gap-1 p-0">
                        @for (g of d.grants; track g.grantId) {
                            <li class="flex flex-wrap items-center gap-2">
                                <span>{{ g.label || 'Untitled link' }}</span>
                                <inspecto-status-badge [value]="tone(g)" [label]="g.status"></inspecto-status-badge>
                                <span class="text-secondary">expires {{ g.expiresAt }}</span>
                                <span class="text-secondary">{{ g.viewCount }} views</span>
                                <span class="text-secondary">{{ scopeLabel(g) }}, masking {{ g.maskingMode ?? 'as the Space' }}</span>
                                @if (g.status === 'active' && canMint()) {
                                    <button
                                        mat-button
                                        [disabled]="busy() || dark()"
                                        [attr.aria-label]="'Revoke link ' + (g.label || g.grantId)"
                                        (click)="revoke(g)"
                                    >
                                        Revoke
                                    </button>
                                }
                            </li>
                        }
                    </ul>
                }
            }
            @if (!lens.canShareInvestigation()) {
                <p class="text-secondary m-0">
                    Read-only - minting and revoking embed links needs the canShareInvestigation capability and being a
                    lead of this Investigation.
                </p>
            } @else if (!loading() && !isLead()) {
                <p class="text-secondary m-0">
                    Read-only - only a lead of this Investigation can mint or revoke embed links.
                </p>
            }
            @if (lens.canShareInvestigation() && isLead()) {
                <div>
                    <button mat-stroked-button [disabled]="busy() || dark() || loading()" (click)="openMint($event)">
                        Create link
                    </button>
                </div>
            }
        </section>
    `,
})
export class LinkAnalysisEmbedComponent {
    readonly investigationId = input.required<string>();

    protected readonly lens = inject(LensService);
    private readonly inv = inject(InvService);
    private readonly settings = inject(LinkAnalysisSettingsService);
    private readonly dialog = inject(MatDialog);
    private readonly confirm = inject(InspectoConfirmService);
    readonly flagHint = FLAG_HINT;

    readonly data = signal<EmbedGrantList | null>(null);
    readonly loading = signal(true);
    readonly busy = signal(false);
    readonly error = signal('');
    /** The feature is off on this host: the list said so, or a mint / revoke answered 503. */
    readonly dark = signal(false);
    /** The caller's role as the members route says; null `you` = no Subject attached, nothing enforced, controls offered. */
    private readonly role = signal<string | null | undefined>(undefined);
    readonly isLead = computed(() => this.role() === 'lead' || this.role() === null);
    readonly canMint = computed(() => this.lens.canShareInvestigation() && this.isLead());

    constructor() {
        effect(() => {
            const id = this.investigationId();
            untracked(() => this.load(id));
        });
    }

    scopeLabel(g: EmbedGrantSummary): string {
        return (EMBED_SCOPES.find((s) => s.value === (g.scope ?? 'full'))?.label ?? 'full view').toLowerCase();
    }

    tone(g: EmbedGrantSummary): string {
        return g.status === 'active' ? 'success' : g.status === 'revoked' ? 'error' : 'neutral';
    }

    private async load(id: string): Promise<void> {
        this.loading.set(true);
        this.data.set(null);
        this.error.set('');
        this.dark.set(false);
        this.role.set(undefined);
        try {
            const [list, members] = await Promise.all([
                firstValueFrom(this.inv.investigationEmbeds(id)),
                firstValueFrom(this.inv.investigationMembers(id)).catch(() => null),
            ]);
            if (id !== this.investigationId()) return;
            this.role.set(members ? members.you : 'reader');
            this.data.set(list);
            this.dark.set(!list.enabled);
        } catch (err) {
            if (id === this.investigationId()) this.error.set(embedErrorMessage(err, 'Could not read the embed links.'));
        } finally {
            if (id === this.investigationId()) this.loading.set(false);
        }
    }

    async openMint(event?: Event): Promise<void> {
        const opener = (event?.currentTarget ?? null) as HTMLElement | null;
        let force: { defaultTtlHours: number; maxTtlHours: number } | undefined;
        let mode = 'typed';
        try {
            const s = await firstValueFrom(this.settings.get());
            force = s.embedInForce;
            mode = s.maskingModeInForce ?? mode;
        } catch {
            // the server still validates the lifetime; fall back to its documented bounds
        }
        const data: EmbedMintData = {
            investigationId: this.investigationId(),
            defaultTtlHours: force?.defaultTtlHours ?? 24,
            maxTtlHours: force?.maxTtlHours ?? 720,
            maskingMode: mode,
        };
        const ref = this.dialog.open<LinkAnalysisEmbedMintDialog, EmbedMintData, boolean>(LinkAnalysisEmbedMintDialog, {
            data,
            restoreFocus: opener ?? true,
        });
        const minted = await firstValueFrom(ref.afterClosed());
        if (minted) await this.load(this.investigationId());
    }

    async revoke(g: EmbedGrantSummary): Promise<void> {
        const ok = await this.confirm.confirmDestructive(
            'Revoke this link? Anyone holding it loses access at once. This cannot be undone.',
            { title: 'Revoke embed link', confirmText: 'Revoke' },
        );
        if (!ok) return;
        this.busy.set(true);
        this.error.set('');
        try {
            await firstValueFrom(this.inv.revokeInvestigationEmbed(this.investigationId(), g.grantId));
            this.busy.set(false);
            await this.load(this.investigationId());
        } catch (err) {
            if ((err as { status?: number } | null)?.status === 503) this.dark.set(true);
            else this.error.set(embedErrorMessage(err, 'Could not revoke the link.'));
        } finally {
            this.busy.set(false);
        }
    }
}
