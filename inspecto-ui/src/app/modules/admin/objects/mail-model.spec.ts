import { describe, expect, it } from 'vitest';
import { OperationalObject } from 'app/inspecto/api';
import { postmortemGaps } from './mail-model';

/** An Incident carrying `postmortem` (raw JSON) and `dueAt`. */
function incident(postmortem: unknown, dueAt: string | undefined = '1790000000000'): OperationalObject {
    const attributes: Record<string, string> = { postmortem: JSON.stringify(postmortem) };
    if (dueAt !== undefined) attributes['dueAt'] = dueAt;
    return { id: 'INCIDENT-1', objectType: 'INCIDENT', status: 'DIAGNOSING', attributes } as OperationalObject;
}

const COMPLETE = { timeline: [{ time: '09:00', text: 'x' }], causeAnalysis: ['y'], actions: [{ text: 'z' }] };

/**
 * `postmortemGaps` gates Resolve before the server's hard I1 gate (`ObjectService.incidentResolutionGaps`), so it
 * must agree with it: a gap the server would refuse must show here, and a shape the server accepts must not
 * throw. It once read the legacy `fiveWhys` key the server does not, so such an Incident passed this gate, the
 * dialog asked for a Disposition and posted the comment, and then the resolve was refused.
 */
describe('postmortemGaps mirrors the server resolution gate', () => {
    it('a complete pattern has no gaps', () => {
        expect(postmortemGaps(incident(COMPLETE))).toEqual([]);
    });

    it('a legacy fiveWhys blob still lacks cause analysis, as the server reads it', () => {
        const legacy = {
            timeline: COMPLETE.timeline,
            fiveWhys: ['schema drift', '', '', '', ''],
            actions: COMPLETE.actions,
        };
        expect(postmortemGaps(incident(legacy))).toEqual(['cause analysis']);
    });

    it('a timeline row or an action with a missing or non-string field does not throw', () => {
        const partial = { timeline: [{ text: 'only text' }], causeAnalysis: ['y'], actions: [{ text: 42 }] };
        expect(() => postmortemGaps(incident(partial))).not.toThrow();
        expect(postmortemGaps(incident(partial))).toEqual([]);
    });

    it('a blank dueAt is no SLA', () => {
        expect(postmortemGaps(incident(COMPLETE, '  '))).toEqual(['SLA']);
    });
});
