import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom } from 'rxjs';
import { apiErrorMessage, LensService } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { DatasetRowsService } from '@inspecto/core/viz/dataset-rows.service';
import { LA_DATASETS, LaDataset } from '@inspecto/link-analysis/la-host';
import { IdentityGroupIndex, IdentityImportResult, InvService } from '@inspecto/link-analysis/api/inv.service';
import { IDENTITIES_NEED, entityListErrorMessage } from './investigation-state';

/**
 * What the head read of the Identity Fact log says, in words. The server re-verifies the WHOLE hash chain on every
 * read (contiguous seqs, each fact's hash over its predecessor), so a successful read IS a verified chain; a broken
 * chain answers 500 INTEGRITY_VIOLATION and nothing is shown folded. One honest limit, stated on screen: cutting the
 * TAIL leaves a valid shorter chain, so only a head hash cited elsewhere can catch it.
 */
export function chainMessage(err: unknown, fallback: string): string {
    const status = err instanceof HttpErrorResponse ? err.status : (err as { status?: number } | null)?.status;
    if (status === 500) return 'The chain did NOT verify — ' + apiErrorMessage(err, fallback);
    // A 404 here is the import's mapping Dataset, never an Entity List: do not borrow the Entity List wording.
    if (status === 404) return 'Not found — ' + apiErrorMessage(err, fallback);
    return importFieldNames(entityListErrorMessage(
        err,
        fallback,
        false,
        IDENTITIES_NEED,
    ));
}

/** The import request's wire names, as the form labels them — a refusal never shows `aCol` / `bCol` to the analyst. */
const IMPORT_FIELD_LABELS: Record<string, string> = {
    aCol: 'Column A',
    bCol: 'Column B',
    aType: 'Type A',
    bType: 'Type B',
};

/** Replace the import's internal field names in a server message with the form's labels. */
export function importFieldNames(message: string): string {
    return message.replace(/'?\b(aCol|bCol|aType|bType)\b'?/g, (_m, k: string) => `"${IMPORT_FIELD_LABELS[k]}"`);
}

/**
 * **Link Analysis — Identity Fact log**: the head hash and a "chain verified" indicator (read from
 * `GET /inv/entity-identities`), the resolved groups' sizes, and a bulk import of identity assertions from a mapping
 * Dataset (`POST /inv/entity-identities/import`). Keys are never displayed — they arrive masked and only counts are
 * shown. The import answers COUNTS only; a repeated import of the same rows adds nothing.
 */
@Component({
    selector: 'inspecto-link-analysis-identity',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        InspectoOptionPickerComponent,
        PenFieldComponent,
        ReactiveFormsModule,
        MatButtonModule,
        InspectoAlertComponent,
    ],
    host: { class: 'block' },
    template: `
        <section class="flex flex-col gap-2 text-xs" aria-label="Identity Fact log">
            <div class="flex items-center gap-1">
                <h3 class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">Identity Fact log</h3>
                <button mat-stroked-button class="ml-auto" [disabled]="busy() || !canManage()" (click)="load()">Refresh</button>
            </div>
            @if (error()) {
                <inspecto-alert variant="error" [title]="chainBroken() ? 'Chain NOT verified' : 'Identity facts'">{{
                    error()
                }}</inspecto-alert>
            }
            @if (head(); as h) {
                <inspecto-alert variant="success" title="Chain verified">
                    The server re-checked every link of the chain on this read: {{ h.headSeq }} fact(s), none altered or
                    missing in between. Cutting the END of a chain leaves a shorter valid chain, so note the head hash
                    and compare it later.
                </inspecto-alert>
                <dl class="m-0 grid grid-cols-[auto_1fr] gap-x-2" aria-label="Identity Fact log head">
                    <dt class="text-secondary">Head</dt>
                    <dd class="m-0 tabular-nums">fact {{ h.headSeq }}</dd>
                    <dt class="text-secondary">Head hash</dt>
                    <dd class="m-0 break-all">
                        <code>{{ h.headHash }}</code>
                    </dd>
                    <dt class="text-secondary">Resolved groups</dt>
                    <dd class="m-0 tabular-nums">{{ h.groups.length }}</dd>
                </dl>
            }

            @if (!canManage()) {
                <p class="text-secondary m-0" data-testid="identity-read-only">
                    Read-only: the Identity Fact log and its import need the Incident management capability - reading
                    it too, because a group read would reveal which identifiers belong together.
                </p>
            } @else {
            <form
                class="flex flex-wrap items-start gap-2"
                aria-label="Import identity assertions"
                (submit)="$event.preventDefault(); import()"
            >
                <inspecto-option-picker label="Mapping Dataset" [formControl]="dataset" [options]="datasetOptions()" />
                <inspecto-option-picker label="Column A" [formControl]="aCol" [options]="columnOptions()" />
                <inspecto-option-picker label="Column B" [formControl]="bCol" [options]="columnOptions()" />
                <inspecto-pen-field
                    label="Type A (optional)"
                    [formControl]="aType"
                />
                <inspecto-pen-field
                    label="Type B (optional)"
                    [formControl]="bType"
                />
                <inspecto-pen-field
                    label="Reason"
                    [formControl]="reason"
                />
                <button mat-flat-button type="submit" [disabled]="busy() || !valid()">Import identities</button>
            </form>
            <p class="text-secondary m-0">
                Each row of the two columns asserts that the two values are one identity. Types default to the columns'
                classified Entity Types; at most 1 000 rows by default.
            </p>
            @if (imported(); as r) {
                <inspecto-alert [variant]="r.imported ? 'success' : 'info'" title="Import result">
                    {{ r.imported }} assertion(s) added from {{ r.rowsRead }} row(s) read{{
                        r.truncated ? ' (read truncated)' : ''
                    }}. Skipped: {{ r.skipped.alreadyAsserted }} already asserted, {{ r.skipped.duplicate }} duplicate,
                    {{ r.skipped.empty }} empty, {{ r.skipped.self }} self-pair.
                </inspecto-alert>
            }
            }
        </section>
    `,
})
export class LinkAnalysisIdentityComponent implements OnInit {
    private inv = inject(InvService);
    private datasetsPort = inject(LA_DATASETS);
    private rows = inject(DatasetRowsService);
    private readonly lens = inject(LensService);
    /** Reads AND writes are gated server-side on `canManageIncidents`; the panel asks the same question. */
    readonly canManage = computed(() => this.lens.canManageIncidents());

    /** The Datasets the mapping can come from, and the picked one's columns (declared, else probed). */
    readonly datasets = signal<LaDataset[]>([]);
    readonly columns = signal<string[]>([]);
    readonly datasetOptions = computed<PickerOption[]>(() =>
        this.datasets().map((d) => ({ value: d.id, label: d.name || d.id })),
    );
    readonly columnOptions = computed<PickerOption[]>(() => this.columns().map((c) => ({ value: c, label: c })));

    readonly head = signal<IdentityGroupIndex | null>(null);
    readonly imported = signal<IdentityImportResult | null>(null);
    readonly busy = signal(false);
    readonly error = signal('');
    readonly chainBroken = signal(false);

    readonly dataset = new FormControl('', { nonNullable: true, validators: [Validators.required] });
    readonly aCol = new FormControl('', { nonNullable: true, validators: [Validators.required] });
    readonly bCol = new FormControl('', { nonNullable: true, validators: [Validators.required] });
    readonly aType = new FormControl('', { nonNullable: true });
    readonly bType = new FormControl('', { nonNullable: true });
    readonly reason = new FormControl('', { nonNullable: true, validators: [Validators.required] });

    constructor() {
        this.dataset.valueChanges.pipe(takeUntilDestroyed()).subscribe((id) => void this.loadColumns(id));
    }

    ngOnInit(): void {
        if (!this.canManage()) return;
        void this.load();
        this.datasetsPort.list().subscribe({ next: (d) => this.datasets.set(d), error: () => this.datasets.set([]) });
    }

    /** A new Dataset clears the column picks — a column of the previous one would be refused. */
    private async loadColumns(id: string): Promise<void> {
        this.aCol.setValue('');
        this.bCol.setValue('');
        this.columns.set([]);
        const ds = this.datasets().find((d) => d.id === id);
        if (!ds) return;
        try {
            const cols = (await this.rows.columns(ds)).map((c) => c.name);
            if (this.dataset.value === id) this.columns.set(cols);
        } catch {
            this.columns.set([]);
        }
    }

    valid(): boolean {
        return this.dataset.valid && this.aCol.valid && this.bCol.valid && this.reason.valid;
    }

    async load(): Promise<void> {
        this.busy.set(true);
        this.error.set('');
        this.chainBroken.set(false);
        try {
            this.head.set(await firstValueFrom(this.inv.listIdentityGroups()));
        } catch (err) {
            this.head.set(null); // never leave a stale "verified" beside a failed read
            this.chainBroken.set((err as { status?: number } | null)?.status === 500);
            this.error.set(chainMessage(err, 'Could not read the Identity Fact log.'));
        } finally {
            this.busy.set(false);
        }
    }

    async import(): Promise<void> {
        if (!this.valid() || !this.canManage()) return;
        this.busy.set(true);
        this.error.set('');
        this.imported.set(null);
        const aType = this.aType.value.trim();
        const bType = this.bType.value.trim();
        try {
            this.imported.set(
                await firstValueFrom(
                    this.inv.importIdentities({
                        dataset: this.dataset.value.trim(),
                        aCol: this.aCol.value.trim(),
                        bCol: this.bCol.value.trim(),
                        ...(aType ? { aType } : {}),
                        ...(bType ? { bType } : {}),
                        reason: this.reason.value.trim(),
                    }),
                ),
            );
        } catch (err) {
            this.error.set(chainMessage(err, 'The import failed.'));
            this.busy.set(false);
            return;
        }
        this.busy.set(false);
        await this.load(); // the head moved: show the new head hash
    }
}
