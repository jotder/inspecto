#!/usr/bin/env node
// regroup-modules — the scripted, dry-runnable DIRECTORY regroup of the Maven modules (MODULE-REORG-D-MR2; plan
// docs/superpower/module-architecture-reorg-plan.md §8 D-MR2, operator decision 2026-10-07: directories ONLY, artifactIds UNCHANGED).
//
//   node tools/regroup-modules.mjs                # dry-run (default): prints the table and what each rewrite WOULD change; writes NOTHING
//   node tools/regroup-modules.mjs --dry-run      # same
//   node tools/regroup-modules.mjs --apply        # refuses unless `git status --porcelain` is empty AND the branch is master
//
// --apply makes two commits (the plan's recipe):
//   commit 1 (Phase A)  pure `git mv <dir> <group>/<dir>` + the root pom's <module> paths. NO aggregator poms: a group
//                       directory with a pom would add a Maven level (a parent chain, a second <relativePath> hop, a new
//                       reactor node per group) for a layout that is only for human navigation; plain <module>features/inspecto-ops</module>
//                       entries need none of that and keep every artifactId, every parent reference and every Maven coordinate as is.
//   commit 2 (Phase B)  every path rewrite, each a NAMED function below (rewriteChildPomRelativePath, rewriteGitignore, ...) that
//                       reports a count per file class and is idempotent (a re-run changes nothing).
// Phase C only PRINTS the verification commands: this script never runs Maven.
//
// The `inspecto` module (the processor/app module) STAYS at the repo root: it is where package.ps1 lives, it is `inspecto/target/...` in CI
// and the launchers, and its `../pom.xml`/`../spaces` relative paths stay valid. The dry-run prints the blast radius of moving it.
//
// Refusal rule: a match the script cannot classify with certainty is PRINTED as an ambiguity and left alone, never guessed.

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { posix } from 'node:path';
import { fileURLToPath } from 'node:url';
import { reactorModuleDirs } from './reactor-modules.mjs';

/** THE TABLE (data). group -> module directories, decided from each module's META-INF/inspecto/module.toon buildRole + offeringRole. */
export const TABLE = {
    spi: ['inspecto-audit-spi', 'inspecto-auth-spi', 'inspecto-http-spi'],                                   // buildRole contract
    platform: ['inspecto-api', 'inspecto-util', 'inspecto-config', 'inspecto-sql', 'inspecto-etl', 'inspecto-event', 'inspecto-workflow',
               'inspecto-acquire', 'inspecto-entity-store', 'inspecto-engine'],                              // buildRole foundation|platform, offeringRole base
    features: ['inspecto-agent', 'inspecto-backup', 'inspecto-entity-list', 'inspecto-exchange', 'inspecto-intelligence', 'inspecto-observability',
               'inspecto-ops', 'inspecto-reconciliation', 'inspecto-scoring'],                               // implementation + offeringRole optional
    la: ['inspecto-la-core', 'inspecto-la-api', 'inspecto-la-graph', 'inspecto-la-storage', 'inspecto-la-store-pg', 'inspecto-geo-link'], // the Link Analysis family (geo-link is its bridge)
    providers: ['inspecto-agent-hosted', 'inspecto-connectors', 'inspecto-connectors-kafka', 'inspecto-demo-auth', 'inspecto-geo-country',
                'inspecto-notify-channels', 'inspecto-oidc', 'inspecto-policy', 'inspecto-secrets', 'inspecto-telecom-asn1',
                'asn-parser'],                                                                               // implementation + offeringRole provider; asn-parser = nested reactor, moves as a unit
};
/** Top-level directories that deliberately do NOT move. `inspecto` = the product module (see header). */
export const STAYS_AT_ROOT = ['inspecto'];

const BINARY_EXT = /\.(png|jpe?g|gif|ico|zip|7z|gz|tgz|jar|class|xlsx|xls|parquet|duckdb|db|pdf|woff2?|ttf|eot|bin|dat|so|dll|exe|wasm|mp4|mov)$/i;
const SKIP_FILES = /(^|\/)(package-lock\.json|yarn\.lock|pnpm-lock\.yaml)$|^compliance\/evidence\/route-gating\.md$/;   // route-gating.md is GENERATED: regenerate it, never rewrite it
/** Guard tests whose fixtures are SYNTHETIC layouts (they assert on `features/inspecto-ops`-style data, not on the real tree): never rewritten. Tests that edit REAL files (check-demo-auth-isolation.test.mjs) DO get rewritten, in lock-step with the file they edit. */
const FIXTURE_FILES = new Set(['tools/check-module-architecture.test.mjs', 'tools/reactor-modules.mjs']);   // only their EXACT patches run: reactor-modules.mjs is path-agnostic by design (alias matched by SUFFIX)
const ARCHIVE = 'docs/archived-documents/', PLANS = 'docs/superpower/';

// ───────────────────────── table helpers ─────────────────────────
export const buildMoves = (table = TABLE) => new Map(Object.entries(table).flatMap(([g, ds]) => ds.map((d) => [d, g])));
export const mapPath = (p, moves) => { const s = p.split('/')[0]; return moves.has(s) ? `${moves.get(s)}/${p}` : p; };
export const unmapPath = (p, moves) => {
    const [g, s] = p.split('/');
    return moves.get(s) === g && p.startsWith(`${g}/${s}`) ? p.slice(g.length + 1) : p;
};
/** The module unit (top-level dir) a NEW path sits in, when that unit moved. */
const movedUnit = (np, moves) => { const [g, s] = np.split('/'); return moves.get(s) === g ? s : null; };

export function fileClass(np) {
    if (np === 'pom.xml') return 'root-pom';
    if (np.endsWith('/pom.xml')) return 'child-pom';
    if (np === '.gitignore') return 'gitignore';
    if (np.startsWith('.github/')) return 'workflow';
    if (np.startsWith('.claude/')) return 'claude';
    if (np.startsWith('tools/')) return 'tools';
    if (/^(scripts|deploy|dev-infra|\.githooks)\//.test(np) || /\.(ps1|sh|bat|cmd)$/.test(np)) return 'scripts';
    if (np.startsWith('compliance/')) return 'compliance';
    if (np.endsWith('.md')) return np.startsWith(PLANS) ? 'docs-plans' : 'docs';
    if (np.endsWith('.java')) return 'java';
    return 'other';
}

// ───────────────────────── context ─────────────────────────
/** moves: Map dir->group; exists(newRepoPath): file or dir in the NEW tree; artifactId(dir): the pom artifactId of a module dir. */
export function makeCtx({ moves, newPaths, artifactIds = new Map() }) {
    const names = [...moves.keys()].sort((a, b) => b.length - a.length);
    const alt = names.map((n) => n.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|');
    const exists = new Set(['', '.']);
    for (const p of newPaths) { let d = p; exists.add(d); while (d.includes('/')) { d = d.slice(0, d.lastIndexOf('/')); exists.add(d); } }
    const roots = new Set([...newPaths].map((p) => p.split('/')[0]).filter((s) => !moves.has(s) && ![...new Set(moves.values())].includes(s)));
    return {
        moves, names, alt, exists: (p) => exists.has(p), roots, artifactIds,
        // a module dir token at a PATH position (followed by a separator), not already inside another path
        reGeneric: new RegExp(`(?:(?<![\\w./\\\\:@~-])|(?<=(?<![\\w.])\\.[/\\\\]))(${alt})(?=[/\\\\])`, 'g'),
        reVar: new RegExp(`((?:\\$\\{?[A-Za-z_][\\w.]*\\}?|\\}\\}|\\$\\([^)]*\\))[/\\\\])(${alt})(?=[/\\\\])`, 'g'),
        reRel: new RegExp(`(?<![\\w.])((?:\\.\\.[/\\\\])+)(${alt})(?=[/\\\\])`, 'g'),
    };
}

const R = (text, count, notes = []) => ({ text, count, notes });
const lineOf = (text, i) => text.slice(0, i).split('\n').length;

// ───────────────────────── rewrites (each pure: (text, file, ctx) -> {text, count, notes}) ─────────────────────────
// file = { np, op, cls }  (np = path in the regrouped tree, op = path before the regroup)

/** Phase A. Root pom: every <module>X</module> naming a moved dir (default list, all profiles) gets its group prefix. */
export function rewriteRootPomModules(text, file, ctx) {
    if (file.np !== 'pom.xml') return R(text, 0);
    let n = 0;
    const out = text.replace(/<module>(\s*)([^<\s]+)(\s*)<\/module>/g, (m, a, p, b) => {
        const q = mapPath(p, ctx.moves);
        if (q === p) return m;
        n++; return `<module>${a}${q}${b}</module>`;
    });
    return R(out, n);
}

/** Resolve one relative target of a file at (np; written at op) -> new relative target, null = unchanged, undefined = unresolvable. */
export function relinkTarget(t, file, ctx) {
    if (!t || /^([a-z][a-z0-9+.-]*:|\/\/|#|\/)/i.test(t)) return null;
    const cut = t.search(/[#?]/), p = cut < 0 ? t : t.slice(0, cut), suffix = cut < 0 ? '' : t.slice(cut);
    if (!p) return null;
    const nd = posix.dirname(file.np), od = posix.dirname(file.op);
    if (ctx.exists(posix.normalize(`${nd}/${p}`).replace(/\/$/, ''))) return null;           // already valid where the file now sits
    const c2 = posix.normalize(`${od}/${p}`).replace(/\/$/, '');
    if (c2.startsWith('..')) return undefined;
    const m = mapPath(c2, ctx.moves);
    if (!ctx.exists(m)) return undefined;
    let rel = posix.relative(nd, m) || '.';
    if (p.endsWith('/') && !rel.endsWith('/')) rel += '/';
    if (p.startsWith('./') && !rel.startsWith('.')) rel = `./${rel}`;
    return rel === p ? null : rel + suffix;
}

/** Child poms: <relativePath>../pom.xml</relativePath> -> computed from the module's actual new depth (nested asn aggregator: unchanged, moves as a unit). */
export function rewriteChildPomRelativePath(text, file, ctx) {
    if (file.cls !== 'child-pom') return R(text, 0);
    let n = 0; const notes = [];
    const out = text.replace(/<relativePath>([^<]+)<\/relativePath>/g, (m, t) => {
        const r = relinkTarget(t.trim(), file, ctx);
        if (r === undefined) { notes.push(`${file.np}: relativePath ${t} resolves to nothing`); return m; }
        if (r === null) return m;
        n++; return `<relativePath>${r}</relativePath>`;
    });
    return R(out, n, notes);
}

/** .gitignore: anchored `/inspecto-xxx/...` (and `!/inspecto-xxx/...`) lines get the group prefix. Per-dir (not `**\/target/`): exact, and keeps the existing negations meaningful. */
export function rewriteGitignore(text, file, ctx) {
    if (file.cls !== 'gitignore') return R(text, 0);
    let n = 0;
    const out = text.replace(new RegExp(`^(!?)/(${ctx.alt})(?=/)`, 'gm'), (m, bang, d) => { n++; return `${bang}/${ctx.moves.get(d)}/${d}`; });
    return R(out, n);
}

/** `dir: 'providers/inspecto-oidc'` (tools/bundle-modules.mjs and its twins). */
export function rewriteDirEntries(text, file, ctx) {
    if (!/^(tools|scripts)\//.test(file.np)) return R(text, 0);
    let n = 0;
    const out = text.replace(new RegExp(`(\\bdir:\\s*)(['"])(${ctx.alt})\\2`, 'g'), (m, k, q, d) => { n++; return `${k}${q}${ctx.moves.get(d)}/${d}${q}`; });
    return R(out, n);
}

/** Quoted dir-name path ARGUMENTS: join(ROOT, 'platform', 'inspecto-engine', 'pom.xml') -> join(ROOT, 'platform', 'inspecto-engine', ...); PowerShell Join-Path $r 'providers\inspecto-oidc' -> 'providers\inspecto-oidc'. */
export function rewriteQuotedPathArgs(text, file, ctx) {
    if (!['tools', 'scripts', 'workflow', 'claude'].includes(file.cls)) return R(text, 0);
    let n = 0;
    let out = text.replace(new RegExp(`(\\b(?:join|resolve)\\((?:[^()\\n]*?,\\s*)?)'(${ctx.alt})'(?=\\s*[,)])`, 'g'),
        (m, pre, d) => { if (pre.endsWith(`'${ctx.moves.get(d)}', `)) return m; n++; return `${pre}'${ctx.moves.get(d)}', '${d}'`; });
    out = out.replace(new RegExp(`(Join-Path\\s+\\S+\\s+)'(${ctx.alt})'`, 'g'), (m, pre, d) => { n++; return `${pre}'${ctx.moves.get(d)}\\${d}'`; });
    return R(out, n);
}

const plToken = (tok, ctx) => {
    const m = /^([!?-]*)(.+)$/.exec(tok); if (!m) return tok;
    return ctx.moves.has(m[2]) && ctx.artifactIds.has(m[2]) ? `${m[1]}:${ctx.artifactIds.get(m[2])}` : tok;
};
/** Maven `-pl a,b` / `--projects a,b` (and a quoted list that is wholly module tokens, e.g. package.ps1's $modules): a bare module token -> `:artifactId`. */
export function rewriteMavenProjectLists(text, file, ctx) {
    if (file.cls === 'docs-plans') return R(text, 0);                 // in-flight plans are not maintained by rewrites
    let n = 0;
    let out = text.replace(/(-pl|--projects)([ =]+)(["']?)([^\s"'`)]+)/g, (m, f, sp, q, list) => {
        const toks = list.split(','), mapped = toks.map((t) => plToken(t, ctx));
        const c = mapped.filter((t, i) => t !== toks[i]).length;
        if (!c) return m; n += c; return `${f}${sp}${q}${mapped.join(',')}`;
    });
    if (['tools', 'scripts', 'workflow'].includes(file.cls))          // a quoted comma list that is wholly module tokens (also an unterminated fragment: tests quote the head of one)
        out = out.replace(/(["'])((?:[\w.:!-]+,)+[\w.:!-]*)/g, (m, q, list) => {
            const toks = list.split(',').filter(Boolean);
            if (!toks.every((t) => ctx.moves.has(t.replace(/^[!?-]+/, '')) || t === 'inspecto' || t.startsWith(':'))) return m;
            const mapped = toks.map((t) => plToken(t, ctx)), c = mapped.filter((t, i) => t !== toks[i]).length;
            if (!c) return m; n += c; return `${q}${mapped.join(',')}${list.endsWith(',') ? ',' : ''}`;
        });
    return R(out, n);
}

/** `cd features/inspecto-ops` / `working-directory: features/inspecto-ops` in scripts, workflows, docs. */
export function rewriteDirContexts(text, file, ctx) {
    let n = 0;
    const out = text.replace(new RegExp(`(\\bcd\\s+|working-directory:\\s*)(["']?)(${ctx.alt})(?![\\w/\\\\.-])`, 'g'),
        (m, k, q, d) => { n++; return `${k}${q}${ctx.moves.get(d)}/${d}`; });
    return R(out, n);
}

/** Markdown links `[t](target)` and reference defs `[t]: target`: every relative target recomputed from the file's NEW location (the target and/or the file moved). Fenced blocks are quoted text: skipped. */
export function rewriteMarkdownLinks(text, file, ctx) {
    if (!file.np.endsWith('.md') || file.np.startsWith(ARCHIVE)) return R(text, 0);
    let n = 0; const notes = [];
    const fix = (t, at) => {
        const r = relinkTarget(t, file, ctx);
        if (r === undefined) { notes.push(`${file.np}:${lineOf(text, at)} link ${t} (unresolvable before and after)`); return t; }
        if (r === null) return t;
        n++; return r;
    };
    const lines = text.split('\n'); let fence = false, off = 0;
    const out = lines.map((ln) => {
        const start = off; off += ln.length + 1;
        if (/^\s*(```|~~~)/.test(ln)) { fence = !fence; return ln; }
        if (fence) return ln;
        let l = ln.replace(/\]\(\s*(<?)([^)\s>]+)(>?)((?:\s+"[^"]*")?\s*)\)/g, (m, a, t, b, tail, i) => `](${a}${fix(t, start + i)}${b}${tail})`);
        l = l.replace(/^(\s{0,3}\[[^\]]+\]:\s*)(<?)(\S+?)(>?)(\s.*)?$/, (m, k, a, t, b, tail = '') => `${k}${a}${fix(t, start)}${b}${tail}`);
        return l;
    });
    return R(out.join('\n'), n, notes);
}

/** Relative `../inspecto-xxx/` TEXT citations (not links) from a file that did NOT move: insert the group. From a file that moved: refused (ambiguity). */
export function rewriteRelativeCitations(text, file, ctx) {
    if (file.np.startsWith(ARCHIVE)) return R(text, 0);
    const moved = movedUnit(file.np, ctx.moves) != null;
    let n = 0; const notes = [];
    const out = text.replace(ctx.reRel, (m, up, d, i) => {
        if (moved) { notes.push(`${file.np}:${lineOf(text, i)} relative ${m} inside a moved module - depth AND group change, left as is`); return m; }
        n++; return `${up}${ctx.moves.get(d)}/${d}`;
    });
    return R(out, n, notes);
}

/** The generic one: a module dir token at a path position (`features/inspecto-ops/src/...`, `providers\inspecto-oidc\target`, `$ROOT/features/inspecto-ops/`) -> `group/` + token, with the separator the text already uses. */
export function rewriteModulePaths(text, file, ctx) {
    if (file.np.startsWith(ARCHIVE) || file.cls === 'docs-plans' || file.cls === 'gitignore' || file.cls === 'root-pom') return R(text, 0);
    let n = 0; const notes = [];
    const put = (d, sep) => { n++; return `${ctx.moves.get(d)}${sep}${d}`; };
    const code = /\.(mjs|js|ts)$/.test(file.np);
    // `/inspecto-la-core reaches la/inspecto-la-storage/` is a JS regex literal ending in a slash, not a path
    const inRegex = (i) => code && /(?:^|[(,=:!&|?;])\s*\/(?![/*])[^/]*$/.test(text.slice(text.lastIndexOf('\n', i - 1) + 1, i));
    let out = text.replace(ctx.reGeneric, (m, d, i) => (inRegex(i) ? (notes.push(`${file.np}:${lineOf(text, i)} ${d}/ inside a regex literal - left as is`), m) : put(d, text[i + d.length])));
    out = out.replace(ctx.reVar, (m, pre, d, i, whole) => `${pre}${put(d, whole[i + pre.length + d.length])}`);
    return R(out, n, notes);
}

/** Java: cwd-relative repo paths in code of a MOVED module gain one `..` (surefire runs in the module dir); `../<moved module>` gains its group everywhere. */
export function rewriteJavaRelativePaths(text, file, ctx) {
    if (file.cls !== 'java' || file.np.startsWith('providers/asn-parser/') || file.np.startsWith('providers/asn-parser/')) return R(text, 0);
    const moved = movedUnit(file.np, ctx.moves) != null;
    let n = 0; const notes = [];
    const grp = (d) => ctx.moves.get(d);
    let out = text.replace(/\b(Path\.of|Paths\.get)\(\s*"\.\."\s*(\)|,\s*"([^"]*)")/g, (m, fn, rest, nxt) => {
        if (rest === ')') { if (!moved) return m; n++; return `${fn}("..", "..")`; }
        if (ctx.moves.has(nxt)) { n++; return moved ? `${fn}("..", "..", "${grp(nxt)}", "${nxt}"` : `${fn}("..", "${grp(nxt)}", "${nxt}"`; }
        if (moved && ctx.roots.has(nxt)) { n++; return `${fn}("..", "..", "${nxt}"`; }
        return m;
    });
    out = out.replace(/"\.\.\/([\w.-]+)(?=["/])/g, (m, nxt) => {
        if (ctx.moves.has(nxt)) { n++; return moved ? `"../../${grp(nxt)}/${nxt}` : `"../${grp(nxt)}/${nxt}`; }
        if (moved && ctx.roots.has(nxt)) { n++; return `"../../${nxt}`; }
        return m;
    });
    if (moved && /\/src\/test\//.test(file.np)) for (const m of out.matchAll(/user\.dir|getParent\(\)\.getParent\(\)\.getParent\(\)/g)) notes.push(`${file.np}:${lineOf(out, m.index)} ${m[0]} - cwd/ancestor walk, check by hand`);
    return R(out, n, notes);
}

/** Exact-text patches for code that SCANS the top level / treats an artifactId as a directory instead of naming a path
 *  (refused, and printed, if the anchor is gone and the patch is not already in). Each was found by running the real guards on a regrouped scratch copy. */
const REACTOR_DIRS_JS = "reactorModuleDirs((p) => (existsSync(join(ROOT, p)) ? readFileSync(join(ROOT, p), 'utf8') : null))";
export const EXACT_PATCHES = [
    {
        file: 'tools/check-doc-counts.mjs',
        why: 'javaMainFiles() scanned only top-level inspecto* dirs: after the regroup it would silently count ONE module (asn-* modules were never in the set: keep the basename filter)',
        from: "    for (const mod of readdirSync(ROOT)) {\n        if (!mod.startsWith('inspecto')) continue;\n",
        to: `    for (const mod of ${REACTOR_DIRS_JS}) {\n        if (!mod.split('/').pop().startsWith('inspecto')) continue;\n`,
        imports: [{ after: "import { trackedPaths } from './tracked-paths.mjs';\n", add: "import { reactorModuleDirs } from './reactor-modules.mjs';\n" }],
    },
    {
        file: 'tools/check-module-deps.mjs',
        why: 'readPom(root, artifactId) joined the artifactId as a directory',
        from: "function readPom(root, module) {\n    const p = join(root, module, 'pom.xml');\n",
        to: "function readPom(root, module) {\n    const dirs = [...reactorModuleDirs((q) => (existsSync(join(root, q)) ? readFileSync(join(root, q), 'utf8') : null))];\n" +
            "    const p = join(root, dirs.find((d) => d === module || d.endsWith(`/${module}`)) ?? module, 'pom.xml');\n",
        imports: [{ after: "import { existsSync, readFileSync } from 'node:fs';\n", add: "import { reactorModuleDirs } from './reactor-modules.mjs';\n" }],
    },
    {
        file: 'tools/reactor-modules.mjs',
        why: 'LABEL_ALIAS was keyed on the full dir: match it by suffix so both the real (providers/asn-parser/asn-decoders) and the synthetic layout label as asn-parser',
        from: "if (d) return LABEL_ALIAS[d] ?? d.slice(d.lastIndexOf('/') + 1);",
        to: "if (d) return Object.entries(LABEL_ALIAS).find(([k]) => d === k || d.endsWith(`/${k}`))?.[1] ?? d.slice(d.lastIndexOf('/') + 1);",
    },
    {
        file: 'tools/check-module-architecture.test.mjs',
        why: "the registries() fixture is keyed on the registry file's REAL path",
        from: "'spi/inspecto-auth-spi/src/main/java/com/gamma/control/CapabilityManifest.java': 'new Entry",
        to: "'spi/inspecto-auth-spi/src/main/java/com/gamma/control/CapabilityManifest.java': 'new Entry",
    },
    {
        file: 'tools/check-sbom-modules.mjs',
        why: "package.ps1's $modules now names `:artifactId` tokens, the generator's `dir` is a path",
        from: 'const expected = set(editionOnlyModules(edition).map((m) => m.dir));',
        to: 'const expected = set(editionOnlyModules(edition).map((m) => `:${m.artifactId}`));',
    },
    {
        file: 'tools/run-backend.ps1',
        why: '`$dir = $artifact` treated an artifactId as a directory (UNTESTED here: it runs only against a built tree)',
        from: 'else { $artifact }',
        to: 'else { $f = Get-ChildItem -Path $repo -Directory -Depth 1 -Filter $artifact | Select-Object -First 1; if ($f) { [IO.Path]::GetRelativePath($repo, $f.FullName) } else { $artifact } }',
    },
];
export function applyExactPatches(text, file) {
    let n = 0; const notes = [];
    for (const p of EXACT_PATCHES.filter((x) => x.file === file.np)) {
        if (text.includes(p.to)) continue;
        if (!text.includes(p.from)) { notes.push(`${file.np}: exact patch anchor not found (${p.why}) - fix by hand`); continue; }
        text = text.replace(p.from, () => p.to); n++;
        for (const im of p.imports ?? []) if (!text.includes(im.add)) text = text.replace(im.after, () => im.after + im.add);
    }
    return R(text, n, notes);
}

/** PowerShell helper calls that hand a module to `mvn -pl`: tools/bench-duckdb.ps1 `Invoke-Mvn 'inspecto-engine' ...`. */
export function rewriteInvokeMvnArgs(text, file, ctx) {
    if (!file.np.endsWith('.ps1')) return R(text, 0);
    let n = 0;
    const out = text.replace(new RegExp(`(Invoke-Mvn\\s+)'(${ctx.alt})'`, 'g'), (m, k, d) => (ctx.artifactIds.has(d) ? (n++, `${k}':${ctx.artifactIds.get(d)}'`) : m));
    return R(out, n);
}

/** Phase B rewrite order matters only in that links/relative citations run before the generic path pass. */
export const PHASE_B = [rewriteChildPomRelativePath, rewriteGitignore, rewriteDirEntries, rewriteQuotedPathArgs, rewriteMavenProjectLists,
    rewriteDirContexts, rewriteMarkdownLinks, rewriteRelativeCitations, rewriteModulePaths, rewriteJavaRelativePaths, rewriteInvokeMvnArgs, applyExactPatches];
export const PHASE_A = [rewriteRootPomModules];

/** Report-only detectors: forms the rewrites deliberately do not touch. */
export function detectAmbiguities(text, file, ctx) {
    const out = [];
    if (file.np.startsWith(ARCHIVE)) return out;
    const push = (kind, i, m) => out.push({ kind, where: `${file.np}:${lineOf(text, i)}`, text: m });
    for (const m of text.matchAll(new RegExp(`(?<=[/\\\\])(${ctx.alt})(?=[/\\\\])`, 'g'))) {
        const pre = text.slice(Math.max(0, m.index - 40), m.index);
        if (/(^|[^\w])(spi|platform|features|la|providers)[/\\]$/.test(pre) || /gamma[/\\]inspector[/\\]$/.test(pre) || /:\/\//.test(pre)) continue;
        if (/\.\.[/\\]$/.test(pre)) continue;                         // handled (or refused) by rewriteRelativeCitations
        push('path-after-unknown-prefix', m.index, `${pre.slice(-25)}${m[0]}`);
    }
    for (const m of text.matchAll(/(?<![\w.])inspecto-\*[/\\]|\*\*[/\\]inspecto-/g)) push('glob-over-module-dirs', m.index, m[0]);
    if (['tools', 'scripts', 'workflow', 'claude'].includes(file.cls))
        for (const m of text.matchAll(new RegExp(`(['"])(${ctx.alt})\\1`, 'g'))) push('quoted-bare-module-token', m.index, m[0]);
    return out;
}

// ───────────────────────── git + file plumbing ─────────────────────────
const git = (root, ...a) => execFileSync('git', a, { cwd: root, encoding: 'utf8', maxBuffer: 1 << 28 });
const trackedFiles = (root) => git(root, 'ls-files', '-z').split('\0').filter(Boolean);

/** Text content or null (binary, huge, or not byte-exact utf8). */
function readText(root, p) {
    if (BINARY_EXT.test(p)) return null;
    let buf; try { buf = readFileSync(join(root, p)); } catch { return null; }
    if (buf.length > 3 << 20 || buf.subarray(0, 8192).includes(0)) return null;
    const t = buf.toString('utf8');
    return Buffer.from(t, 'utf8').equals(buf) ? t : null;
}

export function coverage(root, moves) {
    const tracked = trackedFiles(root);
    const read = (p) => (existsSync(join(root, p)) ? readFileSync(join(root, p), 'utf8') : null);
    // the module UNIT of a path: its top-level dir, or - once regrouped - the dir under its group
    const unitOf = (path) => { const [g, s2] = path.split('/'); return moves.get(s2) === g ? s2 : g; };
    const units = new Set([...reactorModuleDirs(read)].map(unitOf));
    for (const f of tracked) if (f.endsWith('META-INF/inspecto/module.toon')) units.add(unitOf(f));
    const uncovered = [...units].filter((u) => !moves.has(u) && !STAYS_AT_ROOT.includes(u)).sort();
    const missing = [...moves].filter(([d, g]) => !tracked.some((f) => f.startsWith(`${d}/`) || f.startsWith(`${g}/${d}/`))).map(([d]) => d);
    return { units, uncovered, missing };
}

function artifactIdsOf(root, tracked, moves) {
    const ids = new Map();
    for (const d of moves.keys()) {
        const pom = [`${d}/pom.xml`, `${moves.get(d)}/${d}/pom.xml`].find((p) => tracked.includes(p));
        const t = pom && readText(root, pom);
        const m = t && /<artifactId>([^<]+)<\/artifactId>/.exec(t.replace(/<parent>[\s\S]*?<\/parent>/, ''));
        if (m) ids.set(d, m[1].trim());
    }
    return ids;
}

/**
 * Compute (and in apply mode write) one phase. entries: [{np, op}] — np is where the file is when the phase reads it
 * (dry-run: the VIRTUAL new path; apply: its real one), op the path before the regroup. Returns {stats, changes, notes, ambiguities}.
 */
function runPhase(root, rewrites, entries, ctx, { write, detect, readNew }) {
    const stats = {}, changes = new Map(), notes = [], ambiguities = [];
    for (const e of entries) {
        if (SKIP_FILES.test(e.np)) continue;
        const text = readText(root, readNew ? e.np : e.op);
        if (text == null) continue;
        const file = { ...e, cls: fileClass(e.np) };
        let cur = text;
        for (const rw of FIXTURE_FILES.has(e.np) ? rewrites.filter((x) => x === applyExactPatches) : rewrites) {
            const r = rw(cur, file, ctx);
            if (r.count) { const s = ((stats[rw.name] ??= {})[file.cls] ??= { files: 0, occ: 0 }); s.files++; s.occ += r.count; }
            notes.push(...r.notes); cur = r.text;
        }
        if (detect) ambiguities.push(...detectAmbiguities(cur, file, ctx));
        if (cur !== text) { changes.set(e.np, cur); if (write) writeFileSync(join(root, e.np), cur); }
    }
    return { stats, changes, notes, ambiguities };
}

function printStats(title, stats, log = console.log) {
    log(`\n${title}`);
    const rows = Object.entries(stats).flatMap(([fn, byCls]) => Object.entries(byCls).map(([c, s]) => [fn, c, s.files, s.occ]));
    if (!rows.length) return log('  (nothing to change)');
    for (const [fn, c, f, o] of rows) log(`  ${fn.padEnd(32)} ${c.padEnd(11)} ${String(f).padStart(5)} file(s) ${String(o).padStart(6)} occurrence(s)`);
}

export function verificationCommands() {
    return [
        'Phase C - verification (this script never runs Maven; run these by hand, one at a time):',
        '  mvn -o -B -Pedition-enterprise -DskipTests clean package                  # the WHOLE reactor builds from the new layout',
        '  mvn -o -B -Pedition-standard validate ; mvn -o -B -Pedition-professional validate ; mvn -o -B -Pedition-preview validate ; mvn -o -B validate   # every edition resolves',
        '  node tools/route-gating-report.mjs                                       # regenerate compliance/evidence/route-gating.md (its source paths moved), then --check',
        '  every no-build guard of .github/workflows/ci.yml:  node tools/check-vocabulary.mjs ; check-doc-links ; check-doc-citations ; check-doc-counts ; check-bundle-doc-links ;',
        '      check-authgate-coverage ; check-module-deps (+ --test) ; check-module-architecture (+ --test) ; check-offerings (+ --test) ; check-sbom-modules ; check-demo-auth-isolation ;',
        '      check-launchers ; check-bundle-platform ; check-native-licences ; check-bundle-spaces ; check-seed-paths ; check-safety-roots-declared ; check-backlog-homes ; check-nul-bytes ; check-secrets',
        '  mvn -o -B -pl :inspecto -am -Dtest=CapabilityManifestTest,ModuleManifestGuardTest,ConfigWriteFunnelTest,ImportLoaderInventoryTest -Dsurefire.failIfNoSpecifiedTests=false test',
        '  (ReactorModules.java is exercised by those three; the path-relative tests run in their own modules: see the dry-run Java section)',
        '  GAUNTLET: mvn -o clean test -Pedition-enterprise   # the full gate, ~10 min',
        '  pwsh -File inspecto/package.ps1 -Edition Professional                    # the bundle still assembles (every Join-Path moved)',
    ];
}

// ───────────────────────── main ─────────────────────────
export function run({ root, apply = false, table = TABLE, quiet = false, force = false } = {}) {
    const log = quiet ? () => {} : console.log;
    const moves = buildMoves(table);
    const cov = coverage(root, moves);
    log(`TABLE (${moves.size} module dirs; \`${STAYS_AT_ROOT.join(', ')}\` stays at the root):`);
    for (const [g, ds] of Object.entries(table)) log(`  ${g}/  ${ds.join(', ')}`);
    log(`\nModules not covered by the table: ${cov.uncovered.length ? cov.uncovered.join(', ') : 'none'}`);
    if (cov.missing.length) log(`Table entries with no tracked files here: ${cov.missing.join(', ')}`);
    if (cov.uncovered.length) throw new Error(`refusing: module(s) not covered by the table: ${cov.uncovered.join(', ')}`);

    if (apply && !force) {
        const dirty = git(root, 'status', '--porcelain').trim();
        const branch = git(root, 'rev-parse', '--abbrev-ref', 'HEAD').trim();
        if (dirty) throw new Error('refusing --apply: `git status --porcelain` is not empty (commit or stash first)');
        if (branch !== 'master') throw new Error(`refusing --apply: branch is ${branch}, not master`);
    }

    const tracked = trackedFiles(root);
    const alreadyMoved = tracked.some((f) => [...moves].some(([d, g]) => f.startsWith(`${g}/${d}/`)));
    const newOf = (p) => (alreadyMoved ? p : mapPath(p, moves));
    const entriesNow = tracked.map((p) => ({ np: newOf(p), op: alreadyMoved ? unmapPath(p, moves) : p }));
    const ctx = makeCtx({ moves, newPaths: entriesNow.map((e) => e.np), artifactIds: artifactIdsOf(root, tracked, moves) });
    const result = { cov, moves };

    // dry-run reads the OLD paths; after a real move, Phase B reads the new ones
    const a = runPhase(root, PHASE_A, entriesNow.map((e) => ({ np: e.np, op: e.op })), ctx, { write: false, detect: false, readNew: alreadyMoved });
    const b = runPhase(root, PHASE_B, entriesNow, ctx, { write: false, detect: !apply, readNew: alreadyMoved });
    result.a = a; result.b = b;
    if (!apply) {
        printStats('PHASE A (commit 1): root pom <module> paths', a.stats, log);
        printStats('PHASE B (commit 2): path rewrites, by rewrite function and file class', b.stats, log);
        log(`\n  files that would change: ${a.changes.size} (A) + ${b.changes.size} (B); directory moves: ${[...moves.keys()].length}`);
        const byKind = {};
        for (const x of b.ambiguities) (byKind[x.kind] ??= []).push(x);
        log('\nAMBIGUITIES - printed, never guessed (first 6 per kind):');
        for (const [k, xs] of Object.entries(byKind)) { log(`  ${k}: ${xs.length}`); for (const x of xs.slice(0, 6)) log(`     ${x.where}  ${x.text.replace(/\s+/g, ' ').slice(0, 90)}`); }
        const notes = b.notes.filter(Boolean); log(`\nREFUSED / UNRESOLVED notes: ${notes.length}`); for (const n of notes.slice(0, 25)) log(`  ${n}`);
        const inspectoRefs = tracked.filter((p) => { const t = readText(root, p); return t && /(?<![\w./\\:@~-])inspecto[/\\]/.test(t) && !p.startsWith(ARCHIVE); });
        log(`\n\`inspecto\` -> platform/inspecto would touch ~${inspectoRefs.length} tracked files naming \`inspecto/\` as a path (package.ps1, CI jar paths, launchers, JaCoCo, every doc);`);
        log('  RECOMMENDATION: keep `inspecto` at the repo root (product module; its ../pom.xml and ../spaces paths stay valid; no `-pl`/path churn in CI).');
        log(''); verificationCommands().forEach((l) => log(l));
        return result;
    }

    // ── apply ──
    const present = [...moves].filter(([d]) => existsSync(join(root, d)) && !existsSync(join(root, moves.get(d), d)));
    for (const [d, g] of present) { mkdirSync(join(root, g), { recursive: true }); git(root, 'mv', d, `${g}/${d}`); }
    const aReal = runPhase(root, PHASE_A, trackedFiles(root).map((p) => ({ np: p, op: unmapPath(p, moves) })), ctx, { write: true, readNew: true });
    const msg = (s) => `${s}\n\nCo-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`;
    const commit = (m) => { if (git(root, 'status', '--porcelain').trim()) { git(root, 'add', '-A'); git(root, 'commit', '-q', '-m', msg(m)); return true; } return false; };
    result.commit1 = commit('refactor(arch): MODULE-REORG-1 D-MR2 - regroup module directories (pure git mv + root pom <module> paths; artifactIds unchanged)');
    const entriesB = trackedFiles(root).map((p) => ({ np: p, op: unmapPath(p, moves) }));
    const ctxB = makeCtx({ moves, newPaths: entriesB.map((e) => e.np), artifactIds: ctx.artifactIds });
    const bReal = runPhase(root, PHASE_B, entriesB, ctxB, { write: true, readNew: true });
    result.commit2 = commit('refactor(arch): MODULE-REORG-1 D-MR2 - rewrite module-path references after the directory regroup');
    result.a = aReal; result.b = bReal;
    printStats('APPLIED phase B', bReal.stats, log);
    log(`\ncommit 1: ${result.commit1 ? 'made' : 'nothing to commit (already regrouped)'}; commit 2: ${result.commit2 ? 'made' : 'nothing to commit'}`);
    log(''); verificationCommands().forEach((l) => log(l));
    return result;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
    try { run({ root, apply: process.argv.includes('--apply') }); }
    catch (e) { console.error(`regroup-modules: ${e.message}`); process.exit(1); }
}
