import { describe, expect, it } from 'vitest';
import { AlertRule, EventRow } from 'app/inspecto/api';
import { deriveFreshness, freshnessBadge, maximumAgeFor, parseMaximumAge } from './dataset-freshness';

const NOW = 1_000_000_000;
const row = (ts: number) => ({ ts }) as EventRow;

describe('dataset-freshness', () => {
    it('parses Ns/Nm/Nh/Nd and refuses anything else', () => {
        expect(parseMaximumAge('30s')).toBe(30_000);
        expect(parseMaximumAge('5m')).toBe(300_000);
        expect(parseMaximumAge('2h')).toBe(7_200_000);
        expect(parseMaximumAge('1d')).toBe(86_400_000);
        expect(parseMaximumAge('2w')).toBeNull();
        expect(parseMaximumAge(null)).toBeNull();
    });

    it('finds the freshness rule for a dataset only', () => {
        const rules = [
            { name: 'a', dataset: 'x', maximumAge: null },
            { name: 'b', dataset: 'y', maximumAge: '1h' },
        ] as AlertRule[];
        expect(maximumAgeFor(rules, 'y')).toBe('1h');
        expect(maximumAgeFor(rules, 'x')).toBeNull();
    });

    it('compares the NEWEST write to maximumAge', () => {
        const rows = [row(NOW - 7_200_000), row(NOW - 60_000)];
        expect(deriveFreshness(rows, '5m', NOW).state).toBe('fresh');
        expect(deriveFreshness(rows, '30s', NOW).state).toBe('stale');
        expect(deriveFreshness(rows, '30s', NOW).lastWrite).toBe(NOW - 60_000);
    });

    it('never says fresh without a declared limit', () => {
        const f = deriveFreshness([row(NOW)], null, NOW);
        expect(f.state).toBe('published');
        expect(freshnessBadge(f).label).toMatch(/^Published /);
    });

    it('fails to unknown on an empty page or a failed fetch — even with a limit', () => {
        expect(deriveFreshness([], '1h', NOW).state).toBe('unknown');
        expect(deriveFreshness(null, '1h', NOW).state).toBe('unknown');
    });
});
