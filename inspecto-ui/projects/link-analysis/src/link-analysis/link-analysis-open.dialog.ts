import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { toSignal } from '@angular/core/rxjs-interop';
import { firstValueFrom } from 'rxjs';
import { ComponentsService, TagsService, apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { InvService, InvestigationListItem } from '@inspecto/link-analysis/api/inv.service';
import { InvestigationSessionStore } from './link-analysis-investigation.store';
import { LinkAnalysisService, LinkAnalysisView, SAVED_VIEW_NOT_EVIDENCE } from './link-analysis.service';

/**
 * LA-STUDIO-UI-REORG-1 §5.11 (UIR-D12): the ONE Open dialog. Two tabs - Saved views and Investigations - each a search,
 * filters, a list and a preview pane. It replaces the saved-views menu, *List Investigations*, *Open by id* (now a search
 * on an id) and the remembered-Investigations picker (now the Recent filter).
 *
 * Rename and Duplicate need no new route (OP-2): a view's name travels as `title` in its content, so Rename is an
 * update of the same id, and Duplicate is a create under a fresh id. There is no thumbnail: nothing stores one, and
 * a view re-projects live data, so a picture of the last draw would show a graph the view may no longer draw.
 */
export interface LaOpenDialogData {
    tab?: OpenTab;
    /** Comments and Tags need the ops module. */
    opsEnabled: boolean;
    shareable(view: LinkAnalysisView): boolean;
    history(view: LinkAnalysisView): void;
    comments(view: LinkAnalysisView): void;
    tags(view: LinkAnalysisView): void;
    offer(view: LinkAnalysisView): void;
    /** The page's Investigation session (component-scoped, so it is handed over, not injected). */
    store: InvestigationSessionStore;
    /** May delete an Investigation (the host's write capability). */
    canDeleteInvestigations: boolean;
    /** Why Rename / Duplicate cannot write a view here (`view-access.ts`); ''/absent = they can. */
    viewSaveBlock?: string;
    /** Why Delete cannot remove this view here (capability, shared-view owner rule); ''/absent = it can. */
    viewDeleteBlock?(view: LinkAnalysisView): string;
}

export type LaOpenResult =
    | { kind: 'view'; view: LinkAnalysisView }
    | { kind: 'investigation'; id: string; title?: string };

type OpenTab = 'views' | 'investigations';

/** A saved view as the list shows it: the view plus the file's last-changed time. */
export interface OpenViewRow {
    view: LinkAnalysisView;
    modified: string | null;
}

/** An Investigation row: the server's listing, or a Recent one this screen remembers but the listing did not return. */
export interface OpenInvestigationRow {
    id: string;
    title: string | null;
    dataset: string;
    owner: string | null;
    changed: string | null;
    steps: number | null;
    caseRef?: string;
    readOnly: boolean;
    recent: boolean;
    /** Returned by the server listing (a Recent-only row is not known to be readable or deletable). */
    listed: boolean;
}

const DAY = 86_400_000;

/** The Open list's order: most recently changed first (the default), or by name (A-Z). */
type OpenSort = 'name' | 'changed';

/** The Dataset a view reads: its Start's, else its projection's. */
export function viewDataset(view: LinkAnalysisView): string {
    const q = view.query as { projection?: { datasetId?: string } };
    return view.start?.datasetId ?? q.projection?.datasetId ?? '';
}

/** A fresh id for a copy: `<id>-copy`, then `-copy-2`, … never one already taken. */
export function copyId(id: string, taken: ReadonlySet<string>): string {
    let n = 1;
    let next = `${id}-copy`;
    while (taken.has(next)) next = `${id}-copy-${++n}`;
    return next;
}

/** Whether an ISO time falls within the last `days` days (`days` 0 = any time; an unknown time only matches "any"). */
export function withinDays(iso: string | null, days: number, now = Date.now()): boolean {
    if (!days) return true;
    const t = iso ? Date.parse(iso) : NaN;
    return Number.isFinite(t) && now - t <= days * DAY;
}

@Component({
    selector: 'la-open-dialog',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatTooltipModule,
        InspectoAlertComponent,
    ],
    template: `
        <h2 mat-dialog-title>Open</h2>
        <mat-dialog-content class="flex flex-col gap-3" style="min-width: min(56rem, 90vw)">
            <div class="flex gap-1" role="tablist" aria-label="What to open">
                @for (t of tabs; track t.id) {
                    <button
                        mat-button
                        role="tab"
                        [attr.aria-selected]="tab() === t.id"
                        [color]="tab() === t.id ? 'primary' : undefined"
                        [attr.data-testid]="'la-open-tab-' + t.id"
                        (click)="setTab(t.id)"
                    >
                        {{ t.label }}
                    </button>
                }
            </div>

            <div class="flex flex-wrap items-end gap-2">
                <mat-form-field class="min-w-48 flex-1" subscriptSizing="dynamic">
                    <mat-label>{{ tab() === 'views' ? 'Search name or description' : 'Search title or id' }}</mat-label>
                    <input matInput [formControl]="search" data-testid="la-open-search" />
                </mat-form-field>
                @if (tab() === 'views') {
                    <mat-form-field class="w-40" subscriptSizing="dynamic">
                        <mat-label>Tag</mat-label>
                        <input matInput [formControl]="tag" (keydown.enter)="applyTag()" (blur)="applyTag()" />
                    </mat-form-field>
                } @else {
                    <mat-form-field class="w-40" subscriptSizing="dynamic">
                        <mat-label>Owner</mat-label>
                        <mat-select [formControl]="owner">
                            <mat-option value="">Anyone</mat-option>
                            @for (o of owners(); track o) {
                                <mat-option [value]="o">{{ o }}</mat-option>
                            }
                        </mat-select>
                    </mat-form-field>
                    <mat-form-field class="w-44" subscriptSizing="dynamic">
                        <mat-label>Status</mat-label>
                        <mat-select [formControl]="status">
                            <mat-option value="">Any</mat-option>
                            <mat-option value="recent">Recent (this screen)</mat-option>
                            <mat-option value="owner">Mine</mat-option>
                            <mat-option value="case">Shared through a Case</mat-option>
                        </mat-select>
                    </mat-form-field>
                }
                <mat-form-field class="w-40" subscriptSizing="dynamic">
                    <mat-label>Dataset</mat-label>
                    <mat-select [formControl]="dataset">
                        <mat-option value="">Any</mat-option>
                        @for (d of datasets(); track d) {
                            <mat-option [value]="d">{{ d }}</mat-option>
                        }
                    </mat-select>
                </mat-form-field>
                <mat-form-field class="w-36" subscriptSizing="dynamic">
                    <mat-label>Sort by</mat-label>
                    <mat-select [formControl]="sortBy" data-testid="la-open-sort">
                        <mat-option value="name">Name</mat-option>
                        <mat-option value="changed">Last changed</mat-option>
                    </mat-select>
                </mat-form-field>
                <mat-form-field class="w-36" subscriptSizing="dynamic">
                    <mat-label>Changed</mat-label>
                    <mat-select [formControl]="days">
                        <mat-option [value]="0">Any time</mat-option>
                        <mat-option [value]="1">Today</mat-option>
                        <mat-option [value]="7">Last 7 days</mat-option>
                        <mat-option [value]="30">Last 30 days</mat-option>
                    </mat-select>
                </mat-form-field>
            </div>

            @if (error()) {
                <inspecto-alert variant="error" title="Open">{{ error() }}</inspecto-alert>
            }

            <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <ul class="m-0 flex max-h-96 list-none flex-col gap-0.5 overflow-auto p-0" aria-label="Results">
                    @if (tab() === 'views') {
                        @for (r of viewRows(); track r.view.id) {
                            <li>
                                <button
                                    type="button"
                                    class="w-full rounded px-2 py-1 text-left text-sm"
                                    [class.bg-hover]="selectedViewId() === r.view.id"
                                    [attr.aria-pressed]="selectedViewId() === r.view.id"
                                    [attr.data-testid]="'la-open-view-' + r.view.id"
                                    (click)="selectView(r.view.id)"
                                    (dblclick)="openView(r.view)"
                                >
                                    <span class="font-medium">{{ r.view.name }}</span>
                                    <span class="text-secondary"> — {{ datasetOf(r.view) || 'no Dataset' }}</span>
                                </button>
                            </li>
                        } @empty {
                            <li class="text-secondary px-2 text-sm">
                                {{ loading() ? 'Loading…' : allViews().length ? 'No saved view matches.' : 'Nothing saved yet: draw a graph, then File ▸ Save view.' }}
                            </li>
                        }
                    } @else {
                        @for (r of investigationRows(); track r.id) {
                            <li>
                                <button
                                type="button"
                                class="w-full rounded px-2 py-1 text-left text-sm"
                                [class.bg-hover]="selectedInvestigationId() === r.id"
                                [attr.aria-pressed]="selectedInvestigationId() === r.id"
                                [attr.data-testid]="'la-open-inv-' + r.id"
                                (click)="selectInvestigation(r.id)"
                                (dblclick)="openInvestigation(r)"
                            >
                                <span class="font-medium">{{ r.title || r.id }}</span>
                                @if (r.title) {
                                    <span class="text-secondary"> ({{ r.id }})</span>
                                }
                                @if (r.recent) {
                                    <span class="text-secondary"> · recent</span>
                                }
                                </button>
                            </li>
                        } @empty {
                            <li class="text-secondary px-2 text-sm">
                                {{ loading() ? 'Loading…' : 'No Investigation you may read matches.' }}
                            </li>
                        }
                        @if (idCandidate(); as id) {
                            <li class="px-2 text-sm">
                                <button mat-button (click)="openInvestigation({ id, title: null })" data-testid="la-open-by-id">
                                    Open Investigation by id “{{ id }}”
                                </button>
                            </li>
                        }
                        @if (truncated()) {
                            <li class="text-secondary px-2 text-xs">More Investigations exist than are listed: search narrows the listing.</li>
                        }
                    }
                </ul>

                <section class="flex flex-col gap-2 text-sm" aria-label="Preview" data-testid="la-open-preview">
                    @if (tab() === 'views') {
                        @if (selectedView(); as r) {
                            @if (renaming()) {
                                <form class="flex items-start gap-2" [formGroup]="renameForm" (ngSubmit)="rename(r.view)">
                                    <mat-form-field class="flex-1">
                                        <mat-label>Name</mat-label>
                                        <input matInput formControlName="name" data-testid="la-open-rename-input" />
                                        @if (renameTo.hasError('required')) {
                                            <mat-error>A name is required.</mat-error>
                                        }
                                    </mat-form-field>
                                    <button mat-stroked-button type="submit" [disabled]="busy()">Rename</button>
                                    <button mat-button type="button" (click)="renaming.set(false)">Cancel</button>
                                </form>
                            } @else {
                                <h3 class="m-0 text-sm font-semibold">{{ r.view.name }}</h3>
                            }
                            <p class="m-0">{{ r.view.description || 'No description.' }}</p>
                            <dl class="m-0 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5">
                                <dt class="text-secondary">Dataset</dt><dd class="m-0">{{ datasetOf(r.view) || '—' }}</dd>
                                <dt class="text-secondary">Counts</dt><dd class="m-0">Counted when opened (the view re-projects live data)</dd>
                                <dt class="text-secondary">Owner</dt><dd class="m-0">Not recorded for a saved view</dd>
                                <dt class="text-secondary">Last changed</dt><dd class="m-0">{{ when(r.modified) }}</dd>
                                <dt class="text-secondary">Investigations</dt><dd class="m-0">{{ r.view.investigations?.length ?? 0 }}</dd>
                                <dt class="text-secondary">Pack</dt><dd class="m-0">{{ r.view.profile || 'generic' }}</dd>
                            </dl>
                            <p class="text-secondary m-0 text-xs leading-snug" data-testid="la-open-not-evidence">
                                <span class="text-warn font-semibold">Not evidence</span> — {{ notEvidence }}
                            </p>
                            <div class="flex flex-wrap gap-1">
                                <button mat-flat-button color="primary" (click)="openView(r.view)" data-testid="la-open-open">Open</button>
                                <span class="inline-flex" [matTooltip]="data.viewSaveBlock || ''">
                                    <button mat-button (click)="beginRename(r.view)" [disabled]="busy() || !!data.viewSaveBlock">Rename</button>
                                </span>
                                <span class="inline-flex" [matTooltip]="data.viewSaveBlock || ''">
                                    <button mat-button (click)="duplicate(r.view)" [disabled]="busy() || !!data.viewSaveBlock" data-testid="la-open-duplicate">Duplicate</button>
                                </span>
                                <span class="inline-flex" [matTooltip]="deleteBlock(r.view)">
                                    <button mat-button (click)="deleteView(r.view)" [disabled]="busy() || !!deleteBlock(r.view)" data-testid="la-open-delete">Delete</button>
                                </span>
                                <button mat-button (click)="data.history(r.view)">Version history</button>
                                @if (data.opsEnabled) {
                                    <button mat-button (click)="data.comments(r.view)">Comments</button>
                                    <button mat-button (click)="data.tags(r.view)">Tags</button>
                                }
                                @if (data.shareable(r.view)) {
                                    <button mat-button (click)="data.offer(r.view)">Offer for sharing</button>
                                }
                            </div>
                        } @else {
                            <p class="text-secondary m-0">Pick a view to see it here.</p>
                        }
                    } @else {
                        @if (selectedInvestigation(); as r) {
                            <h3 class="m-0 text-sm font-semibold">{{ r.title || r.id }}</h3>
                            <dl class="m-0 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5">
                                <dt class="text-secondary">Id</dt><dd class="m-0">{{ r.id }}</dd>
                                <dt class="text-secondary">Dataset</dt><dd class="m-0">{{ r.dataset || '—' }}</dd>
                                <dt class="text-secondary">Steps</dt><dd class="m-0">{{ r.steps ?? '—' }}</dd>
                                <dt class="text-secondary">Owner</dt><dd class="m-0">{{ r.owner || '—' }}</dd>
                                <dt class="text-secondary">Created</dt><dd class="m-0">{{ when(r.changed) }}</dd>
                                <dt class="text-secondary">Linked Case</dt><dd class="m-0">{{ r.caseRef || '—' }}{{ r.readOnly ? ' (read-only)' : '' }}</dd>
                                <dt class="text-secondary">Pack</dt><dd class="m-0" data-testid="la-open-inv-pack">{{ investigationPack() }}</dd>
                            </dl>
                            <div class="flex flex-wrap gap-1">
                                <button mat-flat-button color="primary" (click)="openInvestigation(r)" data-testid="la-open-open">Open</button>
                                @if (data.canDeleteInvestigations && !r.readOnly && r.listed) {
                                    <button mat-button (click)="deleteInvestigation(r)" [disabled]="busy()">Delete</button>
                                }
                            </div>
                        } @else {
                            <p class="text-secondary m-0">Pick an Investigation to see it here.</p>
                        }
                    }
                </section>
            </div>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class LinkAnalysisOpenDialog {
    readonly data = inject<LaOpenDialogData>(MAT_DIALOG_DATA);
    private ref = inject(MatDialogRef<LinkAnalysisOpenDialog, LaOpenResult>);
    private components = inject(ComponentsService);
    private viewsService = inject(LinkAnalysisService);
    private tagsService = inject(TagsService);
    private inv = inject(InvService);
    private confirm = inject(InspectoConfirmService);

    readonly notEvidence = SAVED_VIEW_NOT_EVIDENCE;
    readonly tabs: { id: OpenTab; label: string }[] = [
        { id: 'views', label: 'Saved views' },
        { id: 'investigations', label: 'Investigations' },
    ];
    private store = this.data.store;
    readonly tab = signal<OpenTab>(this.data.tab ?? 'views');

    readonly search = new FormControl('', { nonNullable: true });
    readonly tag = new FormControl('', { nonNullable: true });
    readonly owner = new FormControl('', { nonNullable: true });
    readonly status = new FormControl('', { nonNullable: true });
    readonly dataset = new FormControl('', { nonNullable: true });
    readonly days = new FormControl(0, { nonNullable: true });
    readonly sortBy = new FormControl<OpenSort>('changed', { nonNullable: true });
    private readonly sortValue = toSignal(this.sortBy.valueChanges, { initialValue: 'changed' as OpenSort });
    /** The selected Investigation's stored pack, read from its header (the listing does not carry it). */
    readonly investigationPack = signal('—');
    readonly renameForm = new FormGroup({ name: new FormControl('', { nonNullable: true, validators: [Validators.required] }) });
    readonly renameTo = this.renameForm.controls.name;
    private readonly searchText = toSignal(this.search.valueChanges, { initialValue: '' });
    private readonly ownerValue = toSignal(this.owner.valueChanges, { initialValue: '' });
    private readonly statusValue = toSignal(this.status.valueChanges, { initialValue: '' });
    private readonly datasetValue = toSignal(this.dataset.valueChanges, { initialValue: '' });
    private readonly daysValue = toSignal(this.days.valueChanges, { initialValue: 0 });

    readonly loading = signal(false);
    readonly busy = signal(false);
    readonly error = signal('');
    readonly renaming = signal(false);
    readonly allViews = signal<OpenViewRow[]>([]);
    /** The view ids carrying the Tag filter's tag; null = no tag filter. */
    private readonly taggedIds = signal<Set<string> | null>(null);
    private readonly listed = signal<InvestigationListItem[] | null>(null);
    readonly truncated = signal(false);
    readonly selectedViewId = signal<string | null>(null);
    readonly selectedInvestigationId = signal<string | null>(null);

    readonly datasetOf = viewDataset;
    when(iso: string | null): string {
        return iso ? iso.slice(0, 16).replace('T', ' ') : '—';
    }

    readonly viewRows = computed(() => {
        const q = this.searchText().trim().toLowerCase();
        const ds = this.datasetValue();
        const days = this.daysValue();
        const tagged = this.taggedIds();
        return this.allViews()
            .filter(
                (r) =>
                    (!q || `${r.view.name} ${r.view.description ?? ''} ${r.view.id}`.toLowerCase().includes(q)) &&
                    (!ds || viewDataset(r.view) === ds) &&
                    (!tagged || tagged.has(r.view.id)) &&
                    withinDays(r.modified, days),
            )
            .sort((a, b) =>
                this.sortValue() === 'changed'
                    ? (b.modified ?? '').localeCompare(a.modified ?? '')
                    : a.view.name.localeCompare(b.view.name),
            );
    });

    /** Listed Investigations plus the Recent ones (remembered by this screen) the listing did not return. */
    private readonly allInvestigations = computed<OpenInvestigationRow[]>(() => {
        const recentIds = new Set(this.store.refs().map((r) => r.id));
        const rows: OpenInvestigationRow[] = (this.listed() ?? []).map((i) => ({
            id: i.id,
            title: i.title,
            dataset: i.dataset,
            owner: i.owner,
            changed: i.createdAt,
            steps: i.headStep,
            caseRef: i.caseRef,
            readOnly: i.readOnly,
            recent: recentIds.has(i.id),
            listed: true,
        }));
        const seen = new Set(rows.map((r) => r.id));
        for (const r of this.store.refs())
            if (!seen.has(r.id))
                rows.push({ id: r.id, title: r.title ?? null, dataset: '', owner: null, changed: null, steps: null, readOnly: false, recent: true, listed: false });
        return rows;
    });

    readonly investigationRows = computed(() => {
        const q = this.searchText().trim().toLowerCase();
        const owner = this.ownerValue();
        const status = this.statusValue();
        const ds = this.datasetValue();
        const days = this.daysValue();
        return this.allInvestigations()
            .filter(
            (r) =>
                (!q || `${r.title ?? ''} ${r.id}`.toLowerCase().includes(q)) &&
                (!owner || r.owner === owner) &&
                (!ds || r.dataset === ds) &&
                (status !== 'recent' || r.recent) &&
                (status !== 'owner' || (!r.readOnly && r.owner !== null)) &&
                (status !== 'case' || !!r.caseRef) &&
                withinDays(r.changed, days),
            )
            .sort((a, b) =>
                this.sortValue() === 'changed'
                    ? (b.changed ?? '').localeCompare(a.changed ?? '')
                    : (a.title || a.id).localeCompare(b.title || b.id),
            );
    });

    /** The search text as an id to open directly, when it names no listed Investigation (Open by id, kept as a search). */
    readonly idCandidate = computed(() => {
        const q = this.searchText().trim();
        if (!q || q.length > 200 || /\s/.test(q)) return '';
        return this.allInvestigations().some((r) => r.id === q) ? '' : q;
    });

    readonly owners = computed(() =>
        [...new Set(this.allInvestigations().map((r) => r.owner).filter((o): o is string => !!o))].sort(),
    );
    readonly datasets = computed(() =>
        [
            ...new Set(
                this.tab() === 'views'
                    ? this.allViews().map((r) => viewDataset(r.view))
                    : this.allInvestigations().map((r) => r.dataset),
            ),
        ]
            .filter((d) => !!d)
            .sort(),
    );

    readonly selectedView = computed(() => this.viewRows().find((r) => r.view.id === this.selectedViewId()) ?? null);
    readonly selectedInvestigation = computed(
        () => this.investigationRows().find((r) => r.id === this.selectedInvestigationId()) ?? null,
    );

    constructor() {
        void this.loadViews();
        if (this.tab() === 'investigations') void this.loadInvestigations();
    }

    setTab(tab: OpenTab): void {
        this.tab.set(tab);
        this.dataset.setValue('');
        if (tab === 'investigations' && this.listed() === null) void this.loadInvestigations();
    }

    selectInvestigation(id: string): void {
        this.selectedInvestigationId.set(id);
        this.investigationPack.set('…');
        this.inv.investigationLog(id, 1).subscribe({
            next: (log) => {
                if (this.selectedInvestigationId() === id) this.investigationPack.set(log.header.pack || "none (the Space's pack)");
            },
            error: () => {
                if (this.selectedInvestigationId() === id) this.investigationPack.set('—');
            },
        });
    }

    selectView(id: string): void {
        this.selectedViewId.set(id);
        this.renaming.set(false);
    }

    async loadViews(): Promise<void> {
        this.loading.set(true);
        try {
            const defs = await firstValueFrom(this.components.list('link-analysis-view'));
            this.allViews.set(
                defs.map((d) => ({ view: this.viewsService.fromContent(d.name, d.content), modified: d.modified ?? null })),
            );
        } catch (err) {
            this.error.set(apiErrorMessage(err, 'Could not list the saved views.'));
        } finally {
            this.loading.set(false);
        }
    }

    async loadInvestigations(): Promise<void> {
        this.loading.set(true);
        try {
            const res = await firstValueFrom(this.inv.listInvestigations());
            this.listed.set(res.items);
            this.truncated.set(res.truncated);
        } catch (err) {
            this.listed.set([]);
            this.error.set(apiErrorMessage(err, 'Could not list the Investigations.'));
        } finally {
            this.loading.set(false);
        }
    }

    async applyTag(): Promise<void> {
        const tag = this.tag.value.trim();
        if (!tag) {
            this.taggedIds.set(null);
            return;
        }
        try {
            const hits = await firstValueFrom(this.tagsService.targets(tag));
            this.taggedIds.set(new Set(hits.filter((h) => h.targetKind === 'link-analysis-view').map((h) => h.targetId)));
        } catch {
            this.taggedIds.set(new Set()); // an unknown tag carries nothing
        }
    }

    openView(view: LinkAnalysisView): void {
        this.ref.close({ kind: 'view', view });
    }

    openInvestigation(r: { id: string; title: string | null }): void {
        this.ref.close({ kind: 'investigation', id: r.id, title: r.title ?? undefined });
    }

    /** Why this view cannot be deleted here; '' = it can. */
    deleteBlock(view: LinkAnalysisView): string {
        return this.data.viewDeleteBlock?.(view) ?? '';
    }

    beginRename(view: LinkAnalysisView): void {
        this.renameTo.setValue(view.name);
        this.renaming.set(true);
    }

    /** Rename = an update of the same id with a new `title` (OP-2: no new route). */
    async rename(view: LinkAnalysisView): Promise<void> {
        this.renameTo.markAsTouched();
        const name = this.renameTo.value.trim();
        if (!name) return;
        await this.write(async () => {
            const renamed = { ...view, name };
            await firstValueFrom(this.viewsService.save(renamed, { update: true }));
            this.allViews.update((all) => all.map((r) => (r.view.id === view.id ? { ...r, view: renamed } : r)));
            this.renaming.set(false);
        }, 'Renaming the view failed.');
    }

    async duplicate(view: LinkAnalysisView): Promise<void> {
        await this.write(async () => {
            const id = copyId(view.id, new Set(this.allViews().map((r) => r.view.id)));
            const copy = { ...view, id, name: `Copy of ${view.name}` };
            await firstValueFrom(this.viewsService.save(copy));
            this.allViews.update((all) => [...all, { view: copy, modified: new Date().toISOString() }]);
            this.selectView(id);
        }, 'Duplicating the view failed.');
    }

    async deleteView(view: LinkAnalysisView): Promise<void> {
        const ok = await this.confirm.confirmDestructive(
            `Delete the saved view “${view.name}”?\n\nIts prior copies stay in its version history.`,
            { title: 'Delete saved view?', confirmText: 'Delete', destructive: true },
        );
        if (!ok) return;
        await this.write(async () => {
            await firstValueFrom(this.viewsService.remove(view.id));
            this.allViews.update((all) => all.filter((r) => r.view.id !== view.id));
            this.selectedViewId.set(null);
        }, 'Deleting the view failed.');
    }

    async deleteInvestigation(r: OpenInvestigationRow): Promise<void> {
        const ok = await this.confirm.confirmDestructive(
            `Delete the Investigation “${r.title || r.id}”?\n\nIts log, its Working Sets and its Drafts are removed from the server. ` +
                'This cannot be undone. A record that it was deleted, by whom and when, is kept in the audit trail.',
            { title: 'Delete Investigation?', confirmText: 'Delete', destructive: true },
        );
        if (!ok) return;
        await this.write(async () => {
            if (!(await this.store.deleteInvestigation(r.id))) throw new Error(this.store.error() || 'Could not delete the Investigation.');
            this.listed.update((all) => (all ?? []).filter((i) => i.id !== r.id));
            this.selectedInvestigationId.set(null);
        }, 'Could not delete the Investigation.');
    }

    private async write(work: () => Promise<void>, fallback: string): Promise<void> {
        this.busy.set(true);
        this.error.set('');
        try {
            await work();
        } catch (err) {
            this.error.set(apiErrorMessage(err, fallback));
        } finally {
            this.busy.set(false);
        }
    }
}
