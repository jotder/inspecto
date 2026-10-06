import { isComplete } from './query-eval';
import { Condition, ConditionGroup, Operator } from './query-types';

/**
 * Framework-free rules for the Condition Language extensions (`negate`, `valueField`, `ignoreCase`,
 * `matches`) — the UI mirror of Java `ConditionTree.validate`, which stays the authority (the server
 * re-validates on save). Keep the operator sets and the length cap identical to the Java side.
 */

/** Longest `matches` pattern accepted (Java `ConditionTree.MAX_PATTERN_LENGTH`). */
export const MAX_PATTERN_LENGTH = 256;

const VALUE_FIELD_OPS: ReadonlySet<Operator> = new Set<Operator>([
    '=',
    '!=',
    '<',
    '<=',
    '>',
    '>=',
    'contains',
    'startsWith',
    'endsWith',
]);
const IGNORE_CASE_OPS: ReadonlySet<Operator> = new Set<Operator>([
    '=',
    '!=',
    'in',
    'contains',
    'startsWith',
    'endsWith',
    'matches',
]);

/** Constructs `java.util.regex` has but DuckDB's RE2 lacks: lookaround, atomic groups, possessive
 *  quantifiers, back-references (same textual check as Java `ConditionTree.NON_RE2`). */
const NON_RE2 = /\(\?(=|!|<=|<!|>|<[A-Za-z])|\\[1-9k]|[*+?}]\+/;

/** May this operator compare against another field's cell (`valueField`)? */
export function supportsValueField(op: Operator): boolean {
    return VALUE_FIELD_OPS.has(op);
}

/** May this operator ignore case (`ignoreCase`)? */
export function supportsIgnoreCase(op: Operator): boolean {
    return IGNORE_CASE_OPS.has(op);
}

/**
 * A plain-language reason the condition would be refused, or `null`. Mirrors Java `validateLeaf`.
 *
 * ⚠ The regular-expression check is NOT equivalent to the server's: `new RegExp` is the browser's
 * ECMAScript dialect, the in-JVM evaluator is `java.util.regex` and the SQL backend is RE2. So an
 * accepted pattern here can still be refused by the server, and (rarely) a Java-valid one — e.g. an
 * inline `(?i)` flag — is flagged here. The message is advice; the server is the gate.
 */
export function validateCondition(c: Condition): string | null {
    if (c.valueField) {
        if (!supportsValueField(c.operator))
            return 'Comparing to another field only works with = ≠ < ≤ > ≥ contains starts with ends with.';
        if (c.value) return 'Set either a value or another field, not both.';
    }
    if (c.ignoreCase && !supportsIgnoreCase(c.operator))
        return 'Ignore case only works with = ≠ in contains starts with ends with matches.';
    if (c.operator === 'matches' && c.value) {
        if (c.value.length > MAX_PATTERN_LENGTH) return `The pattern is longer than ${MAX_PATTERN_LENGTH} characters.`;
        if (NON_RE2.test(c.value))
            return 'The pattern uses lookahead, lookbehind, atomic groups, possessive quantifiers or back-references, which the SQL backend does not support.';
        try {
            new RegExp(c.value);
        } catch (e) {
            return `Not a valid regular expression: ${e instanceof Error ? e.message : String(e)}`;
        }
    }
    return null;
}

const WORDS: Record<Operator, string> = {
    '=': 'equals',
    '!=': 'does not equal',
    '<': 'is less than',
    '<=': 'is at most',
    '>': 'is greater than',
    '>=': 'is at least',
    contains: 'contains',
    startsWith: 'starts with',
    endsWith: 'ends with',
    matches: 'matches pattern',
    in: 'is one of',
    between: 'is between',
    isNull: 'is empty',
    isNotNull: 'is not empty',
};

function describeLeaf(c: Condition): string {
    const word = WORDS[c.operator] ?? c.operator;
    const ic = c.ignoreCase ? ', ignoring case' : '';
    if (c.operator === 'isNull' || c.operator === 'isNotNull') return `${c.field} ${word}`;
    if (c.valueField) return `${c.field} ${word} the value of ${c.valueField}${ic}`;
    if (c.operator === 'between') return `${c.field} ${word} ${c.value} and ${c.value2}`;
    return `${c.field} ${word} ${c.value}${ic}`;
}

/** A readable one-line summary of a group, e.g. `NOT (a equals 1 and b contains x)` — for a screen reader. */
export function describeGroup(g: ConditionGroup): string {
    const parts = g.items
        .filter((it) => it.kind === 'group' || isComplete(it))
        .map((it) => (it.kind === 'group' ? describeGroup(it) : describeLeaf(it)))
        .filter((s) => s.length > 0);
    if (parts.length === 0) return 'no conditions';
    const body = parts.join(g.op === 'AND' ? ' and ' : ' or ');
    return g.negate ? `NOT (${body})` : parts.length > 1 ? `(${body})` : body;
}
