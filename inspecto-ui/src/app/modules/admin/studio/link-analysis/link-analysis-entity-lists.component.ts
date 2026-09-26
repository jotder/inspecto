import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { firstValueFrom } from 'rxjs';
import {
    EntityListDetail,
    EntityListSummary,
    InvService,
    LensService,
    SessionService,
    unmatchedCount,
} from 'app/inspecto/api';
import { LinkAnalysisSettingsService } from 'app/inspecto/api/link-analysis-settings.service';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { entityListErrorMessage, listableIds } from './investigation-state';
import { InvestigationSessionStore } from './link-analysis-investigation.store';
import {
    CreateEntityListDialog,
    CreateEntityListData,
    ENTITY_LIST_PURPOSES,
    EntityListReasonData,
    EntityListReasonDialog,
} from './link-analysis-entity-lists.dialogs';
import { isUnavailable } from './link-analysis-template.dialogs';

/**
 * **Link Analysis — Entity Lists** (LA-17 step 6, SPA half) inside the Investigation panel, over `/inv/entity-lists*`
 * (entity-model design §4.3.1): list the Space's lists, create one, add the selected canvas entity, retire one, and —
 * on the open Investigation — append `excludeBy` / `seedBy` through the store's op path, so undo, replay and the log
 * refresh work as for every other op.
 *
 * ⛔ Members are never displayed: they arrive masked per `maskingMode` and member browsing is out of scope, so a list
 * shows its SIZE only.
 */
@Component({
    selector: 'inspecto-link-analysis-entity-lists',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        MatButtonModule,
        MatIconModule,
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        InspectoSkeletonComponent,
        StatusBadgeComponent,
    ],
    host: { class: 'block' },
    template: `
        @if (session.geoLinkEnabled()) {
            <section class="flex flex-col gap-2 text-xs" aria-label="Entity Lists">
                <div class="flex items-center gap-1">
                    <h3 class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">Entity Lists</h3>
                    <button
                        mat-icon-button
                        class="ml-auto"
                        [disabled]="loading()"
                        (click)="load()"
                        matTooltip="Re-read the Entity Lists"
                        aria-label="Refresh the Entity Lists"
                    >
                        <mat-icon class="icon-size-4" svgIcon="heroicons_outline:arrow-path"></mat-icon>
                    </button>
                    @if (canWrite()) {
                        <button mat-stroked-button [disabled]="busy()" (click)="create()">New list…</button>
                    }
                </div>
                @if (!canWrite()) {
                    <p class="text-secondary m-0">
                        Changing Entity Lists needs the Incident-management capability — you can read them.
                    </p>
                }
                @if (error()) {
                    <inspecto-alert [variant]="unavailable() ? 'info' : 'error'" title="Entity Lists">{{
                        error()
                    }}</inspecto-alert>
                }
                @if (notice()) {
                    <inspecto-alert variant="success" title="Entity List">{{ notice() }}</inspecto-alert>
                }
                @if (lists(); as ls) {
                    @if (ls.length) {
                        @if (canWrite()) {
                            <p class="text-secondary m-0" aria-label="Selection for Entity Lists">
                                @if (store.selected(); as n) {
                                    Selected: <strong>{{ n.data.label }}</strong>
                                    @if (selection().masked) {
                                        — {{ selection().masked }} masked value(s) cannot be added (a list needs the
                                        real value).
                                    }
                                } @else {
                                    Click an entity on the canvas while an Investigation is open to add it to a list.
                                }
                            </p>
                        }
                        <ul class="m-0 flex list-none flex-col gap-1 p-0" aria-label="Entity Lists of this Space">
                            @for (l of ls; track l.id) {
                                <li class="rounded-md border px-2 py-1" style="border-color: var(--gamma-border)">
                                    <div class="flex items-center gap-1">
                                        <span class="min-w-0 truncate font-semibold">{{ l.title }}</span>
                                        @if (l.retired) {
                                            <inspecto-status-badge
                                                value="retired"
                                                label="Retired"
                                            ></inspecto-status-badge>
                                        }
                                    </div>
                                    <div class="text-secondary tabular-nums">
                                        {{ purposeLabel(l.purpose) }} · {{ typeLabel(l.entityType) }} · {{ l.size }}
                                        {{ l.size === 1 ? 'member' : 'members' }}
                                    </div>
                                    @if (canWrite() && !l.retired) {
                                        <div class="mt-1 flex flex-wrap gap-1">
                                            <button
                                                mat-button
                                                [disabled]="busy() || !selection().ids.length"
                                                (click)="addSelection(l)"
                                                [attr.aria-label]="'Add the selected entity to ' + l.title"
                                            >
                                                Add selection
                                            </button>
                                            @if (store.active()) {
                                                <button
                                                    mat-button
                                                    [disabled]="store.busy()"
                                                    (click)="excludeBy(l)"
                                                    [attr.aria-label]="'Exclude by list ' + l.title"
                                                >
                                                    Exclude by list
                                                </button>
                                                <button
                                                    mat-button
                                                    [disabled]="store.busy()"
                                                    (click)="seedBy(l)"
                                                    [attr.aria-label]="'Seed by list ' + l.title"
                                                >
                                                    Seed by list
                                                </button>
                                            }
                                            <button
                                                mat-button
                                                color="warn"
                                                [disabled]="busy()"
                                                (click)="retire(l)"
                                                [attr.aria-label]="'Retire ' + l.title"
                                            >
                                                Retire
                                            </button>
                                        </div>
                                    }
                                </li>
                            }
                        </ul>
                    } @else {
                        <inspecto-empty-state
                            title="No Entity Lists"
                            message="This Space has no Entity Lists yet. A list is a named set of Entity keys — allow, block, watch or exclusion — that an Investigation can exclude or seed by."
                        ></inspecto-empty-state>
                    }
                } @else if (loading()) {
                    <inspecto-skeleton [lines]="3"></inspecto-skeleton>
                }
            </section>
        }
    `,
})
export class LinkAnalysisEntityListsComponent {
    private inv = inject(InvService);
    private dialog = inject(MatDialog);
    private settings = inject(LinkAnalysisSettingsService);
    private lens = inject(LensService);
    readonly session = inject(SessionService);
    readonly store = inject(InvestigationSessionStore);

    readonly lists = signal<EntityListSummary[] | null>(null);
    readonly loading = signal(false);
    /** A list write is in flight (the store's own `busy` covers the list-bound ops). */
    readonly busy = signal(false);
    readonly error = signal('');
    readonly unavailable = signal(false);
    readonly notice = signal('');

    /** Entity List writes are gated server-side on `canManageIncidents`; the section asks the same question. */
    readonly canWrite = computed(() => this.lens.canManageIncidents());
    /** The selected canvas entity's raw values — masked pseudonyms are split out and never sent. */
    readonly selection = computed(() => listableIds(this.store.selected()));

    constructor() {
        // Off the module, every route 503s — no call for an explained absence the page already renders.
        if (this.session.geoLinkEnabled()) void this.load();
    }

    async load(): Promise<void> {
        this.loading.set(true);
        this.error.set('');
        this.unavailable.set(false);
        try {
            this.lists.set((await firstValueFrom(this.inv.listEntityLists())).lists);
        } catch (err) {
            this.lists.set(null);
            this.fail(err, 'Could not read the Entity Lists.');
        } finally {
            this.loading.set(false);
        }
    }

    purposeLabel(purpose: string): string {
        return ENTITY_LIST_PURPOSES.find((p) => p.value === purpose)?.label ?? purpose;
    }

    /** The Entity Type's label from `entityTypesInForce`; a type no longer in force says so (member writes 409). */
    typeLabel(id: string): string {
        const t = this.settings.limits().entityTypesInForce.find((x) => x.id === id);
        return t ? t.label : `${id} (not in force)`;
    }

    async create(): Promise<void> {
        const data: CreateEntityListData = { entityTypes: this.settings.limits().entityTypesInForce };
        const created = await firstValueFrom(
            this.dialog
                .open<CreateEntityListDialog, CreateEntityListData, EntityListDetail | undefined>(
                    CreateEntityListDialog,
                    {
                        data,
                        width: '32rem',
                    },
                )
                .afterClosed(),
        );
        if (!created) return;
        this.notice.set(`“${created.title}” created.`);
        await this.load();
    }

    async addSelection(list: EntityListSummary): Promise<void> {
        const { ids } = this.selection();
        if (!ids.length) return;
        const reason = await this.askReason({
            title: 'Add to Entity List',
            message: `Add ${ids.join(', ')} to “${list.title}”. The values are normalised as ${this.typeLabel(list.entityType)}.`,
            confirmLabel: 'Add',
            maxLength: 1000,
        });
        if (!reason) return;
        await this.write('Could not change the Entity List.', async () => {
            const res = await firstValueFrom(this.inv.changeEntityListMembers(list.id, { add: ids, reason }));
            this.notice.set(
                res.changed
                    ? `${res.changed} ${res.changed === 1 ? 'member' : 'members'} added to “${res.title}” — it now has ${res.size}.`
                    : `Nothing changed — “${res.title}” already holds ${ids.length === 1 ? 'that value' : 'those values'}.`,
            );
        });
    }

    async retire(list: EntityListSummary): Promise<void> {
        const reason = await this.askReason({
            title: 'Retire Entity List',
            message:
                `Retire “${list.title}”? A retired list keeps its history but can no longer change, and ` +
                'Investigations can no longer exclude or seed by it. This cannot be undone.',
            confirmLabel: 'Retire',
            destructive: true,
            maxLength: 1000,
        });
        if (!reason) return;
        await this.write('Could not retire the Entity List.', async () => {
            const res = await firstValueFrom(this.inv.retireEntityList(list.id, reason));
            this.notice.set(`“${res.title}” retired.`);
        });
    }

    /** §4.4.1 `excludeBy` — resolved at the list's head and sealed; the reason is required, as for `exclude`. */
    async excludeBy(list: EntityListSummary): Promise<void> {
        const reason = await this.askReason({
            title: 'Exclude by Entity List',
            message: `Exclude every entity of the Working Set — and every one a later expand would admit — whose key is on “${list.title}”, as the list is now.`,
            confirmLabel: 'Exclude',
            maxLength: 200,
        });
        if (!reason) return;
        this.notice.set('');
        if (await this.store.apply({ op: 'excludeBy', listId: list.id, reason })) {
            const r = this.store.lastStep()?.list;
            const unmatched = unmatchedCount(r);
            this.notice.set(
                `Excluded ${r?.removed ?? 'the'} ${r?.removed === 1 ? 'entity' : 'entities'} on “${list.title}”` +
                    (unmatched ? ` — ${unmatched} members matched nothing.` : '.'),
            );
        }
    }

    /** §4.4.1 `seedBy` — the server reads which raw values normalise to a member and seals them. */
    async seedBy(list: EntityListSummary): Promise<void> {
        this.notice.set('');
        if (await this.store.apply({ op: 'seedBy', listId: list.id })) {
            const r = this.store.lastStep()?.list;
            const unmatched = unmatchedCount(r);
            this.notice.set(
                `Seeded ${r?.seeded ?? 'the'} ${r?.seeded === 1 ? 'entity' : 'entities'} from “${list.title}”` +
                    (unmatched ? ` — ${unmatched} members matched no row.` : '.'),
            );
        }
    }

    private askReason(data: EntityListReasonData): Promise<string | undefined> {
        return firstValueFrom(
            this.dialog
                .open<EntityListReasonDialog, EntityListReasonData, string | undefined>(EntityListReasonDialog, {
                    data,
                    width: '28rem',
                })
                .afterClosed(),
        );
    }

    private async write(fallback: string, body: () => Promise<void>): Promise<void> {
        this.busy.set(true);
        this.error.set('');
        this.unavailable.set(false);
        this.notice.set('');
        try {
            await body();
            await this.load();
        } catch (err) {
            this.fail(err, fallback);
        } finally {
            this.busy.set(false);
        }
    }

    private fail(err: unknown, fallback: string): void {
        this.unavailable.set(isUnavailable(err));
        this.error.set(entityListErrorMessage(err, fallback));
    }
}
