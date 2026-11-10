// The TOON subset the repo's build tools read (module manifests, offerings/, product-map/, products/, apps/, industries/):
// `k: v`, `k[N]: a,b` and `k:` with indented children. No tabular arrays, no `#` lines (TOON has no comments).
// Its own file so tools/bundle-modules.mjs can read manifests without importing tools/check-offerings.mjs, which imports it.

/** Parse the TOON subset used by manifests and offerings: `k: v`, `k[N]: a,b`, `k:` + indented children. */
export function parseToon(text) {
    const lines = text.split(/\r?\n/).filter((l) => l.trim() && l.trim() !== '---');
    const root = {};
    const stack = [{ indent: -1, obj: root }];
    for (const raw of lines) {
        const indent = raw.length - raw.trimStart().length;
        const m = /^([A-Za-z_][\w.]*)(?:\[(\d+)\])?:\s*(.*)$/.exec(raw.trim());
        if (!m) throw new Error(`unparseable line: ${raw}`);
        while (stack.length > 1 && indent <= stack.at(-1).indent) stack.pop();
        const parent = stack.at(-1).obj;
        const [, key, n, val] = m;
        if (n !== undefined) {
            const items = val === '' ? [] : val.split(',').map((s) => s.trim());
            if (items.length !== Number(n)) throw new Error(`${key}[${n}] has ${items.length} items`);
            parent[key] = items;
        } else if (val === '') { const o = {}; parent[key] = o; stack.push({ indent, obj: o }); }
        else parent[key] = val;
    }
    return root;
}
