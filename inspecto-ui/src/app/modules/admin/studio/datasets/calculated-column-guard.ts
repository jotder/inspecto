import GUARD_CONTRACT from 'app/inspecto/contracts/expression-guard.contract.json';

/**
 * Client-side mirror of the backend `com.gamma.query.ExpressionGuard` (DAT-5,
 * `docs/superpower/calculated-columns-design.md`) — the same three rules (closed token alphabet,
 * keyword deny-set, function-call whitelist). **Not authoritative**: this exists purely so an author
 * gets instant inline feedback instead of a save-then-query round trip; the server re-validates at
 * query time (`DatasetRelation.withCalculated`) and is the only enforcement that matters for safety.
 *
 * ⚠ The six vocabularies and the token alphabet are **read from the committed contract**, not retyped
 * here. They used to be a hand-kept copy of the Java sets, which is the one shape this repo has been
 * bitten by repeatedly (`DERIVED_USE` drifted three times before it was pinned). Importing the JSON
 * makes drift on THIS side structurally impossible; `ExpressionGuardContractTest` asserts the Java
 * side against the same file, so a divergence is a reviewable diff on the contract rather than a
 * silently over-permissive form that only fails after the author hits save.
 *
 * ⛔ Never inline one of these lists back into this file — the contract is the source.
 */

const MAX_LENGTH = GUARD_CONTRACT.maxLength;

/** Mirrors `ExpressionGuard.TOKEN` — sticky so each match must start exactly at `lastIndex`. */
const TOKEN = new RegExp(GUARD_CONTRACT.token, 'y');
const IDENT = /^[A-Za-z_][A-Za-z0-9_]*$/;

/** Statement/structure keywords that must never appear, even as bare identifiers. */
const DENIED = new Set<string>(GUARD_CONTRACT.denied);

/** Flow keywords of a CASE expression + predicate glue — allowed as bare words (never called). */
const FLOW_KEYWORDS = new Set<string>(GUARD_CONTRACT.flowKeywords);

/** The scalar functions a calculated column may call. */
const FUNCTIONS = new Set<string>(GUARD_CONTRACT.functions);

/** Functions callable ONLY as a window call — must be immediately followed by `OVER (…)`. The windowed
 *  aggregates are deliberately absent from FUNCTIONS, so used bare they stay rejected. */
const WINDOW_FUNCTIONS = new Set<string>(GUARD_CONTRACT.windowFunctions);

/** Bare words allowed only inside an `OVER (…)` clause (never call targets). */
const WINDOW_KEYWORDS = new Set<string>(GUARD_CONTRACT.windowKeywords);

/** The type names a `cast(x AS type)` may target. */
const TYPES = new Set<string>(GUARD_CONTRACT.types);

/** Validate a calculated column's expression fragment; returns an error message, or `null` if clean. */
export function checkCalculatedExpr(expr: string): string | null {
    if (!expr || !expr.trim()) return 'Expression is empty.';
    const e = expr.trim();
    if (e.length > MAX_LENGTH) return `Expression exceeds ${MAX_LENGTH} characters.`;

    const noStrings = e.replace(/'(?:[^']|'')*'/g, "''");
    if (noStrings.includes('--') || noStrings.includes('/*') || noStrings.includes('*/'))
        return 'Comment sequences (--, /*, */) are not allowed.';

    let pos = 0;
    let parens = 0;
    let prevWord: string | null = null;
    let afterAs = false;
    let expectOver = false; // a window call just closed — the next token MUST be 'over'
    let expectOverParen = false; // 'over' just seen — the next token MUST be '('
    let windowOpenDepth = -1; // parens depth inside a window fn's arg list (to detect its close)
    let windowSpecDepth = -1; // parens depth inside the OVER (…) clause (window keywords legal here)
    while (pos < e.length) {
        TOKEN.lastIndex = pos;
        const m = TOKEN.exec(e);
        if (!m || m.index !== pos) return `Illegal character at: '${e.slice(pos, pos + 12)}'`;
        const tok = m[0];
        pos += tok.length;
        if (!tok.trim()) continue;

        // A window call must be followed by OVER (…) — gated before anything else so a bare
        // aggregate/window call (no OVER) can never slip through as a normal expression.
        if (expectOver) {
            if (!(IDENT.test(tok) && tok.toLowerCase() === 'over'))
                return 'A window function must be followed by an OVER (…) clause.';
            expectOver = false;
            expectOverParen = true;
            prevWord = null;
            continue;
        }
        if (expectOverParen) {
            if (tok !== '(') return "OVER must be followed by '('.";
            expectOverParen = false;
            parens++;
            windowSpecDepth = parens;
            prevWord = null;
            continue;
        }

        if (tok === '(') {
            const windowCall = prevWord !== null && WINDOW_FUNCTIONS.has(prevWord);
            if (prevWord !== null && !FUNCTIONS.has(prevWord) && !windowCall)
                return `Function '${prevWord}' is not allowed (allowed: ${[...FUNCTIONS].sort().join(', ')}).`;
            parens++;
            if (windowCall) windowOpenDepth = parens;
            prevWord = null;
            continue;
        }
        if (tok === ')') {
            const closingWindowCall = windowOpenDepth !== -1 && parens === windowOpenDepth;
            const closingWindowSpec = windowSpecDepth !== -1 && parens === windowSpecDepth;
            parens--;
            if (parens < 0) return "Unbalanced ')' in expression.";
            prevWord = null;
            if (closingWindowCall) {
                expectOver = true;
                windowOpenDepth = -1;
            }
            if (closingWindowSpec) windowSpecDepth = -1;
            continue;
        }

        if (IDENT.test(tok)) {
            const w = tok.toLowerCase();
            if (DENIED.has(w)) return `'${tok}' is not allowed in a calculated column.`;
            if (afterAs) {
                if (!TYPES.has(w))
                    return `Cast target type '${tok}' is not allowed (allowed: ${[...TYPES].sort().join(', ')}).`;
                afterAs = false;
                prevWord = null;
                continue;
            }
            if (w === 'as') {
                afterAs = true;
                prevWord = null;
                continue;
            }
            // window keywords are only meaningful inside the OVER (…) clause; elsewhere they lex as
            // ordinary column refs (a DuckDB bind error at worst — never a structural break).
            if (windowSpecDepth !== -1 && parens >= windowSpecDepth && WINDOW_KEYWORDS.has(w)) {
                prevWord = null;
                continue;
            }
            // a flow keyword is never a call target; anything else may be a column ref OR a function
            // name — resolved when the next token is '('
            prevWord = FLOW_KEYWORDS.has(w) ? null : w;
            continue;
        }
        afterAs = false;
        prevWord = null; // literals/operators break any identifier-then-paren pairing
    }
    if (parens !== 0) return "Unbalanced '(' in expression.";
    if (afterAs) return 'Dangling AS in expression.';
    if (expectOver) return 'A window function must be followed by an OVER (…) clause.';
    if (expectOverParen) return "OVER must be followed by '('.";
    return null;
}

/** SAFE_IDENT check for a calculated column's name (mirrors `DatasetRelation.SAFE_IDENT`). */
export function checkCalculatedName(name: string): string | null {
    const n = (name ?? '').trim();
    if (!n) return 'Name is required.';
    if (!IDENT.test(n)) return 'Letters, digits, underscore only; must start with a letter or underscore.';
    return null;
}
