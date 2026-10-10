/**
 * The `runbook` component kind (operator 2026-10-10): linked guidance for the person working an Incident or Case.
 * No automation. The server validates the same shape (`com.gamma.alert.Runbook`); this module only reads and writes
 * it, so a key the editor does not model (an `x-` annotation, the R3 `owner`/`shares` envelope) is carried through.
 */

export type RunbookLinkKind = 'dataset' | 'query' | 'dashboard' | 'case';

export const RUNBOOK_LINK_KINDS: { value: RunbookLinkKind; label: string }[] = [
    { value: 'dataset', label: 'Dataset' },
    { value: 'query', label: 'Query' },
    { value: 'dashboard', label: 'Dashboard' },
    { value: 'case', label: 'Case' },
];

export interface RunbookStep {
    text: string;
    link?: { kind: RunbookLinkKind; id: string } | null;
}

export interface Runbook {
    id: string;
    title: string;
    summary: string;
    steps: RunbookStep[];
    ownerRole: string;
    tags: string[];
}

const KINDS = new Set<string>(RUNBOOK_LINK_KINDS.map((k) => k.value));

function text(v: unknown): string {
    return typeof v === 'string' || typeof v === 'number' ? String(v).trim() : '';
}

/** A stored component's content as a Runbook; tolerant of a partial or malformed record (it renders what it can). */
export function runbookFromContent(id: string, content: Record<string, unknown>): Runbook {
    const steps = Array.isArray(content['steps']) ? content['steps'] : [];
    return {
        id,
        title: text(content['title']) || id,
        summary: text(content['summary']),
        ownerRole: text(content['ownerRole']),
        tags: Array.isArray(content['tags']) ? content['tags'].map(text).filter(Boolean) : [],
        steps: steps
            .map((s): RunbookStep | null => {
                if (!s || typeof s !== 'object') return null;
                const r = s as Record<string, unknown>;
                const link = r['link'] as Record<string, unknown> | undefined;
                const kind = text(link?.['kind']);
                const linkId = text(link?.['id']);
                return {
                    text: text(r['text']),
                    link: KINDS.has(kind) && linkId ? { kind: kind as RunbookLinkKind, id: linkId } : null,
                };
            })
            .filter((s): s is RunbookStep => !!s && !!s.text),
    };
}

/** The content to save: the edited keys over `kept` (every stored key the editor does not model). */
export function runbookToContent(r: Runbook, kept: Record<string, unknown> = {}): Record<string, unknown> {
    const out: Record<string, unknown> = { ...kept, id: r.id, title: r.title.trim() };
    delete out['summary'];
    delete out['ownerRole'];
    delete out['tags'];
    if (r.summary.trim()) out['summary'] = r.summary.trim();
    out['steps'] = r.steps
        .filter((s) => s.text.trim())
        .map((s) =>
            s.link?.id.trim()
                ? { text: s.text.trim(), link: { kind: s.link.kind, id: s.link.id.trim() } }
                : { text: s.text.trim() },
        );
    if (r.ownerRole.trim()) out['ownerRole'] = r.ownerRole.trim();
    const tags = r.tags.map((t) => t.trim()).filter(Boolean);
    if (tags.length) out['tags'] = tags;
    return out;
}

/** Router commands (+ query params) that open a step's link, or null for a kind with no page. */
export function runbookLinkTarget(link: { kind: RunbookLinkKind; id: string }): {
    commands: string[];
    queryParams?: Record<string, string>;
} {
    switch (link.kind) {
        case 'dataset':
            return { commands: ['/catalog/datasets', link.id] };
        case 'dashboard':
            return { commands: ['/studio/dashboards', link.id] };
        case 'case':
            return { commands: ['/cases', link.id] };
        case 'query':
            return { commands: ['/studio/queries'], queryParams: { id: link.id } };
    }
}
