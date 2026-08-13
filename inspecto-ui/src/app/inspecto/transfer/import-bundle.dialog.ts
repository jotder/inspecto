import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { ToastrService } from 'ngx-toastr';
import { LensService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { BundleTransferService, ImportAction, ImportStatus } from './bundle-transfer.service';
import {
    BUNDLE_KINDS,
    BundleKind,
    ImportRow,
    MetadataBundle,
    RequireStatus,
    TargetIndex,
    parseBundle,
    planImport,
    resolveRequires,
    targetIndex,
} from './bundle';

export interface ImportBundleData {
    /** When set, only these kind names are importable here (a library scopes its import); absent = all. */
    allowedKinds?: string[];
    title?: string;
}

interface Row extends ImportRow {
    /** The server's own per-item verdict — including `unchanged`, which the client fan-out could not report. */
    result?: ImportStatus;
    message?: string;
}

/**
 * The shared Metadata-Bundle import preview + apply (R6) — one component behind every import surface
 * (Settings, library toolbars, editor menus). Loads the target's artifacts, fit-checks an uploaded
 * bundle (new / exists / drifted per item + a satisfied/missing `requires` panel), lets the user pick
 * import/overwrite/skip per row, and applies through {@link BundleTransferService}. Closes with the
 * number of artifacts written so the host can reload.
 */
@Component({
    selector: 'app-import-bundle-dialog',
    standalone: true,
    imports: [
        MatDialogModule,
        MatButtonModule,
        MatFormFieldModule,
        MatSelectModule,
        MatIconModule,
        MatProgressSpinnerModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        StatusBadgeComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './import-bundle.dialog.html',
})
export class ImportBundleDialog {
    private transfer = inject(BundleTransferService);
    private toastr = inject(ToastrService);
    private ref = inject(MatDialogRef<ImportBundleDialog>);
    readonly data = inject<ImportBundleData>(MAT_DIALOG_DATA) ?? {};
    readonly lens = inject(LensService);

    readonly loading = signal(true);
    readonly fileName = signal<string | null>(null);
    readonly parseErrors = signal<string[]>([]);
    readonly rows = signal<Row[]>([]);
    readonly requires = signal<RequireStatus[]>([]);
    readonly applying = signal(false);
    readonly applied = signal(false);
    readonly importedCount = signal(0);

    private target = signal<TargetIndex>(new Map());
    /** The parsed envelope, kept so apply() can send it back UNMODIFIED apart from the row selection —
     *  rebuilding it would restamp provenance and lose the origin hash the server's drift check reads. */
    private bundle = signal<MetadataBundle | null>(null);

    readonly existingCount = computed(() => this.rows().filter((r) => r.exists).length);
    readonly driftedCount = computed(() => this.rows().filter((r) => r.drifted).length);
    readonly actionableCount = computed(() => this.rows().filter((r) => r.action !== 'skip').length);
    readonly missingRequires = computed(() => this.requires().filter((r) => r.status === 'missing'));
    readonly bundleHasConnections = computed(() => this.rows().some((r) => r.item.kind === 'connection'));

    constructor() {
        this.transfer.loadAll().subscribe((items) => {
            this.target.set(targetIndex(items));
            this.loading.set(false);
        });
    }

    async onFile(event: Event): Promise<void> {
        const input = event.target as HTMLInputElement;
        const file = input.files?.[0];
        input.value = '';
        if (!file) return;
        this.fileName.set(file.name);
        this.applied.set(false);
        const { bundle, errors } = parseBundle(await file.text());
        this.parseErrors.set(errors);
        this.bundle.set(bundle ?? null);
        this.requires.set(bundle ? resolveRequires(bundle, this.target()) : []);
        this.rows.set(bundle ? this.filtered(bundle) : []);
    }

    private filtered(bundle: MetadataBundle): Row[] {
        const allowed = this.data.allowedKinds;
        return planImport(bundle, this.target()).filter((r) => !allowed || allowed.includes(r.item.kind));
    }

    setAction(row: Row, action: Row['action']): void {
        this.rows.update((rows) => rows.map((r) => (r === row ? { ...r, action } : r)));
    }

    overwriteAllExisting(): void {
        this.rows.update((rows) => rows.map((r) => (r.exists ? { ...r, action: 'overwrite' } : r)));
    }

    skipAllExisting(): void {
        this.rows.update((rows) => rows.map((r) => (r.exists ? { ...r, action: 'skip' } : r)));
    }

    kindLabel(kind: BundleKind): string {
        return BUNDLE_KINDS.find((k) => k.kind === kind)?.label ?? kind;
    }

    /**
     * Apply through the backend's bundle pipe in ONE call (U-F). Previously this looped a per-kind write
     * per row client-side, which bypassed `/bundle/import`'s referential-integrity gate, its connection
     * secret check, and its dependency-ordered apply — see {@link BundleTransferService#applyImport}.
     *
     * <p>The envelope is the parsed bundle narrowed to the actionable rows, so each item keeps its origin
     * provenance (the hash the server's drift / `unchanged` check reads). Skipped rows are left out
     * entirely rather than sent with `skip`: the row the user chose not to touch is not the server's
     * business, and the counts then describe only what was actually attempted.
     */
    apply(): void {
        const source = this.bundle();
        if (!this.lens.canAuthorWorkbench() || this.applying() || !source) return;
        const work = this.rows().filter((r) => r.action !== 'skip');
        if (!work.length) return;

        const actions: Record<string, ImportAction> = {};
        for (const r of work) if (r.action === 'overwrite') actions[`${r.item.kind}/${r.item.id}`] = 'overwrite';

        this.applying.set(true);
        this.transfer
            .applyImport({ ...source, items: work.map((r) => r.item) }, actions)
            .pipe(catchError((err) => of(apiErrorMessage(err, 'Import failed.'))))
            .subscribe((outcome) => {
                this.applying.set(false);
                this.applied.set(true);
                if (typeof outcome === 'string') {
                    // A gate rejected the bundle as a WHOLE (422 referential integrity, 503 writes
                    // disabled). Nothing was written, so no row gets a result — say so once, loudly,
                    // instead of painting per-row failures the server never reported.
                    this.importedCount.set(0);
                    this.toastr.error(outcome);
                    return;
                }
                const byRef = new Map(outcome.results.map((r) => [`${r.kind}/${r.id}`, r]));
                this.rows.update((rows) =>
                    rows.map((r) => {
                        const res = byRef.get(`${r.item.kind}/${r.item.id}`);
                        return res ? { ...r, result: res.status, message: res.message } : r;
                    }),
                );
                const written = outcome.imported + outcome.overwritten;
                this.importedCount.set(written);
                if (outcome.failed) {
                    this.toastr.warning(
                        `Imported ${written} artifact(s); ${outcome.failed} failed — see the result column.`,
                    );
                } else if (outcome.unchanged && !written) {
                    // Worth its own message: an identical re-promotion is a SUCCESS that wrote nothing,
                    // and "Imported 0 artifact(s)" would read as a failure.
                    this.toastr.info(`Already up to date — ${outcome.unchanged} artifact(s) unchanged.`);
                } else {
                    this.toastr.success(`Imported ${written} artifact(s)`);
                }
            });
    }

    close(): void {
        this.ref.close(this.importedCount());
    }
}
