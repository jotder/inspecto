import { Finding } from 'app/inspecto/api';

/**
 * The non-mutating agent tools the inline surface can invoke (AGT-6a A1). All are draft-only: they
 * validate or derive, and persist nothing — applying a draft is a separate human action through the
 * pane's own validated route. `POST /agent/tools/{name}` refuses anything mutating with a 403.
 *
 * ⚠ This union and {@link adaptToolResult} are a **pair**. A backend tool missing from either yields an
 * empty candidate list, which the surface renders as "no suggestion" with no error — the failure mode
 * that makes a new tool look like a model problem.
 */
export type AiToolName =
    | 'component_draft'
    | 'query_author'
    | 'kpi_report_builder'
    | 'pipeline_author'
    | 'projection_author'
    | 'suggest_expectations';

/**
 * One candidate the operator can review and apply — the normalized form of every tool's result, so
 * the shared surface renders findings/diff/Apply identically regardless of which tool ran.
 *
 * A tool returns either ONE draft (`component_draft`, `query_author`, `kpi_report_builder`,
 * `pipeline_author`) or SEVERAL candidates to choose between (`suggest_expectations`), which is why
 * the adapters below always produce a list.
 */
export interface AiDraft {
    /** Short, human-readable label for the candidate — what the operator picks by. */
    label: string;
    /** The config to apply, exactly as the tool produced it. */
    config: Record<string, unknown>;
    /** False when the tool's validation raised findings; the draft is still reviewable. */
    clean: boolean;
    /** Anchored validation findings — `fieldPath` points at the offending field. */
    findings: Finding[];
    /** Optional supporting evidence (profile stats, simulation counts) shown under the label. */
    note?: string;
    /** Extra drafts this candidate depends on, applied FIRST (kpi_report_builder's widgets before
     *  its dashboard). Empty for every other tool. */
    prerequisites?: AiDraft[];
}

function isRecord(v: unknown): v is Record<string, unknown> {
    return typeof v === 'object' && v !== null && !Array.isArray(v);
}

function findingsOf(result: Record<string, unknown>): Finding[] {
    const raw = result['findings'];
    return Array.isArray(raw) ? (raw as Finding[]) : [];
}

function str(v: unknown, fallback: string): string {
    return typeof v === 'string' && v.trim() !== '' ? v.trim() : fallback;
}

/**
 * Normalize one tool result into the candidates the surface renders.
 *
 * The authoring tools that compose a single component already share the backend's
 * `{kind, id?, clean, findings, draft}` shape, so they share one branch — `projection_author` joins it,
 * its `draft` being a `{query:{projections:[…]}}` fragment rather than a whole config. `suggest_expectations`
 * returns several *derived* candidates with no validation of their own (they are profiled from real
 * data, so there is nothing to repair — findings stay empty until a human validates on apply).
 * `pipeline_author` returns a parsed+simulated graph rather than a config envelope.
 *
 * Returns an empty list for an unrecognized shape — the surface then shows "no suggestion", never a
 * broken card.
 */
export function adaptToolResult(tool: AiToolName, result: unknown): AiDraft[] {
    if (!isRecord(result)) return [];
    switch (tool) {
        case 'suggest_expectations': {
            const suggestions = result['suggestions'];
            if (!Array.isArray(suggestions)) return [];
            const profile = isRecord(result['profile']) ? result['profile'] : {};
            const rows = profile['rows'];
            const evidence = typeof rows === 'number' ? `profiled ${rows.toLocaleString()} rows` : undefined;
            return suggestions.filter(isRecord).map((s) => ({
                label: str(s['name'], String(s['kind'] ?? 'expectation')),
                config: s,
                // Derived from real data by deterministic SQL — nothing to repair.
                clean: true,
                findings: [],
                note: [str(s['description'], ''), evidence].filter(Boolean).join(' · ') || undefined,
            }));
        }
        case 'pipeline_author': {
            // `flow` is the round-tripped GRAPH (A5.3). It used to be the flow's NAME, which this branch
            // could not read at all — every real-backend draft fell through to "no suggestion".
            const flow = result['flow'];
            if (!isRecord(flow)) return [];
            const nodes = Array.isArray(result['nodes']) ? result['nodes'].length : 0;
            const simulated = result['simulated'] === true;
            return [
                {
                    label: str(result['name'], str(flow['name'], 'pipeline')),
                    config: flow,
                    // Structural validation is real (A5.3) — a dangling edge or a cycle is a finding, so
                    // this must not keep claiming every topology is clean.
                    clean: result['clean'] !== false,
                    findings: findingsOf(result),
                    note: `${nodes} node${nodes === 1 ? '' : 's'}${simulated ? ' · dry-run simulated' : ''}`,
                },
            ];
        }
        case 'kpi_report_builder': {
            const draft = result['draft'];
            if (!isRecord(draft)) return [];
            // The widgets must be applied before the dashboard that tiles them.
            const widgets = Array.isArray(result['widgets']) ? result['widgets'].filter(isRecord) : [];
            return [
                {
                    label: str(result['id'], 'dashboard'),
                    config: draft,
                    clean: result['clean'] === true,
                    findings: findingsOf(result),
                    note: widgets.length
                        ? `${widgets.length} widget${widgets.length === 1 ? '' : 's'} applied first`
                        : undefined,
                    prerequisites: widgets
                        .filter((w) => isRecord(w['draft']))
                        .map((w) => ({
                            label: str(w['id'], 'widget'),
                            config: w['draft'] as Record<string, unknown>,
                            clean: true,
                            findings: [],
                        })),
                },
            ];
        }
        default: {
            // component_draft / query_author / projection_author — one validated config envelope.
            const draft = result['draft'];
            if (!isRecord(draft)) return [];
            return [
                {
                    label: str(result['id'], str(result['kind'], 'draft')),
                    config: draft,
                    clean: result['clean'] === true,
                    findings: findingsOf(result),
                },
            ];
        }
    }
}

/** One line of the draft-vs-current comparison the operator reviews before applying. */
export interface DiffRow {
    /** Dotted path to the field. */
    path: string;
    change: 'added' | 'changed' | 'removed' | 'same';
    before: string;
    after: string;
}

const ABSENT = '—';

function render(v: unknown): string {
    if (v === undefined || v === null) return ABSENT;
    if (typeof v === 'string') return v;
    return JSON.stringify(v);
}

/** Flatten nested objects to dotted paths; arrays are compared whole (order matters, and a
 *  per-element diff would be noise the operator cannot act on). */
function flatten(value: unknown, prefix: string, into: Map<string, unknown>): void {
    if (isRecord(value)) {
        for (const [k, v] of Object.entries(value)) flatten(v, prefix ? `${prefix}.${k}` : k, into);
        return;
    }
    if (prefix) into.set(prefix, value);
}

/**
 * Compare a draft against the pane's current config, so Apply is never a blind write. `current` null
 * (a create, nothing to compare) yields every field as `added`. Unchanged fields are included as
 * `same` and the surface hides them behind a toggle — the operator can still audit the whole config.
 */
export function configDiff(current: Record<string, unknown> | null, next: Record<string, unknown>): DiffRow[] {
    const before = new Map<string, unknown>();
    const after = new Map<string, unknown>();
    flatten(current ?? {}, '', before);
    flatten(next, '', after);

    const rows: DiffRow[] = [];
    for (const path of [...new Set([...before.keys(), ...after.keys()])].sort()) {
        const had = before.has(path);
        const has = after.has(path);
        const b = before.get(path);
        const a = after.get(path);
        let change: DiffRow['change'];
        if (had && !has) change = 'removed';
        else if (!had && has) change = 'added';
        else change = render(b) === render(a) ? 'same' : 'changed';
        rows.push({ path, change, before: render(b), after: render(a) });
    }
    return rows;
}
