import { G6GraphData } from './graph-types';

/** One tile of the Link Analysis "Working set" overlay. */
export interface WorkingSetStat {
    label: string;
    value: string;
    /** What the tile was derived from (a tooltip). */
    hint?: string;
}

/** How the overlay labels and derives its tiles — a domain profile supplies these. */
export interface WorkingSetOptions {
    /** Tile labels; defaults are neutral graph vocabulary. */
    labels?: Partial<Record<'nodes' | 'links' | 'rows', string>>;
    /** Edge-attribute columns to sum. Omitted ⇒ every column whose values all parse as numbers. */
    measureColumns?: string[];
    /** Edge-attribute column whose min → max span is shown. Omitted ⇒ the first column whose values all parse as dates. */
    timeColumn?: string;
}

const compact = new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 });
const plain = new Intl.NumberFormat();

function values(g: G6GraphData, col: string): string[] {
    return g.edges.map((e) => e.data.attrs?.[col]).filter((v): v is string => v != null && v !== '');
}

/** A column is numeric when it has values and every one of them parses as a finite number. */
export function isNumericColumn(g: G6GraphData, col: string): boolean {
    const vs = values(g, col);
    return vs.length > 0 && vs.every((v) => Number.isFinite(Number(v.replace(/,/g, ''))));
}

/** A column is temporal when it has values, none are plain numbers, and every one parses as a date. */
export function isTemporalColumn(g: G6GraphData, col: string): boolean {
    const vs = values(g, col);
    return vs.length > 0 && vs.every((v) => !/^-?\d+(\.\d+)?$/.test(v) && Number.isFinite(Date.parse(v)));
}

/** Every edge-attribute column present anywhere in the graph, sorted. */
export function attrColumns(g: G6GraphData): string[] {
    return [...new Set(g.edges.flatMap((e) => Object.keys(e.data.attrs ?? {})))].sort();
}

/**
 * The tiles describing what is on the canvas versus what was loaded: nodes and links shown over total,
 * folded rows (Σ `count` on projected edges), a sum per numeric attribute column and the span of the
 * temporal column. Pure and framework-free so a domain profile can be unit-tested against fixtures.
 */
export function workingSetStats(
    shown: G6GraphData | null,
    full: G6GraphData | null,
    opts: WorkingSetOptions = {},
): WorkingSetStat[] {
    if (!full) return [];
    const s = shown ?? full;
    const labels = { nodes: 'Nodes', links: 'Links', rows: 'Folded rows', ...opts.labels };
    const out: WorkingSetStat[] = [
        { label: labels.nodes, value: ratio(s.nodes.length, full.nodes.length), hint: 'shown / loaded' },
        { label: labels.links, value: ratio(s.edges.length, full.edges.length), hint: 'shown / loaded' },
    ];
    const counts = s.edges.map((e) => (e.data as { count?: number }).count).filter((c): c is number => c != null);
    if (counts.length) {
        out.push({
            label: labels.rows,
            value: plain.format(counts.reduce((a, b) => a + b, 0)),
            hint: 'Σ count over the shown links',
        });
    }
    const cols = attrColumns(full);
    const measures = opts.measureColumns ?? cols.filter((c) => isNumericColumn(full, c)).slice(0, 2);
    for (const col of measures) {
        const vs = values(s, col)
            .map((v) => Number(v.replace(/,/g, '')))
            .filter((n) => Number.isFinite(n));
        if (!vs.length) continue;
        out.push({ label: `Σ ${col}`, value: compact.format(vs.reduce((a, b) => a + b, 0)), hint: `sum of ${col}` });
    }
    const timeCol = opts.timeColumn ?? cols.find((c) => isTemporalColumn(full, c));
    if (timeCol) {
        const ts = values(s, timeCol)
            .map((v) => Date.parse(v))
            .filter((t) => Number.isFinite(t));
        if (ts.length) {
            const day = (t: number): string => new Date(t).toISOString().slice(0, 10);
            out.push({
                label: timeCol,
                value: `${day(Math.min(...ts))} → ${day(Math.max(...ts))}`,
                hint: 'earliest → latest',
            });
        }
    }
    return out;
}

function ratio(shown: number, total: number): string {
    return shown === total ? plain.format(total) : `${plain.format(shown)} / ${plain.format(total)}`;
}
