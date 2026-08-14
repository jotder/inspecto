import { ChangeDetectionStrategy, Component, DestroyRef, Input, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { Router, RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { ComponentHistoryDialog } from 'app/inspecto/components/component-history.dialog';
import { TransferMenuComponent } from 'app/inspecto/transfer';
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

const KINDS: DatasetKind[] = ['virtual', 'physical', 'materialized'];

/** Rejects a value (case-insensitive, trimmed) already present in `taken` → `{ duplicate: true }`. */
function uniqueNameValidator(taken: string[]): ValidatorFn {
    const set = new Set(taken.map((t) => t.trim().toLowerCase()));
    return (c: AbstractControl) =>
        set.has(
            String(c.value ?? '')
                .trim()
                .toLowerCase(),
        )
            ? { duplicate: true }
            : null;
}

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
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        MatSelectModule,
        RouterLink,
        InspectoAlertComponent,
        QueryPanelComponent,
        DatasetColumnsComponent,
        DatasetCalculatedComponent,
        DatasetMeasuresComponent,
        TransferMenuComponent,
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

    /** Route param — the dataset id to edit; absent on the `new` route (create mode). */
    @Input() id?: string;

    /** This saved dataset as a transfer reference — export is offered only in edit mode. */
    get transferItems(): { kind: 'dataset'; id: string }[] {
        return this.id ? [{ kind: 'dataset', id: this.id }] : [];
    }

    readonly kinds = KINDS;
    /** The stores this space actually has (`/db/catalog`), plus the saved dataset's own source when the
     *  catalog no longer lists it — a `mat-select` whose value is absent from its options renders BLANK,
     *  which reads as "no source chosen" rather than "this store went away". */
    readonly sourceNames = signal<string[]>([]);
    /** Why the store list is empty, when it is because the catalog could not be read. */
    readonly storesError = signal<string | null>(null);
    /** Why the picked store shows no preview rows (unknown store, unreadable, no offline sample). */
    readonly previewProblem = computed(() => this.page()?.error ?? null);
    readonly editing = signal(false);
    readonly saving = signal(false);
    readonly writesDisabled = signal(false);

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

        if (this.id) {
            this.editing.set(true);
            this.loadExisting(this.id);
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
            // Product-wide rule: block a duplicate id inline on create rather than relying on the server 409.
            this.datasets
                .list()
                .pipe(takeUntilDestroyed(this.destroyRef))
                .subscribe((all) => {
                    this.form.controls.name.addValidators(uniqueNameValidator(all.map((d) => d.id)));
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
        this.datasets.save(ds, { update: this.editing() }).subscribe({
            next: () => {
                this.saving.set(false);
                this.toastr.success(`Dataset "${name}" saved`);
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
