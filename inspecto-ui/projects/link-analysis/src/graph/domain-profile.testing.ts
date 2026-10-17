import { IndustryPackDto, installDomainProfiles, profileFromPack } from './domain-profile';

/**
 * Specs only: the `telecom` Industry pack's profile block as the server answers it (the manifest
 * `META-INF/inspecto/industry-packs/telecom/industry-pack.toon` in inspecto-entity-store), without its terms. Specs that
 * exercise "Investigate a number" install it, because the SPA holds no industry profile of its own (LA-EDITIONS-1).
 */
export const TELECOM_PACK_FIXTURE: IndustryPackDto = {
    id: 'telecom',
    name: 'Telecom',
    description: 'Test fixture of the telecom Industry pack.',
    spaceTemplates: ['telco-fraud', 'la-showcase'],
    patternPacks: ['forwarding-relay', 'telecom-forwarding-chain'],
    profile: {
        measureHints: ['duration', 'seconds', 'minutes'],
        timeHints: ['start', '_at$', 'time', 'date'],
        suggestedTools: ['communities', 'explain', 'cohesion'],
        investigate: {
            entityType: 'msisdn',
            dataset: 'telecom_links_dataset',
            sourceCol: 'a_msisdn',
            targetCol: 'b_msisdn',
            linkKindCol: 'link_kind',
            timeCol: 'last_seen',
            eventsCol: 'events',
            windowDays: 30,
            minEvents: 1,
            maxFanOut: 500,
            budget: 10000,
            maxDegree: 4,
        },
    },
    terms: [],
    entityTypes: [],
    linkKinds: ['voice', 'forwarding'],
};

/** Install the telecom fixture as the only pack (`active` = the Space's selection, `generic` by default). */
export function installTelecomFixture(active: string | null = null): void {
    installDomainProfiles([profileFromPack(TELECOM_PACK_FIXTURE)], active);
}
