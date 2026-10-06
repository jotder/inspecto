import { describe, expect, it } from 'vitest';
import { OperationalObject } from 'app/inspecto/api';
import { findingsCaseType, parseFindings, postmortemGaps, slaBadges } from './mail-model';

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

describe('slaBadges (ASSURE-WORKFLOW-SLA-1)', () => {
    const obj = (status: string, attributes: Record<string, string>): OperationalObject =>
        ({ id: 'INCIDENT-2', objectType: 'INCIDENT', status, attributes }) as OperationalObject;

    it('an object with no SLA stamps shows nothing', () => {
        expect(slaBadges(obj('IDENTIFIED', {}))).toEqual([]);
    });

    it('an open object shows its response and resolution due dates as info', () => {
        const b = slaBadges(obj('IDENTIFIED', { responseDueAt: '1790000000000', dueAt: '1790003600000' }));
        expect(b.map((x) => x.tone)).toEqual(['INFO', 'INFO']);
        expect(b[0].label).toMatch(/^Respond by /);
        expect(b[1].label).toMatch(/^Due /);
    });

    it('a breach marker wins over the due date and is critical', () => {
        const b = slaBadges(
            obj('DIAGNOSING', { responseDueAt: '1', slaResponseBreachedAt: '2', dueAt: '3', slaBreachedAt: '4' }),
        );
        expect(b.map((x) => x.label)).toEqual(['Response breached', 'SLA breached']);
        expect(b.every((x) => x.tone === 'CRITICAL')).toBe(true);
    });

    it('a resolved object keeps its breach but drops its due dates', () => {
        const b = slaBadges(obj('RESOLVED', { responseDueAt: '1790000000000', dueAt: '5', slaBreachedAt: '6' }));
        expect(b.map((x) => x.label)).toEqual(['SLA breached']);
    });

    it('a blank or non-numeric stamp is no SLA', () => {
        expect(slaBadges(obj('IDENTIFIED', { dueAt: '', slaBreachedAt: 'x' }))).toEqual([]);
    });
});

describe('parseFindings reads a Case built-in Impact field from its typed Impact', () => {
    const impact = {
        suspected: null,
        confirmed: '1500.25',
        recovered: null,
        prevented: null,
        outstanding: '1500.25',
        currency: 'EUR',
        period: null,
        basis: null,
    };
    it('overlays impact.confirmed on a Case, even with no stored blob', () => {
        const o = { objectType: 'CASE', attributes: {}, impact } as unknown as OperationalObject;
        expect(parseFindings(o)).toEqual({ impact: '1500.25' });
        const withBlob = { ...o, attributes: { findings: '{"summary":"s","impact":"9"}' } } as OperationalObject;
        expect(parseFindings(withBlob)).toEqual({ summary: 's', impact: '1500.25' });
    });
    it('leaves an Incident blob alone', () => {
        const o = {
            objectType: 'INCIDENT',
            attributes: { findings: '{"impact":"9"}' },
            impact,
        } as unknown as OperationalObject;
        expect(parseFindings(o)).toEqual({ impact: '9' });
    });
});

describe('findingsCaseType', () => {
    it('reads a trimmed attributes.caseType, null when absent or blank', () => {
        expect(findingsCaseType({ attributes: { caseType: ' sim-box ' } })).toBe('sim-box');
        expect(findingsCaseType({ attributes: { caseType: '  ' } })).toBeNull();
        expect(findingsCaseType({})).toBeNull();
        expect(findingsCaseType(null)).toBeNull();
    });
});
