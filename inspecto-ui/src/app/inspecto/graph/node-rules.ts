import type { G6Node } from './graph-types';

/** The comparisons a conditional-formatting rule offers (client-side only; numeric ones need both sides numeric). */
export type NodeRuleOp = 'eq' | 'neq' | 'contains' | 'gt' | 'lt';

export const NODE_RULE_OPS: readonly { value: NodeRuleOp; label: string }[] = [
    { value: 'eq', label: 'equals' },
    { value: 'neq', label: 'is not' },
    { value: 'contains', label: 'contains' },
    { value: 'gt', label: 'is greater than' },
    { value: 'lt', label: 'is less than' },
];

/**
 * **Conditional formatting** (Link Analysis canvas): colour the nodes whose attribute satisfies a predicate. `kind`
 * (optional) scopes the rule to one Entity Type; `attr` is a key of the node's `attrs` (a node mapping's attribute
 * columns). `color` is a chart-token swatch. Rules are ordered — the FIRST match wins — and persist with a saved view
 * as `display.nodeRules`. A rule's colour outranks the per-kind colour; the analysis-overlay group colour outranks it;
 * the halo / dotted-outline / selection cues are drawn independently of colour so they are never lost.
 */
export interface NodeRule {
    kind?: string;
    attr: string;
    op: NodeRuleOp;
    value: string;
    color: string;
}

/** Whether one node satisfies one rule. A node without the attribute never matches (not even `is not`). */
export function nodeRuleMatches(rule: NodeRule, node: G6Node['data']): boolean {
    if (rule.kind && rule.kind !== node.kind) return false;
    const raw = node.attrs?.[rule.attr];
    if (raw === undefined || raw === null) return false;
    switch (rule.op) {
        case 'eq':
            return raw === rule.value;
        case 'neq':
            return raw !== rule.value;
        case 'contains':
            return raw.toLowerCase().includes(rule.value.toLowerCase());
        case 'gt':
        case 'lt': {
            const a = Number(raw);
            const b = Number(rule.value);
            if (raw.trim() === '' || rule.value.trim() === '' || !Number.isFinite(a) || !Number.isFinite(b)) return false;
            return rule.op === 'gt' ? a > b : a < b;
        }
    }
}

/** The colour of the first rule the node satisfies, or `undefined` when none does. */
export function nodeRuleColor(rules: readonly NodeRule[] | undefined, node: G6Node['data']): string | undefined {
    return rules?.find((r) => nodeRuleMatches(r, node))?.color;
}

/** A rule's one-line reading, e.g. `msisdn · risk is greater than 5`. */
export function describeNodeRule(rule: NodeRule): string {
    const op = NODE_RULE_OPS.find((o) => o.value === rule.op)?.label ?? rule.op;
    return `${rule.kind ? rule.kind + ' · ' : ''}${rule.attr} ${op} ${rule.value}`;
}
