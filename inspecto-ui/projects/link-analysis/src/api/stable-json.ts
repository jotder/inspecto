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
