/**
 * BI-INSIGHTS-1 P2 — "Explain this change": the `POST /insights/explain` body a Widget's own query asks for. Framework-free.
 *
 * The Widget's `/bi/query` body supplies the Dataset, its filters, the Measure (the first plain Measure, else the first
 * ratio) and the date column with its grain (exactly one grained `groupBy` column). The explained bucket is the last
 * one the chart shows. Dimensions are the Dataset's dimension columns (not the date, not hidden), at most 12; "moved
 * with it" offers the sum of each other measure column, at most 10. Anything that cannot be explained gets a plain
 * reason instead of a body; whether the aggregate is additive is the server's call, refused there in words.
 */
import type { BiQueryBody } from 'app/inspecto/api/bi-query.service';

export interface ExplainColumn {
    name: string;
    role: string;
    hidden?: boolean;
}

export interface ExplainMeasureWire {
    agg: string;
    field?: string;
}

export interface ExplainRequest {
    dataset: string;
    filters?: unknown[];
    measure?: ExplainMeasureWire;
    ratio?: { num: ExplainMeasureWire; den: ExplainMeasureWire };
    field: string;
    grain: string;
    at: string;
    dimensions: string[];
    related: ExplainMeasureWire[];
}

export const MAX_DIMENSIONS = 12;
export const MAX_RELATED = 10;

/** The request, or the reason this Widget cannot be explained. `lastLabel` is the chart's last x label. */
export function explainRequest(
    body: BiQueryBody | null,
    columns: readonly ExplainColumn[],
    lastLabel: string | undefined,
): { request: ExplainRequest } | { reason: string } {
    if (!body) return { reason: 'This Widget’s query cannot run on the server, so it cannot be explained.' };
    if (body.query) return { reason: 'A Widget on a saved Query cannot be explained yet — only one on a Dataset.' };
    const grained = Object.entries(body.grains ?? {});
    if (grained.length !== 1)
        return { reason: 'Explain needs a chart over time: one date grouped by day, week or month.' };
    const [field, grain] = grained[0];
    const ratio = body.ratios?.[0];
    const measure = body.measures?.[0];
    if (!measure && !ratio) return { reason: 'Explain needs a Measure.' };
    if (!lastLabel || !/^\d{4}-\d{2}(-\d{2})?$/.test(lastLabel))
        return { reason: 'There is no period on the chart to explain yet.' };
    const at = lastLabel.length === 7 ? lastLabel + '-01' : lastLabel;
    const grouped = new Set(body.groupBy ?? []);
    const dimensions = columns
        .filter((c) => !c.hidden && c.name !== field && (c.role === 'dimension' || c.role === 'entity' || c.role === 'detail'))
        .map((c) => c.name)
        .sort((a, b) => Number(grouped.has(b)) - Number(grouped.has(a)))
        .slice(0, MAX_DIMENSIONS);
    if (!dimensions.length) return { reason: 'The Dataset has no dimension column to explain the change by.' };
    const target = measure ? [measure.field] : [ratio!.num.field, ratio!.den.field];
    const related = columns
        .filter((c) => !c.hidden && c.role === 'measure' && !target.includes(c.name))
        .slice(0, MAX_RELATED)
        .map((c) => ({ agg: 'sum', field: c.name }));
    const request: ExplainRequest = { dataset: body.dataset, field, grain, at, dimensions, related };
    if (body.filters?.length) request.filters = body.filters;
    if (measure) request.measure = measure;
    else request.ratio = ratio;
    return { request };
}
