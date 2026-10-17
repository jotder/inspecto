import { signal } from '@angular/core';
import { WorkingSetOptions } from './working-set-stats';

/**
 * A **Domain Profile** shapes how Link Analysis reads a projected graph: what the tiles and concepts are called, which
 * edge-attribute columns are the measure and the time axis, and which analysis tools are foregrounded. Since
 * LA-EDITIONS-1 (settles `LDP-D7`) a profile is an **Industry pack** the server ships and a Space selects
 * (`GET /settings/link-analysis/domain-profile`); this file holds only the neutral `generic` profile and the registry
 * the server's packs are installed into. ⛔ A pack relabels canonical concept keys; it never adds a concept, and no
 * industry word lives here (`check-vocabulary.mjs` rule `industry-pack-term`, AC-09). Framework-free apart from the
 * registry signal; the id is persisted with a view, a case and an Investigation.
 */
export type DomainProfileId = string;

/** One relabel of a canonical concept key (`concept.node`, `entity_type.<id>`, `link_kind.<id>`, `measure.<id>`, ...). */
export interface DomainTerm {
    label: string;
    abbreviation: string;
    definition: string;
}

export interface DomainProfile {
    id: DomainProfileId;
    label: string;
    /** One line for the picker's hint. */
    description: string;
    /** Tile labels for the working-set overlay. */
    labels: { nodes: string; links: string; rows: string };
    /** Column-name patterns that mark a measure (summed) column, in preference order. */
    measureHints: RegExp[];
    /** Column-name patterns that mark the time axis, in preference order. */
    timeHints: RegExp[];
    /** Analysis toolbox group ids to badge as suggested for this domain. */
    suggestedTools: string[];
    /** "Investigate a number": the default Investigation binding and the next-degree presets. Absent = not offered. */
    investigate?: InvestigateProfile;
    /** The pack's relabels, by canonical concept key. Empty for `generic`. */
    terms: Record<string, DomainTerm>;
}

/** The Dataset + columns an Investigation binds by default (`POST /inv/investigations`). */
export interface InvestigateMapping {
    dataset: string;
    sourceCol: string;
    targetCol: string;
    linkKindCol?: string;
    timeCol?: string;
    /** An integer column counting the events a pre-aggregated row stands for: expand then weighs a link by its SUM. */
    eventsCol?: string;
}

/** The rung every "Expand next degree" sends (`expand` op options). */
export interface DegreePresets {
    /** The window, in days back from now. Sent only when the Investigation has a time column (else the server 422s). */
    windowDays?: number;
    minEvents?: number;
    maxFanOut?: number;
    budget?: number;
}

export interface InvestigateProfile {
    /** The Entity Type a deep link's `entityType` names. */
    entityType: string;
    mapping: InvestigateMapping;
    expand: DegreePresets;
    /** "Expand next degree" stops here. */
    maxDegree: number;
}

/** The degree "Expand next degree" stops at when the profile does not say. */
export const DEFAULT_MAX_DEGREE = 4;

/** The neutral labels of the `concept.*` keys — the server's `IndustryPack.CONCEPTS`. */
export const NEUTRAL_CONCEPT_LABELS: Record<string, string> = {
    'concept.node': 'Nodes',
    'concept.link': 'Links',
    'concept.row': 'Folded rows',
    'concept.link_from': 'From',
    'concept.link_to': 'To',
};

/** The neutral profile: what every Space reads until it selects an Industry pack. */
export const GENERIC_PROFILE: DomainProfile = {
    id: 'generic',
    label: 'Generic graph',
    description: 'Neutral vocabulary; measures and time axis auto-detected from the attribute columns.',
    labels: {
        nodes: NEUTRAL_CONCEPT_LABELS['concept.node'],
        links: NEUTRAL_CONCEPT_LABELS['concept.link'],
        rows: NEUTRAL_CONCEPT_LABELS['concept.row'],
    },
    measureHints: [],
    timeHints: [],
    suggestedTools: [],
    terms: {},
};

/** One installed pack as `GET /settings/link-analysis/domain-profile` answers it. */
export interface IndustryPackDto {
    id: string;
    name: string;
    description: string;
    spaceTemplates: string[];
    patternPacks: string[];
    profile: {
        measureHints: string[];
        timeHints: string[];
        suggestedTools: string[];
        investigate?: {
            entityType: string;
            dataset: string;
            sourceCol: string;
            targetCol: string;
            linkKindCol?: string;
            timeCol?: string;
            eventsCol?: string;
            windowDays?: number;
            minEvents?: number;
            maxFanOut?: number;
            budget?: number;
            maxDegree?: number;
        };
    };
    terms: ({ key: string } & DomainTerm)[];
    entityTypes: unknown[];
    linkKinds: string[];
}

/** A server pack as a profile: its terms relabel the tiles; its hints become case-insensitive patterns. */
export function profileFromPack(pack: IndustryPackDto): DomainProfile {
    const terms: Record<string, DomainTerm> = {};
    for (const t of pack.terms ?? [])
        terms[t.key] = { label: t.label, abbreviation: t.abbreviation ?? '', definition: t.definition ?? '' };
    const label = (key: string) => terms[key]?.label ?? NEUTRAL_CONCEPT_LABELS[key];
    const inv = pack.profile?.investigate;
    const pattern = (h: string): RegExp | null => {
        try {
            return new RegExp(h, 'i');
        } catch {
            return null; // the server validates the shape; a pattern this engine refuses is dropped, not thrown
        }
    };
    return {
        id: pack.id,
        label: pack.name,
        description: pack.description,
        labels: { nodes: label('concept.node'), links: label('concept.link'), rows: label('concept.row') },
        measureHints: (pack.profile?.measureHints ?? []).map(pattern).filter((r): r is RegExp => !!r),
        timeHints: (pack.profile?.timeHints ?? []).map(pattern).filter((r): r is RegExp => !!r),
        suggestedTools: pack.profile?.suggestedTools ?? [],
        terms,
        ...(inv
            ? {
                  investigate: {
                      entityType: inv.entityType,
                      mapping: {
                          dataset: inv.dataset,
                          sourceCol: inv.sourceCol,
                          targetCol: inv.targetCol,
                          ...(inv.linkKindCol ? { linkKindCol: inv.linkKindCol } : {}),
                          ...(inv.timeCol ? { timeCol: inv.timeCol } : {}),
                          ...(inv.eventsCol ? { eventsCol: inv.eventsCol } : {}),
                      },
                      expand: {
                          ...(inv.windowDays != null ? { windowDays: inv.windowDays } : {}),
                          ...(inv.minEvents != null ? { minEvents: inv.minEvents } : {}),
                          ...(inv.maxFanOut != null ? { maxFanOut: inv.maxFanOut } : {}),
                          ...(inv.budget != null ? { budget: inv.budget } : {}),
                      },
                      maxDegree: inv.maxDegree ?? DEFAULT_MAX_DEGREE,
                  },
              }
            : {}),
    };
}

// ── the registry: the server's packs, installed once per Space load (DomainProfileService) ──
const installed = signal<readonly DomainProfile[]>([]);
const active = signal<DomainProfileId>(GENERIC_PROFILE.id);

/** Install the server's packs and the Space's active profile id (`generic` when it names none or an absent one). */
export function installDomainProfiles(
    profiles: readonly DomainProfile[],
    activeId: DomainProfileId | null = null,
): void {
    installed.set(profiles.filter((p) => p.id !== GENERIC_PROFILE.id));
    active.set(activeId && profiles.some((p) => p.id === activeId) ? activeId : GENERIC_PROFILE.id);
}

/** `generic` first, then every installed pack. Reactive: a computed reading it re-runs when the packs arrive. */
export function domainProfiles(): readonly DomainProfile[] {
    return [GENERIC_PROFILE, ...installed()];
}

/** The profile the Space has selected (reactive). */
export function activeDomainProfileId(): DomainProfileId {
    return active();
}

export function domainProfile(id: DomainProfileId | null | undefined): DomainProfile {
    return domainProfiles().find((p) => p.id === id) ?? GENERIC_PROFILE;
}

/** The profile that investigates entities of this type, or null. Case-insensitive. */
export function profileForEntityType(entityType: string | null | undefined): DomainProfile | null {
    const t = (entityType ?? '').trim().toLowerCase();
    return (t && domainProfiles().find((p) => p.investigate?.entityType.toLowerCase() === t)) || null;
}

/** The profile's relabel of a canonical concept key, or null when it states none. */
export function domainTerm(profile: DomainProfile, key: string): DomainTerm | null {
    return profile.terms[key] ?? null;
}

/** What `key` is called under `profile`: its relabel, else the `neutral` label the core shows. */
export function termLabel(profile: DomainProfile, key: string, neutral: string): string {
    return profile.terms[key]?.label ?? neutral;
}

/**
 * The working-set options a profile yields over the columns actually present: labels always; measure and
 * time columns only when a hint matches (otherwise `workingSetStats` auto-detects). At most two measures.
 */
export function workingSetOptionsFor(profile: DomainProfile, columns: string[]): WorkingSetOptions {
    const measures = profile.measureHints.flatMap((re) => columns.filter((c) => re.test(c))).slice(0, 2);
    const time = profile.timeHints.flatMap((re) => columns.filter((c) => re.test(c)))[0];
    return {
        labels: profile.labels,
        ...(measures.length ? { measureColumns: [...new Set(measures)] } : {}),
        ...(time ? { timeColumn: time } : {}),
    };
}
