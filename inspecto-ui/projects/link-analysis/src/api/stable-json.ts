/** JSON with object keys sorted, so two queries that mean the same thing share a key. */
export function stableJson(v: unknown): string {
    return JSON.stringify(v, (_k, x: unknown) =>
        x && typeof x === 'object' && !Array.isArray(x)
            ? Object.fromEntries(
                  Object.entries(x as Record<string, unknown>).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)),
              )
            : x,
    );
}

/**
 * Structural equality of two values (every key of either side is compared, so a new field counts by default).
 * Dates compare by time, Maps by entries, Sets by members (order-insensitive); a cycle is NOT equal, never a stack overflow.
 */
export function deepEqual(a: unknown, b: unknown, seen: WeakMap<object, WeakSet<object>> = new WeakMap()): boolean {
    if (Object.is(a, b)) return true;
    if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false;
    if (Object.getPrototypeOf(a) !== Object.getPrototypeOf(b)) return false;
    let pairs = seen.get(a);
    if (pairs?.has(b)) return false; // met this pair again on the way down: a cycle
    if (!pairs) seen.set(a, (pairs = new WeakSet()));
    pairs.add(b);
    try {
        if (a instanceof Date) return a.getTime() === (b as Date).getTime();
        if (a instanceof Map) {
            const mb = b as Map<unknown, unknown>;
            if (a.size !== mb.size) return false;
            for (const [k, v] of a) if (!mb.has(k) || !deepEqual(v, mb.get(k), seen)) return false;
            return true;
        }
        if (a instanceof Set) {
            const sb = b as Set<unknown>;
            if (a.size !== sb.size) return false;
            const rest = [...sb];
            for (const v of a) {
                const i = rest.findIndex((w) => deepEqual(v, w, seen));
                if (i < 0) return false;
                rest.splice(i, 1);
            }
            return true;
        }
        if (Array.isArray(a)) {
            const bb = b as unknown[];
            return a.length === bb.length && a.every((v, i) => deepEqual(v, bb[i], seen));
        }
        const ra = a as Record<string, unknown>;
        const rb = b as Record<string, unknown>;
        for (const k of new Set([...Object.keys(ra), ...Object.keys(rb)]))
            if (!deepEqual(ra[k], rb[k], seen)) return false;
        return true;
    } finally {
        pairs.delete(b); // a repeated NON-cyclic subtree (a diamond) is a different path, not a cycle
    }
}
