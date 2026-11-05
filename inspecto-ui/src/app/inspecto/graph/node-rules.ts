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
    /**
     * LA-REFERENCE-DETAILS-MORE-1: the Reference (its Dataset id) whose detail column `attr` names. Absent = `attr` is a
     * key of the node's own `attrs`. A Reference rule reads the value from {@link NodeRuleDetails}, never from `attrs`.
     */
    reference?: string;
    attr: string;
    op: NodeRuleOp;
    value: string;
    color: string;
}

/**
 * A Reference's detail values per drawn node: Reference (Dataset id) → node id → detail column → value, as the masked
 * `POST /inv/reference-rows` answered them. Held for colouring only; never saved with a view.
 */
export type NodeRuleDetails = Record<string, Record<string, Record<string, string>>>;

/** The prefix of a masked value (an exploration alias, `MaskTokens.TOKEN_PREFIX`): never coloured by. */
export const MASKED_VALUE_PREFIX = 'masked:';

/**
 * Whether one node satisfies one rule. A node without the attribute never matches (not even `is not`). A Reference rule
 * reads the node's row from `details` (`detailsOf(reference)` = that Reference's values for this node); a node the
 * Reference has no row for, or whose value is masked, never matches.
 */
export function nodeRuleMatches(
    rule: NodeRule,
    node: G6Node['data'],
    detailsOf?: (reference: string) => Record<string, string> | undefined,
): boolean {
    if (rule.kind && rule.kind !== node.kind) return false;
    const raw = rule.reference ? detailsOf?.(rule.reference)?.[rule.attr] : node.attrs?.[rule.attr];
    if (raw === undefined || raw === null) return false;
    if (rule.reference && raw.startsWith(MASKED_VALUE_PREFIX)) return false;
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
export function nodeRuleColor(
    rules: readonly NodeRule[] | undefined,
    node: G6Node['data'],
    detailsOf?: (reference: string) => Record<string, string> | undefined,
): string | undefined {
    return rules?.find((r) => nodeRuleMatches(r, node, detailsOf))?.color;
}

/** A rule's one-line reading, e.g. `msisdn · risk is greater than 5` or `segment (in accounts) equals retail`. */
export function describeNodeRule(rule: NodeRule): string {
    const op = NODE_RULE_OPS.find((o) => o.value === rule.op)?.label ?? rule.op;
    const attr = rule.reference ? `${rule.attr} (in ${rule.reference})` : rule.attr;
    return `${rule.kind ? rule.kind + ' · ' : ''}${attr} ${op} ${rule.value}`;
}
