import { WorkingSetOptions } from './working-set-stats';

/**
 * A **domain profile** shapes how Link Analysis reads a projected graph: what the tiles are called, which
 * edge-attribute columns are the measure and the time axis, and which analysis tools are foregrounded.
 * Data comes from different domains and the statistics differ with the nature of the data (mockup review,
 * 2026-09-20) — the profile is the one place that knowledge lives. Framework-free; persisted with a view.
 */
export type DomainProfileId = 'generic' | 'finance' | 'telecom' | 'supply-chain' | 'cyber';

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
}

export const DOMAIN_PROFILES: readonly DomainProfile[] = [
    {
        id: 'generic',
        label: 'Generic graph',
        description: 'Neutral vocabulary; measures and time axis auto-detected from the attribute columns.',
        labels: { nodes: 'Nodes', links: 'Links', rows: 'Folded rows' },
        measureHints: [],
        timeHints: [],
        suggestedTools: [],
    },
    {
        id: 'finance',
        label: 'Financial crime — transactions',
        description: 'Accounts linked by transfers; sums amounts, foregrounds suspicion and pass-through patterns.',
        labels: { nodes: 'Accounts', links: 'Transfers', rows: 'Transactions' },
        measureHints: [/amount/i, /value/i, /total/i],
        timeHints: [/booked/i, /posted/i, /_at$/i, /date/i, /time/i],
        suggestedTools: ['scoring', 'pattern', 'flow'],
    },
    {
        id: 'telecom',
        label: 'Telecom — call detail records',
        description: 'Subscribers linked by calls; sums duration, foregrounds communities and cohesive groups.',
        labels: { nodes: 'Subscribers', links: 'Calls', rows: 'Call records' },
        measureHints: [/duration/i, /seconds/i, /minutes/i],
        timeHints: [/start/i, /_at$/i, /time/i, /date/i],
        suggestedTools: ['communities', 'explain', 'cohesion'],
    },
    {
        id: 'supply-chain',
        label: 'Supply chain — shipments',
        description: 'Parties linked by shipments; sums weight or quantity, foregrounds flow and cut points.',
        labels: { nodes: 'Parties', links: 'Shipments', rows: 'Consignments' },
        measureHints: [/weight/i, /qty/i, /quantity/i, /volume/i],
        timeHints: [/shipped/i, /dispatch/i, /_at$/i, /date/i],
        suggestedTools: ['flow', 'path', 'cut-points'],
    },
    {
        id: 'cyber',
        label: 'Cyber — network flows',
        description: 'Hosts linked by flows; sums bytes, foregrounds centrality, cycles and communities.',
        labels: { nodes: 'Hosts', links: 'Flows', rows: 'Flow records' },
        measureHints: [/bytes/i, /packets/i, /octets/i],
        timeHints: [/^ts$/i, /timestamp/i, /_at$/i, /time/i],
        suggestedTools: ['centrality', 'cycles', 'communities'],
    },
];

export function domainProfile(id: DomainProfileId | null | undefined): DomainProfile {
    return DOMAIN_PROFILES.find((p) => p.id === id) ?? DOMAIN_PROFILES[0];
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
