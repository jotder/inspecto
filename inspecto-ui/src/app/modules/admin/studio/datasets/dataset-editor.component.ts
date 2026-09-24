import { ChangeDetectionStrategy, Component, DestroyRef, Input, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { concatMap, map, of, tap } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Router, RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { ComponentsService, LensService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { ComponentHistoryDialog } from 'app/inspecto/components/component-history.dialog';
import {
    BundleTransferService,
    ImportDraft,
    ImportDraftBannerComponent,
    ImportDraftHandoff,
    TransferMenuComponent,
    draftPlacement,
    draftSaveWarning,
} from 'app/inspecto/transfer';
import { ColumnMeta, QueryChange, QueryModel, QueryPanelComponent, QuerySource } from 'app/inspecto/query';
import { DatasetCalculatedComponent } from './dataset-calculated.component';
import { DatasetColumnsComponent } from './dataset-columns.component';
import { DatasetMeasuresComponent } from './dataset-measures.component';
import { DatasetRows, DatasetRowsService } from 'app/inspecto/viz/dataset-rows.service';
import {
    buildDataset,
    CalculatedColumn,
    Dataset,
    DatasetColumn,
    DatasetKind,
    NamedMeasure,
    inferRoles,
} from './dataset-types';
import { DatasetsService } from './datasets.service';
import { MaterializeDatasetDialog } from './materialize-dataset.dialog';
import { uniqueNameValidator } from 'app/inspecto/investigation/unique-name';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import { InspectoOptionPickerComponent, pickerOptions } from 'app/inspecto/components/option-picker.component';

const KINDS: DatasetKind[] = ['virtual', 'physical', 'materialized'];

/**
 * Dataset editor — create or edit a Studio {@link Dataset}. A **virtual** dataset embeds the Query Core
 * ({@link QueryPanelComponent}) over a sample source to author its SQL view; physical/materialized carry a
 * reference. Either way the operator tags column **roles/formats** ({@link DatasetColumnsComponent}) and saves
 * via {@link DatasetsService} (the mock-backed `dataset` component kind). Mirrors the rule editor flow.
 */
@Component({
    selector: 'app-dataset-editor',
    standalone: true,
    imports: [
        InspectoOptionPickerComponent,
        InspectoPageHeaderComponent,
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        RouterLink,
        InspectoAlertComponent,
        QueryPanelComponent,
        DatasetColumnsComponent,
        DatasetCalculatedComponent,
        DatasetMeasuresComponent,
        TransferMenuComponent,
        ImportDraftBannerComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './dataset-editor.component.html',
})
export class DatasetEditorComponent implements OnInit {
    private fb = inject(FormBuilder);
    private datasets = inject(DatasetsService);
    private datasetRows = inject(DatasetRowsService);
    private toastr = inject(ToastrService);
    private router = inject(Router);
    private destroyRef = inject(DestroyRef);
    private matDialog = inject(MatDialog);
    private components = inject(ComponentsService);
    private draftHandoff = inject(ImportDraftHandoff);
    private transfer = inject(BundleTransferService);
    /** Materializing is an OPERATION, not authoring — same capability as any job trigger (§7). */
    readonly lens = inject(LensService);

    /** Route param — the dataset id to edit; absent on the `new` route (create mode). */
    @Input() id?: string;

    /** This saved dataset as a transfer reference — export is offered only in edit mode. */
    get transferItems(): { kind: 'dataset'; id: string }[] {
        return this.id ? [{ kind: 'dataset', id: this.id }] : [];
    }

    readonly kinds = pickerOptions(KINDS);
    /** The stores this space actually has (`/db/catalog`), plus the saved dataset's own source when the
     *  catalog no longer lists it — a `mat-select` whose value is absent from its options renders BLANK,
     *  which reads as "no source chosen" rather than "this store went away". */
    readonly sourceNames = signal<string[]>([]);
    readonly sourceOptions = computed(() => pickerOptions(this.sourceNames()));
    /** Why the store list is empty, when it is because the catalog could not be read. */
    readonly storesError = signal<string | null>(null);
    /** Why the picked store shows no preview rows (unknown store, unreadable, no offline sample). */
    readonly previewProblem = computed(() => this.page()?.error ?? null);
    readonly editing = signal(false);
    readonly saving = signal(false);
    readonly materializing = signal(false);
    readonly writesDisabled = signal(false);

    /** An imported draft this editor holds UNSAVED (Import as draft, D1–D8) — in memory only. */
    readonly importDraft = signal<ImportDraft | null>(null);
    /** The stored copy the draft replaces (D6 diff baseline); null when the id is new here. */
    readonly draftStored = signal<Record<string, unknown> | null>(null);
    /** The stored copy's hash, sent as `If-Match` on the draft's Save so it cannot clobber a concurrent edit. */
    private draftIfMatch: string | undefined;

    readonly form = this.fb.group({
        name: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/)]],
        kind: this.fb.nonNullable.control<DatasetKind>('virtual'),
        sourceName: this.fb.nonNullable.control(''),
        physicalRef: this.fb.nonNullable.control(''),
    });

    readonly kind = signal<DatasetKind>('virtual');
    readonly sourceName = signal('');
    readonly columns = signal<DatasetColumn[]>([]);
    readonly calculated = signal<CalculatedColumn[]>([]);
    readonly measures = signal<NamedMeasure[]>([]);
    /** The dataset's saved SQL view (virtual kind only) — seeds `<inspecto-query-panel>` on edit. */
    readonly model = signal<QueryModel | null>(null);
    /** True once there's nothing left to seed the panel from: immediately on create, only after the
     *  async load resolves on edit. Gates mounting `<inspecto-query-panel>` — mounting it earlier (while
     *  `model` is still its pre-load `null`) would let the panel's own first `queryModelChange` echo back
     *  through `onQueryChange` and win the race against the real seed arriving moments later. */
    readonly ready = signal(false);

    /** One page of the picked store, through the rows seam — the real store live, its sample offline. */
    private readonly page = signal<DatasetRows | null>(null);
    /** The Query Core source for the embedded panel — that page's rows + the columns behind them. */
    readonly querySource = computed<QuerySource>(() => ({
        name: this.sourceName(),
        rows: this.page()?.rows ?? [],
        columns: this.inferredColumns(),
    }));
    private readonly inferredColumns = computed<ColumnMeta[]>(() => this.page()?.columns ?? []);

    readonly isVirtual = computed(() => this.kind() === 'virtual');

    ngOnInit(): void {
        // React to source/kind changes so the column tagger + panel stay in sync.
        this.form.controls.kind.valueChanges
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((k) => this.kind.set(k));
        this.form.controls.sourceName.valueChanges
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((s) => void this.onSourcePicked(s));

        // A draft routed here from another editor (Import as draft). Taking it replaces the stored load
        // and the create defaults, so neither can land AFTER the draft and silently overwrite it.
        const pending = this.draftHandoff.take('dataset', this.id);
        if (this.id) {
            this.editing.set(true);
            if (pending) this.adoptDraft(pending);
            else this.loadExisting(this.id);
        } else {
            if (pending) {
                this.adoptDraft(pending);
            } else {
                void this.loadStores().then(() => {
                    // Create: land on a real store rather than an empty picker, but never override a pick the
                    // operator already made while the catalog was still loading.
                    const first = this.sourceNames()[0];
                    if (first && !this.form.controls.sourceName.value) {
                        this.form.controls.sourceName.setValue(first);
                    }
                    this.ready.set(true);
                });
            }
            // Product-wide rule: block a duplicate id inline on create rather than relying on the server 409.
            this.datasets
                .list()
                .pipe(takeUntilDestroyed(this.destroyRef))
                .subscribe((all) => {
                    this.form.controls.name.addValidators(uniqueNameValidator(() => all.map((d) => d.id)));
                    this.form.controls.name.updateValueAndValidity({ emitEvent: false });
                });
        }
    }

    /** A new store pick: re-read its page, re-infer the column tagger, and drop the old view model. */
    private async onSourcePicked(name: string): Promise<void> {
        this.sourceName.set(name);
        await this.loadPage(name);
        this.columns.set(inferRoles(this.inferredColumns()));
        this.model.set(null);
    }

    /** The space's stores. An unreadable catalog is reported, never shown as "this space has none". */
    private async loadStores(): Promise<void> {
        const list = await this.datasetRows.stores();
        this.storesError.set(list.error ?? null);
        this.sourceNames.set(list.names);
    }

    private async loadPage(name: string): Promise<void> {
        this.page.set(name ? await this.datasetRows.rows({ sourceName: name }) : null);
    }

    private loadExisting(id: string): void {
        // Unmount the panel across a reload (e.g. a history restore) too — a live instance's seed-once
        // guard would otherwise ignore the freshly-restored model.
        this.ready.set(false);
        this.datasets.get(id).subscribe({
            next: (d) => this.seed(d),
            error: (e) => this.toastr.error(apiErrorMessage(e, `Could not load dataset "${id}"`)),
        });
    }

    // ── Import as draft (operator decisions 2026-09-25) ──────────────────────────────────────────
    // The dataset is adopted UNSAVED and reaches the store only through this pane's own Save (D2).

    /** The transfer menu's draft: adopt it here, or route to the editor that must show it. */
    onDraftImported(draft: ImportDraft): void {
        if (draftPlacement(this.id, draft) === 'here') {
            this.adoptDraft(draft);
            return;
        }
        this.draftHandoff.open(
            draft,
            ['/catalog/datasets', draft.targetExists ? draft.id : 'new'],
            '/catalog/datasets',
            !!this.id,
        );
    }

    /** Take the incoming content as unsaved edits; for an existing id, read the stored copy first (D6). */
    adoptDraft(draft: ImportDraft): void {
        // Unmount the query panel first: its seed-once guard would otherwise ignore the draft's model.
        this.ready.set(false);
        const apply = async () => {
            await this.seed(this.datasets.fromContent(draft.id, draft.content));
            if (!this.editing()) {
                // seed() locks the id as on edit; a new-id draft stays nameable, prefilled with its own id.
                this.form.controls.name.enable();
                this.form.controls.name.setValue(draft.id);
            }
            this.importDraft.set(draft);
        };
        if (!draft.targetExists) {
            this.draftStored.set(null);
            this.draftIfMatch = undefined;
            void apply();
            return;
        }
        this.components.get('dataset', draft.id).subscribe({
            next: (def) => {
                this.draftStored.set(def.content);
                this.draftIfMatch = def.contentHash;
                void apply();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Could not load dataset "${draft.id}"`)),
        });
    }

    /** Drop the draft: back to the stored dataset, or out of a create that only the draft started. */
    discardDraft(): void {
        this.importDraft.set(null);
        this.draftStored.set(null);
        this.draftIfMatch = undefined;
        if (!this.id) {
            this.router.navigate(['/catalog/datasets']);
            return;
        }
        this.loadExisting(this.id);
    }

    /** Show version history for this saved dataset; reload its state after a restore (MET-5). Edit mode only. */
    history(): void {
        if (!this.id) return;
        const id = this.id;
        this.matDialog
            .open(ComponentHistoryDialog, { data: { type: 'dataset', id, label: id } })
            .afterClosed()
            .subscribe((restored) => {
                if (restored) this.loadExisting(id);
            });
    }

    private async seed(d: Dataset): Promise<void> {
        await this.loadStores();
        if (d.sourceName && !this.sourceNames().includes(d.sourceName)) {
            this.sourceNames.set([d.sourceName, ...this.sourceNames()]);
        }
        // Patch WITHOUT firing the pick handler: it would blank the saved model and re-infer the roles
        // this seed is about to restore. The page is loaded explicitly instead.
        this.form.patchValue(
            { name: d.name, kind: d.kind, sourceName: d.sourceName, physicalRef: d.physicalRef ?? '' },
            { emitEvent: false },
        );
        this.form.controls.name.disable(); // id is immutable on edit
        this.kind.set(d.kind);
        this.sourceName.set(d.sourceName);
        await this.loadPage(d.sourceName);
        // Saved roles take precedence; fall back to fresh inference for any new source columns.
        const inferred = inferRoles(this.inferredColumns());
        const bySaved = new Map(d.columns.map((c) => [c.name, c]));
        this.columns.set(inferred.map((c) => bySaved.get(c.name) ?? c));
        this.calculated.set(d.calculated);
        this.measures.set(d.measures);
        this.model.set(d.query ?? null);
        this.ready.set(true);
    }

    onQueryChange(change: QueryChange): void {
        this.model.set(change.model);
    }

    onColumnsChange(cols: DatasetColumn[]): void {
        this.columns.set(cols);
    }

    onMeasuresChange(measures: NamedMeasure[]): void {
        this.measures.set(measures);
    }

    onCalculatedChange(calculated: CalculatedColumn[]): void {
        this.calculated.set(calculated);
    }

    /**
     * Ask for a target, then fire `POST /datasets/{id}/materialize`. The route is **asynchronous**: a 202
     * says the run was admitted, so the toast reports the run id rather than claiming a snapshot exists.
     *
     * ⚠ There is no run poller in this SPA and this does not invent one — every other trigger call site
     * reloads its list and lets the existing Runs/Jobs views show the outcome, and so does this.
     * ⚠ 409 ("already running") and 422 (bad target) are the states worth naming; a 503 latches the same
     * writes-disabled banner `save()` uses, because it is the same cause.
     */
    materialize(): void {
        if (!this.id || !this.lens.canOperateRuns()) return;
        const source = this.id;
        // The existing ids are fetched HERE rather than on load: the editor's own id list is loaded only
        // in create mode, and materialize is edit-only. One request on click beats one on every open, and
        // the list is only an advisory hint — a failure must not block the action.
        this.datasets
            .list()
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (all) =>
                    this.openMaterializeDialog(
                        source,
                        all.map((d) => d.id),
                    ),
                error: () => this.openMaterializeDialog(source, []),
            });
    }

    private openMaterializeDialog(source: string, existingNames: string[]): void {
        this.matDialog
            .open(MaterializeDatasetDialog, { data: { source, existingNames } })
            .afterClosed()
            .subscribe((target?: string) => {
                if (!target) return;
                this.materializing.set(true);
                this.datasets.materialize(source, target).subscribe({
                    next: (run) => {
                        this.materializing.set(false);
                        this.toastr.success(`Materializing "${source}" into "${target}" — run ${run.runId}`);
                    },
                    error: (e) => {
                        this.materializing.set(false);
                        if (e?.status === 503) this.writesDisabled.set(true);
                        this.toastr.error(materializeError(e, source, target));
                    },
                });
            });
    }

    save(): void {
        const ctrl = this.form.controls.name;
        const name = String(ctrl.value ?? '').trim() || (this.id ?? '');
        if (!name || (ctrl.enabled && ctrl.invalid)) {
            this.form.markAllAsTouched();
            return;
        }
        const kind = this.form.controls.kind.value;
        const ds = buildDataset(name, kind, this.form.controls.sourceName.value, {
            query: kind === 'virtual' ? this.model() : null,
            physicalRef: kind === 'virtual' ? null : this.form.controls.physicalRef.value || null,
            columns: this.columns(),
            measures: this.measures(),
            calculated: this.calculated(),
        });
        this.saving.set(true);
        const draft = this.importDraft();
        const ifMatch = draft ? this.draftIfMatch : undefined;
        // An imported draft's references are re-checked against what is about to be WRITTEN (D3) — advisory:
        // the findings go to the banner and the Save goes ahead either way.
        const recheck$ = draft
            ? this.transfer
                  .draftIntegrity('dataset', name, this.datasets.toContent(ds))
                  .pipe(tap((integrity) => this.importDraft.set({ ...draft, integrity })))
            : of(null);
        recheck$
            .pipe(
                concatMap((integrity) =>
                    this.datasets
                        .save(ds, { update: this.editing(), ...(ifMatch ? { ifMatch } : {}) })
                        .pipe(map(() => integrity)),
                ),
            )
            .subscribe({
                next: (integrity) => {
                    this.saving.set(false);
                    const warning = draft ? draftSaveWarning(name, integrity) : null;
                    if (warning) this.toastr.warning(warning);
                    else this.toastr.success(`Dataset "${name}" saved`);
                    this.router.navigate(['/catalog/datasets']);
                },
                error: (e) => {
                    this.saving.set(false);
                    if (e?.status === 503) this.writesDisabled.set(true);
                    this.toastr.error(
                        e?.status === 503
                            ? 'Writes are disabled (no write root configured).'
                            : apiErrorMessage(e, `Could not save "${name}"`),
                    );
                },
            });
    }
}

/** The materialize failures worth naming; everything else falls back to the server's own message. */
function materializeError(e: { status?: number }, source: string, target: string): string {
    if (e?.status === 503) return 'Writes are disabled (no write root configured).';
    if (e?.status === 409) return `A materialize of "${target}" is already running — wait for it to finish.`;
    return apiErrorMessage(e, `Could not materialize "${source}" into "${target}"`);
}
