import { describe, expect, it } from 'vitest';
import type { ExpansionSensitivity, PendingExpansion } from '@inspecto/link-analysis/api/inv.service';
import { approverWaitingMessage, heldLimitWords, requesterName } from './expansion-decision';

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
