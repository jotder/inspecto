import { describe, expect, it } from 'vitest';

import {
    describeEscalationRule,
    escalationRuleContent,
    slaContent,
    slaDraft,
    splitList,
    workflowContent,
    workflowDraft,
} from './governance-model';

describe('governance-model', () => {
    it('splits and normalises a comma list', () => {
        expect(splitList(' closed, ,archived ')).toEqual(['CLOSED', 'ARCHIVED']);
        expect(splitList('2026-12-25, 2026-12-26', false)).toEqual(['2026-12-25', '2026-12-26']);
    });

    it('round-trips a workflow through its component content, dropping blank rows', () => {
        const content = workflowContent({
            objectType: 'INCIDENT',
            initial: ' new ',
            terminal: 'closed, archived',
            transitions: [
                { from: 'new', to: 'resolved', action: 'Resolve' },
                { from: '', to: '', action: '' },
            ],
        });
        expect(content).toEqual({
            id: 'incident',
            objectType: 'INCIDENT',
            initial: 'NEW',
            terminal: ['CLOSED', 'ARCHIVED'],
            transitions: [{ from: 'NEW', to: 'RESOLVED', action: 'resolve' }],
        });
        expect(workflowDraft('INCIDENT', content).terminal).toBe('CLOSED, ARCHIVED');
    });

    it('builds an SLA policy with an explicit zone and only the targets that are set', () => {
        const content = slaContent({
            objectType: 'INCIDENT',
            zone: 'Europe/London',
            workingDays: ['MON', 'FRI'],
            start: '09:00',
            end: '17:00',
            holidays: '2026-12-25',
            targets: [
                { priority: 'critical', responseMinutes: 30, resolutionMinutes: 240 },
                { priority: '*', responseMinutes: null, resolutionMinutes: 960 },
                { priority: '', responseMinutes: 5, resolutionMinutes: 5 },
            ],
        });
        expect(content['calendar']).toEqual({
            zone: 'Europe/London',
            workingDays: ['MON', 'FRI'],
            start: '09:00',
            end: '17:00',
            holidays: ['2026-12-25'],
        });
        expect(content['targets']).toEqual([
            { priority: 'CRITICAL', responseMinutes: 30, resolutionMinutes: 240 },
            { priority: '*', resolutionMinutes: 960 },
        ]);
        expect(slaDraft('INCIDENT', content).targets[1].responseMinutes).toBeNull();
    });

    it('writes only the trigger fields that apply, and describes a rule in one line', () => {
        const breach = escalationRuleContent({
            id: 'page',
            objectType: 'INCIDENT',
            on: 'breach',
            target: 'response',
            afterMinutes: 60,
            priority: 'critical',
            reassign: 'duty',
            notify: true,
            raisePriority: false,
        });
        expect(breach).toEqual({
            id: 'page',
            objectType: 'INCIDENT',
            on: 'breach',
            target: 'response',
            priority: 'CRITICAL',
            reassign: 'duty',
            notify: true,
        });
        expect(describeEscalationRule(breach)).toBe(
            'INCIDENT CRITICAL on a response SLA breach: reassign to duty, notify',
        );
        const age = escalationRuleContent({
            id: 'page',
            objectType: 'INCIDENT',
            on: 'age',
            afterMinutes: 90,
            target: 'resolution',
            priority: '',
            reassign: '',
            notify: false,
            raisePriority: true,
        });
        expect(age).toEqual({ id: 'page', objectType: 'INCIDENT', on: 'age', afterMinutes: 90, raisePriority: true });
        expect(describeEscalationRule(age)).toBe('INCIDENT at 90 min old: raise priority');
    });

    describe('Escalation Rule keeps what the form does not model (silent-loss trap)', () => {
        const WHEN = {
            kind: 'group',
            op: 'AND',
            items: [{ kind: 'condition', field: 'minutesToDue', operator: '<=', value: '10' }],
        };
        const stored = {
            id: 'page',
            objectType: 'INCIDENT',
            on: 'breach',
            target: 'response',
            notify: true,
            when: WHEN,
            futureKey: 7,
        };
        const draft = {
            id: 'page',
            objectType: 'INCIDENT',
            on: 'breach' as const,
            target: 'response' as const,
            afterMinutes: null,
            priority: '',
            reassign: 'duty', // the unrelated edit
            notify: true,
            raisePriority: false,
        };

        it('carries when and unknown keys forward from the stored rule', () => {
            const out = escalationRuleContent(draft, stored);
            expect(out['reassign']).toBe('duty');
            expect(out['when']).toEqual(WHEN);
            expect(out['futureKey']).toBe(7);
        });

        it('negative probe: nothing stored means nothing invented', () => {
            expect(escalationRuleContent(draft)).not.toHaveProperty('when');
            expect(escalationRuleContent(draft)).not.toHaveProperty('futureKey');
        });

        it('a modelled key the author cleared is dropped, not resurrected from the stored copy', () => {
            const out = escalationRuleContent({ ...draft, notify: false }, stored);
            expect(out).not.toHaveProperty('notify');
        });

        it('an authored when on the draft wins; an empty group removes it', () => {
            const next = { kind: 'group' as const, op: 'OR' as const, items: [] };
            expect(escalationRuleContent({ ...draft, when: next }, stored)).not.toHaveProperty('when');
            const g = { ...WHEN, op: 'OR' as const };
            expect(escalationRuleContent({ ...draft, when: g as never }, stored)['when']).toEqual(g);
        });
    });
});
