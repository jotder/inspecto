// A CASE-EXACT index of what a fresh checkout of this repository actually contains.
//
// WHY THIS EXISTS (`LINKGUARD-CASE-1`, 2026-09-14). `check-doc-links.mjs` and
// `check-doc-citations.mjs` both resolved a cited path with `fs.existsSync` against the WORKING TREE.
// That answers a different question from the one they are asking, and it is wrong in two directions
// at once — both of which shipped, and both of which are invisible on the machine that introduces them:
//
//   1. CASE. The sandbox filesystem is case-insensitive, so `docs/okf/INDEX.md` answered TRUE here and
//      FALSE on the Linux runner. Five OKF pages linked exactly that, and every local run was green for
//      as long as they existed. A guard that cannot fail on the machine you run it on is not a guard.
//   2. UNTRACKED FILES. `existsSync` is equally happy with a file that is gitignored, generated, or
//      simply not committed. `.claude/sessions/snapshot.md` is written by a hook on every stop, so it
//      is present in every working tree and in no checkout — and two instruction files cited it.
//
// Both are the same root cause: the working tree is not the artifact. `git ls-files` is, because it is
// exactly what a checkout would produce — no build output, no ignored files, and the case the index
// actually records.
//
// ⛔ THE CASE-EXACTNESS IS LOAD-BEARING AND EASY TO LOSE. `path.resolve` is pure string work: it never
// touches the filesystem, so the case in the path it returns is the case the DOCUMENT AUTHOR wrote.
// That is precisely what has to be compared. Anything that normalises case — lowercasing keys, a
// case-insensitive Map, `realpathSync`, or an `existsSync` fallback "just in case" — restores the bug
// while leaving the tests green on Linux, where nothing would notice. `resolveInsideRepo` is written to
// keep the author's spelling intact end to end, and `caseOnlyMismatch` exists so a failure can SAY that
// the only thing wrong is capitalisation.

import { execFileSync } from 'node:child_process';
import { relative, sep } from 'node:path';

/**
 * Every tracked path, repo-relative with `/` separators, in the case git records.
 *
 * @throws {Error} when git cannot be reached, or returns implausibly few paths — a caller must treat
 *   that as CANNOT-RUN (exit 2), never as "nothing is broken". An index built from an empty listing
 *   would report every link in the repository dead, which reads as a catastrophe rather than a
 *   misconfiguration and is the more dangerous failure of the two.
 */
export function trackedPaths() {
    let out;
    try {
        out = execFileSync('git', ['ls-files', '-z'], { encoding: 'utf8', maxBuffer: 1 << 28 });
    } catch (e) {
        throw new Error(`cannot run \`git ls-files\`: ${e.message}`);
    }
    // -z: NUL-separated, so a path containing a space, a quote or a newline arrives intact and git does
    // not apply its quoting/escaping. A newline-split listing mangles exactly the paths most likely to
    // be mis-cited.
    const paths = out.split('\0').filter(Boolean);
    if (paths.length < 1000) {
        throw new Error(
            `\`git ls-files\` returned only ${paths.length} path(s); this repository has thousands. ` +
                `Something is wrong with the checkout — refusing to vouch for anything.`,
        );
    }
    return paths;
}

/**
 * A case-exact membership test over a checkout of this repository.
 *
 * @param {string} root absolute path of the repository root
 * @returns {{exists: (abs: string) => boolean, caseOnlyMismatch: (abs: string) => string | null,
 *            fileCount: number, dirCount: number}}
 */
export function checkoutIndex(root) {
    const files = new Set(trackedPaths());

    // Directories are implied, never listed: `git ls-files` emits files only, and a doc may legitimately
    // link a directory (`docs/okf/`). Every ancestor of every tracked file is a real directory in a
    // checkout, and nothing else is.
    const dirs = new Set();
    for (const f of files) {
        let cut = f.lastIndexOf('/');
        while (cut > 0) {
            const dir = f.slice(0, cut);
            if (dirs.has(dir)) break; // this ancestor chain is already recorded
            dirs.add(dir);
            cut = dir.lastIndexOf('/');
        }
    }

    // Lowercase -> the one true spelling, used ONLY to explain a failure. ⛔ Never consulted by
    // `exists`: that is the whole defect. A folded collision (two tracked paths differing only by case)
    // is left as whichever came last — the message is a hint, and the assertion is `exists`.
    const folded = new Map();
    for (const p of [...files, ...dirs]) folded.set(p.toLowerCase(), p);

    /** Repo-relative POSIX form, or null when the path is not inside the repository at all. */
    function resolveInsideRepo(abs) {
        const rel = relative(root, abs);
        // '' is the root itself; a leading '..' means it escaped the repo, and a checkout has nothing
        // there. ⚠ `path.relative` compares the ROOT case-insensitively on Windows but builds its
        // result from the `to` argument, so the author's spelling of the tail survives — which is the
        // property this whole module depends on.
        if (!rel || rel.startsWith('..')) return null;
        return rel.split(sep).join('/');
    }

    return {
        exists(abs) {
            const rel = resolveInsideRepo(abs);
            return rel !== null && (files.has(rel) || dirs.has(rel));
        },
        /** The correctly-cased path when capitalisation is the ONLY thing wrong, else null. */
        caseOnlyMismatch(abs) {
            const rel = resolveInsideRepo(abs);
            if (rel === null || files.has(rel) || dirs.has(rel)) return null;
            return folded.get(rel.toLowerCase()) ?? null;
        },
        fileCount: files.size,
        dirCount: dirs.size,
    };
}
