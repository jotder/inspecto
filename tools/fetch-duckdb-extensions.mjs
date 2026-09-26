#!/usr/bin/env node
// Populate a DuckDB extension cache for EVERY platform a bundle ships, so `package.ps1` has something
// to stage (AIRGAP-EXTENSIONS-CI-1).
//
// WHY THIS EXISTS. `package.ps1` stages extensions **best-effort from a local cache** and prints a yellow
// warning when it finds none — never a build failure, by design, because a developer without a cache must
// still be able to build. Measured 2026-09-14: neither `ci.yml` nor `release.yml` populated such a cache,
// so the release path always took the warning branch and **every released bundle shipped an empty
// `duckdb-extensions/`**. AIRGAP-EXTENSIONS-1 — closed 2026-09-11 to stop `ducklake` needing a network
// INSTALL on an air-gapped install — was therefore never actually delivered by a release. The code half
// was real; the packaging half silently no-opped in the only place that builds a release.
//
// ⛔ WHY NOT `INSTALL <name>` IN CI. A DuckDB INSTALL populates the cache for the RUNNING platform only,
// and `release.yml` runs on ubuntu-latest while the bundle ships windows_amd64 too. Downloading from the
// extension repository is what makes one runner able to stage both.
//
// ⛔ THE EXTENSION LIST IS NOT REPEATED HERE. It is read out of `inspecto/package.ps1` — the script that
// does the staging — so a name added there is fetched here automatically and the two cannot drift. This
// repo has already shipped one defect from a list that existed in two places (see tools/bundle-modules.mjs).
//
//   node tools/fetch-duckdb-extensions.mjs [--out <dir>] [--check] [--only excel,...] [--platform linux_amd64,...]
//
// Writes <dir>/v<version>/<platform>/<name>.duckdb_extension, which is DuckDB's own cache layout and what
// package.ps1's recursive glob expects. Default <dir> is <repo>/.duckdb-extension-cache, one of the
// locations package.ps1 already probes.
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');

function arg(name, dflt) {
    const i = process.argv.indexOf(name);
    return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : dflt;
}

const packagePs1 = join(repoRoot, 'inspecto', 'package.ps1');
const ps1 = readFileSync(packagePs1, 'utf8');

/** Read a PowerShell `@('a', 'b')` array literal by the variable it is assigned to, or by a loop header. */
function ps1Array(pattern, what) {
    const m = pattern.exec(ps1);
    if (!m) {
        console.error(`✖ could not find ${what} in inspecto/package.ps1.`);
        console.error('  This script reads the list FROM that file on purpose, so the two cannot drift.');
        console.error('  If the shape changed, fix the pattern here — do not paste a second copy of the list.');
        process.exit(2);
    }
    return [...m[1].matchAll(/'([^']+)'/g)].map((x) => x[1]);
}

/**
 * Narrow a list read from package.ps1 by a comma-separated flag. ⛔ An unknown value exits 2 naming the
 * known ones: a typo must not shrink the run to nothing and still print ✓ — the silent-no-op this script
 * exists to end. The flag only SELECTS from package.ps1's list; it never adds a name the bundle does not stage.
 */
function narrow(list, flag, what) {
    const want = arg(flag, null);
    if (!want) return list;
    const picked = want.split(',').map((s) => s.trim()).filter(Boolean);
    const unknown = picked.filter((p) => !list.includes(p));
    if (!picked.length || unknown.length) {
        console.error(`✖ ${flag} ${want}: ${unknown.length ? `unknown ${what} ${unknown.join(', ')}` : 'empty'}`
            + ` — package.ps1 stages ${list.join(', ')}.`);
        process.exit(2);
    }
    return list.filter((x) => picked.includes(x));
}

// --only / --platform (D-8, 2026-09-26): CI's test job fetches just linux_amd64/excel (~20 MB, not ~160 MB)
// so PipelineDocumentXlsxTest runs the real workbook write on Linux on every push.
const names = narrow(ps1Array(/\$duckdbExtNames\s*=\s*@\(([^)]*)\)/, '$duckdbExtNames'), '--only', 'extension');
const platforms = narrow(ps1Array(/foreach\s*\(\s*\$plat\s+in\s+@\(([^)]*)\)/, "the platform list (foreach \$plat)"),
    '--platform', 'platform');

// The extension ABI version is DuckDB's own, not the duckdb_jdbc artifact version: the pom carries
// 1.5.2.1 (three DuckDB components plus a JDBC patch) and the cache directory is v1.5.2. Deriving it
// rather than pinning it means a driver bump does not silently keep fetching the old ABI — and the
// download itself verifies the guess, because a wrong version is a 404 rather than a wrong file.
const pom = readFileSync(join(repoRoot, 'pom.xml'), 'utf8');
const driverVersion = (/<duckdb\.version>([^<]+)<\/duckdb\.version>/.exec(pom) || [])[1];
if (!driverVersion) {
    console.error('✖ no <duckdb.version> in the root pom — cannot tell which extension ABI to fetch.');
    process.exit(2);
}
const extVersion = 'v' + driverVersion.split('.').slice(0, 3).join('.');

// --check verifies everything the real run depends on WITHOUT downloading ~160 MB: that the two lists are
// still parseable out of package.ps1, that the ABI version resolves, and that every file this script would
// fetch actually exists (HEAD, not GET).
//
// ⛔ It exists because the full run happens only on a `v*` tag — and "a path that executes only at release
// time" is the exact shape of the defect this whole row is about. A drifted `$duckdbExtNames`, a renamed
// loop variable, or a duckdb bump whose extensions are not published for that ABI would otherwise surface
// during a release rather than on the push that caused it.
const checkOnly = process.argv.includes('--check');

const outDir = arg('--out', join(repoRoot, '.duckdb-extension-cache'));

console.log(`DuckDB extensions: ${names.join(', ')}`);
console.log(`Platforms        : ${platforms.join(', ')}`);
console.log(`Extension ABI    : ${extVersion}  (from duckdb.version=${driverVersion})`);
console.log(`Cache            : ${outDir}\n`);

let failed = 0;
let fetched = 0;

for (const platform of platforms) {
    for (const name of names) {
        const url = `http://extensions.duckdb.org/${extVersion}/${platform}/${name}.duckdb_extension.gz`;
        const dest = join(outDir, extVersion, platform, `${name}.duckdb_extension`);
        if (checkOnly) {
            try {
                const res = await fetch(url, { method: 'HEAD' });
                if (!res.ok) throw new Error(`HTTP ${res.status}`);
                console.log(`  ✓ ${platform}/${name} is published`);
            } catch (e) {
                console.error(`  ✖ ${platform}/${name} — ${e.message}\n    ${url}`);
                failed++;
            }
            continue;
        }
        if (existsSync(dest)) {
            console.log(`  = ${platform}/${name} (already cached)`);
            continue;
        }
        try {
            const res = await fetch(url);
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            const gz = Buffer.from(await res.arrayBuffer());
            const raw = gunzipSync(gz);
            mkdirSync(dirname(dest), { recursive: true });
            writeFileSync(dest, raw);
            const sha = createHash('sha256').update(raw).digest('hex').slice(0, 12);
            console.log(`  + ${platform}/${name}  ${raw.length} bytes  sha256:${sha}…`);
            fetched++;
        } catch (e) {
            console.error(`  ✖ ${platform}/${name} — ${e.message}\n    ${url}`);
            failed++;
        }
    }
}

console.log();
if (failed) {
    // ⛔ Fail loudly. The whole point of this row is that a MISSING extension used to be a warning nobody
    // read; a fetch step that shrugged would reproduce the defect one layer up.
    console.error(`✖ ${failed} extension(s) could not be fetched. The bundle would ship without them, and`);
    console.error('  an air-gapped install would reach for a network INSTALL at run time instead —');
    console.error('  which is fatal on a partitioned topology (D10). Refusing to continue quietly.');
    process.exit(1);
}
if (checkOnly) {
    console.log(`✓ DuckDB extension check: ${names.length * platforms.length} file(s) published for `
        + `${extVersion} — ${names.join(', ')} × ${platforms.join(', ')}, read from package.ps1.`);
    process.exit(0);
}
console.log(`✓ DuckDB extension cache ready — ${fetched} fetched, ${names.length * platforms.length} total.`);
console.log(`  Point package.ps1 at it with:  -DuckdbExtensionCache "${outDir}"`);
console.log('  (or export DUCKDB_EXTENSION_CACHE)');
