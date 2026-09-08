#!/usr/bin/env node
/**
 * Doc-link guard — every relative markdown link in a CURRENT doc must resolve to something on disk.
 *
 * WHY THIS EXISTS. `tools/` polices vocabulary, secrets, dependencies, coverage and the BACKLOG §2 tally —
 * and nothing at all policed links, in a doc set of ~450 markdown files carrying ~2,100 links, 1,055 of
 * which point into `docs/okf/` alone. DOCS-LINKS-1 was therefore a MANUAL sweep, which is the tell: a
 * hand-audited invariant is one that silently rots between audits.
 *
 * It had rotted. The first run of this guard found 14 broken links in the current tier, in four flavours,
 * none of them visible to a reader:
 *   - five in `okf/backend/engine/db-layer.md` pointing at `inspecto-engine/` for classes a module split
 *     had moved to `inspecto-ops/` (DbObjectStore, DbLinkStore, DbNoteStore, PostgresStateStoreTest), plus
 *     ObjectType, which moved package inside its own module;
 *   - one naming a class that has never existed under that name — `StreamingPluginBatchStrategy` for what
 *     the code calls `StreamingPluginIngestStrategy`;
 *   - one in `okf/frontend/features/geo-map.md` still pointing into `superpower/` at a plan archived
 *     2026-09-06 — link rot created BY the doc lifecycle's own archival step;
 *   - **seven in `.claude/skills/*&#47;SKILL.md`**, all off by one directory level (`../../docs/…` from
 *     `.claude/skills/<name>/`, which resolves to the non-existent `.claude/docs/`). Those seven are the
 *     worst of the set: a skill that tells an agent to read PROJECT_NOTES / EDITIONS / BRANCHING, pointing
 *     at nothing. Nobody would see a 404 — the guidance just quietly is not there.
 *
 * WHAT IT CHECKS
 *   1. Every inline markdown link `[text](target)` — images `![alt](src)` included — in a scanned file
 *      whose target is repo-relative resolves to an existing file or directory. An `#anchor` suffix is
 *      stripped before resolving.
 *   2. Emptiness floors (MIN_FILES / MIN_LINKS): a run that parses almost nothing FAILS instead of passing
 *      green. This repo's recurring failure mode is a measurement that quietly covered nothing.
 *
 * WHAT IT DELIBERATELY DOES NOT CHECK
 *   - **`docs/archived-documents/**` as a SOURCE.** CLAUDE.md defines that tier as never maintained, and
 *     DOCS-LINKS-1 settled that its internal links stay broken BY POLICY (544 of them today). Links
 *     pointing *into* the archive from a current doc ARE checked — the archive is allowed to rot, but a
 *     current doc is not allowed to point at nothing. ⚠ That exemption is PRINTED on every run, pass or
 *     fail: a guard's scope is a silent exemption, and this repo has been bitten by an unstated one three
 *     times (most recently a "repo-wide" coverage figure that omitted a tenth of the code).
 *   - External URLs (http/https/mailto/tel/ftp/data) — reachability is a network concern, not a repo one.
 *   - Anchor targets. Whether `#some-heading` exists inside the target file is a separate, noisier check;
 *     the target FILE existing is the failure that actually loses information.
 *   - Reference-style definitions (`[label]: target`) and raw `<a href>`. Neither occurs anywhere in the
 *     real corpus — every apparent hit is inside `.claude/worktrees/**` node_modules, which is skipped.
 *   - **Anything inside a fenced block (``` or ~~~).** A markdown link in a code fence is QUOTED TEXT,
 *     not a live link: it is another document being shown, and it must stay byte-exact to be useful.
 *     `check-vocabulary.mjs` strips fences for the same reason. Added 2026-09-08, when the
 *     docs-consolidation adjudications landed under `docs/superpower/design/` — 158 KB of verbatim
 *     `OLD:`/`NEW:` excerpts whose relative links resolve from the QUOTED file's directory, not the
 *     quoting one. 25 of the 26 hits they produced were exactly that; the 1 that was NOT in a fence was
 *     a real break and is fixed. Rewriting the quoted 25 would have destroyed what makes them appliable.
 *     ⚠ This narrows the guard, so it was falsified both ways: a broken link inside a fence is ignored,
 *     the same link one line outside the fence still fails.
 *
 * Pure Node, no dependencies, ~instant. Run by `.github/workflows/ci.yml` and `.githooks/pre-push`.
 */

import { readdirSync, readFileSync, existsSync } from 'node:fs';
import { join, dirname, resolve, sep } from 'node:path';

/** Trees scanned for links. Root-level `*.md` (CLAUDE.md, README) is added separately. */
const ROOTS = ['docs', 'compliance', '.claude'];

/** Never walked: build output and the git worktrees, which contain whole second copies of the repo. */
const SKIP_DIRS = new Set(['node_modules', '.git', 'worktrees', 'dist', 'target', 'graphify-out']);

/** The archive is exempt AS A SOURCE only — see the header. Kept as a prefix so it reads at the call site. */
const ARCHIVE_PREFIX = 'docs/archived-documents/';

/**
 * Emptiness floors. Today: 451 files, ~2,070 repo-relative links. These are set well below that on
 * purpose — the docs-consolidation plan may retire the archive tier (223 files), and a floor tuned to
 * today's total would then fail a legitimate shrink. ⚠ Lower them only alongside a real, deliberate
 * shrink, never to make a red build green.
 */
const MIN_FILES = 150;
const MIN_LINKS = 500;

/** `[text](target)`, tolerating a `"title"` suffix. Also matches the `[alt](src)` of an image. */
const LINK = /\[[^\]]*\]\(\s*([^)\s]+?)(?:\s+"[^"]*")?\s*\)/g;

const EXTERNAL = /^(https?:|mailto:|tel:|ftp:|data:|#)/i;

const slash = (p) => p.split(sep).join('/');

function fail(message) {
    console.error(`✗ Doc-link guard: ${message}`);
    process.exit(1);
}

function collect(dir, out) {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const p = join(dir, entry.name);
        if (entry.isDirectory()) {
            if (SKIP_DIRS.has(entry.name)) continue;
            collect(p, out);
        } else if (entry.name.endsWith('.md')) {
            out.push(p);
        }
    }
}

const files = [];
for (const root of ROOTS) if (existsSync(root)) collect(root, files);
for (const name of readdirSync('.')) if (name.endsWith('.md')) files.push(name);

if (files.length < MIN_FILES) {
    fail(
        `only ${files.length} markdown file(s) found, below the floor of ${MIN_FILES}. Either the doc ` +
            `trees moved (fix ROOTS) or this is being run from the wrong directory — a link check over ` +
            `nothing is the bug this floor exists for.`,
    );
}

let checked = 0;
let archiveSourceBroken = 0;
const broken = [];

for (const file of files) {
    const rel = slash(file);
    const fromArchive = rel.startsWith(ARCHIVE_PREFIX);
    const lines = readFileSync(file, 'utf8').split('\n');

    let inFence = false;
    lines.forEach((line, i) => {
        const trimmed = line.trimStart();
        if (trimmed.startsWith('```') || trimmed.startsWith('~~~')) {
            inFence = !inFence;
            return;
        }
        if (inFence) return; // quoted text, not a link — see the header
        let m;
        LINK.lastIndex = 0;
        while ((m = LINK.exec(line))) {
            const raw = m[1];
            if (EXTERNAL.test(raw)) continue;
            const target = raw.split('#')[0];
            if (!target) continue; // a bare `#anchor` — same-page, nothing to resolve

            if (!fromArchive) checked++;

            let abs;
            try {
                abs = resolve(dirname(file), decodeURIComponent(target));
            } catch {
                abs = resolve(dirname(file), target);
            }
            if (existsSync(abs)) continue;

            if (fromArchive) archiveSourceBroken++;
            else broken.push({ file: rel, line: i + 1, target: raw });
        }
    });
}

if (checked < MIN_LINKS) {
    fail(
        `only ${checked} repo-relative link(s) parsed out of ${files.length} file(s), below the floor of ` +
            `${MIN_LINKS}. The link pattern has almost certainly stopped matching — fix the parser rather ` +
            `than the floor.`,
    );
}

// The exemption is stated on every run, pass or fail. An unprinted scope is an unaudited one.
const scopeNote =
    `scope: ${files.length} file(s) under ${ROOTS.join(', ')} + root *.md; ` +
    `${archiveSourceBroken} broken link(s) inside ${ARCHIVE_PREFIX}** IGNORED (never-maintained tier, ` +
    `CLAUDE.md doc-lifecycle §3 — links pointing INTO it are still checked)`;

if (broken.length) {
    console.error(`✗ Doc-link guard: ${broken.length} broken link(s) in current docs\n`);
    for (const b of broken) {
        console.error(`  ${b.file}:${b.line}  ->  ${b.target}`);
    }
    console.error(`\n  ${scopeNote}`);
    console.error(
        `\n  Fix the LINK, not this guard. A target that moved needs its new path; a target that was ` +
            `\n  archived needs the ${ARCHIVE_PREFIX}… path; a target that is gone needs the link removed.`,
    );
    process.exit(1);
}

console.log(`✓ Doc-link guard: ${checked} repo-relative link(s) in current docs all resolve — ${scopeNote}.`);
