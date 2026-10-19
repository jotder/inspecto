#!/usr/bin/env node
/**
 * Doc-flag guard — every `-D<name>` a CURRENT doc mentions must be a system property something actually reads.
 *
 * WHY THIS EXISTS. The link, citation, vocabulary and count guards prove a doc's paths, words and numbers. None
 * of them sees a `-Dinspecto.foo=true` in a run-book, and a flag the code never reads fails SILENTLY at run time:
 * the JVM accepts any `-D`, so an operator following a stale doc gets no error and no effect.
 *
 * ── CHECKS ────────────────────────────────────────────────────────────────────────────────────────
 *
 * Every `-D<name>` token in a current doc (fenced blocks INCLUDED — a run-book's command line is the claim)
 * passes when ONE of these holds, and each pass reason is counted and printed on every run:
 *
 *   A. **READ** — `"<name>"` is a string literal in a tracked Java source (main or test — a test-only switch
 *      such as `-Dkafka.it.bootstrap` is read by its test). Keyed on the LITERAL, not the call shape, because the
 *      names reach System.getProperty / Boolean.getBoolean / Integer.getInteger / Long.getLong through
 *      constants, enums (`OpsStoreFamily("objects.db.url")`) and helper wrappers. A PREFIX helper —
 *      `"auth.oidc." + key` (`OidcConfig.get("issuer")`) — is matched as literal `"x."` + literal `"rest"`.
 *   P. **PWSH** — the token is really a PowerShell switch: `-DemoAuth` / `-Days` is `$DemoAuth` / `$Days`
 *      declared in a tracked `.ps1`. The `-D` shape is coincidence, so it is counted apart.
 *   B. **XML** — `${<name>` appears in a tracked `.xml`: a pom property the reactor reads, or a logback
 *      substitution (`level="${ui.static.log:-INFO}"`).
 *   C. **STANDARD** — the name is a JVM / Maven / Surefire / third-party flag on STANDARD below.
 *   D. **MARKED** — the same line states the flag is planned, absent or retired (ABSENCE_MARKER).
 *
 * ── SCOPE (printed on every run — a silent scope is an exemption) ─────────────────────────────────
 *
 *   - Sources: `docs/**\/*.md` and `compliance/**\/*.md`, MINUS `docs/archived-documents/` (never maintained)
 *     and `docs/superpower/` (in-flight plans legitimately name unbuilt flags).
 *   - Launcher scripts (ps1/cmd/sh) and TS are NOT accepted as readers: a launcher that PASSES `-Dx` to java
 *     proves nothing reads it, and TS cannot read a JVM property.
 *   - A Java literal is evidence, not proof that a property read uses it — the guard catches a flag no Java
 *     source names at all, which is the rot actually seen.
 *   - An ALL-CAPS token (`-DECLARES->`, a graph edge label) is not a flag and is not counted.
 *   - `-D` tokens for made-up names in prose about quoting (`-Dfoo.bar`) are PLACEHOLDERS, listed below.
 *
 * Pure Node, no dependencies. Run by `.github/workflows/ci.yml` (guards job).
 */
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');

/** JVM, Maven, Surefire and third-party flags the repo does not own. Prefix entries end in `.`. */
export const STANDARD = [
    'java.', 'jdk.', 'sun.', 'file.encoding', 'user.', 'os.', 'line.separator', 'javax.', 'jakarta.',
    'maven.', 'surefire.', 'failsafe.', 'test', 'skipTests', 'skipITs', 'it.test', 'jacoco.', 'enforcer.',
    'jetty.', 'https.', 'http.', 'socksProxy', 'networkaddress.', 'log4j', 'logback.', 'org.slf4j.',
    'org.apache.', 'polyglot.', 'native.', 'jna.', 'io.netty.', 'duckdb.', 'gpg.', 'checkstyle.', 'spotless.',
    'dependency-check.', 'exec.', 'archetype', 'groupId', 'artifactId', 'version', 'includes', 'excludes',
    'trimStackTrace', 'newVersion', 'processAllModules', 'generateBackupPoms', 'failIfNoTests',
    'failIfNoSpecifiedTests', 'forkCount', 'argLine', 'reuseForks', 'mdep.', 'idea.', 'skip', 'quickly', 'ai.djl.', 'style.color', 'aether.', 'jline.', 'picocli.', 'awt.', 'apple.',
];

export const ABSENCE_MARKER =
    /planned|not (?:yet )?(?:built|implemented|read|wired)|unbuilt|proposed|no longer exist|does not exist|never existed|\bdeleted\b|\bretired\b|\bremoved?\b|\bgone\b|\bdead\b|no (?:Java )?readers?|zero\W+(?:Java\W+)?readers/i;

/**
 * `-Dname` at a token start (not inside a word, a URL or a `--flag`); the name may carry dots, dashes,
 * underscores but never ENDS in a dot, so a trailing `.*` / `.<id>` / `.{x}` is captured as a family marker.
 */
const FLAG = /(?<![\w/,-])-D([A-Za-z_][\w-]*(?:\.[\w-]+)*)(\.[*<{])?/g;

/** Made-up names in docs that explain `-D` quoting, not claims about a real flag. */
export const PLACEHOLDERS = ['first', 'second', 'foo.bar', 'a.b', 'd.e', 'mdep', 'x', 'name', 'key'];

const MIN_FILES = 150;
const MIN_FLAGS = 20;

export function isStandard(name) {
    return STANDARD.some((s) => (s.endsWith('.') ? name.startsWith(s) : name === s || name.startsWith(s + '.')));
}

/**
 * Mentions in one doc: [{line, name, family, text}]. Trailing punctuation is prose, not name. `-Dingest.retry.*` /
 * `-Dx.<scope>.y` is a FAMILY: it passes when some Java literal starts with `name.`.
 */
export function mentions(text) {
    const out = [];
    text.split('\n').forEach((line, i) => {
        for (const m of line.matchAll(FLAG))
            out.push({ line: i + 1, name: m[1].replace(/[.-]+$/, ''), family: Boolean(m[2]), text: line });
    });
    return out;
}

/** world: { docs: {path: text}, java: {path: text}, xmls: [text], ps1: [text] } → { failures, counts }. */
export function check({ docs, java, xmls, ps1 = [] }) {
    const literals = new Set();
    for (const src of Object.values(java)) for (const m of src.matchAll(/"([A-Za-z_][\w.-]*)"/g)) literals.add(m[1]);
    const all = [...literals];
    const prefixes = all.filter((l) => l.endsWith('.'));
    const isRead = (n, family) => literals.has(n) ||
        (family && all.some((l) => l.startsWith(n + '.'))) ||
        prefixes.some((p) => n.startsWith(p) && literals.has(n.slice(p.length)));
    const switches = new Set();
    for (const src of ps1) for (const m of src.matchAll(/\[(?:switch|string|int|string\[\])\]\s*\$(D\w+)/gi)) switches.add(m[1].toLowerCase());
    const mavenProps = new Set();
    for (const xml of xmls) for (const m of xml.matchAll(/\$\{([\w.-]+)[}:]/g)) mavenProps.add(m[1]);

    const counts = { read: 0, maven: 0, standard: 0, pwsh: 0, placeholder: 0, marked: 0, total: 0 };
    const failures = [];
    for (const [path, text] of Object.entries(docs)) {
        for (const { line, name, family, text: l } of mentions(text)) {
            if (/^[A-Z]+$/.test(name)) continue;   // `origin -DECLARES-> step`: an edge label, not a flag
            counts.total++;
            if (isRead(name, family)) counts.read++;
            else if (mavenProps.has(name)) counts.maven++;
            else if (isStandard(name)) counts.standard++;
            else if (switches.has(('D' + name).toLowerCase())) counts.pwsh++;
            else if (PLACEHOLDERS.includes(name)) counts.placeholder++;
            else if (ABSENCE_MARKER.test(l)) counts.marked++;
            else failures.push(`${path}:${line}  -D${name}`);
        }
    }
    return { failures, counts };
}

function git(...args) {
    return execFileSync('git', args, { cwd: ROOT, encoding: 'utf8', maxBuffer: 1 << 28 }).split('\0').filter(Boolean);
}

export function currentDocs() {
    return git('ls-files', '-z', '--', 'docs/*.md', 'compliance/*.md')
        .filter((p) => !p.startsWith('docs/archived-documents/') && !p.startsWith('docs/superpower/'));
}

function main() {
    const read = (p) => readFileSync(join(ROOT, p), 'utf8');
    const docs = Object.fromEntries(currentDocs().map((p) => [p, read(p)]));
    const java = Object.fromEntries(git('ls-files', '-z', '--', '*.java').map((p) => [p, read(p)]));
    const xmls = git('ls-files', '-z', '--', '*.xml').map(read);
    const ps1 = git('ls-files', '-z', '--', '*.ps1').map(read);
    const { failures, counts } = check({ docs, java, xmls, ps1 });

    console.log(`Doc-flag guard — scope: ${Object.keys(docs).length} current docs (docs/**, compliance/**; ` +
        `archived-documents/ and superpower/ excluded), ${Object.keys(java).length} Java sources (main + test), ` +
        `${xmls.length} XML files. Launcher scripts and TS are not accepted as readers.`);
    console.log(`  ${counts.total} -D mentions: ${counts.read} read in Java, ${counts.maven} XML (pom/logback) properties, ` +
        `${counts.standard} standard (allowlist of ${STANDARD.length}), ${counts.pwsh} PowerShell switches, ` +
        `${counts.placeholder} placeholders, ${counts.marked} marked planned/absent on their line.`);
    if (Object.keys(docs).length < MIN_FILES || counts.total < MIN_FLAGS) {
        console.error(`✗ Doc-flag guard: scope below floor (files ≥ ${MIN_FILES}, mentions ≥ ${MIN_FLAGS}) — ran over nothing?`);
        process.exit(1);
    }
    if (failures.length) {
        console.error(`✗ Doc-flag guard: ${failures.length} -D flag(s) no Java source names. Correct the flag from ` +
            `the code, or state on the line that it is planned/absent:`);
        for (const f of failures) console.error(`  ${f}`);
        process.exit(1);
    }
    console.log('✓ Doc-flag guard: every -D flag in the current docs is read, standard, or marked.');
}

if (process.argv[1] && fileURLToPath(import.meta.url) === join(process.argv[1])) main();
