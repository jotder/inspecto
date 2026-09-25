import { ColDef } from 'ag-grid-community';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { humanizeColumn } from './column-label';
import { formatNumber, isRawNumberColumn } from './number-format';
import { followRowLinkOnEnter, RowLinkCell } from './row-link-cell.component';
import { VizRenderOptions } from './viz-types';

export { humanizeColumn } from './column-label';

/** Columns rendered as status badges: the widget's `badgeColumns`, else any column named status / severity / rag. */
export function isBadgeColumn(column: string, opts?: VizRenderOptions): boolean {
    if (opts?.badgeColumns) return opts.badgeColumns.includes(column);
    return /(^|_)(status|severity|rag)$/i.test(column);
}

/** ag-Grid column definitions for the table plugin: readable headers + status badges. */
export function tableColDefs(columns: string[], opts?: VizRenderOptions): ColDef[] {
    // UIE-6: with a `labelField`, the link rides on that readable column and the raw id column is not shown. It stays
    // in the query and the row data — the link reads the id from it. A label the result lacks falls back to the id cell.
    const link = opts?.rowLink;
    const label =
        link?.labelField && link.labelField !== link.idField && columns.includes(link.labelField)
            ? link.labelField
            : undefined;
    const linkField = label ?? link?.idField;
    const shown = label ? columns.filter((c) => c !== link?.idField) : columns;
    return shown.map((field) => {
        const def: ColDef = { field, headerName: opts?.columnLabels?.[field] ?? humanizeColumn(field) };
        // UIE-4: numbers read grouped and to at most two decimals (or the column's / widget's own format); ids and
        // calendar parts stay raw. Non-numeric values pass through untouched.
        if (!isRawNumberColumn(field)) {
            const fmt = opts?.columnFormats?.[field] ?? opts?.format;
            def.valueFormatter = (p: { value: unknown }) =>
                typeof p.value === 'number' ? formatNumber(p.value, fmt) : p.value == null ? '' : String(p.value);
        }
        if (isBadgeColumn(field, opts)) {
            def.cellRenderer = (p: { value: unknown }) => {
                if (p.value == null || p.value === '') return '';
                return statusBadgeHtml(String(p.value));
            };
        }
        // UIE-6: the row's label (else id) cell links to the object it describes (wins over a badge on the same column).
        if (link && linkField === field) {
            def.cellRenderer = RowLinkCell;
            def.cellRendererParams = label ? { kind: link.kind, idField: link.idField } : { kind: link.kind };
            def.suppressKeyboardEvent = followRowLinkOnEnter;
        }
        return def;
    });
}
