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
    });
});
