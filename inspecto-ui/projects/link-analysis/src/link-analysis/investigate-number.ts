import type { EntityProjection } from '@inspecto/core/graph';
import type {
    ExpandRung,
    InvestigationLogEntry,
    InvestigationStepResult,
    PendingExpansion,
    WorkingSet,
} from '@inspecto/link-analysis/api/inv.service';
import {
    DEFAULT_MAX_DEGREE,
    DOMAIN_PROFILES,
    DegreePresets,
    DomainProfileId,
    profileForEntityType,
} from '@inspecto/link-analysis/graph/domain-profile';
import { laLimit } from './la-limits';

/**
 * Sprint 1 "Investigate a number" — the pure half: the deep link's parameters, the Investigation binding a profile
 * yields, the next degree's frontier, the preset rung, and what each degree did in plain words. No Angular, no HTTP.
 */

/** The route the deep link addresses. */
export const LINK_ANALYSIS_ROUTE = '/studio/link-analysis';
/** The entity type a deep link without `entityType` investigates. */
export const DEFAULT_INVESTIGATE_ENTITY_TYPE = 'msisdn';
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
    /** The events column a pre-aggregated Dataset weighs links by (`eventsCol` on create); absent = rows are counted. */
    eventsCol?: string;
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
        ...(m.eventsCol ? { eventsCol: m.eventsCol } : {}),
        presets: inv.expand,
        maxDegree: inv.maxDegree,
    };
}

/** The profile whose default mapping binds this Dataset (what a reopened Investigation restores); null = none. */
export function profileForDataset(datasetId: string | null | undefined): DomainProfileId | null {
    return (datasetId && DOMAIN_PROFILES.find((p) => p.investigate?.mapping.dataset === datasetId)?.id) || null;
}

const plural = (n: number, one: string, many = `${one}s`): string =>
    `${n.toLocaleString('en')} ${n === 1 ? one : many}`;

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
    // Read as a zone-free STRING: `/db/query` serialises a naive TIMESTAMP as epoch ms converted in the HOST's zone
    // (DuckDB's session TimeZone), so on an IST host the end came back 5.5 h early and the window dropped the last
    // hours of links. The expand window reads a naive timestamp as UTC (no `timeColZone`); `latestTimeOf` does too.
    const q = (id: string) => `"${id.replace(/"/g, '""')}"`;
    return `SELECT strftime(MAX(${q(timeCol)}), '%Y-%m-%dT%H:%M:%S') AS latest FROM ${q(sourceName)}`;
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

/**
 * The window end sealed in the latest live (not undone) expand step that carried a window, for a reopened
 * Investigation: its exclusive `to` less the second `presetRung` adds, else its `from` plus `windowDays`. Null = none.
 */
export function sealedWindowEnd(entries: readonly InvestigationLogEntry[], windowDays: number): string | null {
    for (let i = entries.length - 1; i >= 0; i--) {
        const e = entries[i];
        const w = e.op === 'expand' && e.undoneBy == null ? e.params?.window : null;
        if (!w || typeof w !== 'object') continue;
        const to = w.to ? Date.parse(w.to) - 1000 : NaN;
        const t = Number.isFinite(to) ? to : w.from ? Date.parse(w.from) + windowDays * DAY_MS : NaN;
        if (Number.isFinite(t)) return new Date(t).toISOString();
    }
    return null;
}

/** The presets in words, for the line that says what Start will do. */
export function presetsSummary(p: DegreePresets, windowEnd?: string | null): string {
    const parts: string[] = [];
    const w = windowLabel(p, windowEnd);
    if (w) parts.push(w);
    if (p.minEvents != null) parts.push(`at least ${plural(p.minEvents, 'event')} per link`);
    if (p.maxFanOut != null) parts.push(`at most ${p.maxFanOut} links per entity`);
    if (p.budget != null) parts.push(`${p.budget.toLocaleString('en')} rows per degree`);
    return parts.length ? parts.join(', ') : 'the server defaults';
}

/** The mapped columns a Dataset lacks (case-insensitive, as the server matches them); empty = it can be bound. */
export function missingMappedColumns(b: PendingInvestigation, columns: readonly string[]): string[] {
    const have = new Set(columns.map((c) => c.toLowerCase()));
    const p = b.projection;
    return [p.sourceCol, p.targetCol, p.linkKindCol, b.timeCol, b.eventsCol].filter(
        (c): c is string => !!c && !have.has(c.toLowerCase()),
    );
}

/** Why a deep link's seed is not an id of its entity type, or null. Only `msisdn` has a shape to check. */
export function seedError(req: InvestigateRequest): string | null {
    return req.entityType.toLowerCase() === 'msisdn' ? msisdnError(req.seed) : null;
}

/**
 * The Investigation's default title for a suspect id. The title is stored and shown like any value, so the raw id
 * goes into it only when the Space is known NOT to mask (`maskingMode` `none`); masked or not yet known, it reads
 * `Suspect number` — never the raw MSISDN.
 */
export function suspectTitle(seed: string, maskingMode?: string | null): string {
    return maskingMode === 'none' ? `Suspect ${seed}` : 'Suspect number';
}

/** The `maskingMode` pseudonym prefix (D-U6). */
const MASKED = 'masked:';

/**
 * The open Investigation's name: the server's title, unless the server masked it (a title is masked like an entity
 * value) — then the analyst's own title as typed here, else `(title masked)`. A raw pseudonym is never the name.
 */
export function investigationTitle(
    serverTitle: string | null | undefined,
    localTitle: string | null | undefined,
    id: string,
): string {
    const server = (serverTitle ?? '').trim();
    const local = (localTitle ?? '').trim();
    const localOk = !!local && !local.startsWith(MASKED);
    if (!server) return localOk ? local : id;
    if (!server.startsWith(MASKED)) return server;
    return localOk ? local : '(title masked)';
}

/** The hop a request's named ids would admit at: one past the outermost of them in the Working Set; null = unknown. */
export function requestedDegree(ws: WorkingSet | null, ids: readonly string[] | null | undefined): number | null {
    const want = new Set(ids ?? []);
    const hops = (ws?.entities ?? []).filter((e) => want.has(e.id)).map((e) => e.hop);
    return hops.length ? Math.max(...hops) + 1 : null;
}

/** The log steps still in effect that expanded THROUGH a named hub ("Expand anyway", `expandHubs`). */
export function overrideSteps(entries: readonly InvestigationLogEntry[]): Set<number> {
    return new Set(
        entries
            .filter(
                (e) =>
                    e.kind === 'op' &&
                    e.op === 'expand' &&
                    e.undoneBy == null &&
                    (e.params?.expandHubs?.length ?? 0) > 0,
            )
            .map((e) => e.step),
    );
}

/**
 * The flagged entities an analyst override in effect expanded THROUGH, from the log: an expand step (not undone) that
 * named them in `expandHubs` ("Expand anyway", also once a held request is approved and runs as a step), or that
 * ticked `includeHubs` and named them among its ids. `flagged` = the Working Set's high-connectivity ids.
 */
export function overriddenHubs(entries: readonly InvestigationLogEntry[], flagged: ReadonlySet<string>): Set<string> {
    const out = new Set<string>();
    for (const e of entries) {
        if (e.kind !== 'op' || e.op !== 'expand' || e.undoneBy != null) continue;
        for (const id of e.params?.expandHubs ?? []) out.add(id);
        if (e.params?.includeHubs) for (const id of e.params.ids ?? []) if (flagged.has(id)) out.add(id);
    }
    return out;
}

/** The hub threshold the latest expand in effect sealed (its rung arrives resolved in the log); null = none sealed. */
export function sealedHubThreshold(entries: readonly InvestigationLogEntry[]): number | null {
    for (let i = entries.length - 1; i >= 0; i--) {
        const e = entries[i];
        if (e.kind === 'op' && e.op === 'expand' && e.undoneBy == null && typeof e.params?.hubThreshold === 'number')
            return e.params.hubThreshold;
    }
    return null;
}

/**
 * Each flagged entity's sealed contact count from `GET working-set?of=entities` rows (`entityId`, `contacts`) — that
 * route serves `contacts`; `/replay` does not.
 */
export function hubContactsFromRows(rows: readonly Record<string, unknown>[]): Map<string, number> {
    const out = new Map<string, number>();
    for (const r of rows) {
        const id = r['entityId'];
        // a count may arrive as a JSON string (a long serialised as text) — read it either way
        const raw = r['contacts'];
        const c = typeof raw === 'string' && raw.trim() !== '' ? Number(raw) : raw;
        if (typeof id === 'string' && typeof c === 'number' && Number.isFinite(c)) out.set(id, c);
    }
    return out;
}

/** Where "Expand next degree" stands over a Working Set. */
export interface DegreeState {
    /** The outermost hop present (0 = only seeds); -1 for an empty Working Set. */
    current: number;
    /** The entities at that hop — what the next degree expands from (hidden entities are left out). */
    frontier: string[];
    /** Entities at that hop a sealed read flagged high connectivity — left OUT of `frontier` unless the analyst opted
     *  in (`includeHubs`), because the server refuses a named flagged id that is not overridden (422). */
    heldHubs: string[];
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
    /** How the flagged hubs' contacts were counted (`index` = link index, `flat` = the Dataset). */
    hubDegreeSource?: 'index' | 'flat';
    /** The analyst opted in: this degree expanded THROUGH this many flagged entities (sealed in the step's params). */
    hubsOverridden?: number;
    /** D-U7: the expand was held for four-eyes approval — the pending request's id; nothing was read. */
    awaitingApproval?: string;
    /** The server's plain-language reasons the expand was held (`budget 500 > 100`); names the threshold crossed. */
    heldFor?: string[];
    /** The held request was decided elsewhere (read back from the log's `pending` list); absent = still waiting. */
    decision?: 'approved' | 'denied';
    /** On a deny, the reason when one was given. */
    reason?: string;
    /** "Expand anyway" through this flagged entity — an override, not a degree of the ladder. */
    override?: string;
    /** A one-hop expand from the panel (not the ladder's next degree): what it expanded from, in words. */
    oneHop?: string;
}

const BUDGET_KEY = 'four_eyes_budget_above';
const FAN_OUT_KEY = 'four_eyes_fan_out_above';

/** The Space's four-eyes thresholds (`null` = none set, the shipped default: nothing is held on that count). */
export interface FourEyesLimits {
    budgetAbove: number | null;
    fanOutAbove: number | null;
}

/**
 * Whether an expand with this rung will be HELD for a second approver, mirroring the server's rule
 * (`InvestigationRoutes.sensitivity`): the row budget above `four_eyes_budget_above`, or the fan-out cap above
 * `four_eyes_fan_out_above` — a blank cap is unbounded and exceeds any fan-out threshold. `exceeded` is empty when
 * the expand runs straight away. The server stays the gate; this only tells the analyst before the click.
 */
export function expandApproval(
    rung: ExpandRung,
    limits: FourEyesLimits,
): { budget: number; fanOut: number | null; exceeded: string[] } {
    // the server's default row budget for an expand that states none (`investigation.expand_budget_default`)
    const budget = rung.budget ?? laLimit('investigation.expand_budget_default');
    const fanOut = rung.maxFanOut ?? null;
    const exceeded: string[] = [];
    if (limits.budgetAbove != null && budget > limits.budgetAbove)
        exceeded.push(`row budget ${budget.toLocaleString('en')} is above ${limits.budgetAbove.toLocaleString('en')} (${BUDGET_KEY})`);
    if (limits.fanOutAbove != null && (fanOut === null || fanOut > limits.fanOutAbove))
        exceeded.push(
            fanOut === null
                ? `fan-out is unbounded, above ${limits.fanOutAbove.toLocaleString('en')} (${FAN_OUT_KEY})`
                : `fan-out cap ${fanOut.toLocaleString('en')} is above ${limits.fanOutAbove.toLocaleString('en')} (${FAN_OUT_KEY})`,
        );
    return { budget, fanOut, exceeded };
}

/** The held request's reasons (server wording `budget 500 > 100`) with the Settings key that controls each. */
export function thresholdWords(heldFor: readonly string[]): string {
    const named = heldFor.map((r) => {
        const key = r.startsWith('budget') ? BUDGET_KEY : r.startsWith('maxFanOut') ? FAN_OUT_KEY : null;
        return key ? `${r}, set by ${key}` : r;
    });
    return `${named.join('; ')} - Settings > Link Analysis`;
}

/** What a non-ladder expand named, in words: the one entity, a count, or every entity (no ids). */
export function oneHopFrom(ids: readonly string[] | null | undefined): string {
    if (!ids?.length) return 'every entity';
    return ids.length === 1 ? ids[0] : `${ids.length.toLocaleString('en')} entities`;
}

/**
 * True when an expand naming `ids` is NOT the ladder's next degree: it expands every entity (no ids), or names an
 * entity short of the Working Set's outermost visible hop. A lone seed's expand reads as the ladder (it is degree 1).
 */
export function isOneHop(ws: WorkingSet | null, ids: readonly string[] | null | undefined): boolean {
    if (!ids?.length) return true;
    const visible = (ws?.entities ?? []).filter((e) => !e.hidden);
    if (!visible.length) return false;
    const outer = Math.max(...visible.map((e) => e.hop));
    const hops = new Map(visible.map((e) => [e.id, e.hop]));
    return ids.some((id) => hops.has(id) && hops.get(id)! < outer);
}

/** A sealed expand window in words (`30 days to 29 Sep 2026`, `all available data`); null when none was sealed. */
export function sealedRangeLabel(w: NonNullable<InvestigationLogEntry['params']>['window']): string | null {
    if (w === 'full') return 'all available data';
    if (!w || typeof w !== 'object' || !w.from || !w.to) return null;
    const from = Date.parse(w.from);
    const to = Date.parse(w.to);
    if (!Number.isFinite(from) || !Number.isFinite(to)) return null;
    // The preset window ends one second past the Dataset's latest event; the day named is that event's.
    return `${Math.round((to - from) / DAY_MS)} days to ${dayLabel(to - 1000)}`;
}

/**
 * The degree outcome list rebuilt from the sealed log after a reopen (the session's own list is gone): one line per
 * expand step still in effect — what it admitted from the Working Set rows (`admittedBy`), the window from its params,
 * the truncation from its read. A step the rows do not place at a hop takes the next degree after the last line.
 */
export function degreeOutcomesFromLog(entries: readonly InvestigationLogEntry[], ws: WorkingSet | null): DegreeOutcome[] {
    const out: DegreeOutcome[] = [];
    for (const e of entries) {
        if (e.kind !== 'op' || e.op !== 'expand' || e.undoneBy != null) continue;
        const p = e.params ?? {};
        const range = sealedRangeLabel(p.window);
        const override = p.expandHubs?.[0];
        // The log view seals the flagged candidates (`read.hubs`); the ones this step left flagged are those the Working
        // Set still marks. `hubsHeld` arrives as a count the log view takes from the sealed rung.
        const flagged = new Set((ws?.entities ?? []).filter((x) => x.highConnectivity).map((x) => x.id));
        const hubsFlagged = (e.read?.hubs ?? []).filter((h) => flagged.has(h.id)).length;
        out.push({
            degree: admittedHop(ws, e.step) ?? (out.at(-1)?.degree ?? 0) + 1,
            admitted: (ws?.entities ?? []).filter((x) => x.admittedBy === e.step).length,
            linksAdded: (ws?.links ?? []).filter((x) => x.admittedBy === e.step).length,
            truncated: e.read?.truncated ?? false,
            fanOutCapped: e.read?.fanOutCapped ?? 0,
            ...(hubsFlagged ? { hubsFlagged } : {}),
            ...(e.read?.hubsHeld ? { hubsHeld: e.read.hubsHeld } : {}),
            ...(hubsFlagged && e.read?.hubDegreeSource ? { hubDegreeSource: e.read.hubDegreeSource } : {}),
            ...(p.budget != null ? { budget: p.budget } : {}),
            ...(p.maxFanOut != null ? { maxFanOut: p.maxFanOut } : {}),
            ...(range ? { range } : {}),
            ...(override ? { override } : isOneHopStep(e, ws) ? { oneHop: oneHopFrom(p.ids) } : {}),
        });
    }
    return out;
}

/** A sealed expand step that was not a ladder degree: it named entities admitted short of the hop it then filled. */
function isOneHopStep(e: InvestigationLogEntry, ws: WorkingSet | null): boolean {
    const ids = e.params?.ids;
    if (!ids?.length) return true;
    const hop = admittedHop(ws, e.step);
    if (hop == null) return false;
    const hops = new Map((ws?.entities ?? []).map((x) => [x.id, x.hop]));
    return ids.some((id) => hops.has(id) && hops.get(id)! < hop - 1);
}

/** True while a held degree is still waiting for its four-eyes decision. */
export function isAwaiting(o: DegreeOutcome | null | undefined): boolean {
    return !!o?.awaitingApproval && !o.decision;
}

/** The outermost hop of the entities a step admitted, from the refreshed Working Set rows; null when none. */
export function admittedHop(ws: WorkingSet | null, step: number): number | null {
    const hops = (ws?.entities ?? []).filter((e) => e.admittedBy === step).map((e) => e.hop);
    return hops.length ? Math.max(...hops) : null;
}

/**
 * Bring held degrees up to date with the log's four-eyes requests: a decided request becomes `approved` (with what
 * the step it ran as admitted, counted from the Working Set rows) or `denied`. A still-pending request the session
 * does not know about (the Investigation was reopened) is appended as a waiting degree. Returns the same array when
 * nothing changed.
 */
export function reconcileApprovals(
    outcomes: readonly DegreeOutcome[],
    pending: readonly PendingExpansion[] | undefined,
    ws: WorkingSet | null,
): DegreeOutcome[] {
    const byId = new Map((pending ?? []).map((p) => [p.id, p]));
    let changed = false;
    const next = outcomes.map((o) => {
        const p = o.awaitingApproval && !o.decision ? byId.get(o.awaitingApproval) : undefined;
        if (!p || p.status === 'pending') return o;
        changed = true;
        if (p.status === 'denied')
            return { ...o, decision: 'denied' as const, ...(p.reason ? { reason: p.reason } : {}) };
        const admitted = p.step != null ? (ws?.entities ?? []).filter((e) => e.admittedBy === p.step).length : 0;
        const hop = p.step != null ? admittedHop(ws, p.step) : null;
        return { ...o, decision: 'approved' as const, admitted, degree: hop ?? o.degree };
    });
    const known = new Set(outcomes.map((o) => o.awaitingApproval).filter(Boolean));
    for (const p of pending ?? []) {
        if (p.status !== 'pending' || known.has(p.id)) continue;
        // Labelled by the hop it would admit at — one past the ids it names (an "Expand anyway" names a hub inside the
        // Working Set) — the same hop the approved step's admitted rows then carry.
        const visible = (ws?.entities ?? []).filter((e) => !e.hidden);
        const current = visible.length ? Math.max(...visible.map((e) => e.hop)) : 0;
        const named = p.params?.['ids'];
        const ids = Array.isArray(named) ? named.filter((v): v is string => typeof v === 'string') : [];
        const hubs = p.params?.['expandHubs'];
        const override = Array.isArray(hubs) && typeof hubs[0] === 'string' ? hubs[0] : undefined;
        const oneHop = !override && Array.isArray(named) && isOneHop(ws, ids) ? oneHopFrom(ids) : undefined;
        next.push({
            ...(override ? { override } : {}),
            ...(oneHop ? { oneHop } : {}),
            degree: requestedDegree(ws, ids) ?? current + 1,
            admitted: 0,
            linksAdded: 0,
            truncated: false,
            fanOutCapped: 0,
            awaitingApproval: p.id,
            ...(p.sensitivity?.exceeded?.length ? { heldFor: p.sensitivity.exceeded } : {}),
        });
        changed = true;
    }
    return changed ? next : (outcomes as DegreeOutcome[]);
}

/**
 * The rung "Expand next degree" sends: the profile's preset rung with the analyst's Advanced fields laid over it (a
 * field the analyst set wins; an unset one keeps the preset). The hub opt-in travels separately, so it is dropped.
 */
export function mergeRung(preset: ExpandRung, advanced: ExpandRung | null | undefined): ExpandRung {
    const extra: ExpandRung = { ...(advanced ?? {}) };
    delete extra.includeHubs;
    delete extra.expandHubs;
    return { ...preset, ...extra };
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
    includeHubs = false,
    overrides: ReadonlySet<number> = new Set(),
): DegreeState {
    const shown = (ws?.entities ?? []).filter((e) => !e.hidden);
    // A hub's background contacts admitted by "Expand anyway" are not a degree of the ladder: leaving them out of the
    // frontier keeps the next degree's budget for the rings (unless they are all there is).
    const ladder = shown.filter((e) => !overrides.has(e.admittedBy));
    const visible = ladder.length ? ladder : shown;
    if (!visible.length)
        return { current: -1, frontier: [], heldHubs: [], maxDegree, blocked: 'Seed an entity first.' };
    const current = Math.max(...visible.map((e) => e.hop));
    const outer = visible.filter((e) => e.hop === current);
    const heldHubs = outer.filter((e) => e.highConnectivity).map((e) => e.id);
    const frontier = outer.filter((e) => includeHubs || !e.highConnectivity).map((e) => e.id);
    let blocked: string | null = null;
    const last = outcomes.at(-1);
    if (isAwaiting(last))
        blocked = `Degree ${last!.degree} is waiting for approval (request ${last!.awaitingApproval}) — a second person must approve this expand before it runs.`;
    else if (current >= maxDegree) blocked = `Degree ${maxDegree} reached.`;
    else if (last && last.decision !== 'denied' && last.degree === current + 1 && last.admitted === 0)
        blocked = `Degree ${current + 1} found no new entities — there is nothing further to expand.`;
    else if (!frontier.length && heldHubs.length)
        blocked =
            `Every entity at degree ${current} is flagged high connectivity, so none is expanded. Tick ` +
            '“Include hubs” or use “Expand anyway” on one of them.';
    // the server's cap on the ids one `expand` names (`investigation.max_frontier`)
    else if (frontier.length > laLimit('investigation.max_frontier'))
        blocked =
            `Degree ${current} has ${frontier.length.toLocaleString('en')} entities; one expand names at most ` +
            `${laLimit('investigation.max_frontier').toLocaleString('en')}. Exclude or hide hubs first.`;
    return { current, frontier, heldHubs, maxDegree, blocked };
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

/**
 * What a step did, from the `/ops` answer and the rung it sent. `degree` is the expected next degree; when the
 * refreshed Working Set `ws` is given, the degree is the outermost hop the step actually admitted (the rows' own
 * `hop`), never a count of steps.
 */
export function degreeOutcome(
    expected: number,
    step: InvestigationStepResult,
    rung: ExpandRung,
    range?: string | null,
    ws?: WorkingSet | null,
): DegreeOutcome {
    const degree = (step.status !== 'pending' && ws ? admittedHop(ws, step.step) : null) ?? expected;
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
            ...(step.pending?.sensitivity?.exceeded?.length ? { heldFor: step.pending.sensitivity.exceeded } : {}),
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
        ...(step.read?.hubDegreeSource ? { hubDegreeSource: step.read.hubDegreeSource } : {}),
    };
}

const HUB_SOURCE: Record<'index' | 'flat', string> = {
    index: 'counted from the link index',
    flat: 'counted from the Dataset',
};

/** One degree's result in plain language, truncation and the fan-out cap included. */
export function degreeOutcomeMessage(o: DegreeOutcome): string {
    // An override names the hub and the degree its contacts are admitted at — the same words held and approved, so it
    // is never read as the ladder's own next degree.
    const head = o.override
        ? `Expand anyway through ${o.override} (admits at degree ${o.degree})`
        : o.oneHop
          ? `Expand one hop from ${o.oneHop}`
          : `Degree ${o.degree}`;
    if (o.decision === 'approved')
        return `${head}: approved (request ${o.awaitingApproval}) — ${
            o.admitted ? `${plural(o.admitted, 'new entity', 'new entities')} admitted.` : 'nothing new was found.'
        }`;
    if (o.decision === 'denied')
        return `${head}: request ${o.awaitingApproval} was denied${o.reason ? ` (${o.reason})` : ''} — nothing was read.`;
    if (o.awaitingApproval)
        return `${head}: waiting for approval — this expand exceeds ${
            o.heldFor?.length ? `the Space's four-eyes limit (${thresholdWords(o.heldFor)})` : "the Space's four-eyes limit"
        }, so it runs once a second person approves request ${o.awaitingApproval}.`;
    const parts = [
        `${o.override || o.oneHop ? head : `Degree ${o.degree}`}${o.range ? ` (${o.range})` : ''}: ${plural(o.admitted, 'new entity', 'new entities')}, ${plural(o.linksAdded, 'link')} added.`,
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
    if (o.hubsFlagged) {
        const how = o.hubDegreeSource ? HUB_SOURCE[o.hubDegreeSource] : '';
        parts.push(
            `${plural(o.hubsFlagged, 'high-connectivity number')} shown but not expanded${how ? ` (contacts ${how})` : ''}.`,
        );
    }
    if (o.hubsOverridden)
        parts.push(
            `Analyst override: expanded through ${plural(o.hubsOverridden, 'high-connectivity number')} — recorded in the log.`,
        );
    if (o.hubsHeld) parts.push(`${plural(o.hubsHeld, 'high-connectivity number')} of the frontier not expanded from.`);
    return parts.join(' ');
}

/**
 * The high-connectivity badge. `contacts` is the entity's own sealed count when served (null in old logs);
 * `threshold` is the hub threshold the flag was measured against when known (the step's sealed rung, else the
 * Space's value in force). Nothing unserved is guessed.
 */
export function hubBadgeText(threshold?: number | null, contacts?: number | null, overridden = false): string {
    const state = overridden ? 'expanded through override' : 'not expanded';
    if (contacts != null)
        return (
            `High connectivity — ${plural(contacts, 'contact')}` +
            `${threshold != null ? ` (threshold ${threshold.toLocaleString('en')})` : ''}, ${state}`
        );
    return threshold != null
        ? `High connectivity — more than ${threshold.toLocaleString('en')} contacts, ${state}`
        : `High connectivity — ${state}`;
}
