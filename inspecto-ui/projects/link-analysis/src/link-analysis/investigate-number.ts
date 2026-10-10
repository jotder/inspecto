import type { EntityProjection } from '@inspecto/core/graph';
import type { ExpandRung, InvestigationStepResult, WorkingSet } from '@inspecto/link-analysis/api/inv.service';
import {
    DEFAULT_MAX_DEGREE,
    DOMAIN_PROFILES,
    DegreePresets,
    DomainProfileId,
    profileForEntityType,
} from '@inspecto/link-analysis/graph/domain-profile';

/**
 * Sprint 1 "Investigate a number" — the pure half: the deep link's parameters, the Investigation binding a profile
 * yields, the next degree's frontier, the preset rung, and what each degree did in plain words. No Angular, no HTTP.
 */

/** The route the deep link addresses. */
export const LINK_ANALYSIS_ROUTE = '/studio/link-analysis';
/** The entity type a deep link without `entityType` investigates. */
export const DEFAULT_INVESTIGATE_ENTITY_TYPE = 'msisdn';
/** The server's cap on the ids one `expand` names (`InvestigationRoutes.MAX_FRONTIER`). */
export const MAX_EXPAND_FRONTIER = 1000;
/** A seed id's bound — the server compares raw values; anything longer is not an id. */
const MAX_SEED_LENGTH = 200;

/** `?seed=<id>&entityType=<type>[&dataset=<id>]`, parsed. */
export interface InvestigateRequest {
    seed: string;
    entityType: string;
    /** The Dataset to bind instead of the profile's default. */
    dataset?: string;
}

/** Read the deep link's parameters; null when there is no usable `seed`. */
export function parseInvestigateParams(params: { get(name: string): string | null }): InvestigateRequest | null {
    const seed = (params.get('seed') ?? '').trim();
    if (!seed || seed.length > MAX_SEED_LENGTH) return null;
    const entityType = (params.get('entityType') ?? '').trim() || DEFAULT_INVESTIGATE_ENTITY_TYPE;
    const dataset = (params.get('dataset') ?? '').trim();
    return { seed, entityType, ...(dataset ? { dataset } : {}) };
}

/** Two requests name the same investigation (the param stream re-emits on unrelated params). */
export function sameInvestigateRequest(a: InvestigateRequest | null, b: InvestigateRequest | null): boolean {
    return a?.seed === b?.seed && a?.entityType === b?.entityType && a?.dataset === b?.dataset;
}

/** The deep link's query parameters (what the starter card navigates with and the host pages link to). */
export function investigateQueryParams(seed: string, entityType: string, dataset?: string): Record<string, string> {
    return { seed, entityType, ...(dataset ? { dataset } : {}) };
}

/**
 * The investigable entity an Incident/Case record carries, or null. Only a keyed Alert Rule's `key.<column>`
 * attributes name one (the column → value pair it breached on); a column is investigable when a profile's entity
 * type is in its name (`key.msisdn`, `key.a_msisdn`). The first match in attribute order wins.
 */
export function investigableEntityOf(
    attributes: Record<string, string> | null | undefined,
): { seed: string; entityType: string } | null {
    for (const [k, v] of Object.entries(attributes ?? {})) {
        if (!k.startsWith('key.') || !v?.trim() || v === 'NULL') continue;
        const column = k.slice(4).toLowerCase();
        const type = DOMAIN_PROFILES.map((p) => p.investigate?.entityType).find((t) => !!t && column.includes(t));
        if (type) return { seed: v.trim(), entityType: type };
    }
    return null;
}

/** An MSISDN as typed: separators (space, dash, dot, parentheses) dropped; a leading `+` kept. */
export function normaliseMsisdn(raw: string): string {
    return raw.trim().replace(/[\s\-.()]/g, '');
}

/** E.164 bounds: an optional `+`, then 6 to 15 digits (short national numbers included). */
export const MSISDN_PATTERN = /^\+?\d{6,15}$/;

/** Why a typed MSISDN cannot be investigated, or null when it can. */
export function msisdnError(raw: string): string | null {
    const v = normaliseMsisdn(raw);
    if (!v) return 'Enter a number.';
    return MSISDN_PATTERN.test(v) ? null : 'A number is 6 to 15 digits, optionally starting with +.';
}

/** A deep-linked "Investigate a number" waiting for the analyst's purpose (nothing is created before Start). */
export interface PendingInvestigation {
    seed: string;
    /** The profile it was bound from — the screen switches to it. */
    profileId: DomainProfileId;
    projection: EntityProjection;
    timeCol?: string;
    presets: DegreePresets;
    maxDegree: number;
    /** The Dataset's latest event time (ISO) — the preset window ends there, not today; absent = no window is sent. */
    windowEnd?: string;
}

/**
 * The Investigation binding of a deep link: the profile investigating `entityType` and its default mapping, with the
 * deep link's own `dataset` winning. Null when no profile investigates that type (nothing to bind by default).
 */
export function investigateBinding(req: InvestigateRequest): PendingInvestigation | null {
    const profile = profileForEntityType(req.entityType);
    const inv = profile?.investigate;
    if (!profile || !inv) return null;
    const m = inv.mapping;
    return {
        seed: req.seed,
        profileId: profile.id,
        projection: {
            datasetId: req.dataset ?? m.dataset,
            sourceCol: m.sourceCol,
            targetCol: m.targetCol,
            linkKindCol: m.linkKindCol,
            entityType: req.entityType,
        },
        ...(m.timeCol ? { timeCol: m.timeCol } : {}),
        presets: inv.expand,
        maxDegree: inv.maxDegree,
    };
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** A day as the analyst reads it, in UTC: `29 Sep 2026`. */
export function dayLabel(iso: string | number): string {
    const d = new Date(iso); // fixed month names: locale data spells September `Sep` or `Sept`
    return `${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]} ${d.getUTCFullYear()}`;
}

/** What the window covers, in words: `30 days to 29 Sep 2026`, or `all available data` when no end is known. */
export function windowLabel(p: DegreePresets, windowEnd?: string | null): string | null {
    if (p.windowDays == null) return null;
    return windowEnd ? `${p.windowDays} days to ${dayLabel(windowEnd)}` : 'all available data';
}

/**
 * The SQL reading a Dataset's latest event time over `/db/query` (the guarded read-only route Advanced search already
 * runs for analysts). Identifiers are double-quoted with embedded quotes doubled.
 */
export function latestTimeSql(sourceName: string, timeCol: string): string {
    const q = (id: string) => `"${id.replace(/"/g, '""')}"`;
    return `SELECT MAX(${q(timeCol)}) AS latest FROM ${q(sourceName)}`;
}

/** A zone-less timestamp (a naive DuckDB TIMESTAMP) read as UTC, the Investigation's default zone. */
const asUtc = (v: string): string => {
    const iso = v.trim().replace(' ', 'T');
    return /^\d{4}-\d{2}-\d{2}T[\d:.]+$/.test(iso) ? iso + 'Z' : iso;
};

/** The ISO instant of a `latestTimeSql` answer's value, or null when it is empty or not a time. */
export function latestTimeOf(value: unknown): string | null {
    if (value == null || value === '') return null;
    const t = typeof value === 'number' ? value : Date.parse(asUtc(String(value)));
    return Number.isFinite(t) ? new Date(t).toISOString() : null;
}

/** The presets in words, for the line that says what Start will do. */
export function presetsSummary(p: DegreePresets, windowEnd?: string | null): string {
    const parts: string[] = [];
    const w = windowLabel(p, windowEnd);
    if (w) parts.push(w);
    if (p.minEvents != null) parts.push(`at least ${p.minEvents} events per link`);
    if (p.maxFanOut != null) parts.push(`at most ${p.maxFanOut} links per entity`);
    if (p.budget != null) parts.push(`${p.budget.toLocaleString('en')} rows per degree`);
    return parts.length ? parts.join(', ') : 'the server defaults';
}

/** The mapped columns a Dataset lacks (case-insensitive, as the server matches them); empty = it can be bound. */
export function missingMappedColumns(b: PendingInvestigation, columns: readonly string[]): string[] {
    const have = new Set(columns.map((c) => c.toLowerCase()));
    const p = b.projection;
    return [p.sourceCol, p.targetCol, p.linkKindCol, b.timeCol].filter(
        (c): c is string => !!c && !have.has(c.toLowerCase()),
    );
}

/** Why a deep link's seed is not an id of its entity type, or null. Only `msisdn` has a shape to check. */
export function seedError(req: InvestigateRequest): string | null {
    return req.entityType.toLowerCase() === 'msisdn' ? msisdnError(req.seed) : null;
}

/** The Investigation's title for a suspect id. */
export function suspectTitle(seed: string): string {
    return `Suspect ${seed}`;
}

/** Where "Expand next degree" stands over a Working Set. */
export interface DegreeState {
    /** The outermost hop present (0 = only seeds); -1 for an empty Working Set. */
    current: number;
    /** The entities at that hop — what the next degree expands from (hidden entities are left out). */
    frontier: string[];
    maxDegree: number;
    /** Why the next degree cannot be expanded, in the analyst's words; null when it can. */
    blocked: string | null;
}

/** One expanded degree's outcome, as the panel lists it. */
export interface DegreeOutcome {
    degree: number;
    admitted: number;
    linksAdded: number;
    truncated: boolean;
    fanOutCapped: number;
    budget?: number;
    maxFanOut?: number;
    /** What the window covered, in words (`30 days to 29 Sep 2026` / `all available data`); absent = no window preset. */
    range?: string;
    /** Hub suppression: high-connectivity entities admitted but not expanded further, and frontier hubs held back. */
    hubsFlagged?: number;
    hubsHeld?: number;
    /** D-U7: the expand was held for four-eyes approval — the pending request's id; nothing was read. */
    awaitingApproval?: string;
}

/**
 * The frontier of the next degree: the visible entities at the Working Set's outermost hop. Blocked at `maxDegree`,
 * when the last expand of this degree admitted nobody (repeating it would read the same rows), and above the
 * server's frontier cap.
 */
export function degreeState(
    ws: WorkingSet | null,
    maxDegree = DEFAULT_MAX_DEGREE,
    outcomes: readonly DegreeOutcome[] = [],
): DegreeState {
    const visible = (ws?.entities ?? []).filter((e) => !e.hidden);
    if (!visible.length) return { current: -1, frontier: [], maxDegree, blocked: 'Seed an entity first.' };
    const current = Math.max(...visible.map((e) => e.hop));
    const frontier = visible.filter((e) => e.hop === current).map((e) => e.id);
    let blocked: string | null = null;
    const last = outcomes.at(-1);
    if (last?.awaitingApproval)
        blocked = `Degree ${last.degree} is waiting for approval (request ${last.awaitingApproval}) — a second person must approve this expand before it runs.`;
    else if (current >= maxDegree) blocked = `Degree ${maxDegree} reached.`;
    else if (last && last.degree === current + 1 && last.admitted === 0)
        blocked = `Degree ${current + 1} found no new entities — there is nothing further to expand.`;
    else if (frontier.length > MAX_EXPAND_FRONTIER)
        blocked =
            `Degree ${current} has ${frontier.length.toLocaleString('en')} entities; one expand names at most ` +
            `${MAX_EXPAND_FRONTIER.toLocaleString('en')}. Exclude or hide hubs first.`;
    return { current, frontier, maxDegree, blocked };
}

const DAY_MS = 86_400_000;

/**
 * The rung a profile's presets send. The window (`windowDays` back from `windowEnd`, the Dataset's latest event time —
 * never today, which would cut off seed data) only when the Investigation has a time column AND that end is known;
 * otherwise no window (all available data). `to` is exclusive, so it sits one second after the latest event.
 */
export function presetRung(presets: DegreePresets, hasTimeColumn: boolean, windowEnd?: string | null): ExpandRung {
    const end = windowEnd ? Date.parse(windowEnd) : NaN;
    const rung: ExpandRung = {};
    if (presets.budget != null) rung.budget = presets.budget;
    if (presets.minEvents != null) rung.minEvents = presets.minEvents;
    if (presets.maxFanOut != null) rung.maxFanOut = presets.maxFanOut;
    if (presets.windowDays != null && hasTimeColumn && Number.isFinite(end))
        rung.window = {
            from: new Date(end - presets.windowDays * DAY_MS).toISOString(),
            to: new Date(end + 1000).toISOString(),
        };
    return rung;
}

/** What a step at `degree` did, from the `/ops` answer and the rung it sent. */
export function degreeOutcome(
    degree: number,
    step: InvestigationStepResult,
    rung: ExpandRung,
    range?: string | null,
): DegreeOutcome {
    if (step.status === 'pending')
        return {
            degree,
            admitted: 0,
            linksAdded: 0,
            truncated: false,
            fanOutCapped: 0,
            budget: rung.budget,
            maxFanOut: rung.maxFanOut ?? undefined,
            awaitingApproval: step.pending?.id ?? 'pending',
        };
    return {
        degree,
        admitted: step.delta.admitted.length,
        linksAdded: step.delta.linksAdded,
        truncated: step.truncated,
        fanOutCapped: step.read?.fanOutCapped ?? 0,
        budget: rung.budget,
        maxFanOut: rung.maxFanOut ?? undefined,
        ...(range ? { range } : {}),
        hubsFlagged: step.read?.hubsFlagged ?? 0,
        hubsHeld: step.read?.rung?.hubsHeld?.length ?? 0,
    };
}

const plural = (n: number, one: string, many = `${one}s`): string =>
    `${n.toLocaleString('en')} ${n === 1 ? one : many}`;

/** One degree's result in plain language, truncation and the fan-out cap included. */
export function degreeOutcomeMessage(o: DegreeOutcome): string {
    if (o.awaitingApproval)
        return `Degree ${o.degree}: waiting for approval — this expand exceeds the Space's four-eyes limit, so it runs once a second person approves request ${o.awaitingApproval}.`;
    const parts = [
        `Degree ${o.degree}${o.range ? ` (${o.range})` : ''}: ${plural(o.admitted, 'new entity', 'new entities')}, ${plural(o.linksAdded, 'link')} added.`,
    ];
    if (o.truncated)
        parts.push(
            `The read stopped at its budget${o.budget ? ` of ${o.budget.toLocaleString('en')} rows` : ''} — some neighbours are missing.`,
        );
    if (o.fanOutCapped > 0)
        parts.push(
            `${plural(o.fanOutCapped, 'weaker link was', 'weaker links were')} left out: each entity keeps its ` +
                `${o.maxFanOut ? `${o.maxFanOut} ` : ''}strongest links.`,
        );
    if (o.hubsFlagged) parts.push(`${plural(o.hubsFlagged, 'high-connectivity number')} shown but not expanded.`);
    if (o.hubsHeld) parts.push(`${plural(o.hubsHeld, 'high-connectivity number')} of the frontier not expanded from.`);
    return parts.join(' ');
}
