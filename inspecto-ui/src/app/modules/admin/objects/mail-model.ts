import {
    FindingsSection,
    FindingsSpecDef,
    normalizeIncidentStatus,
    OperationalObject,
    WorkflowDef,
} from 'app/inspecto/api';
import { AttributeSpec, AttributeTier, AttributeType } from 'app/inspecto/component-model';

export { normalizeIncidentStatus };

/**
 * The mail-view model over {@link OperationalObject}: the canonical incident lifecycle
 * (GLOSSARY §9: IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED), the priority ladder, and the
 * mail-metaphor concepts (folders, tags, escalation, postmortem) that ride in the generic
 * `attributes` bag — no backend model change (design: docs/superpower/incidents-mail-ui-design.md).
 */

export const INCIDENT_PRIORITIES = ['CRITICAL', 'MAJOR', 'MINOR', 'LOW'] as const;

/** The (normalized) statuses per object type — folder set and Tag-Rule criteria options. */
export const INCIDENT_STATUSES = ['IDENTIFIED', 'DIAGNOSING', 'RESOLVED', 'ARCHIVED'] as const;
export const CASE_STATUSES = ['OPEN', 'INVESTIGATING', 'ESCALATED', 'RESOLVED', 'CLOSED'] as const;

/** The status shown/foldered for an object — normalized for incidents, as-is otherwise. */
export function displayStatus(o: OperationalObject): string {
    return o.objectType === 'INCIDENT' ? normalizeIncidentStatus(o.status) : (o.status ?? '').toUpperCase();
}

/** Tags ride in `attributes.tags` as a CSV. */
export function objectTags(o: OperationalObject): string[] {
    return (o.attributes?.['tags'] ?? '')
        .split(',')
        .map((t) => t.trim())
        .filter(Boolean);
}

/** The 3-layer category path (`attributes.category`, "L1 / L2 / L3"). */
export function objectCategory(o: OperationalObject): string {
    return o.attributes?.['category'] ?? '';
}

/** Escalation: a flag for incidents (`attributes.escalated`), the ESCALATED *status* for cases. */
export function isEscalated(o: OperationalObject): boolean {
    return o.objectType === 'CASE'
        ? (o.status ?? '').toUpperCase() === 'ESCALATED'
        : o.attributes?.['escalated'] === 'true';
}

/** "Me" for the My Cases folder — the auth-free Personal edition has no session identity. */
export function currentOperator(): string {
    return localStorage.getItem('inspecto.operator') || 'operator';
}

// ── Postmortem (attributes.postmortem, JSON) ──────────────────────────────────────────────────

export interface PostmortemTimelineEntry {
    time: string;
    text: string;
}

export interface PostmortemAction {
    done: boolean;
    text: string;
    owner: string;
    due: string;
}

/**
 * The Incident Postmortem template — Summary · Timeline · Cause Analysis · Corrective actions.
 * Cause analysis is a variable-length list with a method label (I2): *usually* The 5 Whys (the
 * seeded default) but not restricted to exactly five points.
 */
export interface Postmortem {
    commander: string;
    incidentDate: string;
    downtime: string;
    businessImpact: string;
    timeline: PostmortemTimelineEntry[];
    causeMethod: string;
    causeAnalysis: string[];
    actions: PostmortemAction[];
}

export const DEFAULT_CAUSE_METHOD = 'The 5 Whys';

export function emptyPostmortem(): Postmortem {
    return {
        commander: '',
        incidentDate: '',
        downtime: '',
        businessImpact: '',
        timeline: [],
        causeMethod: DEFAULT_CAUSE_METHOD,
        causeAnalysis: ['', '', '', '', ''], // the 5-Whys default shape; rows are add/removable
        actions: [],
    };
}

/**
 * Parse the stored postmortem; null when absent or unreadable (the form then starts empty).
 * Migrates the legacy fixed `fiveWhys[5]` shape onto `causeAnalysis` (I2).
 */
export function parsePostmortem(o: OperationalObject): Postmortem | null {
    const raw = o.attributes?.['postmortem'];
    if (!raw) return null;
    try {
        const p = JSON.parse(raw) as Partial<Postmortem> & { fiveWhys?: string[] };
        const causeAnalysis = p.causeAnalysis ?? p.fiveWhys ?? [];
        return {
            ...emptyPostmortem(),
            ...p,
            timeline: p.timeline ?? [],
            causeMethod: p.causeMethod ?? DEFAULT_CAUSE_METHOD,
            causeAnalysis: causeAnalysis.length ? causeAnalysis : [''],
            actions: p.actions ?? [],
        };
    } catch {
        return null;
    }
}

/**
 * I1 — the mandatory resolution pattern (§2b of `case-management-design.md`): which of the four
 * required sections an incident's resolution still lacks. Empty = complete. Drives the soft
 * resolve-gate (warn, never block — the hard/backend gate is the documented follow-up).
 */
export function postmortemGaps(o: OperationalObject): string[] {
    const p = parsePostmortem(o);
    const gaps: string[] = [];
    if (!p?.timeline.some((t) => t.time.trim() || t.text.trim())) gaps.push('timeline');
    if (!p?.causeAnalysis.some((w) => w.trim())) gaps.push('cause analysis');
    if (!p?.actions.some((a) => a.text.trim())) gaps.push('corrective actions');
    if (!o.attributes?.['dueAt']) gaps.push('SLA');
    return gaps;
}

// ── Case Findings (attributes.findings, JSON — C3) + team + loose SLA (C6) ────────────────────

/**
 * The case's resolution artifact — generic/loose vs the incident's fixed postmortem pattern. Since D6 the
 * field set is **deployment-configurable**, so this is an open record keyed by the served spec's section
 * keys rather than a fixed shape. `disposition` is the one key the panel still reads by name (the soft
 * no-disposition gate on resolve), and it degrades to "no gate" when a deployment removes that section.
 */
export type Findings = Record<string, string>;

export function emptyFindings(): Findings {
    return {};
}

/** Parse the stored findings; null when absent or unreadable. */
export function parseFindings(o: OperationalObject): Findings | null {
    const raw = o.attributes?.['findings'];
    if (!raw) return null;
    try {
        const parsed = JSON.parse(raw) as unknown;
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
        return parsed as Findings;
    } catch {
        return null;
    }
}

/**
 * Narrow a served Findings spec (C3/D6) to `AttributeSpec[]` for `<inspecto-schema-form>`. The server
 * already authors sections in this vocabulary and validates them fail-closed at authoring time, so this
 * only casts the open `string` type/tier onto their unions and drops anything unrecognised — a spec the
 * renderer cannot draw is a server bug, and skipping the field beats rendering a broken control.
 */
export function findingsAttributes(spec: FindingsSpecDef | null): AttributeSpec[] {
    if (!spec) return [];
    const out: AttributeSpec[] = [];
    for (const s of spec.sections ?? []) {
        if (!ATTRIBUTE_TYPES.includes(s.type as AttributeType) || !ATTRIBUTE_TIERS.includes(s.tier as AttributeTier)) {
            continue;
        }
        out.push({
            key: s.key,
            label: s.label || s.key,
            type: s.type as AttributeType,
            tier: s.tier as AttributeTier,
            required: s.required,
            default: s.default,
            options: s.options,
            pattern: s.pattern,
            min: s.min,
            max: s.max,
            dependsOn: dependsOnOf(s),
            help: s.help,
            placeholder: s.placeholder,
        });
    }
    return out;
}

const ATTRIBUTE_TYPES: AttributeType[] = [
    'string',
    'identifier',
    'number',
    'boolean',
    'select',
    'autocomplete',
    'multiline',
];
const ATTRIBUTE_TIERS: AttributeTier[] = ['required', 'optional', 'advanced'];

/** The served `{key, equals|notEquals}` clause, kept as exactly one sense (`AttributeSpec.dependsOn`). */
function dependsOnOf(s: FindingsSection): AttributeSpec['dependsOn'] {
    const d = s.dependsOn;
    if (!d?.key) return undefined;
    return 'notEquals' in d && d.notEquals !== undefined
        ? { key: d.key, notEquals: d.notEquals }
        : { key: d.key, equals: d.equals };
}

/** The case's working team (C6) — `attributes.assignees` CSV; `assignee` stays the lead. */
export function objectTeam(o: OperationalObject): string[] {
    return (o.attributes?.['assignees'] ?? '')
        .split(',')
        .map((t) => t.trim())
        .filter(Boolean);
}

/** The case's loose SLA (C6) — a `targetDate` (YYYY-MM-DD) with an overdue hint, no breach sweep. */
export function targetDate(o: OperationalObject): string {
    return o.attributes?.['targetDate'] ?? '';
}

export function isTargetOverdue(o: OperationalObject): boolean {
    const t = targetDate(o);
    if (!t || ['RESOLVED', 'CLOSED', 'ARCHIVED'].includes(displayStatus(o))) return false;
    const due = new Date(t + 'T23:59:59');
    return !Number.isNaN(due.getTime()) && due.getTime() < Date.now();
}

// ── Folders (the mail metaphor's left nav) ────────────────────────────────────────────────────

/** A left-nav folder: a named predicate over the loaded rows (`me` = {@link currentOperator}). */
export interface MailFolder {
    id: string;
    label: string;
    icon: string;
    match: (o: OperationalObject, me: string) => boolean;
}

const notArchived = (o: OperationalObject): boolean => !['ARCHIVED', 'CLOSED'].includes(displayStatus(o));

/** Important → My Cases · Starred → Escalated · Inbox → Identified · Draft → Diagnosing · Sent → Resolved · Trash → Archived. */
export const INCIDENT_FOLDERS: MailFolder[] = [
    {
        id: 'mine',
        label: 'My Cases',
        icon: 'heroicons_outline:user',
        match: (o, me) => o.assignee === me && notArchived(o),
    },
    {
        id: 'escalated',
        label: 'Escalated',
        icon: 'heroicons_outline:arrow-trending-up',
        match: (o) => isEscalated(o) && notArchived(o),
    },
    {
        id: 'identified',
        label: 'Identified',
        icon: 'heroicons_outline:inbox',
        match: (o) => displayStatus(o) === 'IDENTIFIED',
    },
    {
        id: 'diagnosing',
        label: 'Diagnosing',
        icon: 'heroicons_outline:document-text',
        match: (o) => displayStatus(o) === 'DIAGNOSING',
    },
    {
        id: 'resolved',
        label: 'Resolved',
        icon: 'heroicons_outline:paper-airplane',
        match: (o) => displayStatus(o) === 'RESOLVED',
    },
    {
        id: 'archived',
        label: 'Archived',
        icon: 'heroicons_outline:archive-box',
        match: (o) => displayStatus(o) === 'ARCHIVED',
    },
];

/**
 * The built-in CASE lifecycle as a {@link WorkflowDef} — the fallback when `GET /workflows/CASE`
 * is unavailable, mirroring the backend default. A `*_workflow.toon`-overridden deployment replaces
 * this at runtime via the endpoint (C6 — the UI never hardcodes case folders beyond this fallback).
 */
export const DEFAULT_CASE_WORKFLOW: WorkflowDef = {
    type: 'CASE',
    initial: 'OPEN',
    states: ['OPEN', 'INVESTIGATING', 'ESCALATED', 'RESOLVED', 'CLOSED'],
    terminal: ['CLOSED'],
    transitions: [
        { from: 'OPEN', to: 'INVESTIGATING', action: 'investigate' },
        { from: 'INVESTIGATING', to: 'ESCALATED', action: 'escalate' },
        { from: 'INVESTIGATING', to: 'RESOLVED', action: 'resolve' },
        { from: 'ESCALATED', to: 'RESOLVED', action: 'resolve' },
        { from: 'RESOLVED', to: 'CLOSED', action: 'close' },
    ],
};

/**
 * The INCIDENT lifecycle, which {@link DEFAULT_CASE_WORKFLOW} cannot express — its actions
 * (investigate/escalate) and its `from` states (OPEN/INVESTIGATING/…) are both the wrong vocabulary.
 *
 * ⚠ `from` is in DISPLAY terms (GLOSSARY §9: IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED) because
 * that is what `displayStatus()` yields and what the matcher compares against, while `to` is the RAW
 * status the server will store, so an optimistically-patched row normalises exactly like a fetched one.
 *
 * Without this, the mail view fell back to the CASE workflow for incidents — `workflowDef()` is only
 * fetched when the type is not INCIDENT — so no transition ever matched and every incident action's
 * optimistic patch was a silent no-op: the toast announced the new status while the row kept the old
 * badge and stayed in its old folder until the server round-trip landed.
 */
export const DEFAULT_INCIDENT_WORKFLOW: WorkflowDef = {
    type: 'INCIDENT',
    initial: 'OPEN',
    states: [...INCIDENT_STATUSES],
    terminal: ['ARCHIVED'],
    transitions: [
        { from: 'IDENTIFIED', to: 'IN_PROGRESS', action: 'accept' },
        { from: 'DIAGNOSING', to: 'RESOLVED', action: 'resolve' },
        { from: 'RESOLVED', to: 'CLOSED', action: 'archive' },
        { from: 'ARCHIVED', to: 'OPEN', action: 'reopen' },
    ],
};

const STATE_ICONS: Record<string, string> = {
    OPEN: 'heroicons_outline:inbox',
    INVESTIGATING: 'heroicons_outline:magnifying-glass',
    ESCALATED: 'heroicons_outline:arrow-trending-up',
    RESOLVED: 'heroicons_outline:paper-airplane',
    CLOSED: 'heroicons_outline:archive-box',
    ARCHIVED: 'heroicons_outline:archive-box',
};

/** "IN_PROGRESS" → "In progress" — folder labels for workflow-named states. */
export function stateLabel(state: string): string {
    const s = state.replace(/_/g, ' ').toLowerCase();
    return s.charAt(0).toUpperCase() + s.slice(1);
}

/**
 * Case folders derived from the effective workflow (C6): My Cases pinned first, then one folder per
 * lifecycle state in the workflow's presentation order — a TOON-overridden deployment gets matching
 * folders with zero UI change.
 */
export function caseFoldersFrom(wf: WorkflowDef): MailFolder[] {
    return [
        {
            id: 'mine',
            label: 'My Cases',
            icon: 'heroicons_outline:user',
            match: (o, me) => o.assignee === me && notArchived(o),
        },
        ...wf.states.map(
            (state): MailFolder => ({
                id: state.toLowerCase(),
                label: stateLabel(state),
                icon: STATE_ICONS[state] ?? 'heroicons_outline:folder',
                match: (o) => displayStatus(o) === state,
            }),
        ),
    ];
}

/** The built-in case folder set (the fallback workflow rendered as folders). */
export const CASE_FOLDERS: MailFolder[] = caseFoldersFrom(DEFAULT_CASE_WORKFLOW);
