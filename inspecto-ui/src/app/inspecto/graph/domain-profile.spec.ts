import { describe, expect, it } from 'vitest';
import { DOMAIN_PROFILES, domainProfile, workingSetOptionsFor } from './domain-profile';

describe('domain profiles', () => {
    it('fall back to the generic profile for an unknown id', () => {
        expect(domainProfile('finance').label).toMatch(/Financial/);
        expect(domainProfile(undefined).id).toBe('generic');
        expect(domainProfile('nope' as never).id).toBe('generic');
    });

    it('pick the measure and time columns by hint, and leave detection to the stats when nothing matches', () => {
        const fin = workingSetOptionsFor(domainProfile('finance'), ['payer_id', 'booked_at', 'amount', 'channel']);
        expect(fin).toEqual({
            labels: { nodes: 'Accounts', links: 'Transfers', rows: 'Transactions' },
            measureColumns: ['amount'],
            timeColumn: 'booked_at',
        });
        const none = workingSetOptionsFor(domainProfile('finance'), ['a', 'b']);
        expect(none.measureColumns).toBeUndefined();
        expect(none.timeColumn).toBeUndefined();
        expect(none.labels?.nodes).toBe('Accounts');
    });

    it('every profile names real toolbox groups and at most three of them', () => {
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
        for (const p of DOMAIN_PROFILES) {
            expect(p.suggestedTools.length).toBeLessThanOrEqual(3);
            for (const t of p.suggestedTools) expect(known.has(t), `${p.id}: ${t}`).toBe(true);
        }
    });
});
