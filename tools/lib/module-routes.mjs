// Shared by check-doc-counts.mjs (count marker la-routes) and la-demo-smoke.mjs (EXPECTED_LA_ROUTES).
import { readFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

/** Entries of `provides.routes[N]: "GET /x","POST /y"` in a module's module.toon - counted, not read from N. */
export function moduleRoutes(dir) {
    const rel = `${dir}/src/main/resources/META-INF/inspecto/module.toon`;
    const p = join(ROOT, rel);
    if (!existsSync(p)) throw new Error(`derive source is missing: ${rel}`);
    const m = readFileSync(p, 'utf8').match(/^\s*routes\[\d+\]:\s*(.*)$/m);
    if (!m) throw new Error(`${dir}: no provides.routes line`);
    return (m[1].match(/"[^"]*"/g) || []).length;
}

/** The Link Analysis route inventory: /inv (inspecto-la-api) + /geo (inspecto-geo-link). */
export const laRoutes = () => moduleRoutes('la/inspecto-la-api') + moduleRoutes('la/inspecto-geo-link');
