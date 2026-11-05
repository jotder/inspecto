import { describe, expect, it } from 'vitest';
import { NodeRule, describeNodeRule, nodeRuleColor, nodeRuleMatches } from './node-rules';

const node = (kind: string, attrs?: Record<string, string | null>) => ({ label: 'n', kind, attrs });
const rule = (over: Partial<NodeRule>): NodeRule => ({ attr: 'risk', op: 'eq', value: '5', color: 'swatch-EF4444', ...over });

describe('node-rules', () => {
    it('compares text with equals / is not / contains (case-insensitive)', () => {
        expect(nodeRuleMatches(rule({ op: 'eq', value: 'HIGH' }), node('msisdn', { risk: 'HIGH' }))).toBe(true);
        expect(nodeRuleMatches(rule({ op: 'eq', value: 'high' }), node('msisdn', { risk: 'HIGH' }))).toBe(false);
        expect(nodeRuleMatches(rule({ op: 'neq', value: 'low' }), node('msisdn', { risk: 'HIGH' }))).toBe(true);
        expect(nodeRuleMatches(rule({ op: 'contains', value: 'ig' }), node('msisdn', { risk: 'HIGH' }))).toBe(true);
    });

    it('compares numbers only when both sides are numeric', () => {
        expect(nodeRuleMatches(rule({ op: 'gt', value: '5' }), node('msisdn', { risk: '10' }))).toBe(true);
        expect(nodeRuleMatches(rule({ op: 'lt', value: '5' }), node('msisdn', { risk: '10' }))).toBe(false);
        expect(nodeRuleMatches(rule({ op: 'gt', value: '5' }), node('msisdn', { risk: 'n/a' }))).toBe(false);
        expect(nodeRuleMatches(rule({ op: 'gt', value: '' }), node('msisdn', { risk: '10' }))).toBe(false);
        expect(nodeRuleMatches(rule({ op: 'lt', value: '5' }), node('msisdn', { risk: ' ' }))).toBe(false);
    });

    it('never matches a node that lacks the attribute, even for "is not"', () => {
        expect(nodeRuleMatches(rule({ op: 'neq' }), node('msisdn', {}))).toBe(false);
        expect(nodeRuleMatches(rule({ op: 'neq' }), node('msisdn', { risk: null }))).toBe(false);
        expect(nodeRuleMatches(rule({ op: 'eq' }), node('msisdn'))).toBe(false);
    });

    it('scopes to an Entity Type when the rule names one', () => {
        expect(nodeRuleMatches(rule({ kind: 'imei' }), node('msisdn', { risk: '5' }))).toBe(false);
        expect(nodeRuleMatches(rule({ kind: 'msisdn' }), node('msisdn', { risk: '5' }))).toBe(true);
    });

    it('the first matching rule wins; none matching (or no rules) leaves the colour undefined', () => {
        const rules = [rule({ value: '9', color: 'swatch-111111' }), rule({ value: '5', color: 'swatch-222222' }), rule({ color: 'swatch-333333' })];
        expect(nodeRuleColor(rules, node('msisdn', { risk: '5' }))).toBe('swatch-222222');
        expect(nodeRuleColor(rules, node('msisdn', { risk: '1' }))).toBeUndefined();
        expect(nodeRuleColor(undefined, node('msisdn', { risk: '5' }))).toBeUndefined();
    });

    it('describes a rule in one line', () => {
        expect(describeNodeRule(rule({ kind: 'msisdn', op: 'gt', value: '5' }))).toBe('msisdn · risk is greater than 5');
        expect(describeNodeRule(rule({}))).toBe('risk equals 5');
        expect(describeNodeRule(rule({ reference: 'accounts', attr: 'segment', value: 'retail' }))).toBe(
            'segment (in accounts) equals retail',
        );
    });

    describe('a Reference rule (LA-REFERENCE-DETAILS-MORE-1)', () => {
        const segment = rule({ reference: 'accounts', attr: 'segment', value: 'retail', color: 'swatch-111111' });
        const details: Record<string, Record<string, string>> = { 'n-1': { segment: 'retail' } };
        const of = (id: string) => (ref: string) => (ref === 'accounts' ? details[id] : undefined);

        it('reads the value from the Reference details, never from the node attrs', () => {
            expect(nodeRuleColor([segment], node('account'), of('n-1'))).toBe('swatch-111111');
            // the same name on the node's own attrs does not count
            expect(nodeRuleColor([segment], node('account', { segment: 'retail' }), of('n-2'))).toBeUndefined();
        });

        it('leaves a node the Reference has no row for uncoloured, whatever the comparison', () => {
            for (const op of ['eq', 'neq', 'contains'] as const)
                expect(nodeRuleMatches({ ...segment, op }, node('account'), of('n-2'))).toBe(false);
            expect(nodeRuleMatches(segment, node('account'))).toBe(false); // no details at all
        });

        it('never colours by a masked value, even when the rule names it exactly', () => {
            const masked = (ref: string) => (ref === 'accounts' ? { segment: 'masked:ab12' } : undefined);
            for (const r of [
                { ...segment, value: 'masked:ab12' },
                { ...segment, op: 'neq' as const, value: 'retail' },
                { ...segment, op: 'contains' as const, value: 'masked' },
            ])
                expect(nodeRuleMatches(r, node('account'), masked)).toBe(false);
        });
    });
});
