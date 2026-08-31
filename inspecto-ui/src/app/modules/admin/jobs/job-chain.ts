/**
 * The `consignment.process` chain: the pure mapping between its two Job params and the ordered rows an
 * author edits (pipeline spec gap 12).
 *
 * A post-sync chain is authored today as **two params the author keeps aligned by counting**:
 * `processor` — one processor id or an ordered comma-separated chain (`mask,rollup,report`) — and
 * `chain_config`, a JSON **array positionally aligned with it**, each element `{ "config": {…} }`.
 * Position N of the array configures position N of the chain, and nothing checks that claim: a
 * mis-counted array silently configures the wrong step.
 *
 * These functions make the pairing structural, and they are deliberately **lossless in both
 * directions**:
 *
 * <ul>
 *   <li>A `chain_config` element carries through {@link ChainRow#element} **verbatim** — only its
 *       `config` value is replaced on the way out. An element that carries keys this editor does not
 *       model keeps them. (The recurring defect in this codebase is an unmodelled key re-seeded via
 *       `JSON.stringify`, or dropped outright.)</li>
 *   <li>⛔ **A surplus `chain_config` element is NEVER dropped to make the two sides line up.** It
 *       becomes a row with a blank id, so the author sees it and decides. {@link chainRowsValid} is
 *       then false and the caller must refuse to save — losing an authored config to tidy up an
 *       off-by-one is the failure this whole surface exists to prevent.</li>
 * </ul>
 */

/** One step of the chain: a processor id and the `chain_config` element that configures it. */
export interface ChainRow {
    /** The `ConsignmentProcessor` id. ⚠ Blank on a row recovered from a surplus config element. */
    id: string;
    /** The step's config object, edited as JSON text. */
    configText: string;
    /**
     * The original `chain_config` element this row came from, verbatim, so unmodelled keys survive a
     * round trip. `undefined` for a row the author just added.
     */
    element?: Record<string, unknown>;
}

/** `chain_config`'s element shape — `config` plus whatever else the element happened to carry. */
type ChainElement = Record<string, unknown> & { config?: unknown };

/** Split a `processor` param into ids. Blank segments are kept: they are an authoring error to show. */
function splitIds(processor: unknown): string[] {
    const raw = String(processor ?? '').trim();
    if (!raw) return [];
    return raw.split(',').map((s) => s.trim());
}

/**
 * `chain_config` as an element array. Tolerant on purpose — this value has been hand-edited as free
 * text, so anything that is not a JSON array of objects yields `null`, meaning "leave it alone".
 * A caller that gets `null` must fall back to the raw textarea rather than reinterpret the value.
 */
function parseElements(chainConfig: unknown): ChainElement[] | null {
    let v = chainConfig;
    if (typeof v === 'string') {
        const t = v.trim();
        if (!t) return [];
        try {
            v = JSON.parse(t);
        } catch {
            return null;
        }
    }
    if (v === null || v === undefined) return [];
    if (!Array.isArray(v)) return null;
    if (!v.every((e) => e !== null && typeof e === 'object' && !Array.isArray(e))) return null;
    return v as ChainElement[];
}

/** Pretty JSON for the row's editor. `{}` for an element with no `config` of its own. */
function configTextOf(el: ChainElement | undefined): string {
    const c = el?.config;
    if (c === undefined || c === null) return '{}';
    return JSON.stringify(c, null, 2);
}

/**
 * The two params → ordered rows. Returns `null` when `chain_config` is not an array of objects, i.e.
 * when this editor cannot represent the authored value and the caller must leave the raw field alone.
 *
 * ⚠ The two sides are aligned by POSITION and may disagree in either direction. Both disagreements are
 * represented, never smoothed over: a missing element gives a row with an empty `{}` config; a surplus
 * element gives a row with a **blank id**.
 */
export function parseChain(processor: unknown, chainConfig: unknown): ChainRow[] | null {
    const els = parseElements(chainConfig);
    if (els === null) return null;
    const ids = splitIds(processor);
    const rows: ChainRow[] = ids.map((id, i) => ({ id, configText: configTextOf(els[i]), element: els[i] }));
    // Surplus configs: kept as blank-id rows so nothing authored is discarded silently.
    for (let i = ids.length; i < els.length; i++) {
        rows.push({ id: '', configText: configTextOf(els[i]), element: els[i] });
    }
    return rows;
}

/**
 * Whether a row's config text is a `config` object the engine can actually carry.
 *
 * 🔴 **Scalars only, and that is the SERVER's contract, not a preference here.**
 * `ConsignmentProcessJobType.chainConfigsOf` reads each entry into a `Map<String,String>` via
 * `String.valueOf(v)`, so a nested array or object **saves fine and reaches the processor mangled** —
 * `{"columns":["a"]}` arrives as the string `"[a]"`. The engine never complains; the step just reads
 * nonsense. Refusing it here is the only place an author finds out.
 *
 * ⚠ A `null` value is refused for a sharper reason: that same method builds the map with a null value
 * and then calls `Map.copyOf`, which **throws NPE on nulls** — so a null config value fails the RUN with
 * a stack trace rather than a message. Omitting the key is what the author means anyway.
 */
export function rowConfigError(row: ChainRow): string | null {
    const t = row.configText.trim();
    if (!t) return null; // blank ⇒ no config for this step
    let v: unknown;
    try {
        v = JSON.parse(t);
    } catch {
        return 'not valid JSON';
    }
    if (v === null || typeof v !== 'object' || Array.isArray(v)) return 'must be a JSON object';
    for (const [k, val] of Object.entries(v as Record<string, unknown>)) {
        if (val === null) return `value for '${k}' cannot be null — omit the key instead`;
        if (typeof val === 'object') {
            return `value for '${k}' must be text, a number or true/false — the engine stores every config value as text, so a nested list or object would reach the processor mangled`;
        }
    }
    return null;
}

/**
 * Every reason the chain cannot be saved, in row order. A blank id is one of them — it is how a
 * surplus `chain_config` element surfaces, and saving past it is exactly the data loss
 * {@link parseChain} refuses to perform quietly.
 */
export function chainErrors(rows: ChainRow[]): string[] {
    const out: string[] = [];
    rows.forEach((r, i) => {
        const at = `Step ${i + 1}`;
        if (!r.id.trim()) {
            out.push(
                `${at} has no processor id — it came from a surplus 'chain_config' entry. Name the ` +
                    `processor it configures, or remove the step.`,
            );
        } else if (r.id.includes(',')) {
            out.push(`${at}: a processor id cannot contain a comma — the comma is what separates steps.`);
        }
        const err = rowConfigError(r);
        if (err) out.push(`${at} config ${err}.`);
    });
    return out;
}

/** Whether {@link chainToParams} may be called — no blank ids, no unparseable config. */
export function chainRowsValid(rows: ChainRow[]): boolean {
    return chainErrors(rows).length === 0;
}

/**
 * Ordered rows → the two params, aligned by construction. Each element keeps every key it arrived
 * with; only `config` is rewritten. A step with a blank config keeps `config: {}` rather than losing
 * the element, so the array stays positionally aligned with the chain.
 *
 * ⚠ Callers must check {@link chainRowsValid} first — this function assumes valid rows and would
 * otherwise emit a chain with an empty segment.
 */
export function chainToParams(rows: ChainRow[]): { processor: string; chain_config: unknown[] } {
    return {
        processor: rows.map((r) => r.id.trim()).join(','),
        chain_config: rows.map((r) => {
            const t = r.configText.trim();
            const config = t ? JSON.parse(t) : {};
            return { ...(r.element ?? {}), config };
        }),
    };
}
