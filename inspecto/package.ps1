# package.ps1 — Build and bundle inspecto for remote server deployment.
#
# Usage (run from inside inspecto/ or from the sandbox root):
#   pwsh -File inspecto\package.ps1 [-NoBuild] [-Edition Standard|Enterprise]
#
# Run under pwsh 7: this file is BOM-less UTF-8 and Windows PowerShell 5.1 garbles its non-ASCII
# characters (see .claude/skills/build-verify/SKILL.md).
#
# -Edition Enterprise is Standard + inspecto-policy (the ABAC AccessDecider SPI implementation),
# bundled as inspecto-policy.jar. It needs NO extra flag: the module is discovered purely
# through META-INF/services/com.gamma.control.AccessDecider, so being on the classpath is what
# turns policy evaluation on. serve.sh/serve.bat auto-detect it the same way they do the security jar.
#
# -Edition Standard (default: Personal) additionally builds inspecto-security (W6, the OIDC
# Authenticator SPI implementation) and bundles it as inspecto-security.jar; serve.sh/
# serve.bat auto-detect its presence, add it to the classpath, and turn on -Dauth.mode=oidc.
# Standard/Enterprise also bundle the PostgreSQL JDBC driver as postgresql.jar (PG-1) — same
# auto-detect mechanism, inert until -Dinspecto.db=postgres; the fat JAR stays driver-free.
# (issuer/JWKS/audience from AUTH_OIDC_* env vars — never baked into the bundle). The embedded
# jlink runtime's module set (below) is VERIFIED sufficient for inspecto-security too (PKG-4,
# 2026-07-07): jdeps on inspecto-security.jar + Nimbus JOSE+JWT 10.9.1 needs nothing beyond
# java.base/java.sql/java.net.http/jdk.httpserver, and RS256/ES256 resolve via SunRsaSign/SunEC
# (jdk.crypto.ec) on a jlink image built from exactly this list — Standard bundles may embed the
# runtime. jlink can target either platform from this Windows host by pointing --module-path at
# the target JDK's jmods (the invoked jlink.exe is always the Windows one; -NoRuntime skips both).
#
# Output:
#   inspecto-deploy.zip        (Windows target, embedded Windows JVM)
#   inspecto-deploy-linux.zip  (Linux target, embedded Linux JVM — only when a Linux
#                                      GraalVM jmods cache is present under .graalvm-cache)
#   (both in the sandbox root, alongside inbox/ and database/)
#
# Both bundles also carry duckdb-extensions/{windows_amd64,linux_amd64}/excel.duckdb_extension
# when a local DuckDB extension cache is found (multiformat X1 — frontend: xlsx's `excel`
# extension is NOT statically linked into duckdb_jdbc, so an air-gapped deployment needs the file
# shipped; see ExcelExtension.ensureLoaded and -DuckdbExtensionCache below). Missing = a warning,
# never a build failure — serve/run/ura auto-detect it and ExcelExtension falls back to a
# networked INSTALL on first use if there's no cached binary and the deployment has network access.
#
# The zip is a self-contained deployment unit.  On the target server:
#   1. Unzip inspecto-deploy.zip  →  inspecto-deploy/
#   2. Create your inbox directories under inspecto-deploy/inbox/<adapter>/
#   3. java -jar inspecto-deploy/inspecto.jar inspecto-deploy/config/<adapter>/<adapter>_pipeline.toon
#      (or use the bundled run.bat / run.sh — they cd to the bundle root automatically)
#
param(
    [switch]$NoBuild,   # skip mvn build; use existing JAR in target/
    [switch]$NoUi,      # skip the Angular UI build/bundle (inspecto-ui/ is optional)
    [switch]$NoRuntime, # skip embedding a trimmed Java runtime (target server must then provide Java 24+)
    # Editions are build flavors (docs/EDITIONS.md), never branches. 'Standard' additionally builds
    # and bundles inspecto-security (W6, the Authenticator SPI's OIDC implementation) alongside the
    # core jar; serve.sh/serve.bat auto-detect its presence and wire -Dauth.mode=oidc from env vars.
    # 'Enterprise' is Standard PLUS inspecto-policy (the ABAC AccessDecider SPI impl) — the same
    # superset relation the -Pedition-enterprise Maven profile encodes, so it bundles BOTH extra jars.
    [ValidateSet('Personal', 'Standard', 'Enterprise')]
    [string]$Edition = 'Personal',
    # ── release integrity (SOC 2 CC8-04) ──
    # SHA-256 checksums are ALWAYS written next to each artifact (no key needed). -Sign additionally
    # produces a GPG detached signature (.asc) per artifact so customers can verify AUTHENTICITY, not
    # just integrity. Provide the key via -SigningKey or $env:INSPECTO_SIGNING_KEY — never bake a key
    # into the repo/bundle (see compliance/soc2/policies/06-cryptography-policy.md).
    [switch]$Sign,
    [string]$SigningKey = $env:INSPECTO_SIGNING_KEY,
    # Explicit override for the GraalVM JDK cache (jlink.exe + per-target jmods/). Defaults try,
    # in order: this param -> $env:GRAALVM_CACHE -> <repo>/.graalvm-cache (nested-in-repo layout)
    # -> <repo>/../.graalvm-cache (sibling-of-repo layout, e.g. C:\sandbox\.graalvm-cache next to
    # C:\sandbox\inspecto-clean). Resolved once the real path is known, below.
    [string]$GraalvmCache = '',
    # Explicit override for the DuckDB extension cache (multiformat X1 — the `excel` extension for
    # frontend: xlsx is NOT statically linked into duckdb_jdbc, so ExcelExtension.ensureLoaded needs
    # the platform's excel.duckdb_extension file shipped alongside an air-gapped bundle or it can
    # only LOAD when a prior networked `INSTALL excel` already cached it under ~/.duckdb). Defaults
    # try, in order: this param -> $env:DUCKDB_EXTENSION_CACHE -> DuckDB's own default install cache
    # (%USERPROFILE%\.duckdb\extensions or $HOME/.duckdb/extensions — wherever a local `INSTALL excel`
    # already put it) -> <repo>/.duckdb-extension-cache -> <repo>/../.duckdb-extension-cache (same
    # nested/sibling pair as -GraalvmCache). Best-effort per platform: a missing extension for one or
    # both platforms is a warning, never a build failure — ExcelExtension itself still falls back to
    # a networked INSTALL at runtime if the operator's deployment has network access.
    [string]$DuckdbExtensionCache = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ── LF/CRLF-safe writers for the bundled launcher scripts ──────────────────────
# This file is itself CRLF on disk (.gitattributes: *.ps1 text eol=crlf), and a PowerShell
# here-string (@'...'@) carries the EXACT bytes between its delimiters — so every "bash" heredoc
# below (run.sh/ura.sh/serve.sh) already contains embedded \r\n, not \n, before it is ever
# touched. Two bugs followed from that, both fixed by writing through these helpers instead of
# `Set-Content -NoNewline` / `.Replace("`n","`r`n")` directly:
#   1. The *.sh scripts were written CRLF — `#!/usr/bin/env bash\r` fails on Linux with
#      "bad interpreter: No such file or directory" (the \r is part of the interpreter name).
#   2. The *.bat scripts' `.Replace("`n", "`r`n")` — meant to FORCE crlf — instead matched the
#      \n inside each already-present \r\n and produced doubled \r\r\n throughout every .bat file.
#      cmd.exe tolerates a stray \r so this shipped unnoticed, but it was never clean.
# Write-LfScript normalizes any \r\n/\r to bare \n first, so the SOURCE line-ending policy of
# package.ps1 itself can never leak into the bundled scripts either way.
function Write-LfScript {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Content)
    $lf = $Content -replace "`r`n", "`n" -replace "`r", "`n"
    [System.IO.File]::WriteAllText($Path, $lf, [System.Text.UTF8Encoding]::new($false))
}
function Write-CrlfScript {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Content)
    $lf = $Content -replace "`r`n", "`n" -replace "`r", "`n"
    [System.IO.File]::WriteAllText($Path, ($lf -replace "`n", "`r`n"), [System.Text.Encoding]::ASCII)
}

# ── locate repo root (works whether called from inspecto/ or sandbox root) ──
$scriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$adjParserDir = if ((Split-Path -Leaf $scriptDir) -eq 'inspecto') { $scriptDir }
               else { Join-Path $scriptDir 'inspecto' }
$sandboxRoot  = Split-Path -Parent $adjParserDir
$targetDir    = Join-Path $adjParserDir 'target'
$outZip       = Join-Path $sandboxRoot  'inspecto-deploy.zip'
$outZipLinux  = Join-Path $sandboxRoot  'inspecto-deploy-linux.zip'

# ── resolve the GraalVM cache dir (jlink.exe + per-target jmods/) ─────────────
# Historically assumed nested at <repo>/.graalvm-cache; on this sandbox it is a SIBLING of the
# repo (C:\sandbox\.graalvm-cache next to C:\sandbox\inspecto-clean) — a layout the old fixed path
# could never find, so -NoRuntime was effectively forced and the Linux build silently never ran.
# Try both, plus an explicit override, before giving up.
$graalvmCacheDir = $null
$cacheCandidates = @($GraalvmCache, $env:GRAALVM_CACHE,
    (Join-Path $sandboxRoot '.graalvm-cache'),
    (Join-Path (Split-Path -Parent $sandboxRoot) '.graalvm-cache')) | Where-Object { $_ }
foreach ($c in $cacheCandidates) {
    if (Test-Path $c) { $graalvmCacheDir = $c; break }
}
if ($graalvmCacheDir) {
    Write-Host "GraalVM cache: $graalvmCacheDir" -ForegroundColor DarkGray
} else {
    Write-Host "  (no .graalvm-cache found — tried: $($cacheCandidates -join ', '))" -ForegroundColor Yellow
}

# ── resolve the DuckDB extension cache (excel.duckdb_extension, per platform) ─
# Same nested/sibling pair as the GraalVM cache, PLUS DuckDB's own default install location — a
# prior `INSTALL excel` (this machine's own probing, or CI) already populates that one, so it is
# tried before the repo-adjacent conventions. Glob for the version dir (v1.5.2, …) rather than
# pinning it: it's DuckDB's own extension-ABI version, not the duckdb_jdbc artifact version in the
# root pom, and a driver bump must not silently stop finding an already-cached extension.
$duckdbExtCacheDir = $null
$duckdbExtCandidates = @($DuckdbExtensionCache, $env:DUCKDB_EXTENSION_CACHE,
    (Join-Path $env:USERPROFILE '.duckdb\extensions'),
    (Join-Path $HOME '.duckdb/extensions'),
    (Join-Path $sandboxRoot '.duckdb-extension-cache'),
    (Join-Path (Split-Path -Parent $sandboxRoot) '.duckdb-extension-cache')) | Where-Object { $_ } | Select-Object -Unique
foreach ($c in $duckdbExtCandidates) {
    if (Test-Path $c) { $duckdbExtCacheDir = $c; break }
}
if ($duckdbExtCacheDir) {
    Write-Host "DuckDB extension cache: $duckdbExtCacheDir" -ForegroundColor DarkGray
} else {
    Write-Host "  (no DuckDB extension cache found — tried: $($duckdbExtCandidates -join ', '))" -ForegroundColor Yellow
}
$bundleDir    = Join-Path $sandboxRoot  'inspecto-deploy'

# ── step 1: build ─────────────────────────────────────────────────────────────
# Built from the repo root with -pl inspecto -am (same idiom as step 1c) because since S5 the
# core depends on reactor siblings (inspecto-api, …) — a core-alone build from inspecto/
# would only resolve them after a root `mvn install`. -am builds the needed siblings in-pass;
# the shaded JAR still lands in inspecto/target/.
if (-not $NoBuild) {
    Write-Host "Building fat JAR (skipping tests)..." -ForegroundColor Cyan
    Push-Location $sandboxRoot
    & mvn clean package -pl inspecto -am -DskipTests -q
    if ($LASTEXITCODE -ne 0) { throw "mvn build failed" }
    Pop-Location
    Write-Host "Build complete." -ForegroundColor Green
}

# ── step 1b: build the operator UI (optional; guarded so a checkout without inspecto-ui/ still bundles) ──
# The Angular SPA (Inspecto console) lives in the monorepo's top-level inspecto-ui/ (sibling of inspecto/).
# Its toolchain (Node/pnpm) is intentionally NOT part of the Maven reactor — invoked here only for the bundle.
$uiDir    = Join-Path $sandboxRoot 'inspecto-ui'
$uiDistRoot = Join-Path $uiDir 'dist'
$uiBuilt  = $false
if (-not $NoUi -and (Test-Path (Join-Path $uiDir 'package.json'))) {
    Write-Host "Building operator UI (inspecto-ui/)..." -ForegroundColor Cyan
    Push-Location $uiDir
    try {
        & npm ci
        if ($LASTEXITCODE -ne 0) { throw "npm ci failed in inspecto-ui/" }
        & npm run build
        if ($LASTEXITCODE -ne 0) { throw "ng build failed in inspecto-ui/" }
        $uiBuilt = $true
        Write-Host "UI build complete." -ForegroundColor Green
    } finally { Pop-Location }
} elseif (-not (Test-Path (Join-Path $uiDir 'package.json'))) {
    Write-Host "  (no inspecto-ui/ project found — bundling API only; UI hosting will be inactive)" -ForegroundColor Yellow
}

# Discover the shaded JAR by pattern so we don't pin to a specific version number. The
# 'file-processor-*' → 'inspecto-*' artifactId rename settled on 2026-08-10, so the two-pattern
# compatibility shim this line carried since 2026-07-31 is gone: one name, one glob. The BUNDLE
# file name below followed on 2026-08-13 — the deployment surface (serve.sh, the run-example
# scripts, docs/EDITIONS.md) is now 'inspecto.jar', so nothing ships as 'file-processor' anymore.
$jarSrc = Get-ChildItem -Path $targetDir -Filter 'inspecto-processor-*.jar' -ErrorAction SilentlyContinue |
          Select-Object -First 1 -ExpandProperty FullName
if (-not $jarSrc -or -not (Test-Path $jarSrc)) {
    throw "JAR not found matching $targetDir\inspecto-processor-*.jar.  Run without -NoBuild or build manually first."
}

# ── step 1c: Standard/Enterprise editions — build the optional edition modules ─────────────────
# Separate optional modules (docs/EDITIONS.md), NOT in the default reactor <modules> — only built
# when the profile is requested, from the repo root (they are siblings of inspecto/, not submodules).
# Enterprise is a SUPERSET of Standard (the -Pedition-enterprise profile = edition-standard + policy),
# so it bundles the security jar too — an Enterprise deployment authenticates AND authorizes.
$securityJarSrc = $null
$policyJarSrc   = $null
if ($Edition -ne 'Personal') {
    # NB: not $profile — that is a PowerShell automatic variable.
    $editionProfile = if ($Edition -eq 'Enterprise') { 'edition-enterprise' } else { 'edition-standard' }
    $modules = if ($Edition -eq 'Enterprise') { 'inspecto-security,inspecto-policy' } else { 'inspecto-security' }
    if (-not $NoBuild) {
        Write-Host "Building $modules ($Edition edition, -P$editionProfile)..." -ForegroundColor Cyan
        Push-Location $sandboxRoot
        & mvn clean package "-P$editionProfile" -pl $modules -am -DskipTests -q
        if ($LASTEXITCODE -ne 0) { throw "mvn build of the $Edition edition modules failed" }
        Pop-Location
    }
    $securityTargetDir = Join-Path $sandboxRoot 'inspecto-security\target'
    $securityJarSrc = Get-ChildItem -Path $securityTargetDir -Filter 'inspecto-security-*.jar' -ErrorAction SilentlyContinue |
                       Select-Object -First 1 -ExpandProperty FullName
    if (-not $securityJarSrc -or -not (Test-Path $securityJarSrc)) {
        throw "$Edition edition requested but no JAR found matching $securityTargetDir\inspecto-security-*.jar."
    }
    if ($Edition -eq 'Enterprise') {
        $policyTargetDir = Join-Path $sandboxRoot 'inspecto-policy\target'
        $policyJarSrc = Get-ChildItem -Path $policyTargetDir -Filter 'inspecto-policy-*.jar' -ErrorAction SilentlyContinue |
                         Select-Object -First 1 -ExpandProperty FullName
        if (-not $policyJarSrc -or -not (Test-Path $policyJarSrc)) {
            throw "Enterprise edition requested but no JAR found matching $policyTargetDir\inspecto-policy-*.jar."
        }
    }
}

# ── step 2: create bundle directory ───────────────────────────────────────────
Write-Host "Assembling bundle at $bundleDir ..." -ForegroundColor Cyan
if (Test-Path $bundleDir) {
    Get-ChildItem -Path $bundleDir | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
} else {
    $null = New-Item -ItemType Directory $bundleDir
}

# The per-space layout (config + runtime dirs) is bundled from the repo's spaces/ tree in step 4;
# runtime dirs (data/audit/duckdb/flows) are created on first run, so nothing to pre-create here.

# ── step 3: copy JAR (canonical name for deployment) ──────────────────────────
Copy-Item $jarSrc "$bundleDir\inspecto.jar"
if ($securityJarSrc) {
    Copy-Item $securityJarSrc "$bundleDir\inspecto-security.jar"
    Write-Host "Bundled Standard-edition security module → inspecto-security.jar" -ForegroundColor Green
}
if ($policyJarSrc) {
    Copy-Item $policyJarSrc "$bundleDir\inspecto-policy.jar"
    Write-Host "Bundled Enterprise-edition policy module → inspecto-policy.jar" -ForegroundColor Green
}

# ── step 3a: Standard/Enterprise — bundle the PostgreSQL JDBC driver as a sidecar (PG-1) ─────────
# The fat JAR and its SBOM stay JDBC-driver-free by design (inspecto/pom.xml, inspecto-engine/pom.xml);
# the driver rides the bundle as postgresql.jar, auto-detected by serve.sh/serve.bat exactly like
# inspecto-security.jar. Personal ships DuckDB only — OperationalDb.verifySelectable fails a
# -Dinspecto.db=postgres boot there, naming this sidecar as the thing to drop in.
if ($Edition -ne 'Personal') {
    $pgVersion = ([xml](Get-Content (Join-Path $sandboxRoot 'pom.xml'))).project.properties.'postgresql.version'
    if (-not $pgVersion) { throw "postgresql.version not found in the parent pom — cannot bundle the driver" }
    $pgJar = Join-Path $env:USERPROFILE ".m2\repository\org\postgresql\postgresql\$pgVersion\postgresql-$pgVersion.jar"
    if (-not (Test-Path $pgJar)) {
        throw "PostgreSQL driver $pgVersion not in the local Maven repo ($pgJar). Run a build once (it is a test-scope dependency) or fetch it, then re-package."
    }
    Copy-Item $pgJar "$bundleDir\postgresql.jar"
    Write-Host "Bundled PostgreSQL JDBC driver $pgVersion → postgresql.jar" -ForegroundColor Green
}

# ── step 3b: copy the built UI dist → bundle/ui (served by ControlApi via -Dui.dir=./ui) ──
# Angular emits to ui/dist/<app>[/browser]; locate the folder that actually holds index.html.
#
# NOT gated on $uiBuilt (PKG, 2026-07-31): it used to be, so -NoUi meant "don't SHIP the UI" as well
# as "don't rebuild it", and the bundle shipped with no ui/ while a perfectly good dist/ sat on disk.
# The failure was silent and only visible on the deployed server: serve.sh's `[ -d ui ]` test fails,
# no -Dui.dir is passed, and every browser request falls through ControlApi's unversioned-path guard
# to `{"error":"not found — API routes are served under /api/v1"}` while the API itself works fine.
# -NoUi now means exactly "skip the npm build"; whatever dist/ exists is still bundled.
$uiBundled = $false
if (Test-Path $uiDistRoot) {
    $indexHtml = Get-ChildItem -Path $uiDistRoot -Filter 'index.html' -Recurse -ErrorAction SilentlyContinue |
                 Select-Object -First 1
    if ($indexHtml) {
        $uiOut = "$bundleDir\ui"
        $null = New-Item -ItemType Directory $uiOut -Force
        Copy-Item -Path (Join-Path $indexHtml.DirectoryName '*') -Destination $uiOut -Recurse -Force
        $uiBundled = $true
        $stale = if ($uiBuilt) { '' } else { ' (pre-existing dist — NOT rebuilt this run)' }
        Write-Host "Bundled UI from $($indexHtml.DirectoryName) → $uiOut$stale" -ForegroundColor Green
    } else {
        Write-Host "  (no index.html under $uiDistRoot — skipping UI bundle)" -ForegroundColor Yellow
    }
}

# ── step 4: copy the multi-space config tree (configs + space.toon) ───────────
# Each space's pipeline configs use repo-root-relative paths (spaces/<id>/config|data/...), which
# resolve identically from the bundle root — no path rewrite needed. Runtime state
# (data/audit/duckdb/flows) is created on first run and is intentionally NOT bundled.
#
# The excluded trees are SKIPPED AT COPY TIME, not copied-then-pruned (2026-07-31): a locally running
# ControlApi holds an exclusive handle on spaces/<id>/duckdb/*.db, so the old copy-everything pass
# failed outright ("being used by another process") — packaging a bundle should not require stopping
# the dev server, least of all to copy files it then deletes.
$spacesSrc = Join-Path $sandboxRoot 'spaces'
if (Test-Path $spacesSrc) {
    $spacesOut = Join-Path $bundleDir 'spaces'
    # Never ship runtime/generated trees: uat is a generated clone (tools/seed-uat.ps1) and _shared
    # holds the Exchange's runtime ledgers. _templates (the shipped template gallery) DOES ship.
    $skipTop = @('uat', '_shared')
    # Per space: skip runtime state but KEEP authored config/flows/ (canonical since the
    # flows-divergence fix) and the pristine data/samples/ feeds (committed, seed scripts copy them).
    $skipGen = @('audit', 'duckdb', 'flows', 'views')
    $null = New-Item -ItemType Directory $spacesOut -Force
    foreach ($entry in Get-ChildItem -Path $spacesSrc -Force) {
        if (-not $entry.PSIsContainer) { Copy-Item $entry.FullName $spacesOut -Force; continue }
        if ($skipTop -contains $entry.Name) { continue }
        $spaceOut = Join-Path $spacesOut $entry.Name
        $null = New-Item -ItemType Directory $spaceOut -Force
        foreach ($child in Get-ChildItem -Path $entry.FullName -Force) {
            if ($skipGen -contains $child.Name) { continue }
            if ($child.PSIsContainer -and $child.Name -eq 'data') {
                $dataOut = Join-Path $spaceOut 'data'
                $null = New-Item -ItemType Directory $dataOut -Force
                $samples = Join-Path $child.FullName 'samples'
                if (Test-Path $samples) { Copy-Item $samples $dataOut -Recurse -Force }
                continue
            }
            Copy-Item $child.FullName $spaceOut -Recurse -Force
        }
    }
    Write-Host "Bundled spaces tree → $spacesOut (samples + config/flows kept, runtime skipped)" -ForegroundColor Green
} else {
    Write-Host "  (no spaces/ tree found at $spacesSrc — skipping config bundle)" -ForegroundColor Yellow
}

# ── step 4b: copy runnable examples ───────────────────────────────────────────
# The examples/ tree is self-contained (each example uses paths relative to its own
# dir and writes only under its own out/), so no path rewrite is needed — copy as-is,
# then drop any out/ left over from local test runs. Users run an example with the
# bundled examples/run-example.(ps1|sh), which resolves the JAR at ../inspecto.jar.
$examplesSrc = Join-Path $adjParserDir 'examples'
if (Test-Path $examplesSrc) {
    $examplesOut = Join-Path $bundleDir 'examples'
    Copy-Item $examplesSrc $examplesOut -Recurse -Force
    Get-ChildItem -Path $examplesOut -Recurse -Directory -Filter 'out' -ErrorAction SilentlyContinue |
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "Bundled runnable examples → $examplesOut" -ForegroundColor Green
}


# ── step 5: bundle run scripts (Linux + Windows) ───────────────────────────────
$runShContent = @'
#!/usr/bin/env bash
# Usage: [INSPECTO_JAVA_OPTS="-Xmx4g"] ./run.sh <adapter>
# Looks up the pipeline file as spaces/<space>/config/<adapter>/*_pipeline.toon (first match wins),
# so it transparently handles both "<adapter>_pipeline.toon" and variants like
# "<adapter>_unknown_pipeline.toon" across every space.
set -euo pipefail
cd "$(dirname "$0")"
ADAPTER="${1:?Usage: run.sh <adapter>   (e.g. voucher)}"
# `|| true` is load-bearing: under `set -euo pipefail` a non-matching glob makes `ls` fail, the pipe
# fail, and the assignment fail -- so the script died at THIS line with exit 2 and no message, and
# the friendly error below was unreachable (found alongside PKG-2, 2026-08-18).
PIPELINE=$(ls spaces/*/config/"${ADAPTER}"/*_pipeline.toon 2>/dev/null | head -1 || true)
if [ -z "$PIPELINE" ]; then
    echo "ERROR: no pipeline file found at spaces/*/config/${ADAPTER}/*_pipeline.toon" >&2
    exit 1
fi
echo "[run.sh] Using pipeline: $PIPELINE"
# Path-jail roots (PKG-6). The one-shot CollectorProcessor runs no space discovery -- unlike serve,
# where SpaceManager registers each space base with DiscoveredRoots before loading configs -- so for
# it `-Dassist.safety.roots` is the ONLY source, and a pipeline carrying a `schema_file:`/
# `mapping_file:` ref died with "no allowed roots configured" on a fresh bundle. Declare exactly the
# SPACE this invocation resolved (the dir holding the config/ subtree -- SpaceManager's own rule),
# never the whole spaces/ tree: configuring roots is a deployment step, and the deployment already
# knows which space it picked. An operator-supplied -Dassist.safety.roots still wins, because
# EXTRA_OPTS is appended after this and a later -D of the same key overrides an earlier one.
SPACE_DIR="${PIPELINE%%/config/*}"
[ "$SPACE_DIR" = "$PIPELINE" ] && SPACE_DIR="$(dirname "$PIPELINE")"
JAVA="java"; [ -x "runtime/bin/java" ] && JAVA="runtime/bin/java"
# Extra JVM flags from the operator, same contract as serve.sh: INSPECTO_JAVA_OPTS (fallback
# EXTRA_JAVA_OPTS), whitespace-separated, appended AFTER the mandatory
# --enable-native-access=ALL-UNNAMED and BEFORE -jar (a JVM flag after it would be parsed as a
# program argument). Not read from JAVA_OPTS: that name is assigned, so it is silently discarded.
JAVA_OPTS=(--enable-native-access=ALL-UNNAMED "-Dassist.safety.roots=${SPACE_DIR}")
# DuckDB excel extension (multiformat X1, frontend: xlsx) — auto-detect the bundled per-platform
# binary, exactly like the runtime/ auto-detect above; a networked deployment without it still
# falls back to INSTALL inside ExcelExtension.
[ -d duckdb-extensions/linux_amd64 ] && JAVA_OPTS+=("-Dduckdb.extension.dir=duckdb-extensions/linux_amd64")
EXTRA_OPTS="${INSPECTO_JAVA_OPTS:-${EXTRA_JAVA_OPTS:-}}"
if [ -n "${EXTRA_OPTS}" ]; then
    read -r -a _extra_opts <<< "${EXTRA_OPTS}"
    JAVA_OPTS+=("${_extra_opts[@]}")
    echo "[run.sh] extra JVM opts: ${EXTRA_OPTS}"
fi
exec "$JAVA" "${JAVA_OPTS[@]}" \
          -jar inspecto.jar \
          "$PIPELINE"
'@
Write-LfScript -Path "$bundleDir\run.sh" -Content $runShContent

$runBatContent = @'
@echo off
rem Usage: [set "INSPECTO_JAVA_OPTS=-Xmx4g"] run.bat ADAPTER    (e.g. voucher)
rem Looks up the pipeline file as spaces\SPACE\config\ADAPTER\*_pipeline.toon (first match wins),
rem so it handles both "ADAPTER_pipeline.toon" and variants like
rem "ADAPTER_unknown_pipeline.toon" across every space.
setlocal
cd /d "%~dp0"
if "%1"=="" (
    echo Usage: run.bat ADAPTER   [e.g. voucher]
    exit /b 1
)
rem PKG-2: the lookup used to be a single `for %%F in (spaces\*\config\%1\*_pipeline.toon)`, which
rem NEVER matched -- cmd's set-based FOR globs the FILENAME only, so a wildcard in a DIRECTORY
rem component silently finds nothing. `for /d` DOES glob directories, so enumerate the spaces first
rem and leave only the filename wildcard to the inner FOR. First match wins, as in run.sh.
set "PIPELINE="
set "SPACE_DIR="
for /d %%S in (spaces\*) do (
    for %%F in ("%%S\config\%1\*_pipeline.toon") do (
        if not defined PIPELINE if exist "%%~F" (
            set "PIPELINE=%%~F"
            rem The space dir comes free from the outer loop -- no string surgery on the path.
            set "SPACE_DIR=%%S"
        )
    )
)
if not defined PIPELINE (
    echo ERROR: no pipeline file found at spaces\*\config\%1\*_pipeline.toon
    exit /b 1
)
echo [run.bat] Using pipeline: %PIPELINE%
set "JAVA=java"
if exist "runtime\bin\java.exe" set "JAVA=runtime\bin\java.exe"
rem Extra JVM flags from the operator, same contract as serve.bat: INSPECTO_JAVA_OPTS (fallback
rem EXTRA_JAVA_OPTS), appended AFTER the mandatory --enable-native-access=ALL-UNNAMED and BEFORE
rem -jar (a JVM flag after it would be parsed as a program argument). Not read from JAVA_OPTS:
rem that name is assigned, so it is silently discarded.
set "OPTS=--enable-native-access=ALL-UNNAMED"
rem Path-jail roots (PKG-6). The one-shot CollectorProcessor runs no space discovery -- unlike
rem serve, where SpaceManager registers each space base with DiscoveredRoots before loading
rem configs -- so for it -Dassist.safety.roots is the ONLY source, and a pipeline carrying a
rem schema_file:/mapping_file: ref died with "no allowed roots configured" on a fresh bundle.
rem Declare exactly the SPACE this invocation resolved (the dir holding the config\ subtree --
rem SpaceManager's own rule), never the whole spaces\ tree. An operator-supplied
rem -Dassist.safety.roots still wins: EXTRA_OPTS is appended after this and a later -D of the
rem same key overrides an earlier one.
set "OPTS=%OPTS% -Dassist.safety.roots=%SPACE_DIR%"
rem DuckDB excel extension (multiformat X1, frontend: xlsx) - auto-detect the bundled
rem per-platform binary; a networked deployment without it still falls back to INSTALL
rem inside ExcelExtension.
if exist "duckdb-extensions\windows_amd64" set "OPTS=%OPTS% -Dduckdb.extension.dir=duckdb-extensions\windows_amd64"
set "EXTRA_OPTS=%INSPECTO_JAVA_OPTS%"
if "%EXTRA_OPTS%"=="" set "EXTRA_OPTS=%EXTRA_JAVA_OPTS%"
if not "%EXTRA_OPTS%"=="" set "OPTS=%OPTS% %EXTRA_OPTS%"
if not "%EXTRA_OPTS%"=="" echo [run.bat] extra JVM opts: %EXTRA_OPTS%
"%JAVA%" %OPTS% ^
     -jar inspecto.jar ^
     "%PIPELINE%"
'@
Write-CrlfScript -Path "$bundleDir\run.bat" -Content $runBatContent

# ── step 6: bundle ura scripts (pre-ETL utility CLI, Linux + Windows) ─────────
$uraShContent = @'
#!/usr/bin/env bash
# URA File Management Suite — utility CLI runner
#
# Usage: [INSPECTO_JAVA_OPTS="-Xmx4g"] ./ura.sh [--dry-run] <command> <pipeline.toon> [args...]
#
# Examples:
#   ./ura.sh help
#   ./ura.sh search           spaces/ucc/config/voucher/voucher_pipeline.toon
#   ./ura.sh copy             spaces/ucc/config/voucher/voucher_pipeline.toon
#   ./ura.sh --dry-run backup spaces/ucc/config/voucher/voucher_pipeline.toon
#   ./ura.sh prepare-inbox    spaces/ucc/config/voucher/voucher_pipeline.toon
#   ./ura.sh create-schema    voucher  samples/voucher_sample.csv  spaces/ucc/config/voucher/voucher_gen.toon
set -euo pipefail
cd "$(dirname "$0")"
JAVA="java"; [ -x "runtime/bin/java" ] && JAVA="runtime/bin/java"
# Extra JVM flags from the operator, same contract as serve.sh: INSPECTO_JAVA_OPTS (fallback
# EXTRA_JAVA_OPTS), whitespace-separated, appended AFTER the mandatory
# --enable-native-access=ALL-UNNAMED and BEFORE -cp (a JVM flag after it would be parsed as a
# program argument). Not read from JAVA_OPTS: that name is assigned, so it is silently discarded.
JAVA_OPTS=(--enable-native-access=ALL-UNNAMED)
[ -d duckdb-extensions/linux_amd64 ] && JAVA_OPTS+=("-Dduckdb.extension.dir=duckdb-extensions/linux_amd64")
EXTRA_OPTS="${INSPECTO_JAVA_OPTS:-${EXTRA_JAVA_OPTS:-}}"
if [ -n "${EXTRA_OPTS}" ]; then
    read -r -a _extra_opts <<< "${EXTRA_OPTS}"
    JAVA_OPTS+=("${_extra_opts[@]}")
    echo "[ura.sh] extra JVM opts: ${EXTRA_OPTS}"
fi
exec "$JAVA" "${JAVA_OPTS[@]}" \
          -cp inspecto.jar \
          com.gamma.inspector.MainApp "$@"
'@
Write-LfScript -Path "$bundleDir\ura.sh" -Content $uraShContent

$uraBatContent = @'
@echo off
rem URA File Management Suite - utility CLI runner
rem Usage: [set "INSPECTO_JAVA_OPTS=-Xmx4g"] ura.bat [--dry-run] COMMAND pipeline.toon [args...]
rem   Commands: search, copy, copy-tars, extract, backup, prepare-inbox,
rem             create-schema, move-by-date, extract-unknown, extract-move, help
rem   Run 'ura.bat help' for full command reference.
setlocal
cd /d "%~dp0"
set "JAVA=java"
if exist "runtime\bin\java.exe" set "JAVA=runtime\bin\java.exe"
rem Extra JVM flags from the operator, same contract as serve.bat: INSPECTO_JAVA_OPTS (fallback
rem EXTRA_JAVA_OPTS), appended AFTER the mandatory --enable-native-access=ALL-UNNAMED and BEFORE
rem -cp (a JVM flag after it would be parsed as a program argument). Not read from JAVA_OPTS:
rem that name is assigned, so it is silently discarded.
set "OPTS=--enable-native-access=ALL-UNNAMED"
if exist "duckdb-extensions\windows_amd64" set "OPTS=%OPTS% -Dduckdb.extension.dir=duckdb-extensions\windows_amd64"
set "EXTRA_OPTS=%INSPECTO_JAVA_OPTS%"
if "%EXTRA_OPTS%"=="" set "EXTRA_OPTS=%EXTRA_JAVA_OPTS%"
if not "%EXTRA_OPTS%"=="" set "OPTS=%OPTS% %EXTRA_OPTS%"
if not "%EXTRA_OPTS%"=="" echo [ura.bat] extra JVM opts: %EXTRA_OPTS%
"%JAVA%" %OPTS% ^
     -cp inspecto.jar ^
     com.gamma.inspector.MainApp %*
'@
Write-CrlfScript -Path "$bundleDir\ura.bat" -Content $uraBatContent

# ── step 6b: bundle serve scripts (run the control plane + operator UI) ─────────
# Unlike run.sh (one-shot ETL), serve.sh launches the long-running ControlApi service with the
# HTTP control plane + operator UI. It serves the bundled SPA from ./ui via -Dui.dir. Tokens are
# read from the environment so secrets stay out of the bundle: CONTROL_TOKEN (required to use the
# control plane) and ASSIST_TOKEN (optional, enables the assist/catalog read routes).
$serveShContent = @'
#!/usr/bin/env bash
# Usage: CONTROL_TOKEN=... [ASSIST_TOKEN=...] [PORT=8080] [SPACES_ROOT=spaces] ./serve.sh
# Extra JVM flags: INSPECTO_JAVA_OPTS="-Dui.static.log=DEBUG" ./serve.sh   (see below)
# Starts the control plane + operator UI over every space under the spaces/ root (discover mode).
set -euo pipefail
cd "$(dirname "$0")"
PORT="${PORT:-8080}"
SPACES_ROOT="${SPACES_ROOT:-spaces}"
JAVA_OPTS=(--enable-native-access=ALL-UNNAMED "-Dcontrol.port=${PORT}" "-Dspaces.root=${SPACES_ROOT}")
[ -d ui ] && JAVA_OPTS+=("-Dui.dir=./ui")
# DuckDB excel extension (multiformat X1, frontend: xlsx) — auto-detect the bundled per-platform
# binary; a networked deployment without it still falls back to INSTALL inside ExcelExtension.
[ -d duckdb-extensions/linux_amd64 ] && JAVA_OPTS+=("-Dduckdb.extension.dir=duckdb-extensions/linux_amd64")
[ -n "${CONTROL_TOKEN:-}" ] && JAVA_OPTS+=("-Dcontrol.token=${CONTROL_TOKEN}")
[ -n "${ASSIST_TOKEN:-}" ]  && JAVA_OPTS+=("-Dassist.read.token=${ASSIST_TOKEN}")
[ -n "${CORS_ORIGIN:-}" ]   && JAVA_OPTS+=("-Dcontrol.cors=${CORS_ORIGIN}")
[ -n "${HTTPS_KEYSTORE:-}" ]          && JAVA_OPTS+=("-Dhttps.keystore=${HTTPS_KEYSTORE}")
[ -n "${HTTPS_KEYSTORE_PASSWORD:-}" ] && JAVA_OPTS+=("-Dhttps.keystore.password=${HTTPS_KEYSTORE_PASSWORD}")
# Edition auto-detects from the bundle (W6, docs/EDITIONS.md): inspecto-security.jar present
# ⇒ Standard — put it on the classpath and turn on OIDC (issuer/JWKS/audience from env, never baked
# into the bundle); + inspecto-policy.jar ⇒ Enterprise. Neither ⇒ Personal, byte-for-byte the
# historic auth-free classpath/flags.
CP="inspecto.jar"
EDITION="Personal"
if [ -f inspecto-security.jar ]; then
    CP="inspecto.jar:inspecto-security.jar"
    EDITION="Standard"
    JAVA_OPTS+=("-Dauth.mode=oidc")
    [ -n "${AUTH_OIDC_ISSUER:-}" ]    && JAVA_OPTS+=("-Dauth.oidc.issuer=${AUTH_OIDC_ISSUER}")
    [ -n "${AUTH_OIDC_JWKS_URI:-}" ]  && JAVA_OPTS+=("-Dauth.oidc.jwksUri=${AUTH_OIDC_JWKS_URI}")
    [ -n "${AUTH_OIDC_AUDIENCE:-}" ]  && JAVA_OPTS+=("-Dauth.oidc.audience=${AUTH_OIDC_AUDIENCE}")
    [ -n "${AUTH_OIDC_CLIENT_ID:-}" ] && JAVA_OPTS+=("-Dauth.oidc.clientId=${AUTH_OIDC_CLIENT_ID}")
    # Confidential-client secret (optional; W6d BFF): pass a SecretResolver REFERENCE, not the value —
    # the backend expands ${ENV:...} at use, so the secret never appears on the process command line.
    [ -n "${AUTH_OIDC_CLIENT_SECRET:-}" ] && JAVA_OPTS+=('-Dauth.oidc.clientSecret=${ENV:AUTH_OIDC_CLIENT_SECRET}')
    # inspecto-policy.jar present ⇒ Enterprise (Standard + ABAC). No flag: the module is found
    # via META-INF/services/com.gamma.control.AccessDecider, so the classpath entry IS the switch.
    if [ -f inspecto-policy.jar ]; then
        CP="${CP}:inspecto-policy.jar"
        EDITION="Enterprise"
    fi
fi
# PostgreSQL JDBC driver sidecar (PG-1): present in Standard/Enterprise bundles, and honored on ANY
# bundle so a drop-in works — the classpath entry is inert until -Dinspecto.db=postgres selects it.
[ -f postgresql.jar ] && CP="${CP}:postgresql.jar"
# Operator-supplied extra JVM flags. Appended LAST, on purpose: the flags this script requires
# (--enable-native-access=ALL-UNNAMED, port, spaces root, auth) are already in the array and
# cannot be clobbered from the environment. Whitespace-separated; INSPECTO_JAVA_OPTS wins over
# EXTRA_JAVA_OPTS. Deliberately NOT named JAVA_OPTS: that name is ASSIGNED above, so exporting it
# never reached the JVM -- a silently inert flag fabricates evidence (BUNDLE-1, 2026-08-18).
EXTRA_OPTS="${INSPECTO_JAVA_OPTS:-${EXTRA_JAVA_OPTS:-}}"
if [ -n "${EXTRA_OPTS}" ]; then
    read -r -a _extra_opts <<< "${EXTRA_OPTS}"
    JAVA_OPTS+=("${_extra_opts[@]}")
fi
JAVA="java"; [ -x "runtime/bin/java" ] && JAVA="runtime/bin/java"
echo "[serve.sh] ControlApi on :${PORT}  (spaces: ./${SPACES_ROOT}, UI: $([ -d ui ] && echo ./ui || echo none), edition: ${EDITION})${EXTRA_OPTS:+  extra JVM opts: ${EXTRA_OPTS}}"
exec "$JAVA" "${JAVA_OPTS[@]}" -cp "$CP" com.gamma.control.ControlApi
'@
Write-LfScript -Path "$bundleDir\serve.sh" -Content $serveShContent

$serveBatContent = @'
@echo off
rem Usage: set CONTROL_TOKEN=... && serve.bat
rem Optional env: ASSIST_TOKEN, PORT (default 8080), CORS_ORIGIN, SPACES_ROOT (default spaces).
rem Extra JVM flags: set "INSPECTO_JAVA_OPTS=-Dui.static.log=DEBUG"   (see below)
rem Starts the control plane + operator UI over every space under .\spaces (serves bundled .\ui).
setlocal
cd /d "%~dp0"
if "%PORT%"=="" set "PORT=8080"
if "%SPACES_ROOT%"=="" set "SPACES_ROOT=spaces"
set "OPTS=--enable-native-access=ALL-UNNAMED -Dcontrol.port=%PORT% -Dspaces.root=%SPACES_ROOT%"
if exist ui set "OPTS=%OPTS% -Dui.dir=./ui"
rem DuckDB excel extension (multiformat X1, frontend: xlsx) - auto-detect the bundled
rem per-platform binary; a networked deployment without it still falls back to INSTALL
rem inside ExcelExtension.
if exist "duckdb-extensions\windows_amd64" set "OPTS=%OPTS% -Dduckdb.extension.dir=duckdb-extensions\windows_amd64"
if not "%CONTROL_TOKEN%"=="" set "OPTS=%OPTS% -Dcontrol.token=%CONTROL_TOKEN%"
if not "%ASSIST_TOKEN%"=="" set "OPTS=%OPTS% -Dassist.read.token=%ASSIST_TOKEN%"
if not "%CORS_ORIGIN%"=="" set "OPTS=%OPTS% -Dcontrol.cors=%CORS_ORIGIN%"
if not "%HTTPS_KEYSTORE%"=="" set "OPTS=%OPTS% -Dhttps.keystore=%HTTPS_KEYSTORE%"
if not "%HTTPS_KEYSTORE_PASSWORD%"=="" set "OPTS=%OPTS% -Dhttps.keystore.password=%HTTPS_KEYSTORE_PASSWORD%"
rem Edition auto-detects from the bundle (W6, docs/EDITIONS.md): inspecto-security.jar present
rem => Standard - put it on the classpath and turn on OIDC (issuer/JWKS/audience from env);
rem + inspecto-policy.jar => Enterprise. Neither => Personal, byte-for-byte the historic
rem auth-free classpath/flags.
set "CP=inspecto.jar"
set "EDITION=Personal"
if exist inspecto-security.jar (
    set "CP=inspecto.jar;inspecto-security.jar"
    set "EDITION=Standard"
    set "OPTS=%OPTS% -Dauth.mode=oidc"
    if not "%AUTH_OIDC_ISSUER%"=="" set "OPTS=%OPTS% -Dauth.oidc.issuer=%AUTH_OIDC_ISSUER%"
    if not "%AUTH_OIDC_JWKS_URI%"=="" set "OPTS=%OPTS% -Dauth.oidc.jwksUri=%AUTH_OIDC_JWKS_URI%"
    if not "%AUTH_OIDC_AUDIENCE%"=="" set "OPTS=%OPTS% -Dauth.oidc.audience=%AUTH_OIDC_AUDIENCE%"
    if not "%AUTH_OIDC_CLIENT_ID%"=="" set "OPTS=%OPTS% -Dauth.oidc.clientId=%AUTH_OIDC_CLIENT_ID%"
    rem Confidential-client secret (optional; W6d BFF): pass a SecretResolver REFERENCE, not the value.
    if not "%AUTH_OIDC_CLIENT_SECRET%"=="" set "OPTS=%OPTS% -Dauth.oidc.clientSecret=${ENV:AUTH_OIDC_CLIENT_SECRET}"
    rem inspecto-policy.jar present => Enterprise (Standard + ABAC). No flag needed: the module
    rem is found via META-INF/services/com.gamma.control.AccessDecider, so the classpath IS the switch.
    if exist inspecto-policy.jar (
        set "CP=inspecto.jar;inspecto-security.jar;inspecto-policy.jar"
        set "EDITION=Enterprise"
    )
)
rem PostgreSQL JDBC driver sidecar (PG-1): present in Standard/Enterprise bundles, and honored on ANY
rem bundle so a drop-in works - the classpath entry is inert until -Dinspecto.db=postgres selects it.
if exist postgresql.jar set "CP=%CP%;postgresql.jar"
rem Operator-supplied extra JVM flags. Appended LAST, on purpose: the flags this script requires
rem (--enable-native-access=ALL-UNNAMED, port, spaces root, auth) are already in OPTS and cannot
rem be clobbered from the environment. INSPECTO_JAVA_OPTS wins over EXTRA_JAVA_OPTS. Deliberately
rem NOT named JAVA_OPTS: that name is ASSIGNED above, so setting it never reached the JVM - a
rem silently inert flag fabricates evidence (BUNDLE-1, 2026-08-18).
set "EXTRA_OPTS=%INSPECTO_JAVA_OPTS%"
if "%EXTRA_OPTS%"=="" set "EXTRA_OPTS=%EXTRA_JAVA_OPTS%"
if not "%EXTRA_OPTS%"=="" set "OPTS=%OPTS% %EXTRA_OPTS%"
if not "%EXTRA_OPTS%"=="" echo [serve.bat] extra JVM opts: %EXTRA_OPTS%
set "JAVA=java"
if exist "runtime\bin\java.exe" set "JAVA=runtime\bin\java.exe"
echo [serve.bat] ControlApi on :%PORT%  (spaces: .\%SPACES_ROOT%, edition: %EDITION%)
"%JAVA%" %OPTS% -cp %CP% com.gamma.control.ControlApi
'@
Write-CrlfScript -Path "$bundleDir\serve.bat" -Content $serveBatContent

# ── step 6b-2: Dockerfile wrapping serve.sh (PKG-3, backend-hardening plan item 6) ──────
# Containerized deployment over EXISTING seams only: serve.sh already reads PORT/SPACES_ROOT/
# CONTROL_TOKEN/... from the environment, so the Dockerfile adds no configuration surface of its
# own. The base image provides java; .dockerignore excludes the embedded runtime/ so serve.sh's
# `[ -x runtime/bin/java ]` preference misses and it falls back to the image JVM (an embedded
# per-platform runtime inside a container would be dead weight, and the Windows one can't run).
# HEALTHCHECK hits /health tokenless — correct, it is in ControlApi's PUBLIC_PATHS. It probes via
# bash /dev/tcp, NOT curl: eclipse-temurin:24-jre ships no curl/wget (verified 2026-08-28 — the
# plan's curl one-liner would have reported unhealthy forever), and bash is in the Ubuntu base.
$dockerfileContent = @'
# Build from an unzipped inspecto-deploy bundle:  docker build -t inspecto .
# Run:  docker run -p 8080:8080 -e CONTROL_TOKEN=... inspecto
# All serve.sh env vars pass straight through (-e PORT / SPACES_ROOT / ASSIST_TOKEN /
# CORS_ORIGIN / AUTH_OIDC_* / INSPECTO_JAVA_OPTS ...). Persist data by mounting the spaces
# root:  -v /srv/inspecto/spaces:/app/spaces
FROM eclipse-temurin:24-jre
WORKDIR /app
COPY . /app
ENV PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=15s CMD ["bash", "-c", \
  "exec 3<>/dev/tcp/localhost/${PORT} && printf 'GET /health HTTP/1.0\r\n\r\n' >&3 && grep -q '200' <&3"]
ENTRYPOINT ["./serve.sh"]
'@
Write-LfScript -Path "$bundleDir\Dockerfile" -Content $dockerfileContent
$dockerignoreContent = @'
# Keep the image lean: the base image supplies the JVM (serve.sh falls back to `java` when
# runtime/ is absent), and Windows-only launchers are dead weight in a Linux container.
runtime/
*.bat
inspecto-deploy*.zip*
'@
Write-LfScript -Path "$bundleDir\.dockerignore" -Content $dockerignoreContent

# ── step 6c: embed a trimmed Java runtime (jlink) so the bundle is self-contained ──
# Produces bundle/runtime/ — the run/serve/ura scripts auto-prefer it over system java.
# jlink is itself a JVM tool: the platform of the jlink *executable* need not match the platform
# being targeted, because --module-path selects which jmods (which carry the platform-native code)
# get assembled into the output image. We always invoke the Windows jlink.exe (host-executable) and
# vary --module-path to target either platform: omitted → that JDK's own (Windows) jmods; pointed at
# a Linux GraalVM cache's jmods/ → a Linux-native image, even though it's built on this Windows host.
function New-JlinkRuntime {
    param(
        [Parameter(Mandatory)] [string]$JlinkExe,
        [Parameter(Mandatory)] [string]$Modules,
        [Parameter(Mandatory)] [string]$OutputDir,
        [string]$ModulePath,      # target JDK's jmods dir; omit for a same-platform (Windows) build
        [Parameter(Mandatory)] [string]$PlatformLabel
    )
    Write-Host "Embedding trimmed Java runtime ($PlatformLabel) via $JlinkExe ..." -ForegroundColor Cyan
    if (Test-Path $OutputDir) { Remove-Item $OutputDir -Recurse -Force }
    $jlinkArgs = @('--add-modules', $Modules, '--strip-debug', '--no-header-files', '--no-man-pages', '--compress=zip-9', '--output', $OutputDir)
    if ($ModulePath) { $jlinkArgs = @('--module-path', $ModulePath) + $jlinkArgs }
    & $JlinkExe @jlinkArgs
    if ($LASTEXITCODE -ne 0) { throw "jlink failed ($PlatformLabel)" }
    $rtSize = [math]::Round(((Get-ChildItem $OutputDir -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), 1)
    Write-Host "Embedded runtime ready: $OutputDir (${rtSize} MB, $PlatformLabel)" -ForegroundColor Green
}

$builtLinuxRuntime = $false
if (-not $NoRuntime) {
    # Module set = jdeps core for inspecto.jar (java.base, java.compiler, java.desktop,
    # java.naming, java.scripting, java.sql, jdk.httpserver) + runtime-only safety modules that
    # jdeps cannot see in a fat JAR: jdk.crypto.ec (TLS/JDBC ciphers), jdk.unsupported
    # (sun.misc.Unsafe), java.net.http (HttpClient), jdk.zipfs (.zip via NIO), java.management (JMX).
    $runtimeModules = 'java.base,java.compiler,java.desktop,java.naming,java.scripting,java.sql,jdk.httpserver,jdk.crypto.ec,jdk.unsupported,java.net.http,jdk.zipfs,java.management'

    # Locate a jlink: prefer the resolved GraalVM cache, then JAVA_HOME, then PATH. This must be
    # the Windows jlink.exe (the host-executable tool) regardless of which target(s) we build.
    $jlink = $null
    if ($graalvmCacheDir) {
        $jlink = Get-ChildItem -Path $graalvmCacheDir -Filter 'jlink.exe' -Recurse -ErrorAction SilentlyContinue |
                 Select-Object -First 1 -ExpandProperty FullName
    }
    if (-not $jlink -and $env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\jlink.exe")) { $jlink = "$env:JAVA_HOME\bin\jlink.exe" }
    if (-not $jlink) { $jlink = (Get-Command jlink.exe -ErrorAction SilentlyContinue).Source }
    if (-not $jlink) { throw "jlink.exe not found (looked in .graalvm-cache ($graalvmCacheDir), JAVA_HOME, PATH). Re-run with -NoRuntime to skip embedding a JVM." }

    $runtimeOut = Join-Path $bundleDir 'runtime'
    New-JlinkRuntime -JlinkExe $jlink -Modules $runtimeModules -OutputDir $runtimeOut -PlatformLabel 'Windows'

    # Linux jmods dir: glob for it (don't pin the version string) so a cache refresh doesn't break this.
    $linuxJmods = $null
    if ($graalvmCacheDir) {
        $linuxJmods = Get-ChildItem -Path $graalvmCacheDir -Directory -Filter '*linux*' -ErrorAction SilentlyContinue |
                      ForEach-Object { Join-Path $_.FullName 'jmods' } |
                      Where-Object { Test-Path $_ } |
                      Select-Object -First 1
    }
    if ($linuxJmods) {
        $linuxRuntimeOut = Join-Path $sandboxRoot 'inspecto-deploy-linux-runtime'
        try {
            New-JlinkRuntime -JlinkExe $jlink -Modules $runtimeModules -OutputDir $linuxRuntimeOut -ModulePath $linuxJmods -PlatformLabel 'Linux'
            $builtLinuxRuntime = $true
        } catch {
            Write-Warning "Linux runtime build failed ($($_.Exception.Message)) — skipping $outZipLinux."
        }
    } else {
        Write-Host "  (no Linux jmods cache found under .graalvm-cache — skipping $outZipLinux)" -ForegroundColor Yellow
    }
} else {
    Write-Host "  (-NoRuntime: skipping embedded JVM; target server must provide Java 24+)" -ForegroundColor Yellow
}

# ── step 6d: bundle the DuckDB excel extension (multiformat X1), per platform ──
# ExcelExtension.ensureLoaded (inspecto-etl) loads it in three layers: LOAD (cached/preinstalled) ->
# LOAD from -Dduckdb.extension.dir (THIS step's whole purpose — an air-gapped deployment ships the
# file so frontend: xlsx works out of the box) -> INSTALL (networked fallback). Both platforms'
# binaries are bundled into the SAME $bundleDir (harmless — like run.sh sitting unused in the
# Windows zip): serve.bat/run.bat/ura.bat auto-detect windows_amd64, serve.sh/run.sh/ura.sh
# auto-detect linux_amd64, each only on its own OS. Best-effort: a platform whose binary isn't
# cached locally is a yellow warning, never a build failure — the fail-closed INSTALL fallback in
# ExcelExtension still covers a networked deployment.
$duckdbExtOut = Join-Path $bundleDir 'duckdb-extensions'
$bundledAnyExt = $false
if ($duckdbExtCacheDir) {
    foreach ($plat in @('windows_amd64', 'linux_amd64')) {
        $found = Get-ChildItem -Path $duckdbExtCacheDir -Recurse -Filter 'excel.duckdb_extension' -ErrorAction SilentlyContinue |
                 Where-Object { $_.FullName -match [regex]::Escape($plat) } | Select-Object -First 1
        if ($found) {
            $dest = Join-Path $duckdbExtOut $plat
            New-Item -ItemType Directory -Path $dest -Force | Out-Null
            Copy-Item $found.FullName -Destination (Join-Path $dest 'excel.duckdb_extension') -Force
            Write-Host "Bundled DuckDB excel extension ($plat) -> duckdb-extensions/$plat/" -ForegroundColor Green
            $bundledAnyExt = $true
        } else {
            Write-Host "  (no cached excel.duckdb_extension for $plat under $duckdbExtCacheDir — xlsx pipelines need network on first run, or a manual -Dduckdb.extension.dir)" -ForegroundColor Yellow
        }
    }
} else {
    Write-Host "  (skipping excel extension bundling — no cache resolved; xlsx pipelines need network on first run, or a manual -Dduckdb.extension.dir)" -ForegroundColor Yellow
}

# ── step 7: copy README + docs tree ─────────────────────────────────────────────
# In the repo the README lives in inspecto/ and links to ../docs/. In the
# bundle the README sits at the root, so rewrite ../docs/ → docs/ and ship the
# docs tree alongside it so the links resolve.
$readme = Get-Content "$adjParserDir\README.md" -Raw
$readme = $readme -replace '\.\./docs/', 'docs/'
Set-Content -Path "$bundleDir\README.md" -Value $readme -NoNewline
$docsSrc = Join-Path $sandboxRoot 'docs'
if (Test-Path $docsSrc) {
    # Copy file-by-file (not one Copy-Item -Recurse) so a single locked/inaccessible
    # file (e.g. held by another process/AV) can't silently truncate the rest of the
    # tree under $ErrorActionPreference = 'Stop' — a whole-tree recursive copy was
    # observed to abort at the first such file and skip every remaining item.
    $docsOut = "$bundleDir\docs"
    Get-ChildItem -Path $docsSrc -Recurse -File | ForEach-Object {
        $srcFile = $_
        $destPath = Join-Path $docsOut $srcFile.FullName.Substring($docsSrc.Length + 1)
        try {
            $destDir = Split-Path $destPath -Parent
            if (-not (Test-Path $destDir)) { New-Item -ItemType Directory -Path $destDir -Force | Out-Null }
            Copy-Item $srcFile.FullName -Destination $destPath -Force -ErrorAction Stop
        } catch {
            Write-Warning "docs copy: skipped '$($srcFile.FullName)' ($($_.Exception.Message))"
        }
    }
}

# ── step 8: zip (Windows bundle, then swap runtime/ and zip again for Linux) ───
if (Test-Path $outZip) { Remove-Item $outZip -Force }
Compress-Archive -Path $bundleDir -DestinationPath $outZip

if ($builtLinuxRuntime) {
    # Common bundle content (jar, config, docs, UI, scripts) was already assembled once above;
    # only the runtime/ folder differs per target, so swap it in place and re-zip rather than
    # rebuilding the whole bundle a second time.
    $windowsRuntimeOut = Join-Path $bundleDir 'runtime'
    $windowsRuntimeTmp = Join-Path $sandboxRoot 'inspecto-deploy-windows-runtime'
    if (Test-Path $windowsRuntimeTmp) { Remove-Item $windowsRuntimeTmp -Recurse -Force }
    Move-Item $windowsRuntimeOut $windowsRuntimeTmp
    Move-Item (Join-Path $sandboxRoot 'inspecto-deploy-linux-runtime') $windowsRuntimeOut

    if (Test-Path $outZipLinux) { Remove-Item $outZipLinux -Force }
    Compress-Archive -Path $bundleDir -DestinationPath $outZipLinux

    # Restore the Windows runtime so $bundleDir on disk matches $outZip (in case anything inspects it).
    Remove-Item $windowsRuntimeOut -Recurse -Force
    Move-Item $windowsRuntimeTmp $windowsRuntimeOut
}

# ── step 8b: release integrity — SHA-256 checksums (+ optional GPG signatures) [SOC 2 CC8-04] ──
# Emit a sha256sum-compatible checksum file next to each artifact so customers can verify integrity
# (Linux: `sha256sum -c <zip>.sha256`; Windows: `Get-FileHash <zip> -Algorithm SHA256`). When -Sign is
# passed and gpg + a signing key are available, also emit a detached signature (<zip>.asc) so customers
# can verify AUTHENTICITY. Customer steps: compliance/soc2/CC8-04-release-verification.md.
function New-ReleaseIntegrity {
    param([Parameter(Mandatory)][string]$ArtifactPath)
    if (-not (Test-Path $ArtifactPath)) { return }
    $name = Split-Path -Leaf $ArtifactPath
    $hash = (Get-FileHash -Path $ArtifactPath -Algorithm SHA256).Hash.ToLower()
    # sha256sum format: "<hash>  <filename>" (two spaces), bare filename so `sha256sum -c` resolves it
    # when run from the artifact's directory.
    [System.IO.File]::WriteAllText("$ArtifactPath.sha256", "$hash  $name`n", [System.Text.Encoding]::ASCII)
    Write-Host "  SHA-256  $name = $hash" -ForegroundColor DarkGray

    if ($Sign) {
        $gpg = (Get-Command gpg -ErrorAction SilentlyContinue).Source
        if (-not $gpg) {
            Write-Warning "  -Sign requested but 'gpg' not found on PATH — skipping signature for $name."
            return
        }
        if (-not $SigningKey) {
            Write-Warning "  -Sign requested but no signing key (pass -SigningKey or set INSPECTO_SIGNING_KEY) — skipping signature for $name."
            return
        }
        $ascFile = "$ArtifactPath.asc"
        if (Test-Path $ascFile) { Remove-Item $ascFile -Force }
        & $gpg --batch --yes --local-user $SigningKey --armor --detach-sign --output $ascFile $ArtifactPath
        if ($LASTEXITCODE -ne 0) { throw "gpg detached-sign failed for $name" }
        Write-Host "  Signed   $name -> $ascFile (key: $SigningKey)" -ForegroundColor Green
    }
}

$sigNote = if ($Sign) { ' + GPG signature' } else { '' }
Write-Host "Generating release integrity artifacts (SHA-256$sigNote)..." -ForegroundColor Cyan
New-ReleaseIntegrity -ArtifactPath $outZip
if ($builtLinuxRuntime) { New-ReleaseIntegrity -ArtifactPath $outZipLinux }

Write-Host ""
Write-Host "Deployment bundle ready:" -ForegroundColor Green
Write-Host "  $outZip  (+ .sha256$sigNote)"
if ($builtLinuxRuntime) { Write-Host "  $outZipLinux  (+ .sha256$sigNote)" }
# A bundle with no ui/ still starts and still serves /api/v1 — but every browser hit returns
# ControlApi's `{"error":"not found — API routes are served under /api/v1"}` 404, which looks like a
# broken deployment rather than a packaging choice. It shipped that way once (2026-07-31) precisely
# because nothing said so. Verify the assembled bundle, not just the copy flag.
if (-not (Test-Path (Join-Path $bundleDir 'ui\index.html'))) {
    Write-Host ""
    Write-Warning "NO OPERATOR UI IN THIS BUNDLE (no ui/index.html)."
    Write-Warning "  serve.sh/serve.bat will start WITHOUT -Dui.dir, so http://<host>:<port>/ answers"
    Write-Warning "  404 {`"error`":`"not found - API routes are served under /api/v1`"} in the browser."
    Write-Warning "  The /api/v1 surface still works; only the SPA is missing."
    if ($NoUi)             { Write-Warning "  Cause: -NoUi was passed and inspecto-ui/dist holds no index.html — build the UI first (npm run build in inspecto-ui/)." }
    elseif (-not $uiBuilt) { Write-Warning "  Cause: no inspecto-ui/ project found in this checkout." }
    else                   { Write-Warning "  Cause: the UI build produced no index.html under $uiDistRoot." }
    Write-Host ""
}

Write-Host ""
Write-Host "Deploy to remote server:" -ForegroundColor Cyan
Write-Host "  1. Copy $outZip to the server"
Write-Host "  2. Expand-Archive inspecto-deploy.zip   (PowerShell)"
Write-Host "     or:  unzip inspecto-deploy.zip       (Linux)"
Write-Host "  3. cd inspecto-deploy"
Write-Host "  4. ETL pipeline (one-shot):"
Write-Host "       run.bat voucher         (Windows)"
Write-Host "       bash run.sh voucher     (Linux)"
Write-Host "  4b. Control plane + operator UI (long-running service):"
Write-Host "       set CONTROL_TOKEN=secret && serve.bat        (Windows)"
Write-Host "       CONTROL_TOKEN=secret bash serve.sh           (Linux)"
Write-Host "       then open http://localhost:8080/  (UI served from ./ui)"
Write-Host "  5. Pre-ETL utilities:"
Write-Host "       ura.bat help            (Windows)"
Write-Host "       bash ura.sh help        (Linux)"
Write-Host "       bash ura.sh search  spaces/ucc/config/voucher/voucher_pipeline.toon"
Write-Host "       bash ura.sh backup  spaces/ucc/config/voucher/voucher_pipeline.toon"
Write-Host "  6. Try the worked feature examples (self-contained, synthetic data):"
Write-Host "       pwsh examples/run-example.ps1 01-ingest/hello-csv     (Windows)"
Write-Host "       bash examples/run-example.sh  01-ingest/hello-csv     (Linux)"
Write-Host "     or run one as a service (poll loop + Control API probes):"
Write-Host "       pwsh examples/serve-example.ps1 06-serve/sequence-gap --demo"
Write-Host "       see examples/README.md for the full catalog"
Write-Host ""
if (-not $NoRuntime) {
    Write-Host "Embedded Java runtime included (bundle\runtime\) — no JVM needed on the target."
    if ($builtLinuxRuntime) {
        Write-Host "  $outZip        → Windows-native embedded JVM" -ForegroundColor Green
        Write-Host "  $outZipLinux  → Linux-native embedded JVM" -ForegroundColor Green
    } else {
        Write-Host "  $outZip → Windows-native embedded JVM" -ForegroundColor Green
        Write-Host "  (no Linux GraalVM jmods cache found — inspecto-deploy-linux.zip not built;" -ForegroundColor Yellow
        Write-Host "   the bundled *.sh launchers fall back to system java on Linux instead.)" -ForegroundColor Yellow
    }
    Write-Host "The run/serve/ura launchers auto-prefer the embedded runtime when present."
} else {
    Write-Host "Java 24+ required on the target server.  No other dependencies needed."
}
