import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
    GENERIC_PROFILE,
    IndustryPackDto,
    activeDomainProfileId,
    domainProfile,
    domainProfiles,
    installDomainProfiles,
    profileForEntityType,
    profileFromPack,
    termLabel,
    workingSetOptionsFor,
} from './domain-profile';
import { TELECOM_PACK_FIXTURE } from './domain-profile.testing';

/** The telecom fixture WITH its relabels, as `GET /settings/link-analysis/domain-profile` answers them. */
const TELECOM: IndustryPackDto = {
    ...TELECOM_PACK_FIXTURE,
    terms: [
        { key: 'concept.node', label: 'Subscribers', abbreviation: '', definition: 'The lines on the canvas.' },
        { key: 'concept.link', label: 'Calls', abbreviation: '', definition: 'Contacts between two lines.' },
        { key: 'concept.row', label: 'Call detail records', abbreviation: 'CDR / XDR', definition: 'Usage records.' },
        { key: 'entity_type.msisdn', label: 'MSISDN', abbreviation: '', definition: 'The phone number of a line.' },
    ],
};
const MONEY: IndustryPackDto = {
    ...TELECOM_PACK_FIXTURE,
    id: 'mobile-money',
    name: 'Mobile Money',
    profile: { measureHints: ['amount'], timeHints: ['booked'], suggestedTools: ['scoring'] },
    terms: [{ key: 'concept.node', label: 'Wallets', abbreviation: '', definition: 'Wallets and agents.' }],
};

describe('domain profiles', () => {
    // The registry is module state shared with other specs in the worker: start each test from it empty.
    beforeEach(() => installDomainProfiles([], null));
    afterEach(() => installDomainProfiles([], null));

    it('the suggested first lists of a pack reach the profile; the neutral profile suggests neutral ones (phase 7)', () => {
        const lists = [{ title: 'Call centres', purpose: 'exclusion' as const, entityType: 'msisdn', why: 'Busy.' }];
        expect(profileFromPack({ ...TELECOM, profile: { ...TELECOM.profile, suggestedLists: lists } }).suggestedLists).toEqual(lists);
        expect(profileFromPack(MONEY).suggestedLists).toEqual([]); // an older server sends none
        expect(GENERIC_PROFILE.suggestedLists?.map((l) => l.purpose)).toEqual(['exclusion', 'watch']);
        expect(GENERIC_PROFILE.suggestedLists?.every((l) => l.entityType === '')).toBe(true);
    });

    it('hold only the neutral generic profile until the server installs its packs', () => {
        expect(domainProfiles().map((p) => p.id)).toEqual(['generic']);
        expect(domainProfile('telecom').id).toBe('generic');
        expect(domainProfile(undefined).id).toBe('generic');
        expect(profileForEntityType('msisdn')).toBeNull();
        expect(activeDomainProfileId()).toBe('generic');
    });

    it('install the server packs and the Space selection; an uninstalled selection reads generic', () => {
        installDomainProfiles([profileFromPack(TELECOM), profileFromPack(MONEY)], 'mobile-money');
        expect(domainProfiles().map((p) => p.id)).toEqual(['generic', 'telecom', 'mobile-money']);
        expect(activeDomainProfileId()).toBe('mobile-money');
        expect(profileForEntityType('MSISDN')?.id).toBe('telecom');
        installDomainProfiles([profileFromPack(TELECOM)], 'atlantis');
        expect(activeDomainProfileId()).toBe('generic');
    });

    it('switch the working-set labels with the pack, and keep the neutral words without one', () => {
        installDomainProfiles([profileFromPack(TELECOM), profileFromPack(MONEY)], 'telecom');
        const cols = ['a_msisdn', 'start_at', 'duration_s', 'amount'];
        expect(workingSetOptionsFor(GENERIC_PROFILE, cols)).toEqual({
            labels: { nodes: 'Nodes', links: 'Links', rows: 'Folded rows' },
        });
        expect(workingSetOptionsFor(domainProfile('telecom'), cols)).toEqual({
            labels: { nodes: 'Subscribers', links: 'Calls', rows: 'Call detail records' },
            measureColumns: ['duration_s'],
            timeColumn: 'start_at',
        });
        const money = workingSetOptionsFor(domainProfile('mobile-money'), cols);
        expect(money.labels).toEqual({ nodes: 'Wallets', links: 'Links', rows: 'Folded rows' }); // unrelabelled = neutral
        expect(money.measureColumns).toEqual(['amount']);
    });

    it('look a term up by canonical concept key, falling back to the neutral label', () => {
        installDomainProfiles([profileFromPack(TELECOM)], 'telecom');
        expect(termLabel(domainProfile('telecom'), 'entity_type.msisdn', 'Phone number')).toBe('MSISDN');
        expect(termLabel(GENERIC_PROFILE, 'entity_type.msisdn', 'Phone number')).toBe('Phone number');
        expect(domainProfile('telecom').terms['concept.row'].abbreviation).toBe('CDR / XDR');
    });

    it('turn the pack investigate block into the binding and presets', () => {
        const p = profileFromPack(TELECOM);
        expect(p.investigate).toEqual({
            entityType: 'msisdn',
            mapping: {
                dataset: 'telecom_links_dataset',
                sourceCol: 'a_msisdn',
                targetCol: 'b_msisdn',
                linkKindCol: 'link_kind',
                timeCol: 'last_seen',
                eventsCol: 'events',
            },
            expand: { windowDays: 30, minEvents: 1, maxFanOut: 500, budget: 10000 },
            maxDegree: 4,
        });
        expect(profileFromPack(MONEY).investigate).toBeUndefined();
    });

    it('every installed profile names real toolbox groups and at most three of them', () => {
        installDomainProfiles([profileFromPack(TELECOM), profileFromPack(MONEY)], null);
        const known = new Set([
            'path',
            'all-paths',
            'explain',
            'centrality',
            'communities',
            'components',
            'cycles',
            'cut-points',
            'cohesion',
            'similarity',
            'flow',
            'scoring',
            'pattern',
        ]);
        for (const p of domainProfiles()) {
            expect(p.suggestedTools.length).toBeLessThanOrEqual(3);
            for (const t of p.suggestedTools) expect(known.has(t), `${p.id}: ${t}`).toBe(true);
        }
    });
});
