import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { firstValueFrom, forkJoin, of, catchError, map } from 'rxjs';
import { apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { ChipComponent } from '@inspecto/core/components/chip.component';
import { DomainProfileId } from '@inspecto/link-analysis/graph/domain-profile';
import {
    GraphPropagatedRiskView,
    GraphRunResult,
    graphRunErrorMessage,
} from '@inspecto/link-analysis/api/graph-runs.service';
import { EntityListSummary, isHeldListChange } from '@inspecto/link-analysis/api/inv.service';
import { LinkAnalysisSettingsService } from './link-analysis-settings.service';
import { EntityListReasonData, EntityListReasonDialog } from './link-analysis-entity-lists.dialogs';
import { factorLine, nodeScoresFor, numberOf, riskMappingFor, riskOf, round1 } from './node-risk';
import { NodeRiskService, ReferenceTable } from './node-risk.service';
import { CaseHistoryService, EntityHistory, EntityHistoryStatus } from './case-history.service';
import { laLimit } from './la-limits';

/** What the node detail dialog's insight sections know about the clicked node and the graph around it. */
export interface NodeInsightContext {
    /** The canvas node id. */
    nodeId: string;
    label: string;
    /** The RAW key reference Datasets and Entity Lists are keyed by (the node's first raw spelling). */
    key: string;
    profileId: DomainProfileId;
    /** The Entity Type Entity Lists are filtered by (`msisdn` on the telecom profile); absent = every list. */
    entityType?: string;
    /** The Investigation whose Working Set the canvas draws; null = no server run is possible here. */
    investigationId: string | null;
    /** The cache key of the graph a propagated score belongs to (Investigation + Working Set hash). */
    graphKey: string | null;
    /** This node's server id, and every canvas node's (the run's `nodeScores` keys). */
    serverId: string | null;
    serverNodeIds: string[];
    /** A server origin id in the analyst's words (its canvas label when drawn). */
    originLabel: (serverId: string) => string;
    canRunGraph: boolean;
    canManageLists: boolean;
    /** Called after this dialog changed an Entity List (a direct add), so open list panels re-read their counts. */
    onListChanged?: () => void;
    /** Hide is offered in exploration only (an Investigation hides through its own op). */
    canHide: boolean;
}

interface Membership {
    list: EntityListSummary;
    matched: boolean;
}

/**
 * The node detail dialog's **Risk**, **Enrichment** and **Actions** sections (telecom demo S2 UI, storyboard W4 / W11).
 * Risk: the node's own indicator score and factor columns from the profile's indicators Dataset, plus - on demand - its
 * propagated score and top contributing origins from a `propagatedRisk` run over the Working Set seeded with those
 * scores. Enrichment: the mapped CRM/KYC row and Entity List membership. Actions: add to a watch / block list (reason
 * required, four-eyes aware) and hide on the canvas. Case history: the Incidents and Cases that reference the node's
 * entity, read only when the section is opened: `GET /objects/by-entity` for a raw key, `POST /inv/case-history` for a
 * masked one (the server resolves the alias; no raw number reaches the browser).
 */
@Component({
    selector: 'inspecto-la-node-insight',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, InspectoAlertComponent, ChipComponent, RouterLink, DatePipe],
    template: `
        <section class="mt-4" aria-labelledby="ni-risk">
            <h3 id="ni-risk" class="text-base font-semibold">Risk</h3>
            @if (!mapping()) {
                <p class="text-secondary text-sm" data-testid="risk-unmapped">
                    No indicators Dataset is mapped for this domain profile.
                </p>
            } @else {
                @if (indicators()?.truncated) {
                    <inspecto-alert variant="warning" data-testid="risk-truncated"
                        >{{ mapping()!.indicators.dataset }} matched more rows than one read returns: scores may be
                        missing.</inspecto-alert
                    >
                }
                @if (indicators()?.masked) {
                    <p class="text-secondary text-xs" data-testid="risk-masked">{{ maskedNote }}</p>
                }
                @if (indicators(); as t) {
                    @if (t.error) {
                        <inspecto-alert variant="warning"
                            >The indicators could not be read: {{ t.error }}</inspecto-alert
                        >
                    } @else if (ownRow(); as row) {
                        <dl class="mt-1 flex flex-col gap-1 text-sm" data-testid="risk-own">
                            <div class="flex items-baseline gap-3">
                                <dt class="text-secondary w-40 shrink-0">Indicator score</dt>
                                <dd class="font-semibold">{{ ownScore() ?? '—' }}</dd>
                            </div>
                            @for (f of factors(); track f.name) {
                                <div class="flex items-baseline gap-3">
                                    <dt class="text-secondary w-40 shrink-0">{{ f.name }}</dt>
                                    <dd>{{ f.value }}</dd>
                                </div>
                            }
                        </dl>
                    } @else {
                        <p class="text-secondary text-sm" data-testid="risk-no-row">
                            {{ ctx().label }} has no row in {{ mapping()!.indicators.dataset }}.
                        </p>
                    }
                } @else {
                    <p class="text-secondary text-sm">Reading indicators…</p>
                }

                @if (propagated(); as p) {
                    <div class="mt-2 text-sm" data-testid="risk-propagated">
                        <p>
                            Propagated score <span class="font-semibold">{{ round(p.score) }}</span> (raw
                            {{ round(p.raw) }}, own {{ round(p.own) }}, {{ p.contributors }}
                            {{ p.contributors === 1 ? 'contributor' : 'contributors' }})
                        </p>
                        @if (factorLines().length) {
                            <ul class="mt-1 list-inside list-disc">
                                @for (l of factorLines(); track $index) {
                                    <li>{{ l }}</li>
                                }
                            </ul>
                        }
                    </div>
                } @else if (riskComputed()) {
                    <p class="text-secondary mt-2 text-sm" data-testid="risk-none">
                        No propagated risk reaches this node.
                    </p>
                } @else {
                    <div class="mt-2 flex flex-wrap items-center gap-2">
                        <button
                            mat-stroked-button
                            data-testid="compute-risk"
                            [disabled]="!!computeBlocked() || computing()"
                            (click)="computeRisk()"
                        >
                            {{ computing() ? 'Computing…' : 'Compute risk' }}
                        </button>
                        @if (computeBlocked(); as why) {
                            <span class="text-secondary text-xs" data-testid="compute-blocked">{{ why }}</span>
                        } @else if (runState()) {
                            <span class="text-secondary text-xs" role="status">{{ runState() }}</span>
                        }
                    </div>
                }
                @if (riskError(); as e) {
                    <inspecto-alert variant="error">{{ e }}</inspecto-alert>
                }
            }
        </section>

        <section class="mt-4" aria-labelledby="ni-enrich">
            <h3 id="ni-enrich" class="text-base font-semibold">Enrichment</h3>
            @if (mapping()?.enrichment; as e) {
                @if (enrichment()?.truncated) {
                    <inspecto-alert variant="warning" data-testid="enrich-truncated"
                        >{{ e.dataset }} matched more rows than one read returns: this row may be missing.</inspecto-alert
                    >
                }
                @if (enrichment()?.masked) {
                    <p class="text-secondary text-xs" data-testid="enrich-masked">{{ maskedNote }}</p>
                }
                @if (enrichment(); as t) {
                    @if (t.error) {
                        <p class="text-secondary text-sm" data-testid="enrich-error">
                            {{ e.dataset }} could not be read: {{ t.error }}
                        </p>
                    } @else if (enrichRows().length) {
                        <dl class="mt-1 flex flex-col gap-1 text-sm" data-testid="enrich-rows">
                            @for (r of enrichRows(); track r.name) {
                                <div class="flex items-baseline gap-3">
                                    <dt class="text-secondary w-40 shrink-0">{{ r.name }}</dt>
                                    <dd class="min-w-0 break-words">{{ r.value }}</dd>
                                </div>
                            }
                        </dl>
                    } @else {
                        <p class="text-secondary text-sm">No {{ e.dataset }} row for {{ ctx().label }}.</p>
                    }
                } @else {
                    <p class="text-secondary text-sm">Reading {{ e.dataset }}…</p>
                }
            }
            <div class="mt-2 text-sm" data-testid="lists">
                <span class="text-secondary">Entity Lists:</span>
                @if (memberships(); as ms) {
                    @if (onLists().length) {
                        @for (m of onLists(); track m.list.id) {
                            <inspecto-chip class="ml-1" [tone]="m.list.purpose === 'block' ? 'warning' : 'neutral'"
                                >{{ m.list.purpose }}: {{ m.list.title }}</inspecto-chip
                            >
                        }
                    } @else {
                        <span class="ml-1">{{
                            ms.length ? 'on none of ' + ms.length : 'no list for this Entity Type'
                        }}</span>
                    }
                } @else if (listsError()) {
                    <span class="ml-1">{{ listsError() }}</span>
                } @else {
                    <span class="ml-1">checking…</span>
                }
            </div>
        </section>

        <section class="mt-4" aria-labelledby="ni-history">
            <h3 id="ni-history" class="text-base font-semibold">
                <button
                    type="button"
                    class="flex items-center gap-1"
                    data-testid="history-toggle"
                    aria-controls="ni-history-body"
                    [attr.aria-expanded]="historyOpen()"
                    (click)="toggleHistory()"
                >
                    <span aria-hidden="true">{{ historyOpen() ? '▾' : '▸' }}</span> Case history
                </button>
            </h3>
            @if (historyOpen()) {
                <div id="ni-history-body" class="mt-1 text-sm">
                    @if (historyBlocked(); as why) {
                        <p class="text-secondary" data-testid="history-unavailable">{{ why }}</p>
                    } @else {
                        <label class="text-secondary text-xs">
                            Show
                            <select
                                class="ml-1"
                                data-testid="history-status"
                                [value]="historyStatus()"
                                (change)="setHistoryStatus($any($event.target).value)"
                            >
                                <option value="all">all</option>
                                <option value="open">open</option>
                                <option value="closed">closed</option>
                            </select>
                        </label>
                        @if (history(); as h) {
                            @if (h.error) {
                                <inspecto-alert variant="warning" data-testid="history-error">{{ h.error }}</inspecto-alert>
                            } @else {
                                @if (h.truncated) {
                                    <inspecto-alert variant="warning" data-testid="history-truncated">
                                        @if (h.truncatedMessage) {
                                            {{ h.truncatedMessage }}
                                        } @else {
                                            Only the newest objects were searched: older Incidents or Cases may be
                                            missing.
                                        }
                                        @if (h.total != null) {
                                            ({{ h.total.toLocaleString('en') }} in all)
                                        }
                                    </inspecto-alert>
                                }
                                @if (historyItems().length) {
                                    <ul class="mt-1 flex flex-col gap-1" data-testid="history-list">
                                        @for (o of historyItems(); track o.kind + o.id) {
                                            <li class="flex flex-wrap items-baseline gap-2">
                                                <inspecto-chip [tone]="o.kind === 'case' ? 'primary' : 'neutral'">{{
                                                    o.kind === 'case' ? 'Case' : 'Incident'
                                                }}</inspecto-chip>
                                                <a [routerLink]="[o.kind === 'case' ? '/cases' : '/incidents', o.id]">{{
                                                    o.title || o.id
                                                }}</a>
                                                <span>{{ o.status }}</span>
                                                @if (o.severity) {
                                                    <span class="text-secondary">{{ o.severity }}</span>
                                                }
                                                <span class="text-secondary text-xs">{{ o.createdAt | date: 'medium' }}</span>
                                                @if (o.matchedOn === 'investigation') {
                                                    <span class="text-secondary text-xs" data-testid="history-via"
                                                        >via Investigation {{ o.via }}</span
                                                    >
                                                } @else if (o.matchedOn === 'snapshot') {
                                                    <span class="text-secondary text-xs" data-testid="history-via"
                                                        >via sealed snapshot</span
                                                    >
                                                } @else if (o.via) {
                                                    <span class="text-secondary text-xs" data-testid="history-via"
                                                        >via <a [routerLink]="['/incidents', o.via]">Incident {{ o.via }}</a></span
                                                    >
                                                }
                                            </li>
                                        }
                                    </ul>
                                } @else {
                                    <p class="text-secondary mt-1" data-testid="history-empty">
                                        No {{ historyStatus() === 'all' ? '' : historyStatus() + ' ' }}Incident or Case
                                        references {{ ctx().label }}.
                                    </p>
                                }
                            }
                        } @else {
                            <p class="text-secondary mt-1" role="status">Reading case history…</p>
                        }
                    }
                </div>
            }
        </section>

        <section class="mt-4" aria-labelledby="ni-actions">
            <h3 id="ni-actions" class="text-base font-semibold">Actions</h3>
            <div class="mt-1 flex flex-wrap items-center gap-2">
                @for (l of addTargets(); track l.id) {
                    <button
                        mat-stroked-button
                        [attr.data-testid]="'add-' + l.purpose + '-' + l.id"
                        [disabled]="!ctx().canManageLists || busy()"
                        (click)="addTo(l)"
                    >
                        Add to {{ l.purpose }} list: {{ l.title }}
                    </button>
                }
                @if (ctx().canHide) {
                    <button mat-stroked-button data-testid="hide-node" (click)="hide()">Hide on canvas</button>
                }
            </div>
            @if (!ctx().canManageLists && addTargets().length) {
                <p class="text-secondary mt-1 text-xs" data-testid="lists-forbidden">
                    Adding to a list needs the Manage incidents capability.
                </p>
            } @else if (memberships() && !eligibleLists().length) {
                <p class="text-secondary mt-1 text-xs" data-testid="lists-none">
                    No watch or block list can take this entity - create one in Entity Lists.
                </p>
            }
            @if (actionNote(); as n) {
                <inspecto-alert [variant]="n.variant">{{ n.text }}</inspecto-alert>
            }
        </section>
    `,
})
export class NodeInsightComponent implements OnInit {
    private readonly svc = inject(NodeRiskService);
    private readonly settings = inject(LinkAnalysisSettingsService);

    /** Reference rows come back masked under the Space's masking mode, like every other Link Analysis read. */
    readonly maskedNote = 'Entity masking is on: identifying values are shown as aliases.';
    /** The per-hop weights a run without `weights` takes; part of the risk cache key. */
    private readonly weights = computed(() =>
        (this.settings.limits()?.propagatedRiskWeightsInForce ?? [1, 0.6, 0.35, 0.15]).join(','),
    );
    private readonly dialog = inject(MatDialog);
    private readonly ref = inject(MatDialogRef, { optional: true });

    readonly ctx = input.required<NodeInsightContext>();

    readonly mapping = computed(() => riskMappingFor(this.ctx().profileId));
    readonly indicators = signal<ReferenceTable | null>(null);
    readonly enrichment = signal<ReferenceTable | null>(null);
    readonly riskResult = signal<GraphRunResult | null>(null);
    readonly computing = signal(false);
    readonly runState = signal('');
    readonly riskError = signal('');
    readonly memberships = signal<Membership[] | null>(null);
    readonly listsError = signal('');
    readonly busy = signal(false);
    readonly actionNote = signal<{ variant: 'success' | 'info' | 'error'; text: string } | null>(null);

    readonly ownRow = computed(() => this.indicators()?.byKey.get(this.ctx().key) ?? null);
    readonly ownScore = computed(() => {
        const m = this.mapping();
        return m ? numberOf(this.ownRow()?.[m.indicators.scoreCol]) : null;
    });
    readonly factors = computed(() => {
        const row = this.ownRow();
        const m = this.mapping();
        if (!row || !m) return [];
        return m.indicators.factorCols.filter((c) => c in row).map((c) => ({ name: c, value: String(row[c] ?? '—') }));
    });
    readonly enrichRows = computed(() => {
        const e = this.mapping()?.enrichment;
        const row = e && this.enrichment()?.byKey.get(this.ctx().key);
        return row
            ? Object.entries(row)
                  .filter(([k]) => k !== e!.keyCol)
                  .map(([name, v]) => ({ name, value: v === null || v === undefined ? '—' : String(v) }))
            : [];
    });
    readonly riskComputed = computed(() => this.riskResult() !== null);
    readonly propagated = computed<GraphPropagatedRiskView | null>(() => {
        const id = this.ctx().serverId;
        return id ? riskOf(this.riskResult(), id) : null;
    });
    readonly factorLines = computed(() =>
        (this.propagated()?.factors ?? []).map((f) => factorLine(f, this.ctx().originLabel(f.origin))),
    );
    readonly computeBlocked = computed(() => {
        const c = this.ctx();
        if (!c.investigationId || !c.graphKey)
            return 'Propagated risk runs on the server over an Investigation: open one to compute it.';
        if (!c.canRunGraph) return 'You are not allowed to run graph analysis on the server.';
        if (this.indicators()?.error) return 'The indicator scores could not be read.';
        if (this.indicators()?.truncated)
            return 'The indicators were only partly read: an incomplete score map would understate risk.';
        return '';
    });
    readonly onLists = computed(() => (this.memberships() ?? []).filter((m) => m.matched));
    /** Watch / block lists of this entity's type, whether or not it is already on them. */
    readonly eligibleLists = computed(() =>
        (this.memberships() ?? []).filter((m) => m.list.purpose === 'watch' || m.list.purpose === 'block'),
    );
    readonly addTargets = computed(() => this.eligibleLists().filter((m) => !m.matched).map((m) => m.list));
    /** Lists this dialog added the entity to: kept as members even if the re-check lags behind the write. */
    private readonly addedHere = new Set<string>();

    private readonly historySvc = inject(CaseHistoryService);
    readonly historyOpen = signal(false);
    readonly historyStatus = signal<EntityHistoryStatus>('all');
    readonly history = signal<EntityHistory | null>(null);
    /** Newest first (the server orders so too; kept explicit so the list never depends on it). */
    readonly historyItems = computed(() =>
        [...(this.history()?.items ?? [])].sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
    );
    /** A masked key (alias / pseudonym) is looked up on the server, which resolves it and picks the Entity Type. */
    readonly historyMasked = computed(() => this.ctx().key.startsWith('masked:'));
    /** Why no lookup is possible: a raw key with no Entity Type to look up as. */
    readonly historyBlocked = computed(() => {
        const c = this.ctx();
        if (!this.historyMasked() && !c.entityType)
            return 'Case history needs an Entity Type, and this domain profile names none.';
        return '';
    });

    round = round1;

    toggleHistory(): void {
        const open = !this.historyOpen();
        this.historyOpen.set(open);
        // read lazily on first open; a failed read is retried on the next open
        if (open && (!this.history() || this.history()!.error)) void this.loadHistory();
    }

    setHistoryStatus(status: EntityHistoryStatus): void {
        this.historyStatus.set(status);
        void this.loadHistory();
    }

    private async loadHistory(): Promise<void> {
        const c = this.ctx();
        if (this.historyBlocked()) return;
        this.history.set(null);
        const status = this.historyStatus();
        const h = this.historyMasked()
            ? await this.historySvc.byMaskedKey(c.key, c.investigationId, status)
            : await this.historySvc.byEntity(c.entityType!, c.key, status);
        if (status === this.historyStatus()) this.history.set(h);
    }

    ngOnInit(): void {
        const c = this.ctx();
        const m = this.mapping();
        if (m) {
            void this.svc.lookup(m.indicators, [c.key], c.investigationId).then((t) => this.indicators.set(t));
            if (m.enrichment)
                void this.svc.lookup(m.enrichment, [c.key], c.investigationId).then((t) => this.enrichment.set(t));
        }
        if (c.graphKey) this.riskResult.set(this.svc.cachedRisk(this.cacheKey(c.graphKey)));
        void this.loadMemberships();
    }

    async computeRisk(): Promise<void> {
        const c = this.ctx();
        const m = this.mapping();
        if (this.computeBlocked() || !m || !c.investigationId || !c.graphKey) return;
        this.computing.set(true);
        this.riskError.set('');
        try {
            // every drawn node's indicators, looked up by its server id (the server resolves aliases / pseudonyms)
            const table = await this.svc.lookup(m.indicators, c.serverNodeIds, c.investigationId);
            if (table.error) {
                this.riskError.set(`The indicator scores could not be read: ${table.error}`);
                return;
            }
            if (table.truncated) {
                this.riskError.set('The indicators were only partly read: an incomplete score map would understate risk.');
                return;
            }
            const { scores } = nodeScoresFor(c.serverNodeIds, table.byKey, m.indicators.scoreCol);
            const graphKey = this.cacheKey(c.graphKey);
            await new Promise<void>((resolve) =>
                this.svc.computeRisk(graphKey, c.investigationId!, scores).subscribe({
                    next: (v) => {
                        this.runState.set(v.status === 'QUEUED' ? 'Queued on the server…' : 'Running on the server…');
                        if (v.status === 'COMPLETED' && v.result) this.riskResult.set(v.result);
                        else if (v.status === 'BUDGET_EXCEEDED')
                            this.riskError.set(v.reason ?? 'The run hit its budget.');
                        else if (v.status === 'FAILED' || v.status === 'CANCELLED')
                            this.riskError.set(`The risk run ended ${v.status.toLowerCase()}.`);
                    },
                    error: (e) => {
                        this.riskError.set(graphRunErrorMessage(e, 'Propagated risk could not be computed.'));
                        resolve();
                    },
                    complete: () => resolve(),
                }),
            );
        } finally {
            this.computing.set(false);
            this.runState.set('');
        }
    }

    async addTo(list: EntityListSummary): Promise<void> {
        if (!this.ctx().canManageLists) return;
        const reason = await firstValueFrom(
            this.dialog
                .open<EntityListReasonDialog, EntityListReasonData, string | undefined>(EntityListReasonDialog, {
                    width: '28rem',
                    data: {
                        title: `Add to ${list.title}`,
                        message: `Add ${this.ctx().label} to the ${list.purpose} list "${list.title}".`,
                        confirmLabel: 'Add',
                        maxLength: laLimit('input.purpose_length'),
                    },
                })
                .afterClosed(),
        );
        if (!reason) return;
        this.busy.set(true);
        this.actionNote.set(null);
        try {
            const r = await firstValueFrom(this.svc.addMember(list.id, this.ctx().key, reason));
            if (isHeldListChange(r)) {
                this.actionNote.set({
                    variant: 'info',
                    text: `Waiting for approval: adding to "${list.title}" needs a second person to approve it.`,
                });
            } else {
                this.actionNote.set({ variant: 'success', text: `Added to "${list.title}".` });
                this.addedHere.add(list.id);
                this.ctx().onListChanged?.();
                await this.loadMemberships();
            }
        } catch (e) {
            const forbidden = e instanceof HttpErrorResponse && e.status === 403;
            this.actionNote.set({
                variant: 'error',
                text: forbidden
                    ? 'You are not allowed to change Entity Lists (it needs the Manage incidents capability).'
                    : apiErrorMessage(e, 'The entity could not be added to the list.'),
            });
        } finally {
            this.busy.set(false);
        }
    }

    private cacheKey(graphKey: string): string {
        return `${graphKey}|w=${this.weights()}`;
    }

    hide(): void {
        this.ref?.close('hide');
    }

    private async loadMemberships(): Promise<void> {
        const c = this.ctx();
        try {
            const idx = await firstValueFrom(this.svc.entityLists());
            const lists = idx.lists.filter(
                (l) => !l.retired && (!c.entityType || l.entityType.toLowerCase() === c.entityType.toLowerCase()),
            );
            const checks = lists.map((l) =>
                this.svc.match(l.id, [c.key]).pipe(
                    map((r) => ({ list: l, matched: !!r.matches[0]?.matched || this.addedHere.has(l.id) })),
                    catchError(() => of({ list: l, matched: this.addedHere.has(l.id) })),
                ),
            );
            this.memberships.set(checks.length ? await firstValueFrom(forkJoin(checks)) : []);
        } catch (e) {
            this.listsError.set(apiErrorMessage(e, 'the lists could not be read'));
        }
    }
}
