import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, LookupMiss, LookupTestAnswer, RunsService } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime } from 'app/inspecto/grid';
import { formatNumber } from 'app/inspecto/viz/number-format';
import { LookupGridRow, toGridRow } from './lookups-view';

const count = (v: number | null | undefined): string => (v == null ? '—' : formatNumber(v));

/**
 * Lookups (LOOKUP-1) — every keyed Reference lookup (`transform.join` with `key:`, or `from:`/`to:`) in this Space's
 * Pipelines, with its last run's hit and miss counts and the Reference's quality counts, from `GET /lookups`.
 * Read-only: a lookup is edited in its Pipeline's join Step.
 */
@Component({
    selector: 'app-lookups',
    standalone: true,
    imports: [
        InspectoPageHeaderComponent,
        InspectoAlertComponent,
        DataTableComponent,
        MatButtonModule,
        MatIconModule,
        MatTooltipModule,
        PenFieldComponent,
        ReactiveFormsModule,
    ],
    templateUrl: './lookups.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LookupsComponent implements OnInit {
    private api = inject(RunsService);
    private toastr = inject(ToastrService);

    readonly rows = signal<LookupGridRow[]>([]);
    readonly loading = signal(false);
    /** False when Step Receipts are switched off: no last run can be shown. */
    readonly lastRunKnown = signal(true);
    /** More lookups exist than the server listed. */
    readonly cut = signal<number | null>(null);
    /** The row whose unmatched-key sample is shown below the list. */
    readonly selected = signal<LookupGridRow | null>(null);

    readonly missColumns: ColDef<LookupMiss>[] = [
        { field: 'mapping', headerName: 'Looked up by', flex: 1, minWidth: 140 },
        { field: 'shape', headerName: 'Value (masked)', flex: 1, minWidth: 160 },
        { field: 'length', headerName: 'Length', minWidth: 90 },
        { field: 'n', headerName: 'Rows', minWidth: 90, valueFormatter: (p) => count(p.value) },
        { field: 'fingerprint', headerName: 'Fingerprint', flex: 1, minWidth: 180 },
    ];

    select(row: LookupGridRow | null): void {
        this.selected.set(row);
        this.answer.set(null);
        this.testError.set(null);
    }

    /** Test a value (LOOKUP-1 P3): the value to look up in the selected lookup's in-memory index. */
    readonly testValue = new FormControl('', { nonNullable: true, validators: Validators.required });
    readonly testForm = new FormGroup({ value: this.testValue });
    readonly testing = signal(false);
    readonly fmtDateTime = fmtDateTime;
    readonly answer = signal<LookupTestAnswer | null>(null);
    readonly testError = signal<string | null>(null);

    /** The answer's added columns, one row per output column. */
    answerRows(a: LookupTestAnswer): { on: string; column: string; value: string }[] {
        return a.mappings.flatMap((m) =>
            Object.entries(m.columns).map(([column, value]) => ({ on: m.on, column, value: value ?? '—' })),
        );
    }

    test(refresh = false): void {
        const s = this.selected();
        if (!s) return;
        if (this.testForm.invalid) {
            this.testForm.markAllAsTouched();
            return;
        }
        this.testing.set(true);
        this.testError.set(null);
        this.api.testLookup(s.pipeline, s.step, this.testValue.value, refresh).subscribe({
            next: (a) => {
                this.answer.set(a);
                this.testing.set(false);
            },
            error: (err) => {
                this.answer.set(null);
                this.testing.set(false);
                this.testError.set(apiErrorMessage(err, 'Could not test the value'));
            },
        });
    }

    readonly columnDefs: ColDef<LookupGridRow>[] = [
        { field: 'pipeline', headerName: 'Pipeline', flex: 1, minWidth: 140 },
        { field: 'step', headerName: 'Step', minWidth: 110 },
        { field: 'reference', headerName: 'Reference', flex: 1, minWidth: 160 },
        { field: 'match', headerName: 'Match', minWidth: 120 },
        { field: 'lookedUpBy', headerName: 'Looked up by', flex: 1, minWidth: 160 },
        {
            field: 'lastRunAt',
            headerName: 'Last run',
            minWidth: 170,
            valueFormatter: (p) => (p.value ? fmtDateTime(p.value) : 'Not run yet'),
        },
        {
            field: 'outcome',
            headerName: 'Outcome',
            minWidth: 120,
            maxWidth: 130,
            cellRenderer: (p: ICellRendererParams<LookupGridRow>) =>
                p.value ? statusBadgeHtml(p.value as string) : '',
        },
        { field: 'hits', headerName: 'Found', minWidth: 100, valueFormatter: (p) => count(p.value) },
        { field: 'misses', headerName: 'Not found', minWidth: 110, valueFormatter: (p) => count(p.value) },
        {
            field: 'missRate',
            headerName: 'Not found %',
            minWidth: 120,
            valueFormatter: (p) =>
                p.value == null ? '—' : formatNumber((p.value as number) * 100, { style: 'percent', decimals: 1 }),
        },
        { field: 'referenceRows', headerName: 'Reference rows', minWidth: 130, valueFormatter: (p) => count(p.value) },
        { field: 'nullKeys', headerName: 'Empty keys', minWidth: 110, valueFormatter: (p) => count(p.value) },
    ];

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.api.lookups().subscribe({
            next: (page) => {
                this.rows.set(page.lookups.map(toGridRow));
                // keep the open sample on a refresh, read from the new rows
                const was = this.selected();
                this.selected.set(
                    was ? (this.rows().find((r) => r.pipeline === was.pipeline && r.step === was.step) ?? null) : null,
                );
                this.lastRunKnown.set(page.known.lastRun);
                this.cut.set(page.total > page.lookups.length ? page.total : null);
                this.loading.set(false);
            },
            error: (err) => {
                this.rows.set([]);
                this.loading.set(false);
                this.toastr.error(apiErrorMessage(err, 'Could not load the lookups'));
            },
        });
    }
}
