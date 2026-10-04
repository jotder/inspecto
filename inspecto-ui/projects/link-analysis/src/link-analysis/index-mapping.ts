import type { AttributeSpec } from '@inspecto/core/component-model';
import { timeZoneOptions } from '@inspecto/core/schema/time-zones';
import type { LinkIndexBuildRequest } from '@inspecto/link-analysis/api/graph-runs.service';

/**
 * LA-INDEX-SPA-SURFACES-1 — a NEW index mapping: the body `POST /inv/index/builds` takes beyond a listed index's own
 * (`IndexRoutes.BODY_KEYS`). Framework-free, like `value-measures.ts`: the spec set and the request it sends. The
 * server is the only judge of the columns (a non-column is a 422 naming it) and of `timeColZone` (it needs a
 * `timeCol`; an unknown zone is a 422).
 */
export function indexMappingAttributes(): AttributeSpec[] {
    const col = (key: string, label: string, required: boolean, help?: string): AttributeSpec => ({
        key,
        label,
        type: 'autocomplete',
        tier: 'required',
        required,
        pattern: '[A-Za-z_][A-Za-z0-9_]*',
        help,
    });
    return [
        { key: 'dataset', label: 'Dataset', type: 'autocomplete', tier: 'required' },
        col('sourceCol', 'Source column', true),
        col('targetCol', 'Target column', true),
        col('kindCol', 'Link kind column', false),
        col('timeCol', 'Time column', false, 'Lets a temporal constraint be served from the index.'),
        {
            key: 'timeColZone',
            label: 'Time column zone',
            type: 'select',
            tier: 'required',
            required: false,
            options: timeZoneOptions('UTC (default)'),
            help: 'The zone a plain TIMESTAMP time column is in; needs a time column.',
        },
        col('weightCol', 'Weight column', false),
        {
            key: 'attrCols',
            label: 'Attribute columns',
            type: 'list',
            tier: 'required',
            required: false,
            help: 'Carried per link, so a filter or an attribute read can be served from the index.',
        },
    ];
}

/** The build request for a chosen mapping: blanks dropped, and ALWAYS a `full` build — append and compact need a live version of the SAME mapping. */
export function indexMappingRequest(v: Record<string, unknown>): LinkIndexBuildRequest {
    const out: Record<string, unknown> = {};
    for (const k of ['dataset', 'sourceCol', 'targetCol', 'kindCol', 'timeCol', 'timeColZone', 'weightCol']) {
        const x = v[k];
        if (typeof x === 'string' && x.trim() !== '') out[k] = x.trim();
    }
    if (!out['timeCol']) delete out['timeColZone']; // a zone never travels without its column (the server refuses it)
    const attrs = Array.isArray(v['attrCols'])
        ? (v['attrCols'] as unknown[]).map((a) => String(a).trim()).filter(Boolean)
        : [];
    if (attrs.length) out['attrCols'] = attrs;
    out['mode'] = 'full';
    return out as unknown as LinkIndexBuildRequest;
}
