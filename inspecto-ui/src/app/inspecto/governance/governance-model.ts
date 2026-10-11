/**
 * Incident governance (ASSURE-WORKFLOW-SLA-1) — the framework-free shapes behind the Settings ▸ Incident governance
 * section: the `workflow`, `sla-policy` and `escalation-rule` component contents, and the list-editing helpers.
 * The SERVER is the judge of every rule (reachability, one initial state, no terminal move around RESOLVED, an
 * IANA zone …); a refusal comes back as a 422 whose message the section shows. Nothing here re-implements it.
 */

import { ColumnMeta, ConditionGroup } from 'app/inspecto/query/query-types';

export const GOVERNED_OBJECT_TYPES = ['INCIDENT', 'CASE', 'TASK'] as const;
/**
 * The match context an Escalation Rule's `when` is evaluated over (server `ObjectService.escalationContext`):
 * strings and numbers only, `minutesToDue` is huge when there is no deadline, the flags are 0/1.
 */
export const ESCALATION_CONTEXT_COLUMNS: ColumnMeta[] = [
    { name: 'type', type: 'string' },
    { name: 'status', type: 'string' },
    { name: 'priority', type: 'string' },
    { name: 'severity', type: 'string' },
    { name: 'category', type: 'string' },
    { name: 'assignee', type: 'string' },
    { name: 'ageMinutes', type: 'number' },
    { name: 'minutesToDue', type: 'number' },
    { name: 'resolutionBreached', type: 'number' },
    { name: 'responseBreached', type: 'number' },
    { name: 'escalated', type: 'number' },
];

export const WEEK_DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'] as const;

export interface TransitionRow {
    from: string;
    to: string;
    action: string;
}

export interface WorkflowDraft {
    objectType: string;
    initial: string;
    terminal: string;
    transitions: TransitionRow[];
}

export interface SlaTargetRow {
    priority: string;
    responseMinutes: number | null;
    resolutionMinutes: number | null;
}

export interface SlaDraft {
    objectType: string;
    zone: string;
    workingDays: string[];
    start: string;
    end: string;
    holidays: string;
    targets: SlaTargetRow[];
}

export interface EscalationRuleDraft {
    id: string;
    objectType: string;
    on: 'breach' | 'age';
    target: 'resolution' | 'response';
    afterMinutes: number | null;
    priority: string;
    reassign: string;
    notify: boolean;
    raisePriority: boolean;
    /** The optional `when` condition tree over the match context; `undefined` = keep the stored one. */
    when?: ConditionGroup | null;
}

/** `"A, b ,, c"` → `['A', 'B', 'C']` (upper-cased when `upper`). */
export function splitList(text: string, upper = true): string[] {
    return (text ?? '')
        .split(',')
        .map((s) => s.trim())
        .filter((s) => s.length > 0)
        .map((s) => (upper ? s.toUpperCase() : s));
}

/** The component id of a per-type document: the lower-cased object type. */
export function governanceId(objectType: string): string {
    return objectType.trim().toLowerCase();
}

export function workflowContent(d: WorkflowDraft): Record<string, unknown> {
    return {
        id: governanceId(d.objectType),
        objectType: d.objectType,
        initial: d.initial.trim().toUpperCase(),
        terminal: splitList(d.terminal),
        transitions: d.transitions
            .filter((t) => t.from.trim() || t.to.trim() || t.action.trim())
            .map((t) => ({
                from: t.from.trim().toUpperCase(),
                to: t.to.trim().toUpperCase(),
                action: t.action.trim().toLowerCase(),
            })),
    };
}

/** A draft from either a stored component's content or the served `GET /workflows/{type}` document. */
export function workflowDraft(objectType: string, content: Record<string, unknown> | null | undefined): WorkflowDraft {
    const c = content ?? {};
    const moves = Array.isArray(c['transitions']) ? (c['transitions'] as Record<string, unknown>[]) : [];
    const terminal = Array.isArray(c['terminal']) ? (c['terminal'] as unknown[]).map(String) : [];
    return {
        objectType,
        initial: String(c['initial'] ?? ''),
        terminal: terminal.join(', '),
        transitions: moves.map((t) => ({
            from: String(t['from'] ?? ''),
            to: String(t['to'] ?? ''),
            action: String(t['action'] ?? ''),
        })),
    };
}

export function slaContent(d: SlaDraft): Record<string, unknown> {
    const calendar: Record<string, unknown> = { zone: d.zone.trim(), workingDays: d.workingDays };
    if (d.start.trim()) calendar['start'] = d.start.trim();
    if (d.end.trim()) calendar['end'] = d.end.trim();
    const holidays = splitList(d.holidays, false);
    if (holidays.length) calendar['holidays'] = holidays;
    return {
        id: governanceId(d.objectType),
        objectType: d.objectType,
        calendar,
        targets: d.targets
            .filter((t) => t.priority.trim())
            .map((t) => ({
                priority: t.priority.trim().toUpperCase(),
                ...(t.responseMinutes ? { responseMinutes: Number(t.responseMinutes) } : {}),
                ...(t.resolutionMinutes ? { resolutionMinutes: Number(t.resolutionMinutes) } : {}),
            })),
    };
}

export function slaDraft(objectType: string, content: Record<string, unknown> | null | undefined): SlaDraft {
    const c = content ?? {};
    const cal = (c['calendar'] ?? {}) as Record<string, unknown>;
    const targets = Array.isArray(c['targets']) ? (c['targets'] as Record<string, unknown>[]) : [];
    const num = (v: unknown): number | null => (v === undefined || v === null || v === '' ? null : Number(v));
    return {
        objectType,
        zone: String(cal['zone'] ?? ''),
        workingDays: Array.isArray(cal['workingDays'])
            ? (cal['workingDays'] as unknown[]).map(String)
            : ['MON', 'TUE', 'WED', 'THU', 'FRI'],
        start: String(cal['start'] ?? '09:00'),
        end: String(cal['end'] ?? '17:00'),
        holidays: Array.isArray(cal['holidays']) ? (cal['holidays'] as unknown[]).map(String).join(', ') : '',
        targets: targets.map((t) => ({
            priority: String(t['priority'] ?? ''),
            responseMinutes: num(t['responseMinutes']),
            resolutionMinutes: num(t['resolutionMinutes']),
        })),
    };
}

/** Keys {@link escalationRuleContent} authors itself; anything else on the stored rule rides through a save. */
const ESCALATION_MODELLED = [
    'id',
    'objectType',
    'on',
    'target',
    'afterMinutes',
    'priority',
    'reassign',
    'notify',
    'raisePriority',
    'when',
];

/**
 * The `escalation-rule` body to save. `stored` is the rule being edited (omit for a new one): keys this form does
 * not model — a `when` the author did not touch, or a key a newer server added — are carried forward untouched
 * (a rebuild from modelled state would silently drop them). A modelled key the author cleared stays cleared.
 */
export function escalationRuleContent(
    d: EscalationRuleDraft,
    stored: Record<string, unknown> = {},
): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    for (const k of Object.keys(stored)) if (!ESCALATION_MODELLED.includes(k)) out[k] = stored[k];
    out['id'] = d.id.trim();
    out['objectType'] = d.objectType;
    out['on'] = d.on;
    if (d.on === 'breach') out['target'] = d.target;
    else out['afterMinutes'] = Number(d.afterMinutes);
    if (d.priority.trim()) out['priority'] = d.priority.trim().toUpperCase();
    if (d.reassign.trim()) out['reassign'] = d.reassign.trim();
    if (d.notify) out['notify'] = true;
    if (d.raisePriority) out['raisePriority'] = true;
    const when = d.when === undefined ? stored['when'] : d.when;
    if (when && !(isGroup(when) && when.items.length === 0)) out['when'] = when;
    return out;
}

/**
 * The form draft of a stored Escalation Rule — the inverse of {@link escalationRuleContent} for the keys it models.
 * `when` is a deep copy (the condition-group editor mutates its group in place), or `undefined` when the stored
 * value is not a condition group, so a save carries it forward untouched.
 */
export function escalationRuleDraft(content: Record<string, unknown> | null | undefined): EscalationRuleDraft {
    const c = content ?? {};
    const after = c['afterMinutes'];
    return {
        id: String(c['id'] ?? ''),
        objectType: String(c['objectType'] ?? ''),
        on: c['on'] === 'age' ? 'age' : 'breach',
        target: c['target'] === 'response' ? 'response' : 'resolution',
        afterMinutes: after === undefined || after === null || after === '' ? null : Number(after),
        priority: String(c['priority'] ?? ''),
        reassign: String(c['reassign'] ?? ''),
        notify: c['notify'] === true,
        raisePriority: c['raisePriority'] === true,
        when: isGroup(c['when']) ? structuredClone(c['when']) : undefined,
    };
}

const isGroup = (v: unknown): v is ConditionGroup =>
    !!v && typeof v === 'object' && Array.isArray((v as ConditionGroup).items);

/** One line describing a stored Escalation Rule, for the list. */
export function describeEscalationRule(c: Record<string, unknown>): string {
    const when =
        c['on'] === 'age'
            ? `at ${c['afterMinutes']} min old`
            : `on a ${String(c['target'] ?? 'resolution')} SLA breach`;
    const does: string[] = [];
    if (c['reassign']) does.push(`reassign to ${c['reassign']}`);
    if (c['notify'] === true || c['notify'] === 'true') does.push('notify');
    if (c['raisePriority'] === true || c['raisePriority'] === 'true') does.push('raise priority');
    const scope = c['priority'] ? ` ${String(c['priority'])}` : '';
    return `${String(c['objectType'] ?? '')}${scope} ${when}: ${does.join(', ')}`;
}

export interface DiagramNode {
    id: string;
    column: number;
    row: number;
    terminal: boolean;
    initial: boolean;
    /** Not reachable from the initial state (the server refuses this; the diagram shows it dashed). */
    unreachable: boolean;
}

export interface DiagramEdge {
    from: string;
    to: string;
    action: string;
}

export interface WorkflowDiagram {
    nodes: DiagramNode[];
    edges: DiagramEdge[];
    columns: number;
}

/**
 * The read-only state diagram's layout: a column per breadth-first distance from the initial state, unreachable
 * states in a final column, rows in first-seen order. Pure — the SVG component only draws it.
 */
export function workflowDiagram(d: WorkflowDraft, extraStates: readonly string[] = []): WorkflowDiagram {
    const content = workflowContent(d);
    const initial = String(content['initial']);
    const terminal = new Set(content['terminal'] as string[]);
    const edges = (content['transitions'] as DiagramEdge[]).filter((e) => e.from && e.to);
    const ids: string[] = [];
    const add = (s: string): void => {
        if (s && !ids.includes(s)) ids.push(s);
    };
    add(initial);
    edges.forEach((e) => {
        add(e.from);
        add(e.to);
    });
    terminal.forEach(add);
    extraStates.map((x) => x.trim().toUpperCase()).forEach(add);
    const depth = new Map<string, number>();
    if (initial) {
        depth.set(initial, 0);
        const queue = [initial];
        while (queue.length) {
            const s = queue.shift() as string;
            for (const e of edges) {
                if (e.from === s && !depth.has(e.to)) {
                    depth.set(e.to, (depth.get(s) as number) + 1);
                    queue.push(e.to);
                }
            }
        }
    }
    const lost = ids.some((s) => !depth.has(s));
    const lostColumn = Math.max(-1, ...depth.values()) + 1;
    const rowsUsed = new Map<number, number>();
    const nodes = ids.map((id) => {
        const column = depth.get(id) ?? lostColumn;
        const row = rowsUsed.get(column) ?? 0;
        rowsUsed.set(column, row + 1);
        return { id, column, row, terminal: terminal.has(id), initial: id === initial, unreachable: !depth.has(id) };
    });
    return { nodes, edges, columns: lost ? lostColumn + 1 : lostColumn };
}

// ── Graphical state-diagram editor (ASSURE-WORKFLOW-SLA-RESIDUALS-1 b) ───────────────────────────────────────────
// Edits are pure WorkflowDraft → WorkflowDraft, so the persisted shape is whatever `workflowContent` already writes
// (states are implicit: initial ∪ terminal ∪ transition endpoints — there is no stored state list or layout).

const norm = (s: string): string => (s ?? '').trim().toUpperCase();

/** Every state the draft names (initial, terminal, transition endpoints), first-seen order. */
export function workflowStates(d: WorkflowDraft): string[] {
    return workflowDiagram(d).nodes.map((n) => n.id);
}

const withTerminal = (d: WorkflowDraft, terminal: string[]): WorkflowDraft => ({ ...d, terminal: terminal.join(', ') });

/** Rename a state everywhere it is named. A blank or already-existing target name is refused (returns `d`). */
export function renameState(d: WorkflowDraft, from: string, to: string): WorkflowDraft {
    const a = norm(from);
    const b = norm(to);
    if (!a || !b || a === b || workflowStates(d).includes(b)) return d;
    const swap = (s: string): string => (norm(s) === a ? b : s);
    return {
        ...d,
        initial: swap(d.initial),
        terminal: splitList(d.terminal).map(swap).join(', '),
        transitions: d.transitions.map((t) => ({ ...t, from: swap(t.from), to: swap(t.to) })),
    };
}

/** Remove a state: its transitions go with it, it leaves the terminal set, and the initial state is cleared if it was it. */
export function removeState(d: WorkflowDraft, id: string): WorkflowDraft {
    const a = norm(id);
    return {
        ...d,
        initial: norm(d.initial) === a ? '' : d.initial,
        terminal: splitList(d.terminal)
            .filter((s) => s !== a)
            .join(', '),
        transitions: d.transitions.filter((t) => norm(t.from) !== a && norm(t.to) !== a),
    };
}

export function setInitialState(d: WorkflowDraft, id: string): WorkflowDraft {
    return { ...d, initial: norm(id) };
}

export function setTerminalState(d: WorkflowDraft, id: string, on: boolean): WorkflowDraft {
    const a = norm(id);
    const rest = splitList(d.terminal).filter((s) => s !== a);
    return withTerminal(d, on ? [...rest, a] : rest);
}

/** Connect two states with a new transition; its action is left blank for the author to name (the server requires one). */
export function connectStates(d: WorkflowDraft, from: string, to: string): WorkflowDraft {
    return { ...d, transitions: [...d.transitions, { from: norm(from), to: norm(to), action: '' }] };
}

/**
 * Live mirror of the server's `Workflow.problems()` / `fromMap` refusals (same wording), so the editor can say what
 * a save would be refused for. Advisory: the SERVER stays the judge, and its 422 message is shown verbatim on save.
 */
export function workflowProblems(d: WorkflowDraft): string[] {
    const c = workflowContent(d);
    const initial = String(c['initial']);
    const terminal = new Set(c['terminal'] as string[]);
    const moves = c['transitions'] as TransitionRow[];
    const out: string[] = [];
    if (!initial) out.push('an initial state is required');
    if (moves.some((t) => !t.from || !t.to || !t.action)) out.push('transition needs from, to and action');
    const ts = moves.filter((t) => t.from && t.to && t.action);
    if (ts.length === 0) out.push('workflow needs at least one transition');
    if (!initial) return out;
    const states = new Set<string>([initial, ...terminal]);
    ts.forEach((t) => {
        states.add(t.from);
        states.add(t.to);
    });
    if (terminal.size === 0) out.push('declare at least one terminal state');
    const reachable = new Set<string>([initial]);
    for (const q = [initial]; q.length; ) {
        const cur = q.shift() as string;
        ts.forEach((t) => {
            if (t.from === cur && !reachable.has(t.to)) {
                reachable.add(t.to);
                q.push(t.to);
            }
        });
    }
    const incident = d.objectType.toUpperCase() === 'INCIDENT';
    for (const s of states) {
        if (!reachable.has(s)) out.push(`state ${s} is unreachable from the initial state ${initial}`);
        if (!terminal.has(s) && !ts.some((t) => t.from === s))
            out.push(`state ${s} has no way out and is not declared terminal`);
    }
    const canFinish = new Set<string>(incident ? ['RESOLVED', 'ARCHIVED'] : terminal);
    for (let grew = true; grew; ) {
        grew = false;
        for (const t of ts) if (canFinish.has(t.to) && !canFinish.has(t.from)) grew = !!canFinish.add(t.from);
    }
    for (const s of states)
        if (!terminal.has(s) && !canFinish.has(s))
            out.push(
                `state ${s} can never finish: no path from it reaches ${incident ? 'RESOLVED or ARCHIVED' : 'a terminal state'}`,
            );
    const seen = new Set<string>();
    for (const t of ts) {
        const key = `${t.from} ${t.action}`;
        if (seen.has(key)) out.push(`two transitions leave ${t.from} by action '${t.action}'`);
        seen.add(key);
    }
    if (incident) {
        if (!states.has('RESOLVED'))
            out.push(
                'an Incident workflow needs a RESOLVED state — it is where the resolution gate records the outcome',
            );
        if (states.has('ARCHIVED') && !terminal.has('ARCHIVED'))
            out.push('ARCHIVED must be terminal in an Incident workflow');
        for (const t of ts)
            if (terminal.has(t.to) && t.to !== 'ARCHIVED' && t.to !== 'RESOLVED' && t.from !== 'RESOLVED')
                out.push(
                    `transition ${t.from} -${t.action}-> ${t.to} finishes an Incident around RESOLVED, skipping the Disposition and postmortem gate`,
                );
    }
    return out;
}
