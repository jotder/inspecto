import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AgGridAngular } from 'ag-grid-angular';
import { CellClassParams, CellValueChangedEvent, ColDef, GridApi, GridReadyEvent, ITooltipParams } from 'ag-grid-community';
import { INSPECTO_DEFAULT_COL_DEF, InspectoGridThemeService, noRowsOverlay } from 'app/inspecto/grid';

/** One column of an editable flat table. `options` set ⇒ a select editor over exactly those values. */
export interface EditableGridColumn {
    key: string;
    label: string;
    options?: string[];
}

/** One cell-anchored finding (the BACKWARD compatibility gate's refusals, S5). */
export interface CellFinding {
    severity: 'error' | 'warning';
    message: string;
}

/** The outcome of an Import CSV, so the host can gate it (validate, diff, confirm) before applying. */
export interface CsvImport {
    /** The parsed rows, already projected onto the declared columns. Empty when `applied` is false. */
    rows: Record<string, string>[];
    /** Header cells that matched no declared column — almost always the sign of the wrong file. */
    unknownHeaders: string[];
    /** Declared columns the header did not carry; these cells import blank. */
    missingColumns: string[];
    /** False when the header matched NO declared column: the grid was left untouched. */
    applied: boolean;
    fileName: string;
}

/**
 * The shared editable flat-table grid (ELT amendment UI plan §2.4, S5): an ag-Grid-hosted
 * spreadsheet-lite for the row-list component kinds (mapping rules, schema fields) — add/remove row,
 * in-cell text/select editing, CSV import/export, and cell-level findings rendered as highlights +
 * tooltips with a `role="alert"` summary owned by the HOST (this component only marks the cells).
 *
 * <p>Presentational: the host owns loading, saving, and what a "row" means. Every edit emits the
 * whole row list via {@link rowsChange} — the host validates/saves through its own route (the
 * mapping editor via `PUT /components/mapping/{id}`, the schema editor via the gated
 * `/config/write`), never this component.
 *
 * <p>Findings are keyed `"<rowIndex>|<columnKey>"` — the HOST translates whatever the server
 * anchored on (the schema gate's dotted `fieldPath`) into that key, because only the host knows
 * which row a `raw.fields[NAME]` path names.
 */
@Component({
    selector: 'inspecto-editable-grid',
    standalone: true,
    imports: [AgGridAngular, MatButtonModule, MatIconModule, MatTooltipModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        @if (editable) {
            <div class="mb-2 flex items-center gap-1">
                <button mat-stroked-button type="button" (click)="addRow()" aria-label="Add row">
                    <mat-icon class="icon-size-4" svgIcon="heroicons_outline:plus"></mat-icon> Add row
                </button>
                <button mat-stroked-button type="button" (click)="removeSelected()" aria-label="Remove selected rows">
                    <mat-icon class="icon-size-4" svgIcon="heroicons_outline:trash"></mat-icon> Remove selected
                </button>
                <span class="flex-1"></span>
                <button mat-stroked-button type="button" (click)="exportCsv()" aria-label="Export rows as CSV">
                    <mat-icon class="icon-size-4" svgIcon="heroicons_outline:arrow-down-tray"></mat-icon> Export CSV
                </button>
                <button mat-stroked-button type="button" (click)="fileInput.click()" aria-label="Import rows from CSV">
                    <mat-icon class="icon-size-4" svgIcon="heroicons_outline:arrow-up-tray"></mat-icon> Import CSV
                </button>
                <input #fileInput type="file" accept=".csv,text/csv" class="hidden" aria-label="Import rows from CSV" (change)="importCsv($event)" />
            </div>
        }
        <ag-grid-angular
            class="block w-full"
            [style.height.px]="height"
            [theme]="gridTheme.theme()"
            [rowData]="gridRows()"
            [columnDefs]="colDefs()"
            [defaultColDef]="defaultColDef"
            [rowSelection]="editable ? { mode: 'multiRow' } : undefined"
            [overlayNoRowsTemplate]="emptyOverlay"
            (gridReady)="onGridReady($event)"
            (cellValueChanged)="onCellEdited($event)"
        ></ag-grid-angular>
    `,
})
export class EditableGridComponent {
    readonly gridTheme = inject(InspectoGridThemeService);

    @Input({ required: true }) set columns(cols: EditableGridColumn[]) {
        this.colSpec.set(cols);
    }
    @Input({ required: true }) set rows(rows: Record<string, string>[]) {
        this.gridRows.set(rows.map((r) => ({ ...r })));
    }
    @Input() editable = true;
    /** Grid height in px — flat row lists are dialog content, so the host bounds it. */
    @Input() height = 320;
    /** Cell findings keyed `"<rowIndex>|<columnKey>"` (host-translated from the server anchoring). */
    @Input() findings: ReadonlyMap<string, CellFinding> = new Map();
    /** Download name for Export CSV. */
    @Input() csvName = 'rows.csv';

    /** The full row list after any edit (cell change, add, remove, import). */
    @Output() readonly rowsChange = new EventEmitter<Record<string, string>[]>();

    /**
     * One Import CSV, with what the header did and did not match. Fires on every import, including a
     * refused one (`applied: false`) — a host that ignores this still gets the rows via `rowsChange`,
     * which is why the two are separate.
     */
    @Output() readonly imported = new EventEmitter<CsvImport>();

    private readonly colSpec = signal<EditableGridColumn[]>([]);
    readonly gridRows = signal<Record<string, string>[]>([]);
    readonly defaultColDef = INSPECTO_DEFAULT_COL_DEF;
    readonly emptyOverlay = noRowsOverlay('No rows yet', 'Add a row or import a CSV.');
    private api: GridApi | null = null;

    readonly colDefs = (): ColDef[] =>
        this.colSpec().map((c) => ({
            field: c.key,
            headerName: c.label,
            editable: this.editable,
            flex: 1,
            ...(c.options
                ? { cellEditor: 'agSelectCellEditor', cellEditorParams: { values: c.options } }
                : {}),
            cellClassRules: {
                'inspecto-cell-error': (p: CellClassParams) => this.findingFor(p)?.severity === 'error',
                'inspecto-cell-warning': (p: CellClassParams) => this.findingFor(p)?.severity === 'warning',
            },
            tooltipValueGetter: (p: ITooltipParams) =>
                this.findings.get(`${p.node?.rowIndex}|${c.key}`)?.message ?? null,
        }));

    private findingFor(p: CellClassParams): CellFinding | undefined {
        return this.findings.get(`${p.node.rowIndex}|${p.column.getColDef().field}`);
    }

    onGridReady(e: GridReadyEvent): void {
        this.api = e.api;
    }

    onCellEdited(_e: CellValueChangedEvent): void {
        this.emitRows();
    }

    addRow(): void {
        const empty = Object.fromEntries(this.colSpec().map((c) => [c.key, '']));
        this.gridRows.update((rows) => [...rows, empty]);
        this.emitRows();
    }

    removeSelected(): void {
        const selected = new Set(this.api?.getSelectedNodes().map((n) => n.rowIndex));
        if (!selected.size) return;
        this.gridRows.update((rows) => rows.filter((_, i) => !selected.has(i)));
        this.emitRows();
    }

    private emitRows(): void {
        // ag-grid mutates the bound row objects in place on cell edit, so gridRows is current.
        this.rowsChange.emit(this.gridRows().map((r) => ({ ...r })));
    }

    // ── CSV import/export (the HttpClient blob pattern's client-only sibling) ──

    exportCsv(): void {
        const cols = this.colSpec();
        const esc = (v: string): string => (/[",\n\r]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v);
        const lines = [
            cols.map((c) => esc(c.key)).join(','),
            ...this.gridRows().map((r) => cols.map((c) => esc(String(r[c.key] ?? ''))).join(',')),
        ];
        const blob = new Blob([lines.join('\n') + '\n'], { type: 'text/csv' });
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = this.csvName;
        a.click();
        URL.revokeObjectURL(a.href);
    }

    /**
     * Import CSV. The header is matched per declared column by `key` first, then by `label`
     * (case/space-insensitive), because Export CSV writes keys while a human editing the file sees
     * labels. If the header matches NO declared column the import is REFUSED and the grid is left
     * as it was — replacing every row with blanks (the earlier behaviour) looks like a successful
     * import of an empty mapping, which is the worst possible outcome for the wrong file.
     */
    async importCsv(event: Event): Promise<void> {
        const input = event.target as HTMLInputElement;
        const file = input.files?.[0];
        input.value = '';
        if (!file) return;
        const parsed = parseCsv(await file.text());
        if (!parsed.length) return;

        const norm = (s: string): string => s.trim().toLowerCase().replace(/[\s_]+/g, '');
        const header = parsed[0];
        const cols = this.colSpec();
        const indexOf = new Map<string, number>(
            cols.map((c) => [
                c.key,
                header.findIndex((h) => norm(h) === norm(c.key) || norm(h) === norm(c.label)),
            ]),
        );
        const matched = cols.filter((c) => (indexOf.get(c.key) ?? -1) >= 0);
        const missingColumns = cols.filter((c) => (indexOf.get(c.key) ?? -1) < 0).map((c) => c.key);
        const claimed = new Set(matched.map((c) => indexOf.get(c.key)));
        const unknownHeaders = header.filter((_, i) => !claimed.has(i)).filter((h) => h.trim().length);

        const applied = matched.length > 0;
        const rows = applied
            ? parsed.slice(1).map((cells) =>
                  Object.fromEntries(cols.map((c) => {
                      const idx = indexOf.get(c.key) ?? -1;
                      return [c.key, idx >= 0 ? (cells[idx] ?? '') : ''];
                  })))
            : [];
        if (applied) {
            this.gridRows.set(rows);
            this.emitRows();
        }
        this.imported.emit({ rows, unknownHeaders, missingColumns, applied, fileName: file.name });
    }
}

/** Minimal RFC-4180 parse (quoted cells carry commas/newlines — how EXPR expressions travel). */
export function parseCsv(text: string): string[][] {
    const rows: string[][] = [];
    let row: string[] = [];
    let cell = '';
    let quoted = false;
    for (let i = 0; i < text.length; i++) {
        const ch = text[i];
        if (quoted) {
            if (ch === '"' && text[i + 1] === '"') { cell += '"'; i++; }
            else if (ch === '"') quoted = false;
            else cell += ch;
        } else if (ch === '"') {
            quoted = true;
        } else if (ch === ',') {
            row.push(cell); cell = '';
        } else if (ch === '\n' || ch === '\r') {
            if (ch === '\r' && text[i + 1] === '\n') i++;
            row.push(cell); cell = '';
            rows.push(row); row = [];
        } else {
            cell += ch;
        }
    }
    if (cell.length || row.length) { row.push(cell); rows.push(row); }
    return rows.filter((r) => r.some((c) => c.trim().length));
}
