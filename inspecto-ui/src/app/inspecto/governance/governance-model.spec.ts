import { describe, expect, it } from 'vitest';

import {
    GOVERNED_OBJECT_TYPES,
    describeEscalationRule,
    escalationRuleContent,
    escalationRuleDraft,
    slaContent,
    slaDraft,
    splitList,
    workflowContent,
    workflowDraft,
    WorkflowDraft,
    connectStates,
    removeState,
    renameState,
    setInitialState,
    setTerminalState,
    workflowProblems,
    workflowStates,
} from './governance-model';

describe('governance-model', () => {
    it('governs Incidents, Cases and Tasks - the Alert object type is retired', () => {
        expect([...GOVERNED_OBJECT_TYPES]).toEqual(['INCIDENT', 'CASE', 'TASK']);
    });

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

    describe('escalationRuleDraft', () => {
        const stored = {
            id: 'r1',
            objectType: 'CASE',
            on: 'age',
            afterMinutes: 30,
            priority: 'P1',
            reassign: 'lead',
            notify: true,
            'x-keep': 1,
            when: {
                kind: 'group',
                op: 'AND',
                items: [{ kind: 'condition', field: 'status', operator: '=', value: 'OPEN' }],
            },
        };

        it('round-trips a stored rule through the form draft without losing a key', () => {
            const d = escalationRuleDraft(stored);
            expect(d).toMatchObject({
                id: 'r1',
                objectType: 'CASE',
                on: 'age',
                target: 'resolution',
                afterMinutes: 30,
                notify: true,
                raisePriority: false,
            });
            expect(escalationRuleContent(d, stored)).toEqual(stored);
        });

        it('copies the when tree so editing it cannot mutate the stored rule', () => {
            const d = escalationRuleDraft(stored);
            expect(d.when).toEqual(stored.when);
            expect(d.when).not.toBe(stored.when);
        });

        it('negative probe: a when that is not a condition group is left undefined so a save keeps it as stored', () => {
            const odd = { ...stored, when: 'status == OPEN' };
            const d = escalationRuleDraft(odd);
            expect(d.when).toBeUndefined();
            expect(escalationRuleContent(d, odd)['when']).toBe('status == OPEN');
        });
    });
});

describe('workflow state-diagram edits', () => {
    const base = (): WorkflowDraft => ({
        objectType: 'INCIDENT',
        initial: 'IDENTIFIED',
        terminal: 'ARCHIVED',
        transitions: [
            { from: 'IDENTIFIED', to: 'RESOLVED', action: 'resolve' },
            { from: 'RESOLVED', to: 'ARCHIVED', action: 'archive' },
        ],
    });

    it('renames a state everywhere and refuses a clash', () => {
        const d = renameState(base(), 'resolved', 'done');
        expect(workflowContent(d)['transitions']).toEqual([
            { from: 'IDENTIFIED', to: 'DONE', action: 'resolve' },
            { from: 'DONE', to: 'ARCHIVED', action: 'archive' },
        ]);
        expect(renameState(base(), 'RESOLVED', 'archived')).toEqual(base());
        expect(workflowStates(renameState(base(), 'IDENTIFIED', 'open'))[0]).toBe('OPEN');
    });

    it('removes a state with its transitions, and clears initial / terminal membership', () => {
        const d = removeState(base(), 'RESOLVED');
        expect(d.transitions).toEqual([]);
        expect(removeState(base(), 'IDENTIFIED').initial).toBe('');
        expect(removeState(base(), 'ARCHIVED').terminal).toBe('');
    });

    it('marks initial / terminal and connects with a blank action; the save shape is the form path shape', () => {
        let d = setInitialState(base(), 'resolved');
        d = setTerminalState(d, 'resolved', true);
        d = connectStates(d, 'archived', 'identified');
        expect(workflowContent(d)).toEqual({
            id: 'incident',
            objectType: 'INCIDENT',
            initial: 'RESOLVED',
            terminal: ['ARCHIVED', 'RESOLVED'],
            transitions: [
                { from: 'IDENTIFIED', to: 'RESOLVED', action: 'resolve' },
                { from: 'RESOLVED', to: 'ARCHIVED', action: 'archive' },
                { from: 'ARCHIVED', to: 'IDENTIFIED', action: '' },
            ],
        });
        expect(setTerminalState(d, 'resolved', false).terminal).toBe('ARCHIVED');
    });

    it('mirrors the server problems with its wording; a valid workflow has none', () => {
        expect(workflowProblems(base())).toEqual([]);
        const p = workflowProblems(connectStates(base(), 'ARCHIVED', 'IDENTIFIED'));
        expect(p).toContain('transition needs from, to and action');
        const orphan = { ...base(), transitions: [...base().transitions, { from: 'X', to: 'RESOLVED', action: 'x' }] };
        expect(workflowProblems(orphan)).toContain('state X is unreachable from the initial state IDENTIFIED');
        const dup = {
            ...base(),
            transitions: [...base().transitions, { from: 'IDENTIFIED', to: 'ARCHIVED', action: 'resolve' }],
        };
        expect(workflowProblems(dup)).toContain("two transitions leave IDENTIFIED by action 'resolve'");
        const skip = {
            ...base(),
            transitions: [...base().transitions, { from: 'IDENTIFIED', to: 'ARCHIVED', action: 'drop' }],
        };
        expect(workflowProblems(skip)).toEqual([]);
        const around = {
            ...base(),
            terminal: 'ARCHIVED, DONE',
            transitions: [...base().transitions, { from: 'IDENTIFIED', to: 'DONE', action: 'skip' }],
        };
        expect(workflowProblems(around).join()).toContain('skipping the Disposition and postmortem gate');
        expect(workflowProblems({ ...base(), initial: '' })).toContain('an initial state is required');
        expect(workflowProblems({ ...base(), terminal: '' })).toContain('declare at least one terminal state');
    });
});
