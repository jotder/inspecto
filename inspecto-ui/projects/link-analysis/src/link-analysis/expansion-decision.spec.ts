import { describe, expect, it } from 'vitest';
import type { ExpansionSensitivity, PendingExpansion } from '@inspecto/link-analysis/api/inv.service';
import type { DegreeOutcome } from './investigate-number';
import {
    approverWaitingMessage,
    heldLimitWords,
    recordedBefore,
    requestDifference,
    requesterName,
} from './expansion-decision';

const sens = (over: Partial<ExpansionSensitivity>): ExpansionSensitivity => ({
    exceeded: [],
    budget: 500,
    maxFanOut: 100,
    fourEyesBudgetAbove: null,
    fourEyesFanOutAbove: null,
    ...over,
});

const PENDING: PendingExpansion = {
    id: 'p1',
    investigationId: 'inv-1',
    op: 'expand',
    params: {},
    sensitivity: sens({ maxFanOut: null, fourEyesFanOutAbove: 2000 }),
    status: 'pending',
    requestedBy: 'fm.analyst',
    requestedAt: '2026-11-15T10:00:00Z',
};

// LA-FULL-CYCLE-TEST-1 presentability: the approver reads who asks and what it would do, in plain words
describe('the approver wording', () => {
    it('one limit: what it would do and the limit, in one sentence', () => {
        expect(heldLimitWords(sens({ maxFanOut: null, fourEyesFanOutAbove: 2000 }))).toBe(
            'It would add every neighbour of each entity, more than the 2,000 allowed without a second approval.',
        );
        expect(heldLimitWords(sens({ maxFanOut: 3000, fourEyesFanOutAbove: 2000 }))).toBe(
            'It would add up to 3,000 neighbours of each entity, more than the 2,000 allowed without a second approval.',
        );
        expect(heldLimitWords(sens({ budget: 5000, fourEyesBudgetAbove: 1000 }))).toBe(
            'It would read up to 5,000 rows, more than the 1,000 allowed without a second approval.',
        );
    });

    it('both limits: the two limits share one clause, so "without a second approval" covers both', () => {
        expect(
            heldLimitWords(
                sens({ budget: 5000, fourEyesBudgetAbove: 1000, maxFanOut: null, fourEyesFanOutAbove: 2000 }),
            ),
        ).toBe(
            'It would read up to 5,000 rows and add every neighbour of each entity; without a second approval the ' +
                'limits are 1,000 rows and 2,000 neighbours of each entity.',
        );
    });

    it("no numbers: the server's sealed reasons, put into words; nothing known says nothing", () => {
        expect(heldLimitWords(sens({ exceeded: ['budget 500 > 100'] }))).toBe(
            'It is held: reads up to 500 rows, above the limit of 100; the limits are in Settings ▸ Link Analysis.',
        );
        expect(heldLimitWords(sens({}))).toBe('');
    });

    it('names the requester, never the request id; with no requester, another analyst', () => {
        const head = {
            degree: 2,
            admitted: 0,
            linksAdded: 0,
            truncated: false,
            fanOutCapped: 0,
            awaitingApproval: 'p1',
        };
        const line = approverWaitingMessage(
            head,
            PENDING,
            requesterName(PENDING, () => 'Fatima Analyst'),
        );
        expect(line).toContain('Fatima Analyst asks for your approval. It would add every neighbour');
        expect(line).not.toContain('p1');
        // LA-EXEC-DEMO-POLISH-1: plainly a further Expand, not the recorded one above it
        expect(line.startsWith('Requested next, not yet run: Degree 2. Fatima Analyst asks')).toBe(true);
        const oneHop = approverWaitingMessage({ ...head, oneHop: 'every entity' }, PENDING, 'Fatima Analyst');
        expect(oneHop.startsWith('Requested next, not yet run: Expand one hop from every entity. Fatima')).toBe(true);
        expect(requesterName({ ...PENDING, requestedBy: null }, (id) => id)).toBe('another analyst');
    });
});

// LA-EXEC-DEMO-POLISH-1 item 17: a request that repeats the recorded Expand says how it differs from it
describe('the approver line against the Expand it repeats', () => {
    /** The recorded "Expand one hop from every entity (step 2)" of the showcase: 6 strongest links, 500 rows. */
    const RAN: DegreeOutcome = {
        degree: 1,
        admitted: 6,
        linksAdded: 7,
        truncated: false,
        fanOutCapped: 8,
        budget: 500,
        maxFanOut: 6,
        oneHop: 'every entity',
        step: 2,
    };
    const HELD: DegreeOutcome = {
        degree: 2,
        admitted: 0,
        linksAdded: 0,
        truncated: false,
        fanOutCapped: 0,
        awaitingApproval: 'p1',
        oneHop: 'every entity',
    };

    it('no limit on links: "again, this time" the difference, then only the number of the limit it crosses', () => {
        expect(approverWaitingMessage(HELD, PENDING, 'Demo FM Analyst', RAN)).toBe(
            'Requested next, not yet run: Expand one hop from every entity again, this time adding every link of each ' +
                'entity, not only the 6 strongest. Demo FM Analyst asks for your approval. That is more than the 2,000 ' +
                'allowed without a second approval.',
        );
    });

    it('a bigger read: the rows, then its limit; both limits crossed: both named', () => {
        const rows = { ...PENDING, sensitivity: sens({ budget: 5000, fourEyesBudgetAbove: 1000, maxFanOut: 6 }) };
        expect(approverWaitingMessage(HELD, rows, 'Demo FM Analyst', RAN)).toBe(
            'Requested next, not yet run: Expand one hop from every entity again, this time reading up to 5,000 rows, ' +
                'not 500. Demo FM Analyst asks for your approval. That is more than the 1,000 allowed without a second ' +
                'approval.',
        );
        const both = {
            ...PENDING,
            sensitivity: sens({ budget: 5000, fourEyesBudgetAbove: 1000, maxFanOut: null, fourEyesFanOutAbove: 2000 }),
        };
        expect(approverWaitingMessage(HELD, both, 'X', RAN)).toContain(
            'again, this time adding every link of each entity, not only the 6 strongest and reading up to 5,000 rows, ' +
                'not 500. X asks for your approval. It would read up to 5,000 rows and add every neighbour',
        );
    });

    it('nothing known differs, or no Expand to repeat: the line as before', () => {
        const same = { ...PENDING, sensitivity: sens({ budget: 500, maxFanOut: 6, fourEyesFanOutAbove: 2 }) };
        expect(requestDifference(same.sensitivity!, RAN).words).toEqual([]);
        expect(approverWaitingMessage(HELD, same, 'X', RAN)).toMatch(
            /^Requested next, not yet run: Expand one hop from every entity\. X asks/,
        );
        expect(approverWaitingMessage(HELD, PENDING, 'X', null)).toMatch(
            /^Requested next, not yet run: Expand one hop from every entity\. X asks/,
        );
    });

    it('the Expand repeated is the latest before the held line with the same head that read data', () => {
        const ladder: DegreeOutcome = { ...RAN, oneHop: undefined, step: undefined };
        const denied: DegreeOutcome = { ...HELD, awaitingApproval: 'p0', decision: 'denied' };
        const approved: DegreeOutcome = { ...HELD, awaitingApproval: 'p9', decision: 'approved', maxFanOut: 9 };
        expect(recordedBefore([RAN, ladder, denied, HELD], HELD)).toBe(RAN); // a denied one never read
        expect(recordedBefore([RAN, approved, HELD], HELD)).toBe(approved); // an approved one ran
        expect(recordedBefore([ladder, HELD, RAN], HELD)).toBeNull(); // after it, or another head: none
    });
});
