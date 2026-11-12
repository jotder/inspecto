import type { ProjectionResult, ProjectionTriple } from './inv.service';

/**
 * PERF-P10-1: the compact edge body of `POST /inv/projection` and `/inv/projection/neighbors`
 * (`application/vnd.inspecto.la-edges.v2+json`): column names once, each row an array in `columns` order. `columns` is
 * `source, target, kind, count`, then `total` when the request named a Measure, then the `attrCols`.
 */
export interface EdgeWire extends Omit<ProjectionResult, 'rows'> {
    columns: string[];
    attrCols: string[];
    rows: unknown[][];
}

/** Decode the compact body into the `ProjectionResult` every fold already reads (one object per row, built once). */
export function decodeEdges(w: EdgeWire): ProjectionResult {
    const { columns, attrCols, rows, ...rest } = w;
    // counted, not named: an attribute column may itself be called `total`
    const hasTotal = columns.length === 5 + attrCols.length;
    const first = hasTotal ? 5 : 4;
    const out: ProjectionTriple[] = new Array(rows.length);
    for (let r = 0; r < rows.length; r++) {
        const a = rows[r];
        const t: ProjectionTriple = {
            source: a[0] as string,
            target: a[1] as string,
            kind: (a[2] as string | null) ?? null,
            count: a[3] as number,
        };
        if (hasTotal) t.total = (a[4] as number | null) ?? null;
        if (attrCols.length) {
            const attrs: Record<string, string | null> = {};
            for (let i = 0; i < attrCols.length; i++) attrs[attrCols[i]] = (a[first + i] as string | null) ?? null;
            t.attrs = attrs;
        }
        out[r] = t;
    }
    return { ...rest, rows: out };
}
