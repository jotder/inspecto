#!/usr/bin/env node
// Committed-secret guard (SEC-INCIDENT-1 remediation — docs/BACKLOG.md §5).
//
// Born from the 2026-07-25 incident: five OAuth client secrets sat in
// inspecto-ui/src/environments/*.ts and were pushed to a PUBLIC remote for six weeks. Removing
// them from HEAD remediated nothing (history keeps the values), so the only durable win left is
// making REINTRODUCTION fail the build instead of shipping quietly.
//
// WHAT IT FLAGS: a secret-ish key assigned to a long literal string — `clientSecret: '<32 hex>'`,
// `password = "…"`, `client_secret=…` in a URL or form body. That is the exact shape of the
// incident, and it is what a config-file copy/paste looks like.
//
// WHAT IT DELIBERATELY DOES NOT FLAG, because a noisy guard gets disabled (the lesson recorded in
// tools/check-vocabulary.mjs):
//   - Empty values, `${ENV:…}`, `process.env.X`, `<placeholder>`, changeme/example/dummy/test —
//     the sanctioned ways to NOT hold a secret. `${ENV:…}` is the bundle contract (BACKLOG D2).
//   - Prose. Only assignment syntax matches, so a comment saying "no client secret here" is fine,
//     as are the BACKLOG rows that name the leaked keys.
//   - Short values (< MIN_SECRET_LEN). Real credentials are long and high-entropy; test fixtures
//     using `password: "test"` are not the risk this guard exists for.
//   - `token`-suffixed keys. `tokenEndpoint`/`tokenUrl` are URLs, and after D15 they are REQUIRED
//     config — flagging them would fire on correct deployments.
//
// TWO MODES, and the second exists because the first hands out a FALSE GREEN:
//   - default — tracked files as they stand. Catches the incident's shape: a secret living in HEAD.
//   - `--range <git-log-args…>` — the ADDED lines of every commit in a push range. A credential
//     committed and then moved to an env var one commit later never reaches HEAD, so the default
//     mode reports clean while the objects carrying the value go out with the push. That is the
//     likelier accident, because it is what happens when the author NOTICES: the careful response
//     (fix it in the next commit) is precisely the one the tree scan blesses. `.githooks/pre-push`
//     runs both.
// Both modes judge a line through the same `scanLine()`; keep it that way.
//
// Zero dependencies (pure Node). Run via `node tools/check-secrets.mjs`; wired into CI (ci.yml).
// Escape hatch: append `secret-allow` in a comment on the offending line for a justified exception.
//
// NOTE ON BRANCHES: `master` is the ONLY line this guard runs on, and there is no second copy to
// keep in sync. It was master-only until 2026-07-25, then brought forward to `4.x` as well once
// PKCE P0+P1 (`481a68d5`, `89cb3cce`, `8c3a7654`) removed `appClientSecret` from that branch; `4.x`
// was deleted 2026-08-17 (BRANCHING.md §0-A). If a maintenance branch is ever cut again, copy this
// guard to it VERBATIM — a divergence means one branch is guarded by weaker rules than the other,
// which is exactly how the incident recurs.

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative, sep } from 'node:path';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const toPosix = (p) => p.split(sep).join('/');

// Directories never worth scanning: dependencies, build output, generated artifacts, and the
// unmaintained archive. `.claude/worktrees/` is gitignored scratch — it held unversioned copies of
// all five leaked secrets, which is why it is cleaned separately rather than guarded here.
const SKIP_DIRS = new Set([
    'node_modules', '.git', 'target', 'dist', 'out', '.angular', '.mvn',
    'graphify-out', 'worktrees', 'archived-documents', 'coverage',
]);

// `.md` is here because a rotation runbook or an incident plan is exactly where someone pastes a
// live credential into a worked example — the same class as SEC-INCIDENT-1's five OAuth secrets,
// and nothing else in the local loop or CI would see it. It costs no noise: only ASSIGNMENT syntax
// matches, so prose naming a leaked key stays clean, and the whole tracked doc corpus is green.
const EXTS = new Set([
    '.ts', '.js', '.mjs', '.cjs', '.java', '.json', '.yml', '.yaml',
    '.toon', '.properties', '.xml', '.ps1', '.sh', '.bat', '.env', '.md',
]);

const SKIP_FILES = new Set(['package-lock.json', 'tools/check-secrets.mjs']);

// A credential-bearing key. `token` is excluded on purpose (see header).
const SECRET_KEY = '[A-Za-z0-9_.-]*(?:secret|password|passwd|credential|api[_-]?key|access[_-]?key|private[_-]?key)[A-Za-z0-9_.-]*';

// Keys that NAME or LOCATE a credential rather than hold one: `apiKeyRef: 'ANTHROPIC_API_KEY'`,
// `passwordFile: /run/secrets/db`. The whole point of these is to keep the value out of the repo.
const INDIRECT_KEY = /(?:ref|name|env|var|file|path|alias)$/i;

// `key: 'value'` / `key = "value"` / `key: value` (unquoted, e.g. TOON / .properties).
const QUOTED = new RegExp(`\\b(${SECRET_KEY})\\s*[:=]\\s*(['"\`])([^'"\`]*)\\2`, 'i');
// `client_secret=…` in a URL query or form body — no quotes, terminated by & or whitespace.
const URL_PARAM = new RegExp(`\\b(${SECRET_KEY})=([^&\\s'"\`<>]+)`, 'i');

// Values that are explicitly NOT a secret: env indirection, placeholders, obvious fixtures.
const PLACEHOLDER = [
    /^\s*$/,                                  // blank — the sanctioned "left empty" state
    /\$\{/,                                   // ${ENV:…}, ${VAR}, Maven/Spring interpolation
    /^\$[A-Za-z_]/,                           // $VAR
    /^%[A-Za-z_][A-Za-z0-9_]*%$/,             // %HTTPS_KEYSTORE_PASSWORD% (cmd/batch indirection)
    /example/i,                               // AWS's published SigV4 vectors (AKIDEXAMPLE,
                                              // wJalrXUtnFEMI/…EXAMPLEKEY) and doc placeholders
                                              // universally embed "EXAMPLE" by convention
    /x{6,}/i,                                 // sk-xxxxxxxxxxxx — a redacted sample key
    /process\.env|System\.getenv|System\.getProperty|import\.meta\.env/,
    /^<.*>$/,                                 // <your-secret-here>
    /^(?:your|my)[_-]?/i,
    /^(?:changeme|change[_-]?me|placeholder|redacted|masked|example|dummy|sample|fake|none|null|undefined|test|testing|secret|password)$/i,
    /^\*+$/,                                  // ****
    /^x+$/i,                                  // xxxx
    /^(?:TODO|FIXME)/i,
    // A history-rewrite redaction marker: `***REMOVED***` (BFG), `<REDACTED>`, and the marker the
    // 2026-07-26 `--replace-text` purge left in this repo's own rewritten commits. Matched by SHAPE
    // rather than by one literal, so a future purge with a different marker is covered too. Only
    // --range mode ever meets these (they live in history, not at HEAD), and without this rule a
    // range scan fires on every push whose range touches the rewrite — i.e. it would be noise from
    // day one, which is how a guard gets switched off. A real credential does not contain the word
    // REDACTED, nor is it free of lowercase.
    /^[^a-z]*\b(?:REDACTED|REMOVED|PURGED|SCRUBBED)\b[^a-z]*$/,
];

// Real credentials are long. Below this, the false-positive rate dwarfs the signal.
const MIN_SECRET_LEN = 16;

function isPlaceholder(value) {
    return PLACEHOLDER.some((re) => re.test(value));
}

function* walk(dir) {
    let entries;
    try {
        entries = readdirSync(dir);
    } catch {
        return;
    }
    for (const name of entries) {
        const full = join(dir, name);
        let st;
        try {
            st = statSync(full);
        } catch {
            continue;
        }
        if (st.isDirectory()) {
            if (SKIP_DIRS.has(name)) continue;
            yield* walk(full);
        } else {
            yield full;
        }
    }
}

// A COMMITTED-secret guard must scan what is committed. Walking the filesystem also read gitignored
// build output — `inspecto-deploy/ui/chunk-*.js` produced four hits on a clean tree (minified
// `withCredentials`/`apiKey` property assignments), so every shift that built the bundle then met a red
// security gate on its own machine while CI, which has no such directory, stayed green. A guard that
// cries wolf locally is a guard people learn to ignore. Falls back to the filesystem walk outside a
// git checkout (a tarball export), where scanning too much beats scanning nothing.
function* trackedFiles() {
    let listed;
    try {
        listed = execFileSync('git', ['-C', repoRoot, 'ls-files', '-z'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
    } catch {
        yield* walk(repoRoot);
        return;
    }
    for (const rel of listed.split('\0')) {
        if (rel) yield join(repoRoot, rel);
    }
}

// THE one place a line is judged. Both modes call it, so the tree scan and the range scan can never
// drift into guarding by different rules — a divergence here is the same failure as two branches
// with two copies of this file.
function scanLine(text) {
    if (text.includes('secret-allow')) return null;
    for (const re of [QUOTED, URL_PARAM]) {
        const m = text.match(re);
        if (!m) continue;
        const key = m[1];
        const value = re === QUOTED ? m[3] : m[2];
        if (INDIRECT_KEY.test(key)) continue;
        if (isPlaceholder(value) || value.length < MIN_SECRET_LEN) continue;
        return { key, len: value.length };
    }
    return null;
}

// A path worth opening at all: right extension, not under a skipped directory, not this file.
function scannablePath(rel) {
    if (SKIP_FILES.has(rel) || SKIP_FILES.has(rel.slice(rel.lastIndexOf('/') + 1))) return false;
    // walk() skips these by never descending; the tracked-file list has to filter them by path.
    if (rel.split('/').slice(0, -1).some((seg) => SKIP_DIRS.has(seg))) return false;
    const dot = rel.lastIndexOf('.');
    return dot >= 0 && EXTS.has(rel.slice(dot));
}

// DEFAULT MODE — tracked files as they stand. Catches the incident's own shape: a secret living in
// HEAD.
function scanWorkingTree() {
    const found = [];
    for (const file of trackedFiles()) {
        const rel = toPosix(relative(repoRoot, file));
        if (!scannablePath(rel)) continue;
        let lines;
        try {
            lines = readFileSync(file, 'utf8').split(/\r?\n/);
        } catch {
            continue;
        }
        lines.forEach((line, i) => {
            const hit = scanLine(line);
            if (hit) found.push({ rel, line: i + 1, ...hit });
        });
    }
    return found;
}

// --range MODE — the ADDED lines of every commit in a push range.
//
// WHY: the tree scan sees a secret only if it SURVIVES to HEAD. A credential committed and then
// moved to an env var one commit later is invisible to it — and that is the likelier accident,
// because it is exactly what happens when the author notices their own mistake. The objects still
// travel in the push, so the disclosure is identical; the difference is that the tree scan hands
// back a green light, which is worse than no check at all.
//
// Returns null (not []) when the range cannot be read, so the caller can refuse rather than assume.
function scanRange(gitArgs) {
    let out;
    try {
        out = execFileSync('git', ['-C', repoRoot, 'log', '--no-color', '--no-renames', '-p',
            '--unified=0', '--format=@@C@@%H%x09%s', ...gitArgs],
            { encoding: 'utf8', maxBuffer: 512 * 1024 * 1024, stdio: ['ignore', 'pipe', 'ignore'] });
    } catch {
        return null;
    }
    const found = [];
    let sha = null, subject = null, rel = null;
    for (const line of out.split('\n')) {
        if (line.startsWith('@@C@@')) {
            const parts = line.slice(5).split('\t');
            sha = parts[0];
            subject = parts.slice(1).join('\t');
            rel = null;
            continue;
        }
        // `+++ b/<path>` opens a file's hunks; `+++ /dev/null` means a deletion — nothing added.
        if (line.startsWith('+++ b/')) { rel = line.slice(6).trim(); continue; }
        if (line.startsWith('+++ ')) { rel = null; continue; }
        if (!line.startsWith('+')) continue;
        if (!rel || !scannablePath(rel)) continue;
        const hit = scanLine(line.slice(1));
        if (hit) found.push({ rel, sha, subject, ...hit });
    }
    return found;
}

const rangeMode = process.argv[2] === '--range';
const violations = rangeMode ? scanRange(process.argv.slice(3)) : scanWorkingTree();

if (violations === null) {
    console.error('\n✖ Committed-secret guard: could not read the push range.');
    console.error('  Refusing to vouch for a range this guard could not open — push again once');
    console.error('  the range is readable, or state the exception deliberately.');
    process.exit(1);
}

if (violations.length && rangeMode) {
    console.error(`\n✖ Committed-secret guard: ${violations.length} probable secret(s) INSIDE THE PUSH RANGE\n`);
    let lastSha = null;
    for (const v of violations) {
        if (v.sha !== lastSha) {
            console.error(`  ${v.sha.slice(0, 8)}  ${v.subject}`);
            lastSha = v.sha;
        }
        // Never echo the value — CI logs are themselves a disclosure surface.
        console.error(`      ${v.rel}  ${v.key} = <${v.len} chars, not shown>`);
    }
    console.error(`
These values are NOT in your working tree — they were added and then changed or removed by a later
commit in this same push. That is why the file looks clean and this guard still refuses.

⚠ Editing the file does not help. The credential is in the COMMIT OBJECTS you are about to publish,
and a push is not reversible: once the objects reach a public remote, deletion remediates nothing.

Rewrite the range before pushing — \`git rebase -i\` to amend the introducing commit, or
\`git filter-repo --replace-text\` for a wider sweep — then push again.

If the value was ALREADY pushed, rotate it at the issuer. See docs/BACKLOG.md §5 (SEC-INCIDENT-1),
whose five OAuth secrets are the reason this check exists.
`);
    process.exit(1);
}

if (violations.length) {
    console.error(`\n✖ Committed-secret guard: ${violations.length} probable secret(s)\n`);
    for (const v of violations) {
        // Never echo the value — CI logs are themselves a disclosure surface.
        console.error(`  ${v.rel}:${v.line}  ${v.key} = <${v.len} chars, not shown>`);
    }
    console.error(`
A credential must never be committed. Move it to deployment config the code reads at runtime
(\`\${ENV:…}\` / an env var / a secret manager) and leave the checked-in value empty.

A browser bundle CANNOT hold a confidential secret — anything in inspecto-ui/src/environments/ is
public by construction. Use a public PKCE client, or exchange the token server-side.

If this really is not a secret, append \`secret-allow\` on the line with a reason.

If a real secret was already pushed, deletion is NOT remediation — rotate it at the issuer.
See docs/BACKLOG.md §5 (SEC-INCIDENT-1).
`);
    process.exit(1);
}

console.log(rangeMode
    ? '✓ Committed-secret guard: no probable secrets introduced anywhere in the push range.'
    : '✓ Committed-secret guard: no probable secrets in committed source or config.');
