import { InvestigationLogEntry, InvestigationTemplateParameter } from 'app/inspecto/api';

/** The id rule every Investigation object shares (`SnapshotStore.SAFE_ID`). */
export const SAFE_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;

/** Ops that name entities of one graph with an analyst's judgement — a template leaves them with the case. */
const CASE_OPS = new Set(['exclude', 'hide', 'keep']);

export interface TemplatePreview {
    parameters: InvestigationTemplateParameter[];
    dropped: { step: number; op: string; count: number }[];
    /** Expands that named a frontier — the template expands the whole Working Set there instead. */
    generalised: { step: number; namedFrontier: number }[];
}

/**
 * What "Save as template" will do to this log, computed BEFORE saving — the route offers no dry run and writes
 * write-once, so the analyst sees the D-E8 extraction first. It mirrors `InvestigationTemplateRoutes.save` over the
 * effective log (ops not undone): seed → parameter `seedN` (`kind:'seed'`), window → parameter `windowN`
 * (`kind:'window'`, the authored window as its default), exclude/hide/keep → dropped (count only), an expand that
 * named ids → generalised (its rung travels whole, so nothing to preview). The server's answer (with `exact`) is shown after the save and is authoritative.
 */
export function templatePreview(entries: InvestigationLogEntry[]): TemplatePreview {
    const out: TemplatePreview = { parameters: [], dropped: [], generalised: [] };
    for (const e of entries) {
        if (e.kind !== 'op' || e.undoneBy != null || !e.op) continue;
        const ids = e.params?.ids ?? [];
        const count = (kind: string) => out.parameters.filter((p) => p.kind === kind).length + 1;
        if (e.op === 'seed') {
            out.parameters.push({
                name: `seed${count('seed')}`,
                kind: 'seed',
                entityType: e.params?.entityType ?? null,
                step: e.step,
            });
        } else if (e.op === 'window') {
            const w = e.params?.window;
            out.parameters.push({
                name: `window${count('window')}`,
                kind: 'window',
                default: w && typeof w === 'object' ? w : null,
                step: e.step,
            });
        } else if (e.op === 'expand') {
            if (ids.length) out.generalised.push({ step: e.step, namedFrontier: ids.length });
        } else if (CASE_OPS.has(e.op)) {
            out.dropped.push({ step: e.step, op: e.op, count: ids.length });
        }
    }
    return out;
}
