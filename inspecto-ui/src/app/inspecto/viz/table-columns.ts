import { ColDef } from 'ag-grid-community';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { humanizeColumn } from './column-label';
import { VizRenderOptions } from './viz-types';

export { humanizeColumn } from './column-label';

/** Columns rendered as status badges: the widget's `badgeColumns`, else any column named status / severity / rag. */
export function isBadgeColumn(column: string, opts?: VizRenderOptions): boolean {
    if (opts?.badgeColumns) return opts.badgeColumns.includes(column);
    return /(^|_)(status|severity|rag)$/i.test(column);
}

function escapeHtml(s: string): string {
    return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);
}

/** ag-Grid column definitions for the table plugin: readable headers + status badges. */
export function tableColDefs(columns: string[], opts?: VizRenderOptions): ColDef[] {
    return columns.map((field) => {
        const def: ColDef = { field, headerName: opts?.columnLabels?.[field] ?? humanizeColumn(field) };
        if (isBadgeColumn(field, opts)) {
            def.cellRenderer = (p: { value: unknown }) => {
                if (p.value == null || p.value === '') return '';
                const text = escapeHtml(String(p.value));
                return statusBadgeHtml(text, text);
            };
        }
        return def;
    });
}
