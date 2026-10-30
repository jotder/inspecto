import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { forkJoin, from, of } from 'rxjs';
import { catchError, map, mergeMap, toArray } from 'rxjs/operators';
import { apiErrorMessage, LensService } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import {
    EmbedGrantList,
    EmbedGrantSummary,
    InvestigationListItem,
    InvestigationRole,
    InvestigationMembers,
    InvService,
} from '@inspecto/link-analysis/api/inv.service';

/** One Investigation as the review shows it: who may open it, and the live links that show it outside. */
interface AccessCard {
    inv: InvestigationListItem;
    members: InvestigationMembers | null;
    /** Active embed links only; `null` when embed links are off on this host. */
    links: EmbedGrantSummary[] | null;
    error: string | null;
}

/** How many Investigations are read at once — each costs two reads. */
const READ_CONCURRENCY = 4;

/**
 * Investigation access (`LA-PRODUCT-NEXT-1` phase 3, operator 2026-10-30: leads only). One screen over the
 * Investigations the caller can ALREADY open (`GET /inv/investigations` lists exactly those): each one's members and its
 * live embed links, with Revoke where the caller is the lead. Nothing is widened — every read and every revoke is the
 * Investigation's own route, so a non-member never sees it here either, and the server still decides each revoke
 * (lead, `canManageIncidents` / `canShareInvestigation`, the last lead kept).
 */
@Component({
    selector: 'la-app-investigation-access-page',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        MatButtonModule,
        MatIconModule,
        MatTooltipModule,
        ChipComponent,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        InspectoPageHeaderComponent,
        InspectoSkeletonComponent,
    ],
    template: `
        <section class="mx-auto flex w-full max-w-4xl flex-col gap-4 p-6" aria-label="Investigation access">
            <inspecto-page-header
                title="Investigation access"
                subtitle="Who can open the Investigations you can open, and the live links that show them."
            />
            @if (truncatedNote(); as note) {
                <inspecto-alert variant="info">{{ note }}</inspecto-alert>
            }
            @if (loading()) {
                <inspecto-skeleton [lines]="4" height="3rem" />
            } @else if (cards().length === 0) {
                <inspecto-empty-state
                    icon="heroicons_outline:key"
                    title="No Investigations"
                    message="You cannot open any Investigation in this Space."
                />
            } @else {
                <div class="grid gap-3">
                    @for (card of cards(); track card.inv.id) {
                        <div class="bg-card flex flex-col gap-3 rounded-lg border p-4">
                            <div class="flex flex-wrap items-center gap-2">
                                <h2 class="m-0 text-sm font-semibold">{{ card.inv.title || card.inv.id }}</h2>
                                @if (card.members?.you; as you) {
                                    <inspecto-chip variant="soft" [tone]="you === 'lead' ? 'primary' : 'neutral'">
                                        You: {{ you }}
                                    </inspecto-chip>
                                }
                                <span class="text-secondary text-xs">{{ card.inv.dataset }}</span>
                            </div>
                            @if (card.inv.access === 'case-member') {
                                <p class="text-secondary m-0 text-sm">
                                    Shared with you through Case {{ card.inv.caseRef || '' }}. Its members are set on
                                    the Case.
                                </p>
                            }
                            @if (card.error) {
                                <inspecto-alert variant="warning">{{ card.error }}</inspecto-alert>
                            }
                            @if (card.members; as m) {
                                <div class="flex flex-col gap-1">
                                    <h3 class="text-secondary m-0 text-xs font-semibold tracking-wider uppercase">
                                        Members
                                    </h3>
                                    @for (member of m.members; track member.subject) {
                                        <div class="flex items-center gap-2 text-sm">
                                            <span>{{ member.subject }}</span>
                                            <span class="text-secondary">{{ member.role }}</span>
                                            <span class="flex-1"></span>
                                            @if (canRevokeMember(card, member)) {
                                                <button
                                                    mat-icon-button
                                                    type="button"
                                                    [attr.aria-label]="
                                                        'Revoke ' + member.subject + ' from ' + name(card)
                                                    "
                                                    matTooltip="Revoke"
                                                    [disabled]="busy()"
                                                    (click)="revokeMember(card, member.subject)"
                                                >
                                                    <mat-icon
                                                        class="icon-size-5"
                                                        svgIcon="heroicons_outline:user-minus"
                                                    />
                                                </button>
                                            }
                                        </div>
                                    }
                                </div>
                            }
                            @if (card.links; as links) {
                                <div class="flex flex-col gap-1">
                                    <h3 class="text-secondary m-0 text-xs font-semibold tracking-wider uppercase">
                                        Live embed links
                                    </h3>
                                    @for (link of links; track link.grantId) {
                                        <div class="flex items-center gap-2 text-sm">
                                            <span>{{ link.label || link.grantId }}</span>
                                            <span class="text-secondary">
                                                by {{ link.issuedBy }}, until {{ link.expiresAt.slice(0, 10) }},
                                                {{ link.viewCount }} {{ link.viewCount === 1 ? 'view' : 'views' }}
                                            </span>
                                            <span class="flex-1"></span>
                                            @if (canRevokeLink(card)) {
                                                <button
                                                    mat-icon-button
                                                    type="button"
                                                    [attr.aria-label]="'Revoke link ' + (link.label || link.grantId)"
                                                    matTooltip="Revoke"
                                                    [disabled]="busy()"
                                                    (click)="revokeLink(card, link)"
                                                >
                                                    <mat-icon
                                                        class="icon-size-5"
                                                        svgIcon="heroicons_outline:no-symbol"
                                                    />
                                                </button>
                                            }
                                        </div>
                                    } @empty {
                                        <span class="text-secondary text-sm">None.</span>
                                    }
                                </div>
                            }
                        </div>
                    }
                </div>
            }
        </section>
    `,
})
export class InvestigationAccessPageComponent {
    private readonly inv = inject(InvService);
    private readonly lens = inject(LensService);
    private readonly confirm = inject(InspectoConfirmService);
    private readonly toastr = inject(ToastrService);

    readonly loading = signal(true);
    readonly busy = signal(false);
    readonly cards = signal<AccessCard[]>([]);
    private readonly listed = signal<{ shown: number; total: number; truncated: boolean } | null>(null);

    readonly truncatedNote = computed(() => {
        const l = this.listed();
        return l?.truncated ? `Showing the first ${l.shown} of ${l.total} Investigations.` : null;
    });

    constructor() {
        this.load();
    }

    name(card: AccessCard): string {
        return card.inv.title || card.inv.id;
    }

    /**
     * The server decides; the button shows only where it would say yes: a lead with the capability, and never on the
     * Investigation's LAST lead (the server keeps one: 422). The owner always folds in as a lead, so an Investigation
     * nobody was added to lists just its owner, with no Revoke.
     */
    canRevokeMember(card: AccessCard, member: { role: InvestigationRole }): boolean {
        if (card.members?.you !== 'lead' || !this.lens.canManageIncidents()) return false;
        return member.role !== 'lead' || card.members.members.filter((m) => m.role === 'lead').length > 1;
    }

    canRevokeLink(card: AccessCard): boolean {
        return card.members?.you === 'lead' && this.lens.canShareInvestigation();
    }

    async revokeMember(card: AccessCard, subject: string): Promise<void> {
        const member = card.members?.members.find((m) => m.subject === subject);
        if (!member || !this.canRevokeMember(card, member) || this.busy()) return;
        const ok = await this.confirm.confirmDestructive(
            `Revoke ${subject} from '${this.name(card)}'? They can no longer open it.`,
            { title: 'Revoke member?', confirmText: 'Revoke' },
        );
        if (!ok) return;
        this.busy.set(true);
        this.inv.revokeInvestigationMember(card.inv.id, subject).subscribe({
            next: (members) => {
                this.patch(card.inv.id, { members });
                this.busy.set(false);
                this.toastr.success(`${subject} can no longer open '${this.name(card)}'`);
                // the server also revokes the links that member made: re-read, so none shows as live
                this.inv
                    .investigationEmbeds(card.inv.id)
                    .pipe(catchError(() => of(null)))
                    .subscribe((embeds) => this.patch(card.inv.id, { links: activeLinks(embeds) }));
            },
            error: (err) => {
                this.busy.set(false);
                this.toastr.error(apiErrorMessage(err, `Could not revoke ${subject}`));
            },
        });
    }

    async revokeLink(card: AccessCard, link: EmbedGrantSummary): Promise<void> {
        if (!this.canRevokeLink(card) || this.busy()) return;
        const label = link.label || link.grantId;
        const ok = await this.confirm.confirmDestructive(`Revoke the link '${label}'? It stops working at once.`, {
            title: 'Revoke link?',
            confirmText: 'Revoke',
        });
        if (!ok) return;
        this.busy.set(true);
        this.inv.revokeInvestigationEmbed(card.inv.id, link.grantId).subscribe({
            next: () => {
                this.patch(card.inv.id, { links: (card.links ?? []).filter((l) => l.grantId !== link.grantId) });
                this.busy.set(false);
                this.toastr.success(`The link '${label}' is revoked`);
            },
            error: (err) => {
                this.busy.set(false);
                this.toastr.error(apiErrorMessage(err, `Could not revoke the link '${label}'`));
            },
        });
    }

    private patch(id: string, change: Partial<AccessCard>): void {
        this.cards.update((cs) => cs.map((c) => (c.inv.id === id ? { ...c, ...change } : c)));
    }

    private load(): void {
        this.inv.listInvestigations().subscribe({
            next: (page) => {
                this.listed.set({ shown: page.items.length, total: page.total, truncated: page.truncated });
                from(page.items)
                    .pipe(
                        mergeMap((item) => this.read(item), READ_CONCURRENCY),
                        toArray(),
                    )
                    .subscribe((cards) => {
                        const order = page.items.map((i) => i.id);
                        this.cards.set(cards.sort((a, b) => order.indexOf(a.inv.id) - order.indexOf(b.inv.id)));
                        this.loading.set(false);
                    });
            },
            error: (err) => {
                this.loading.set(false);
                this.toastr.error(apiErrorMessage(err, 'Could not list your Investigations'));
            },
        });
    }

    /**
     * One Investigation's reads; a failing one degrades its own card, never the page. An Investigation shared through a
     * Case has no members to read (`/members` is members-only), so it is not asked.
     */
    private read(item: InvestigationListItem) {
        const caseShared = item.access === 'case-member';
        return forkJoin({
            members: caseShared ? of(null) : this.inv.investigationMembers(item.id).pipe(catchError(() => of(null))),
            embeds: this.inv.investigationEmbeds(item.id).pipe(catchError(() => of(null))),
        }).pipe(
            map(
                ({ members, embeds }): AccessCard => ({
                    inv: item,
                    members,
                    links: activeLinks(embeds),
                    error: members || caseShared ? null : 'Could not read its members.',
                }),
            ),
        );
    }
}

/** The live links of an answer, or null when embed links are off on this host (or the read failed). */
function activeLinks(embeds: EmbedGrantList | null): EmbedGrantSummary[] | null {
    return embeds?.enabled ? embeds.grants.filter((g) => g.status === 'active') : null;
}
