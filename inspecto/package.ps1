# package.ps1 — Build and bundle inspecto for remote server deployment.
#
# Usage (run from inside inspecto/ or from the sandbox root):
#   pwsh -File inspecto\package.ps1 [-NoBuild] [-Edition Professional|Enterprise]
#
# Run under pwsh 7: this file is BOM-less UTF-8 and Windows PowerShell 5.1 garbles its non-ASCII
# characters (see .claude/skills/build-verify/SKILL.md).
#
# -Edition Enterprise is Professional + inspecto-policy (the ABAC AccessDecider SPI implementation),
# bundled as inspecto-policy.jar. It needs NO extra flag: the module is discovered purely
# through META-INF/services/com.gamma.spi.auth.AccessDecider, so being on the classpath is what
# turns policy evaluation on. (P3d: the classpath is the bundle's modules.list, written by tools/offering-classpath.mjs from the
# Offering, and the edition is its edition.properties - the launchers READ both; they no longer sniff which jars are present.)
#
# -Edition Professional (default: Personal; 'Standard' accepted as legacy alias) additionally builds
# inspecto-oidc (W6, the OIDC Authenticator SPI implementation; split out of inspecto-security by D-MR6, together with inspecto-secrets and inspecto-geo-country) and bundles it as
# inspecto-oidc.jar; modules.list puts it on the classpath and edition.properties makes serve.sh/serve.bat
# turn on -Dauth.mode=oidc. Professional/Enterprise also bundle the PostgreSQL JDBC driver as
# postgresql.jar (PG-1) — listed in modules.list, inert until -Dinspecto.db=postgres; the fat
# JAR stays driver-free. (issuer/JWKS/audience from AUTH_OIDC_* env vars — never baked into the bundle).
# The embedded jlink runtime's module set (below) is VERIFIED sufficient for inspecto-oidc too
# (PKG-4, 2026-07-07): jdeps on inspecto-oidc.jar + Nimbus JOSE+JWT 10.9.1 needs nothing beyond
# java.base/java.sql/java.net.http/jdk.httpserver, and RS256/ES256 resolve via SunRsaSign/SunEC
# (jdk.crypto.ec) on a jlink image built from exactly this list — Professional bundles may embed the
# runtime. jlink can target either platform from a Windows host by pointing --module-path at the
# target JDK's jmods; -NoRuntime skips both. ⚠ The invoked tool is the HOST's (`jlink.exe` on Windows,
# `jlink` elsewhere) — hardcoding the `.exe` is what kept CI from ever embedding a runtime (OPS-07).
#
# Output (RELEASE-BUNDLE-PLATFORM-MISMATCH-1, 2026-09-17 — one zip PER TARGET PLATFORM, named for it):
#   inspecto-deploy-<platform>.zip   where <platform> is read off the runtime ACTUALLY embedded
#                                    (Get-RuntimePlatform: bin\java.exe → windows_amd64, ELF bin/java →
#                                    linux_amd64), never assumed from the host or hard-coded.
#     · the HOST's own platform is always produced (jlink links a host image with no jmods cache);
#     · linux_amd64 is ADDITIONALLY cross-built on a Windows host when a Linux GraalVM jmods cache
#       is present under .graalvm-cache. A failed cross-build THROWS unless -AllowPartialRuntime.
#   🔴 Before this the host image (LINUX on ubuntu-latest) was always zipped as `inspecto-deploy.zip`
#   with the windows_amd64 extension set — a Linux JVM that could load none of its DuckDB extensions.
#   (all in the sandbox root, alongside inbox/ and database/)
#
# Both bundles also carry duckdb-extensions/{windows_amd64,linux_amd64}/{excel,ducklake,postgres_scanner}.duckdb_extension
# when a local DuckDB extension cache is found — none is statically linked into duckdb_jdbc, so an
# air-gapped deployment needs the files shipped (multiformat X1 for `excel`/frontend: xlsx;
# AIRGAP-EXTENSIONS-1 for `ducklake`/output.ducklake.enabled). See DuckDbExtension.tryLoad and
# -DuckdbExtensionCache below. Missing = a warning, never a build failure — serve/run/ura auto-detect
# the directory and set -Dduckdb.extension.dir to it, and from then on (D-8, 2026-09-24) the loader
# reads ONLY that directory: a missing binary fails its feature loudly, naming the file, and never
# falls back to a networked INSTALL. A bundle staging nothing for a platform keeps the INSTALL path.
#
# The zip is a self-contained deployment unit.  On the target server:
#   1. Unzip inspecto-deploy.zip  →  inspecto-deploy/
#   2. Create your inbox directories under inspecto-deploy/inbox/<adapter>/
#   3. Use the bundled run.sh / run.bat (RUNSH-CP-1): they cd to the bundle root and launch with
#      -cp <modules.list>, NEVER java -jar (which ignores -cp and CLASSPATH outright,
#      making every sidecar unreachable) — see the run.sh / run.bat here-strings in step 5.
#      (or use the bundled run.bat / run.sh — they cd to the bundle root automatically)
#
param(
    [switch]$NoBuild,   # skip mvn build; use existing JAR in target/
    [switch]$NoUi,      # skip the Angular UI build/bundle (inspecto-ui/ is optional)
    [switch]$NoRuntime, # skip embedding a trimmed Java runtime (target server must then provide Java 24+)
    # PKG-LINUX-RUNTIME-WARNS-1: a failed linux_amd64 CROSS-build (Windows host + Linux jmods cache) is a
    # THROW by default — a release must not quietly lose a platform. Pass this to downgrade it to a warning
    # and ship only the host platform's zip.
    [switch]$AllowPartialRuntime,
    # Boot smoke (SEC-SIDECAR-BOOT-1 follow-up, 2026-09-07). The staged-artifact checks below assert that
    # Nimbus and the SPI files are PRESENT; that is not the same as ControlApi actually starting. The bug
    # they were written for was a boot failure that every green test suite missed, so packaging now
    # launches the bundle it just built and waits for /health. -SkipBootCheck opts out for a fast local
    # package; CI and releases must never pass it.
    [switch]$SkipBootCheck,
    # Editions are build flavors (docs/EDITIONS.md), never branches. 'Professional' additionally builds
    # and bundles inspecto-oidc (W6, the Authenticator SPI's OIDC implementation) alongside the
    # core jar; serve.sh/serve.bat auto-detect its presence and wire -Dauth.mode=oidc from env vars.
    # 'Enterprise' is Professional PLUS inspecto-policy (the ABAC AccessDecider SPI impl) — the same
    # superset relation the -Pedition-enterprise Maven profile encodes, so it bundles BOTH extra jars.
    # 'Preview' is NOT a customer-facing tier (operator decision 2026-09-21): it is Enterprise's exact
    # staging path internally (byte-identical bundle today), but is labeled 'Preview' in the SBOM and
    # is meant for testing/incubation — a module still in incubation ships here first via
    # -Pedition-preview (pom.xml) / tools/bundle-modules.mjs, without an edition decision being made
    # for it yet.
    [ValidateSet('Personal', 'Professional', 'Standard', 'Enterprise', 'Preview')]
    [string]$Edition = 'Personal',
    # DEMO-AUTH-1: an OFFLINE DEMO BUILD, never an edition. Requires -Edition Enterprise; swaps
    # inspecto-oidc.jar for inspecto-demo-auth.jar (the Demo User sign-in), assembles into
    # inspecto-demo/ (NOT inspecto-deploy/, which other launch configs run from), writes serve-demo.bat /
    # serve-demo.sh bound to 127.0.0.1, removes serve.* and the service installers (without the security
    # jar they would boot an auth-free server on every interface), and zips as inspecto-demo-<platform>.zip.
    # The demo jar is deliberately outside the three jar enumerations tools/check-sbom-modules.mjs parses,
    # because no edition ships it; the SBOM therefore still describes the Enterprise module set.
    # tools/check-demo-auth-isolation.mjs (CI) fails if the demo jar is named anywhere here outside an
    # `if ($DemoAuth)` block, or if the demo branch stops removing inspecto-oidc.jar. The demo bundle's modules.list comes from
    # tools/offering-classpath.mjs --demo (the OIDC trio replaced by the demo jar), which serve-demo.* read like any launcher.
    [switch]$DemoAuth,
    # UI flavor (D-5 step 7, la-separation-d5-design Decisions 6/7): WHICH single-page application the bundle's ui/ holds.
    # NOT an edition (EDITIONS.md): every edition can ship either. 'gamma' is the Inspecto console (the default, the
    # bundle as it always was); 'la-app' is the Link Analysis application (inspecto-ui/projects/la-app). The two
    # Angular applications build to inspecto-ui/dist/<name>/, and ONLY the chosen one is copied: ui/ never holds both.
    # The IAM client id the chosen SPA signs in with is a DEPLOYMENT setting (AUTH_OIDC_CLIENT_ID), not decided here.
    [ValidateSet('gamma', 'la-app')]
    [string]$Ui = 'gamma',
    # ── release integrity (SOC 2 CC8-04) ──
    # SHA-256 checksums are ALWAYS written next to each artifact (no key needed). -Sign additionally
    # produces a GPG detached signature (.asc) per artifact so customers can verify AUTHENTICITY, not
    # just integrity. Provide the key via -SigningKey or $env:INSPECTO_SIGNING_KEY — never bake a key
    # into the repo/bundle (see compliance/soc2/policies/06-cryptography-policy.md).
    [switch]$Sign,
    [string]$SigningKey = $env:INSPECTO_SIGNING_KEY,
    # PER-JAR signing (MODULE-REORG-1 P3h): -SignJars signs EVERY first-party jar in the bundle (inspecto*.jar: the processor, the
    # thin core jars, the optional modules, the sidecars, the demo-auth jar) with `jarsigner` BEFORE the SBOMs are generated, so the
    # per-module SBOM hashes describe the SIGNED bytes; then tools/check-jar-signatures.mjs asserts every jar verifies and all share
    # one signer certificate. Independent of -Sign (the GPG signature over the ZIP): a release uses both. Third-party standalone jars
    # (postgresql.jar) are vendor-owned and left alone. The keystore PASSWORD is read from $env:INSPECTO_JARSIGN_STOREPASS only -
    # never a parameter, never logged, never written into the bundle or the SBOMs. Without -SignJars nothing here runs and the
    # bundle is byte-identical to before. -TsaUrl adds a trusted timestamp (jarsigner -tsa) so the signature outlives the
    # certificate; an offline build omits it, a production release must pass it.
    [switch]$SignJars,
    [string]$JarKeystore = $env:INSPECTO_JARSIGN_KEYSTORE,
    [string]$JarKeystoreType = 'PKCS12',
    [string]$JarAlias = $env:INSPECTO_JARSIGN_ALIAS,
    [string]$TsaUrl = '',
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
    [string]$DuckdbExtensionCache = '',
    # Turn the best-effort extension staging above into a FAIL-CLOSED gate (AIRGAP-EXTENSIONS-CI-1).
    #
    # ⛔ Why this is a switch and not the default: a developer with no cache must still be able to build,
    # which is exactly why the warning exists. But a RELEASE that quietly ships an empty
    # duckdb-extensions/ is the defect this flag was filed for — measured 2026-09-14, EVERY released
    # bundle had shipped that way on every platform, because CI populated no cache and nothing failed.
    # release.yml passes this for the same reason it passes -Sign: the release path is stricter than the
    # desk path, and the difference is stated rather than assumed.
    [switch]$RequireExtensions
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
# platform → zip path, filled in step 8 as inspecto-deploy-<platform>.zip. The key is what
# Get-RuntimePlatform read off the embedded runtime, so name, JVM and extension set cannot disagree.
$outZips      = [ordered]@{}

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
if ($DemoAuth) {
    if ($Edition -ne 'Enterprise') { throw "-DemoAuth builds an Enterprise-capability demo; pass -Edition Enterprise (got '$Edition')." }
    $bundleDir = Join-Path $sandboxRoot 'inspecto-demo'
}

# MODULE-REORG-1 P3a: ONE build id for every jar of this bundle - the git short sha, stamped into each jar's manifest
# as Inspecto-Build-Id by the parent pom (-Dinspecto.build.id). A checkout without git builds as 'dev' (unknown: never a mismatch).
$buildId = 'dev'
try { $sha = (& git -C $sandboxRoot rev-parse --short HEAD 2>$null); if ($LASTEXITCODE -eq 0 -and $sha) { $buildId = "$sha".Trim() } } catch { }
Write-Host "Build id: $buildId" -ForegroundColor DarkGray
# ── per-jar signing (-SignJars, P3h): preconditions + the one signing function ───────────────────────────
$jarsignerExe = $null
if ($SignJars) {
    if (-not $JarKeystore -or -not (Test-Path -LiteralPath $JarKeystore)) { throw "-SignJars needs -JarKeystore <path> (or INSPECTO_JARSIGN_KEYSTORE) naming an existing keystore" }
    if (-not $JarAlias) { throw "-SignJars needs -JarAlias (or INSPECTO_JARSIGN_ALIAS)" }
    if (-not $env:INSPECTO_JARSIGN_STOREPASS) { throw "-SignJars needs the keystore password in `$env:INSPECTO_JARSIGN_STOREPASS (never a command-line argument)" }
    if ($env:JAVA_HOME) { foreach ($cand in 'bin\jarsigner.exe','bin/jarsigner') { $c = Join-Path $env:JAVA_HOME $cand; if (Test-Path $c) { $jarsignerExe = $c; break } } }
    if (-not $jarsignerExe) { $jarsignerExe = (Get-Command jarsigner -ErrorAction SilentlyContinue).Source }
    if (-not $jarsignerExe) { throw "-SignJars needs the JDK's jarsigner (set JAVA_HOME or put the JDK bin on PATH)" }
    if (-not $TsaUrl) { Write-Host "  -SignJars without -TsaUrl: jars are signed WITHOUT a trusted timestamp (fine offline; a production release must pass -TsaUrl)" -ForegroundColor Yellow }
}
function Invoke-JarSign {
    param([Parameter(Mandatory)][string]$Jar)
    $jsArgs = @('-keystore', $JarKeystore, '-storetype', $JarKeystoreType, '-storepass:env', 'INSPECTO_JARSIGN_STOREPASS')
    if ($TsaUrl) { $jsArgs += @('-tsa', $TsaUrl) }
    $jsArgs += @($Jar, $JarAlias)
    $out = & $jarsignerExe @jsArgs 2>&1
    if ($LASTEXITCODE -ne 0) { throw "jarsigner failed for $(Split-Path $Jar -Leaf): $($out -join ' | ')" }
    $out = & $jarsignerExe -verify $Jar 2>&1
    if ($LASTEXITCODE -ne 0 -or -not ($out -match 'jar verified')) { throw "signed jar does not verify: $(Split-Path $Jar -Leaf): $($out -join ' | ')" }
}

# ── step 1: build ─────────────────────────────────────────────────────────────
# Built from the repo root with -pl inspecto -am (same idiom as step 1c) because since S5 the
# core depends on reactor siblings (inspecto-api, …) — a core-alone build from inspecto/
# would only resolve them after a root `mvn install`. -am builds the needed siblings in-pass;
# the shaded JAR still lands in inspecto/target/.
if (-not $NoBuild) {
    Write-Host "Building fat JAR (skipping tests)..." -ForegroundColor Cyan
    Push-Location $sandboxRoot
    # CONNECTORS-BUNDLE-1: `inspecto-connectors` is NOT upstream of `inspecto`, so `-am` (which walks
    # upstream only) never reaches it - it has to be named explicitly or the sidecar is silently absent.
    & mvn clean package "-Dinspecto.build.id=$buildId" -pl inspecto,:inspecto-connectors -am -DskipTests -q
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
    Write-Host "Building operator UI (inspecto-ui/, application '$Ui')..." -ForegroundColor Cyan
    Push-Location $uiDir
    try {
        & npm ci
        if ($LASTEXITCODE -ne 0) { throw "npm ci failed in inspecto-ui/" }
        & npm run build -- $Ui
        if ($LASTEXITCODE -ne 0) { throw "ng build $Ui failed in inspecto-ui/" }
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

# -- step 1b-bis: the remote-connector sidecar (CONNECTORS-BUNDLE-1, 2026-09-07) ---------------
# Until now this module was built by CI, unit-tested, and shipped by nothing: no pom depended on it and
# no copy step existed, so SFTP/FTP/FTPS/S3/GCS/Azure/Kafka and SmtpEmailChannel were unreachable in every
# bundle. Its founding commit described the drop-in ("dropping THIS jar on the classpath is what lights up
# the sftp:/ftp: schemes") but the delivery half was never built. It ships SHADED (classifier `sidecar`)
# because a thin jar is useless: sshj/commons-net would be missing. (The javax.mail half of
# that argument moved to inspecto-notify-channels with SmtpEmailChannel on 2026-09-07, EDG-01 cell 1.)
$connectorsTargetDir = Join-Path $sandboxRoot 'providers\inspecto-connectors\target'
$connectorsJarSrc = Get-ChildItem -Path $connectorsTargetDir -Filter 'inspecto-connectors-*-sidecar.jar' -ErrorAction SilentlyContinue |
          Select-Object -First 1 -ExpandProperty FullName
if (-not $connectorsJarSrc -or -not (Test-Path $connectorsJarSrc)) {
    throw "Connector sidecar not found matching $connectorsTargetDir\inspecto-connectors-*-sidecar.jar. Run without -NoBuild, or build with: mvn package -pl :inspecto-connectors -am -DskipTests"
}

# ── step 1c: Professional/Enterprise editions — build the optional edition modules ─────────────────
# Separate optional modules (docs/EDITIONS.md), NOT in the default reactor <modules> — only built
# when the profile is requested, from the repo root (they are siblings of inspecto/, not submodules).
# Enterprise is a SUPERSET of Professional (the -Pedition-enterprise profile = edition-professional + policy),
# so it bundles the OIDC jar too — an Enterprise deployment authenticates AND authorizes.
$oidcJarSrc = $null
$secretsJarSrc = $null
$geoCountryJarSrc = $null
$policyJarSrc   = $null
$laStorePgJarSrc = $null  # Enterprise/Preview only (set below); strict mode throws on the read at the bundling step for Professional
$channelsJarSrc = $null
$backupJarSrc   = $null
$geoLinkJarSrc  = $null
$exchangeJarSrc = $null
$obsJarSrc      = $null
$opsJarSrc      = $null
$agentJarSrc    = $null
$intelligenceJarSrc = $null
$kafkaJarSrc = $null
$asn1JarSrc = $null
$entityListJarSrc = $null
$laGraphJarSrc = $null
$laStorageJarSrc = $null
$laCoreJarSrc = $null
$laApiJarSrc = $null
$reconJarSrc = $null
$scoringJarSrc = $null
$caseMgmtJarSrc = $null
$actionReqJarSrc = $null
$regReportJarSrc = $null
$screeningJarSrc = $null
$anomalyJarSrc = $null
if ($Edition -eq 'Standard') { $Edition = 'Professional' }
if ($Edition -ne 'Personal') {
    # NB: not $profile — that is a PowerShell automatic variable.
    $editionProfile = if ($Edition -eq 'Preview') { 'edition-preview' } elseif ($Edition -eq 'Enterprise') { 'edition-enterprise' } else { 'edition-professional' }
    # EDG-01 cell 1: inspecto-notify-channels rides with the OIDC provider in BOTH non-Personal editions — CP-15
    # is "Professional and above", and Enterprise is a superset of Professional.
    # EDG-01 cell 2: inspecto-backup (OPS-06) rides alongside, Professional and above.
    # SEP-08: inspecto-entity-list (Entity Lists + the shared fact log; inspecto-geo-link depends on it) rides
    # alongside, Professional and above.
    # EDG-01 cell 3b: inspecto-geo-link (CP-09) rides alongside, Professional and above.
    # EDG-01 cell 4: inspecto-exchange (SEC-10) rides alongside, Professional and above.
    # EDG-01 cells 5+6 / MODULE-REORG-1 P7: inspecto-observability (CP-13: the /metrics exposition + the /events* feed), Professional and above.
    # EDG-01 cell 7: inspecto-ops (CP-11 operational objects), Professional and above.
    # PKG-5 (operator decision 2026-09-12): the assistant is PROFESSIONAL AND ABOVE. Taken first as
    # "all editions, optional" and narrowed the same day once the sidecar's weight was measured -
    # Personal already carries the ~32 MB connector sidecar. NB inspecto-agent is in the DEFAULT
    # reactor (not profile-scoped like the modules beside it); it is listed here only so this pass
    # builds its `sidecar` artifact for the editions that stage it.
    # ASSURE-INTELLIGENCE-BUNDLE-1 (D-P2): inspecto-intelligence (the /agent/* agent, onnxruntime inside)
    # is ENTERPRISE only - also a default-reactor module, listed so this pass builds its `sidecar`.
    # MODULE-REORG-P3d stage 1: the `-pl` list comes from the Offering (tools/offering-classpath.mjs resolves offerings/<edition>.toon,
    # asserts it equals tools/bundle-modules.mjs's set, and prints every module the edition adds beyond the default reactor) - it is
    # no longer a second hand-kept list beside the staging steps and the launchers.
    $modules = ((& node (Join-Path $sandboxRoot 'tools\offering-classpath.mjs') --edition $Edition --list-mvn) | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $modules) { throw "tools/offering-classpath.mjs --list-mvn failed for the $Edition edition (see above)" }
    if (-not $NoBuild) {
        Write-Host "Building $modules ($Edition edition, -P$editionProfile)..." -ForegroundColor Cyan
        Push-Location $sandboxRoot
        & mvn clean package "-P$editionProfile" "-Dinspecto.build.id=$buildId" -pl $modules -am -DskipTests -q
        if ($LASTEXITCODE -ne 0) { throw "mvn build of the $Edition edition modules failed" }
        Pop-Location
    }
    $oidcTargetDir = Join-Path $sandboxRoot 'providers\inspecto-oidc\target'
    # SEC-SIDECAR-BOOT-1 (2026-09-07): the SHADED jar, not the thin one. Until now this copied the plain
    # 16 KB artifact, which carries no com/nimbusds classes at all - so every Professional/Enterprise bundle
    # died at boot, because ControlApi resolves the Authenticator SPI during startup through an unguarded
    # ServiceLoader and OidcAuthenticator needs Nimbus. The glob is '-sidecar' on purpose: a bare
    # 'inspecto-oidc-*.jar' now matches BOTH artifacts and would pick one by luck.
    $oidcJarSrc = Get-ChildItem -Path $oidcTargetDir -Filter 'inspecto-oidc-*-sidecar.jar' -ErrorAction SilentlyContinue |
                       Select-Object -First 1 -ExpandProperty FullName
    if (-not $oidcJarSrc -or -not (Test-Path $oidcJarSrc)) {
        throw "$Edition edition requested but no SHADED JAR found matching $oidcTargetDir\inspecto-oidc-*-sidecar.jar. The thin jar is not usable - it carries no Nimbus classes."
    }
    # MODULE-REORG-1 D-MR6: inspecto-security became three provider modules. The secrets provider has no
    # third-party dependency (THIN jar, like inspecto-backup); the geo-country resolver carries maxmind-db
    # (SHADED, '-sidecar', same reasoning as the OIDC one above).
    $secretsJarSrc = Get-ChildItem -Path (Join-Path $sandboxRoot 'providers\inspecto-secrets\target') -Filter 'inspecto-secrets-*.jar' -ErrorAction SilentlyContinue |
                       Where-Object { $_.Name -notmatch '(sources|javadoc|tests)\.jar$' } | Select-Object -First 1 -ExpandProperty FullName
    if (-not $secretsJarSrc -or -not (Test-Path $secretsJarSrc)) {
        throw "$Edition edition requested but no jar found matching providers\inspecto-secrets\target\inspecto-secrets-*.jar."
    }
    $geoCountryJarSrc = Get-ChildItem -Path (Join-Path $sandboxRoot 'providers\inspecto-geo-country\target') -Filter 'inspecto-geo-country-*-sidecar.jar' -ErrorAction SilentlyContinue |
                       Select-Object -First 1 -ExpandProperty FullName
    if (-not $geoCountryJarSrc -or -not (Test-Path $geoCountryJarSrc)) {
        throw "$Edition edition requested but no SHADED JAR found matching providers\inspecto-geo-country\target\inspecto-geo-country-*-sidecar.jar. The thin jar carries no maxmind-db classes."
    }
    # MODULE-REORG-1 P7: the Kafka stream connector is its own premium module (kafka-clients is the one
    # connector footprint that is distinct). SHADED ('-sidecar'): the thin jar carries no kafka-clients.
    # Professional and above ONLY - Personal's inspecto-connectors.jar no longer registers a Kafka factory.
    $kafkaJarSrc = Get-ChildItem -Path (Join-Path $sandboxRoot 'providers\inspecto-connectors-kafka\target') -Filter 'inspecto-connectors-kafka-*-sidecar.jar' -ErrorAction SilentlyContinue |
                       Select-Object -First 1 -ExpandProperty FullName
    if (-not $kafkaJarSrc -or -not (Test-Path $kafkaJarSrc)) {
        throw "$Edition edition requested but no SHADED JAR found matching providers\inspecto-connectors-kafka\target\inspecto-connectors-kafka-*-sidecar.jar. The thin jar carries no kafka-clients classes."
    }
    # MODULE-REORG-1 P7: the ASN.1 BER decoder is the Telecom industry pack (inspecto-telecom-asn1). SHADED
    # ('-sidecar'): the thin jar carries no asn-facade/asn-core classes. Professional and above ONLY - Personal's
    # inspecto.jar no longer registers an asn1 parser, and the Step Processor catalog reports it not installed.
    $asn1JarSrc = Get-ChildItem -Path (Join-Path $sandboxRoot 'providers\inspecto-telecom-asn1\target') -Filter 'inspecto-telecom-asn1-*-sidecar.jar' -ErrorAction SilentlyContinue |
                      Select-Object -First 1 -ExpandProperty FullName
    if (-not $asn1JarSrc -or -not (Test-Path $asn1JarSrc)) {
        throw "$Edition edition requested but no SHADED JAR found matching providers\inspecto-telecom-asn1\target\inspecto-telecom-asn1-*-sidecar.jar. The thin jar carries no asn-facade classes."
    }
    # The channels sidecar (EDG-01 cell 1). SHADED for the same reason the security one is: the thin jar
    # carries no javax.mail, and NotificationService discovering SmtpEmailChannel without it kills boot
    # with NoClassDefFoundError: javax/mail/Message. The '-sidecar' glob is deliberate - a bare
    # 'inspecto-notify-channels-*.jar' matches BOTH artifacts and would pick one by luck.
    $channelsTargetDir = Join-Path $sandboxRoot 'providers\inspecto-notify-channels\target'
    $channelsJarSrc = Get-ChildItem -Path $channelsTargetDir -Filter 'inspecto-notify-channels-*-sidecar.jar' -ErrorAction SilentlyContinue |
                       Select-Object -First 1 -ExpandProperty FullName
    if (-not $channelsJarSrc -or -not (Test-Path $channelsJarSrc)) {
        throw "$Edition edition requested but no SHADED JAR found matching $channelsTargetDir\inspecto-notify-channels-*-sidecar.jar. The thin jar is not usable - it carries no javax.mail classes."
    }
    # The backup module (EDG-01 cell 2). THIN like inspecto-policy - nothing beyond the core - so the plain
    # artifact is the right one and there is no -sidecar classifier to prefer.
    $backupTargetDir = Join-Path $sandboxRoot 'features\inspecto-backup\target'
    $backupJarSrc = Get-ChildItem -Path $backupTargetDir -Filter 'inspecto-backup-*.jar' -ErrorAction SilentlyContinue |
                     Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-tests.jar' } |
                     Select-Object -First 1 -ExpandProperty FullName
    if (-not $backupJarSrc -or -not (Test-Path $backupJarSrc)) {
        throw "$Edition edition requested but no JAR found matching $backupTargetDir\inspecto-backup-*.jar."
    }
    # The Entity List module (SEP-08). THIN like inspecto-geo-link, which depends on it.
    $entityListTargetDir = Join-Path $sandboxRoot 'features\inspecto-entity-list\target'
    $entityListJarSrc = Get-ChildItem -Path $entityListTargetDir -Filter 'inspecto-entity-list-*.jar' -ErrorAction SilentlyContinue |
                      Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-tests.jar' } |
                      Select-Object -First 1 -ExpandProperty FullName
    if (-not $entityListJarSrc -or -not (Test-Path $entityListJarSrc)) {
        throw "$Edition edition requested but no JAR found matching $entityListTargetDir\inspecto-entity-list-*.jar."
    }
    # LA separation D-1 step 5b: inspecto-la-core (host-free model + the Dataset/Case ports) and inspecto-la-api (the Link
    # Analysis routes) ride beside inspecto-geo-link (the bridge that implements the ports). THIN, optional jars.
    # D-4 step 4: inspecto-la-graph (the ported graph algorithms, JDK only) is la-core's runtime dependency - InMemoryGraphEngine
    # calls it - so it is staged beside it and MUST be on the explicit launcher classpath (a staged jar missing from the
    # classpath is a Link Analysis engine that fails with NoClassDefFoundError the first time a graph run is asked for).
    $laGraphTargetDir = Join-Path $sandboxRoot 'la\inspecto-la-graph\target'
    $laGraphJarSrc = Get-ChildItem -Path $laGraphTargetDir -Filter 'inspecto-la-graph-*.jar' -ErrorAction SilentlyContinue |
                      Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-tests.jar' } |
                      Select-Object -First 1 -ExpandProperty FullName
    if (-not $laGraphJarSrc -or -not (Test-Path $laGraphJarSrc)) {
        throw "$Edition edition requested but no JAR found matching $laGraphTargetDir\inspecto-la-graph-*.jar."
    }
    # D-3 step 2: inspecto-la-storage (the index store skeleton) is staged like la-graph; la-api depends on it from a later step.
    $laStorageTargetDir = Join-Path $sandboxRoot 'la\inspecto-la-storage\target'
    $laStorageJarSrc = Get-ChildItem -Path $laStorageTargetDir -Filter 'inspecto-la-storage-*.jar' -ErrorAction SilentlyContinue |
                      Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-tests.jar' } |
                      Select-Object -First 1 -ExpandProperty FullName
    if (-not $laStorageJarSrc -or -not (Test-Path $laStorageJarSrc)) {
        throw "$Edition edition requested but no JAR found matching $laStorageTargetDir\inspecto-la-storage-*.jar."
    }
    $laCoreTargetDir = Join-Path $sandboxRoot 'la\inspecto-la-core\target'
    $laCoreJarSrc = Get-ChildItem -Path $laCoreTargetDir -Filter 'inspecto-la-core-*.jar' -ErrorAction SilentlyContinue |
                      Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-tests.jar' } |
                      Select-Object -First 1 -ExpandProperty FullName
    if (-not $laCoreJarSrc -or -not (Test-Path $laCoreJarSrc)) {
        throw "$Edition edition requested but no JAR found matching $laCoreTargetDir\inspecto-la-core-*.jar."
    }
    $laApiTargetDir = Join-Path $sandboxRoot 'la\inspecto-la-api\target'
    $laApiJarSrc = Get-ChildItem -Path $laApiTargetDir -Filter 'inspecto-la-api-*.jar' -ErrorAction SilentlyContinue |
                      Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-tests.jar' } |
                      Select-Object -First 1 -ExpandProperty FullName
    if (-not $laApiJarSrc -or -not (Test-Path $laApiJarSrc)) {
        throw "$Edition edition requested but no JAR found matching $laApiTargetDir\inspecto-la-api-*.jar."
    }
    # The geo/link module (EDG-01 cell 3b). THIN like inspecto-policy and inspecto-backup.
    $geoLinkTargetDir = Join-Path $sandboxRoot 'la\inspecto-geo-link\target'
    $geoLinkJarSrc = Get-ChildItem -Path $geoLinkTargetDir -Filter 'inspecto-geo-link-*.jar' -ErrorAction SilentlyContinue |
                      Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-tests.jar' } |
                      Select-Object -First 1 -ExpandProperty FullName
    if (-not $geoLinkJarSrc -or -not (Test-Path $geoLinkJarSrc)) {
        throw "$Edition edition requested but no JAR found matching $geoLinkTargetDir\inspecto-geo-link-*.jar."
    }
    # The exchange module (EDG-01 cell 4). THIN like policy/backup/geo-link.
    $exchangeTargetDir = Join-Path $sandboxRoot 'features\inspecto-exchange\target'
    $exchangeJarSrc = Get-ChildItem -Path $exchangeTargetDir -Filter 'inspecto-exchange-*.jar' -ErrorAction SilentlyContinue |
                       Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-tests.jar' } |
                       Select-Object -First 1 -ExpandProperty FullName
    if (-not $exchangeJarSrc -or -not (Test-Path $exchangeJarSrc)) {
        throw "$Edition edition requested but no JAR found matching $exchangeTargetDir\inspecto-exchange-*.jar."
    }
    # The observability module (MODULE-REORG-1 P7 / D-MR5): /metrics exposition (cell 5) + /events* feed (cell 6). THIN.
    $obsTargetDir = Join-Path $sandboxRoot 'features\inspecto-observability\target'
    $obsJarSrc = Get-ChildItem -Path $obsTargetDir -Filter 'inspecto-observability-*.jar' -ErrorAction SilentlyContinue |
                      Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-tests.jar' } |
                      Select-Object -First 1 -ExpandProperty FullName
    if (-not $obsJarSrc -or -not (Test-Path $obsJarSrc)) {
        throw "$Edition edition requested but no JAR found matching $obsTargetDir\inspecto-observability-*.jar."
    }
    # The operational-objects module (EDG-01 cell 7). NOT thin: the whole com.gamma.ops domain.
    $opsTargetDir = Join-Path $sandboxRoot 'features\inspecto-ops\target'
    # PKG-5: the SHADED `sidecar` artifact, never the thin jar. A thin inspecto-agent.jar on the
    # bundle classpath is present but cannot link (eoiagent-*/langchain4j missing), so the assistant
    # would look installed and silently do nothing. The glob therefore pins '-sidecar'.
    $agentTargetDir = Join-Path $sandboxRoot 'features\inspecto-agent\target'
    $agentJarSrc = Get-ChildItem -Path $agentTargetDir -Filter 'inspecto-agent-*-sidecar.jar' -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $agentJarSrc) { throw "Assistant sidecar not found matching $agentTargetDir\inspecto-agent-*-sidecar.jar. Run without -NoBuild, or build with: mvn package -pl :inspecto-agent -am -DskipTests" }

    $opsJarSrc = Get-ChildItem -Path $opsTargetDir -Filter 'inspecto-ops-*.jar' -ErrorAction SilentlyContinue |
                      Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-tests.jar' } |
                      Select-Object -First 1 -ExpandProperty FullName
    if (-not $opsJarSrc -or -not (Test-Path $opsJarSrc)) {
        throw "$Edition edition requested but no JAR found matching $opsTargetDir\inspecto-ops-*.jar."
    }
    # MODULE-REORG-1 P7: the Reconciliation add-on. THIN like inspecto-ops / inspecto-entity-list; Professional and above.
    $reconTargetDir = Join-Path $sandboxRoot 'features\inspecto-reconciliation\target'
    $reconJarSrc = Get-ChildItem -Path $reconTargetDir -Filter 'inspecto-reconciliation-*.jar' -ErrorAction SilentlyContinue |
                      Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-tests.jar' } |
                      Select-Object -First 1 -ExpandProperty FullName
    if (-not $reconJarSrc -or -not (Test-Path $reconJarSrc)) {
        throw "$Edition edition requested but no JAR found matching $reconTargetDir\inspecto-reconciliation-*.jar."
    }
    # MODULE-REORG-1 P7: the Scoring add-on (Risk Scores). THIN like inspecto-ops / inspecto-entity-list; Professional and above.
    $scoringTargetDir = Join-Path $sandboxRoot 'features\inspecto-scoring\target'
    $scoringJarSrc = Get-ChildItem -Path $scoringTargetDir -Filter 'inspecto-scoring-*.jar' -ErrorAction SilentlyContinue |
                      Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-tests.jar' } |
                      Select-Object -First 1 -ExpandProperty FullName
    if (-not $scoringJarSrc -or -not (Test-Path $scoringJarSrc)) {
        throw "$Edition edition requested but no JAR found matching $scoringTargetDir\inspecto-scoring-*.jar."
    }
    # SCREENING-1: the Screening add-on (/screening* + screening.run). THIN like inspecto-scoring; Professional and above.
    $screeningTargetDir = Join-Path $sandboxRoot 'features\inspecto-screening\target'
    $screeningJarSrc = Get-ChildItem -Path $screeningTargetDir -Filter 'inspecto-screening-*.jar' -ErrorAction SilentlyContinue |
                       Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-tests.jar' } |
                       Select-Object -First 1 -ExpandProperty FullName
    if (-not $screeningJarSrc -or -not (Test-Path $screeningJarSrc)) {
        throw "$Edition edition requested but no JAR found matching $screeningTargetDir\inspecto-screening-*.jar."
    }
    # ANOMALY-DETECTION-1: the Anomaly Detection add-on (anomaly-model kind + anomaly.score). THIN like inspecto-scoring; Professional and above (D-AD4).
    $anomalyTargetDir = Join-Path $sandboxRoot 'features\inspecto-anomaly\target'
    $anomalyJarSrc = Get-ChildItem -Path $anomalyTargetDir -Filter 'inspecto-anomaly-*.jar' -ErrorAction SilentlyContinue |
                     Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-tests.jar' } |
                     Select-Object -First 1 -ExpandProperty FullName
    if (-not $anomalyJarSrc -or -not (Test-Path $anomalyJarSrc)) {
        throw "$Edition edition requested but no JAR found matching $anomalyTargetDir\inspecto-anomaly-*.jar."
    }
    # MODULE-REORG-P7: the Case Management add-on (Case Rules, merge/split, open-from-entities). THIN like inspecto-ops; Professional and above.
    $caseMgmtTargetDir = Join-Path $sandboxRoot 'features\inspecto-case-management\target'
    $caseMgmtJarSrc = Get-ChildItem -Path $caseMgmtTargetDir -Filter 'inspecto-case-management-*.jar' -ErrorAction SilentlyContinue |
                      Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-tests.jar' } |
                      Select-Object -First 1 -ExpandProperty FullName
    if (-not $caseMgmtJarSrc -or -not (Test-Path $caseMgmtJarSrc)) {
        throw "$Edition edition requested but no JAR found matching $caseMgmtTargetDir\inspecto-case-management-*.jar."
    }
    # MODULE-REORG-P7: the Action Requests add-on (four-eyes approved outbound calls, invoke-api). THIN like inspecto-ops; Professional and above.
    $actionReqTargetDir = Join-Path $sandboxRoot 'features\inspecto-action-requests\target'
    $actionReqJarSrc = Get-ChildItem -Path $actionReqTargetDir -Filter 'inspecto-action-requests-*.jar' -ErrorAction SilentlyContinue |
                       Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-tests.jar' } |
                       Select-Object -First 1 -ExpandProperty FullName
    if (-not $actionReqJarSrc -or -not (Test-Path $actionReqJarSrc)) {
        throw "$Edition edition requested but no JAR found matching $actionReqTargetDir\inspecto-action-requests-*.jar."
    }
    # REGULATORY-REPORTING-1: the Regulatory Reporting add-on (regulator-format reports, four-eyes, file drop). THIN; requires ops; Professional and above.
    $regReportTargetDir = Join-Path $sandboxRoot 'features\inspecto-regulatory-reporting\target'
    $regReportJarSrc = Get-ChildItem -Path $regReportTargetDir -Filter 'inspecto-regulatory-reporting-*.jar' -ErrorAction SilentlyContinue |
                       Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-tests.jar' } |
                       Select-Object -First 1 -ExpandProperty FullName
    if (-not $regReportJarSrc -or -not (Test-Path $regReportJarSrc)) {
        throw "$Edition edition requested but no JAR found matching $regReportTargetDir\inspecto-regulatory-reporting-*.jar."
    }
    if ($Edition -eq 'Enterprise' -or $Edition -eq 'Preview') {
        $policyTargetDir = Join-Path $sandboxRoot 'providers\inspecto-policy\target'
        $policyJarSrc = Get-ChildItem -Path $policyTargetDir -Filter 'inspecto-policy-*.jar' -ErrorAction SilentlyContinue |
                         Select-Object -First 1 -ExpandProperty FullName
        if (-not $policyJarSrc -or -not (Test-Path $policyJarSrc)) {
            throw "$Edition edition requested but no JAR found matching $policyTargetDir\inspecto-policy-*.jar."
        }
        # LA-INVESTIGATION-STORE-DESIGN-1 S6 (D-IS7): the optional PostgreSQL Investigation store - a THIN jar (plain JDBC, the
        # driver is postgresql.jar). Enterprise and Preview only; `investigations.backend=db` selects it, the default stays the filesystem.
        $laStorePgTargetDir = Join-Path $sandboxRoot 'la\inspecto-la-store-pg\target'
        $laStorePgJarSrc = Get-ChildItem -Path $laStorePgTargetDir -Filter 'inspecto-la-store-pg-*.jar' -ErrorAction SilentlyContinue |
                           Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-tests.jar' } |
                           Select-Object -First 1 -ExpandProperty FullName
        if (-not $laStorePgJarSrc -or -not (Test-Path $laStorePgJarSrc)) {
            throw "$Edition edition requested but no JAR found matching $laStorePgTargetDir\inspecto-la-store-pg-*.jar."
        }
        # ASSURE-INTELLIGENCE-BUNDLE-1: the SHADED `sidecar`, never the thin jar (same trap as PKG-5).
        $intelligenceTargetDir = Join-Path $sandboxRoot 'features\inspecto-intelligence\target'
        $intelligenceJarSrc = Get-ChildItem -Path $intelligenceTargetDir -Filter 'inspecto-intelligence-*-sidecar.jar' -ErrorAction SilentlyContinue |
            Select-Object -First 1 -ExpandProperty FullName
        if (-not $intelligenceJarSrc) { throw "Intelligence sidecar not found matching $intelligenceTargetDir\inspecto-intelligence-*-sidecar.jar. Run without -NoBuild, or build with: mvn package -pl :inspecto-intelligence -am -DskipTests" }
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

# ── step 3-core: the first-party CORE libraries as THIN jars (MODULE-REORG-P3d stage 2, 2026-10-08) ───────────
# inspecto.jar is no longer one fat jar of everything: inspecto/pom.xml's shade now carries only the processor's own classes plus
# the THIRD-PARTY libraries. api, util, config, sql, etl, the three SPIs, access, entity-store, event, workflow, acquire and engine
# (tools/bundle-modules.mjs CORE_MODULES, printed by `offering-classpath.mjs --list-core`) ship as their own thin jars, named by
# artifactId, in the bundle root and on modules.list right behind inspecto.jar - so each can be signed and SBOM'd on its own and
# the module.toon / ServiceLoader merge hacks of the shade are gone. Never the `-tests` jar (etl and engine attach one).
Add-Type -AssemblyName System.IO.Compression.FileSystem
$coreLines = @(& node (Join-Path $sandboxRoot 'tools\offering-classpath.mjs') --list-core | Where-Object { $_ })
if ($LASTEXITCODE -ne 0 -or $coreLines.Count -lt 1) { throw "tools/offering-classpath.mjs --list-core failed" }
# What a THIN first-party jar must NOT carry: any third-party library (they live in inspecto.jar, once).
$thirdPartyPrefixes = @('org/duckdb/', 'com/fasterxml/', 'tools/jackson/', 'org/slf4j/', 'ch/qos/', 'com/zaxxer/', 'com/opencsv/', 'com/univocity/',
    'org/apache/', 'com/google/', 'dev/toonformat/', 'com/nimbusds/', 'org/postgresql/')
$coreClasses = @{}   # class entry -> thin jar, for the "inspecto.jar holds none of them" check below
foreach ($coreLine in $coreLines) {
    $coreId, $coreDir = $coreLine -split "`t"
    $coreTarget = Join-Path $sandboxRoot "$coreDir\target"
    $coreSrc = Get-ChildItem -Path $coreTarget -Filter "$coreId-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match ('^' + [regex]::Escape($coreId) + '-\d+\.\d+\.\d+(-SNAPSHOT)?\.jar$') } | Select-Object -First 1
    if (-not $coreSrc) { throw "core thin jar $coreId not found under $coreTarget (looked for $coreId-<version>.jar). Run without -NoBuild." }
    Copy-Item $coreSrc.FullName "$bundleDir\$coreId.jar"
    $coreZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\$coreId.jar")
    try {
        $entries = @($coreZip.Entries | ForEach-Object { $_.FullName })
        $leaked = @($entries | Where-Object { $e = $_; $thirdPartyPrefixes | Where-Object { $e.StartsWith($_) } } | Select-Object -First 3)
        if ($leaked.Count) { throw "$coreId.jar is meant to be THIN but carries third-party classes ($($leaked -join ', ')) - its pom shades something; third-party code belongs in inspecto.jar only." }
        if ($entries -notcontains 'META-INF/inspecto/module.toon') { throw "$coreId.jar has no META-INF/inspecto/module.toon - the module would be invisible to GET /modules and the build-id boot check." }
        # every ServiceLoader file the module's source tree ships must be in its jar (a thin jar never merges, but must keep its own)
        $svcDir = Join-Path $sandboxRoot "$coreDir\src\main\resources\META-INF\services"
        if (Test-Path $svcDir) {
            foreach ($svcFile in Get-ChildItem $svcDir -File) {
                if ($entries -notcontains "META-INF/services/$($svcFile.Name)") { throw "$coreId.jar lacks META-INF/services/$($svcFile.Name) that its source tree registers - the SPI implementation would never be discovered." }
            }
        }
        foreach ($e in $entries) { if ($e.EndsWith('.class') -and -not $e.StartsWith('META-INF/')) { $coreClasses[$e] = $coreId } }
    } finally { $coreZip.Dispose() }
}
$procZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto.jar")
try {
    $dup = @($procZip.Entries | Where-Object { $coreClasses.ContainsKey($_.FullName) } | Select-Object -First 3 | ForEach-Object { "$($_.FullName) (also in $($coreClasses[$_.FullName]).jar)" })
    if ($dup.Count) { throw "inspecto.jar still carries first-party library classes that have their own thin jar: $($dup -join '; ') - the shade artifactSet in inspecto/pom.xml no longer excludes them." }
    if (-not ($procZip.Entries | Where-Object { $_.FullName -eq 'com/gamma/control/ControlApi.class' })) { throw "inspecto.jar lacks com/gamma/control/ControlApi.class - the product jar lost the processor's own classes." }
    $procManifests = $procZip.GetEntry('META-INF/inspecto/module.toon')
    if (-not $procManifests) { throw "inspecto.jar has no META-INF/inspecto/module.toon (the processor's own manifest)." }
    $pr = New-Object System.IO.StreamReader($procManifests.Open())
    try { $procToon = $pr.ReadToEnd() } finally { $pr.Dispose() }
    if (([regex]::Matches($procToon, '(?m)^---\s*$')).Count -gt 1) { throw "inspecto.jar's module.toon is a MERGE of several manifests - the shade is folding first-party jars in again." }
    if (-not ($procZip.Entries | Where-Object { $_.FullName -eq 'META-INF/inspecto/known-modules/index.txt' })) { throw "inspecto.jar lacks META-INF/inspecto/known-modules/index.txt - the 'not installed' stubs would be unknown." }
} finally { $procZip.Dispose() }
Write-Host "Bundled $($coreLines.Count) core thin jars (no third-party classes, own module.toon, own services; none of their classes inside inspecto.jar)" -ForegroundColor Green
if ($oidcJarSrc) {
    Copy-Item $oidcJarSrc "$bundleDir\inspecto-oidc.jar"
    Write-Host "Bundled Professional-edition OIDC authenticator module → inspecto-oidc.jar" -ForegroundColor Green

    # Verify the STAGED artifact, the same way the connector sidecar is verified. This is the check that
    # would have caught SEC-SIDECAR-BOOT-1: the module's own tests pass with Nimbus on the compile
    # classpath, and the core-side auth tests inject a lambda, so nothing else can see the thin jar.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $secZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto-oidc.jar")
    try {
        if (-not ($secZip.Entries | Where-Object { $_.FullName -like 'com/nimbusds/*' })) {
            throw "inspecto-oidc.jar carries no com/nimbusds classes - OidcAuthenticator would throw NoClassDefFoundError while ControlApi resolves the Authenticator SPI, and the bundle would not boot."
        }
        foreach ($spi in @('com.gamma.spi.auth.Authenticator', 'com.gamma.spi.auth.TokenRelay')) {
            if (-not ($secZip.Entries | Where-Object { $_.FullName -eq "META-INF/services/$spi" })) {
                throw "inspecto-oidc.jar has no META-INF/services/$spi - the shade dropped the ServicesResourceTransformer, so the bundle would silently fall back to auth-free."
            }
        }
        Write-Host "  verified: Nimbus classes + 2 SPI registrations present in the OIDC sidecar" -ForegroundColor DarkGray
    } finally { $secZip.Dispose() }
}
if ($secretsJarSrc) {
    Copy-Item $secretsJarSrc "$bundleDir\inspecto-secrets.jar"
    Write-Host "Bundled Professional-edition secrets provider → inspecto-secrets.jar" -ForegroundColor Green
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $secretsZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto-secrets.jar")
    try {
        if (-not ($secretsZip.Entries | Where-Object { $_.FullName -eq 'META-INF/services/com.gamma.auth.secrets.SecretsProvider' })) {
            throw "inspecto-secrets.jar has no META-INF/services/com.gamma.auth.secrets.SecretsProvider - the file-keystore secrets provider would never be discovered."
        }
    } finally { $secretsZip.Dispose() }
}
if ($geoCountryJarSrc) {
    Copy-Item $geoCountryJarSrc "$bundleDir\inspecto-geo-country.jar"
    Write-Host "Bundled Professional-edition geo-country resolver → inspecto-geo-country.jar" -ForegroundColor Green
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $geoZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto-geo-country.jar")
    try {
        if (-not ($geoZip.Entries | Where-Object { $_.FullName -like 'com/maxmind/*' })) {
            throw "inspecto-geo-country.jar carries no com/maxmind classes - MaxMindGeoCountryResolver would throw NoClassDefFoundError when the core resolves the GeoCountryResolver SPI."
        }
        if (-not ($geoZip.Entries | Where-Object { $_.FullName -eq 'META-INF/services/com.gamma.spi.auth.GeoCountryResolver' })) {
            throw "inspecto-geo-country.jar has no META-INF/services/com.gamma.spi.auth.GeoCountryResolver - the shade dropped the ServicesResourceTransformer."
        }
    } finally { $geoZip.Dispose() }
}
if ($kafkaJarSrc) {
    Copy-Item $kafkaJarSrc "$bundleDir\inspecto-connectors-kafka.jar"
    Write-Host "Bundled Professional-edition Kafka stream connector -> inspecto-connectors-kafka.jar" -ForegroundColor Green
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $kafkaZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto-connectors-kafka.jar")
    try {
        if (-not ($kafkaZip.Entries | Where-Object { $_.FullName -like 'org/apache/kafka/*' })) {
            throw "inspecto-connectors-kafka.jar carries no org/apache/kafka classes - it is a THIN jar; KafkaConnector would fail with NoClassDefFoundError at run time."
        }
        $kafkaSpi = $kafkaZip.Entries | Where-Object { $_.FullName -eq 'META-INF/services/com.gamma.acquire.CollectorConnectorFactory' }
        if (-not $kafkaSpi) {
            throw "inspecto-connectors-kafka.jar has no META-INF/services/com.gamma.acquire.CollectorConnectorFactory - the shade dropped the ServicesResourceTransformer, so no Kafka connector would be discovered."
        }
        $kafkaReader = New-Object System.IO.StreamReader($kafkaSpi.Open())
        $kafkaSvc = $kafkaReader.ReadToEnd()
        $kafkaReader.Close()
        if ($kafkaSvc -notmatch 'com\.gamma\.acquire\.kafka\.KafkaConnectorFactory') {
            throw "inspecto-connectors-kafka.jar's CollectorConnectorFactory service file does not list com.gamma.acquire.kafka.KafkaConnectorFactory."
        }
    } finally { $kafkaZip.Dispose() }
}
if ($asn1JarSrc) {
    Copy-Item $asn1JarSrc "$bundleDir\inspecto-telecom-asn1.jar"
    Write-Host "Bundled Professional-edition Telecom ASN.1 decoder -> inspecto-telecom-asn1.jar" -ForegroundColor Green
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $asn1Zip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto-telecom-asn1.jar")
    try {
        if (-not ($asn1Zip.Entries | Where-Object { $_.FullName -like 'com/gamma/asn/facade/*' })) {
            throw "inspecto-telecom-asn1.jar carries no com/gamma/asn/facade classes - it is a THIN jar; Asn1ParserPlugin would fail with NoClassDefFoundError at run time."
        }
        $asn1Spi = $asn1Zip.Entries | Where-Object { $_.FullName -eq 'META-INF/services/com.gamma.parse.ParserPlugin' }
        if (-not $asn1Spi) {
            throw "inspecto-telecom-asn1.jar has no META-INF/services/com.gamma.parse.ParserPlugin - the shade dropped the ServicesResourceTransformer, so no asn1 parser would be discovered."
        }
        $asn1Reader = New-Object System.IO.StreamReader($asn1Spi.Open())
        $asn1Svc = $asn1Reader.ReadToEnd()
        $asn1Reader.Close()
        if ($asn1Svc -notmatch 'com\.gamma\.telecom\.asn1\.Asn1ParserPlugin') {
            throw "inspecto-telecom-asn1.jar's ParserPlugin service file does not list com.gamma.telecom.asn1.Asn1ParserPlugin."
        }
    } finally { $asn1Zip.Dispose() }
}
if ($channelsJarSrc) {
    Copy-Item $channelsJarSrc "$bundleDir\inspecto-notify-channels.jar"
    Write-Host "Bundled Professional-edition delivery channels -> inspecto-notify-channels.jar" -ForegroundColor Green

    # Verify the STAGED artifact, exactly as the security and connector sidecars are. Both halves matter:
    # javax.mail missing means SmtpEmailChannel throws NoClassDefFoundError the moment NotificationService
    # enumerates channels, and a dropped ServicesResourceTransformer means the jar ships but registers
    # NOTHING - which looks identical to Personal, i.e. the bug this module exists to fix.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $chZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto-notify-channels.jar")
    try {
        if (-not ($chZip.Entries | Where-Object { $_.FullName -like 'javax/mail/*' })) {
            throw "inspecto-notify-channels.jar carries no javax/mail classes - SmtpEmailChannel would throw NoClassDefFoundError as soon as NotificationService enumerates channels."
        }
        $spiEntry = $chZip.Entries | Where-Object { $_.FullName -eq 'META-INF/services/com.gamma.notify.NotificationChannel' }
        if (-not $spiEntry) {
            throw "inspecto-notify-channels.jar has no META-INF/services/com.gamma.notify.NotificationChannel - the shade dropped the ServicesResourceTransformer, so the bundle would ship with NO external delivery and look exactly like Personal."
        }
        $reader = New-Object System.IO.StreamReader($spiEntry.Open())
        try { $spiBody = $reader.ReadToEnd() } finally { $reader.Dispose() }
        foreach ($impl in @('com.gamma.notify.channel.WebhookChannel', 'com.gamma.notify.channel.SmtpEmailChannel')) {
            if ($spiBody -notmatch [regex]::Escape($impl)) {
                throw "inspecto-notify-channels.jar registers no $impl - the SPI file lists only: $spiBody"
            }
        }
        # sink.webhook's wire (WebhookSinkTransport) rides the same jar; without its SPI entry the node refuses
        # with "ships no outbound HTTP transport" on a Professional bundle - Personal's answer, on the wrong edition.
        $sinkSpi = $chZip.Entries | Where-Object { $_.FullName -eq 'META-INF/services/com.gamma.pipeline.exec.WebhookSinkTransport' }
        if (-not $sinkSpi) {
            throw "inspecto-notify-channels.jar has no META-INF/services/com.gamma.pipeline.exec.WebhookSinkTransport - sink.webhook would refuse as if this were Personal."
        }
        Write-Host "  verified: javax.mail classes + both NotificationChannel registrations + the WebhookSinkTransport registration present" -ForegroundColor DarkGray
    } finally { $chZip.Dispose() }
}
if ($backupJarSrc) {
    Copy-Item $backupJarSrc "$bundleDir\inspecto-backup.jar"
    Write-Host "Bundled Professional-edition backup module -> inspecto-backup.jar" -ForegroundColor Green
    # A thin jar cannot lose classes to a shade, so the only way it ships INERT is a missing
    # META-INF/services entry - and inert here looks exactly like Personal, i.e. the bug this fixes.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $bkZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto-backup.jar")
    try {
        $spiEntry = $bkZip.Entries | Where-Object { $_.FullName -eq 'META-INF/services/com.gamma.job.MaintenanceTaskProvider' }
        if (-not $spiEntry) {
            throw "inspecto-backup.jar has no META-INF/services/com.gamma.job.MaintenanceTaskProvider - backup/backup_verify/restore would be unknown tasks on a bundle that is supposed to have them."
        }
        $reader = New-Object System.IO.StreamReader($spiEntry.Open())
        try { $spiBody = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($spiBody -notmatch 'com\.gamma\.backup\.BackupTaskProvider') {
            throw "inspecto-backup.jar's MaintenanceTaskProvider file does not name BackupTaskProvider: $spiBody"
        }
        Write-Host "  verified: MaintenanceTaskProvider registration present in the backup module" -ForegroundColor DarkGray
    } finally { $bkZip.Dispose() }
}
if ($entityListJarSrc) {
    Copy-Item $entityListJarSrc "$bundleDir\inspecto-entity-list.jar"
    Write-Host "Bundled Entity List module -> inspecto-entity-list.jar" -ForegroundColor Green
    # Thin jar: the only way it ships INERT is a missing META-INF/services entry - the six /entity-lists paths would 503
    # on a bundle supposed to have them, and the risk-score WatchListFeed would never be installed.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $elZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto-entity-list.jar")
    try {
        $spiEntry = $elZip.Entries | Where-Object { $_.FullName -eq 'META-INF/services/com.gamma.spi.http.RouteModule' }
        if (-not $spiEntry) { throw "inspecto-entity-list.jar has no META-INF/services/com.gamma.spi.http.RouteModule - EntityListRoutes would never be discovered." }
        $reader = New-Object System.IO.StreamReader($spiEntry.Open())
        try { $spiBody = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($spiBody -notmatch [regex]::Escape('com.gamma.entitylist.EntityListRoutes')) { throw "inspecto-entity-list.jar registers no com.gamma.entitylist.EntityListRoutes - the SPI file lists only: $spiBody" }
        $feedEntry = $elZip.Entries | Where-Object { $_.FullName -eq 'META-INF/services/com.gamma.entitylist.WatchListFeed' }
        if (-not $feedEntry) { throw "inspecto-entity-list.jar has no META-INF/services/com.gamma.entitylist.WatchListFeed - watch lists would never feed the risk score." }
        Write-Host "  verified: RouteModule and WatchListFeed registrations present in the Entity List module" -ForegroundColor DarkGray
    } finally { $elZip.Dispose() }
}
if ($laGraphJarSrc) {
    Copy-Item $laGraphJarSrc "$bundleDir\inspecto-la-graph.jar"
    Write-Host "Bundled Link Analysis graph algorithms -> inspecto-la-graph.jar" -ForegroundColor Green
}
if ($laStorageJarSrc) {
    Copy-Item $laStorageJarSrc "$bundleDir\inspecto-la-storage.jar"
    Write-Host "Bundled Link Analysis index store -> inspecto-la-storage.jar" -ForegroundColor Green
}
if ($laCoreJarSrc) {
    Copy-Item $laCoreJarSrc "$bundleDir\inspecto-la-core.jar"
    Write-Host "Bundled Link Analysis core module -> inspecto-la-core.jar" -ForegroundColor Green
}
if ($laApiJarSrc) {
    Copy-Item $laApiJarSrc "$bundleDir\inspecto-la-api.jar"
    Write-Host "Bundled Link Analysis routes module -> inspecto-la-api.jar" -ForegroundColor Green
    # Thin jar: the only way it ships INERT is a missing META-INF/services entry - the Link Analysis paths would 503 on a
    # bundle supposed to have them.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $laZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto-la-api.jar")
    try {
        $spiEntry = $laZip.Entries | Where-Object { $_.FullName -eq 'META-INF/services/com.gamma.spi.http.RouteModule' }
        if (-not $spiEntry) { throw "inspecto-la-api.jar has no META-INF/services/com.gamma.spi.http.RouteModule - GeoRoutes/InvRoutes would never be discovered." }
        $reader = New-Object System.IO.StreamReader($spiEntry.Open())
        try { $spiBody = $reader.ReadToEnd() } finally { $reader.Dispose() }
        foreach ($impl in @('com.gamma.la.api.GeoRoutes', 'com.gamma.la.api.InvRoutes')) {
            if ($spiBody -notmatch [regex]::Escape($impl)) { throw "inspecto-la-api.jar registers no $impl - the SPI file lists only: $spiBody" }
        }
        Write-Host "  verified: both RouteModule registrations present in the Link Analysis routes module" -ForegroundColor DarkGray
    } finally { $laZip.Dispose() }
}
if ($geoLinkJarSrc) {
    Copy-Item $geoLinkJarSrc "$bundleDir\inspecto-geo-link.jar"
    Write-Host "Bundled Professional-edition Link Analysis bridge -> inspecto-geo-link.jar" -ForegroundColor Green
    # The bridge implements the two Link Analysis ports. A thin jar cannot lose classes to a shade, so the only way it
    # ships INERT is a missing or incomplete META-INF/services entry - and inert here means every Dataset-reading
    # Link Analysis route answers 503 "no Dataset provider" on a bundle supposed to have them.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $glZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto-geo-link.jar")
    try {
        foreach ($svc in @(
                @('com.gamma.spi.http.RouteModule', 'com.gamma.geolink.InvestigationMeasureRoutes'),
                @('com.gamma.la.core.DatasetProvider', 'com.gamma.geolink.EngineDatasetProvider'),
                @('com.gamma.la.core.CasePort', 'com.gamma.geolink.HostCasePort'),
                @('com.gamma.alert.InvestigationMeasureProbe', 'com.gamma.geolink.WorkingSetMeasures'))) {
            $spiEntry = $glZip.Entries | Where-Object { $_.FullName -eq "META-INF/services/$($svc[0])" }
            if (-not $spiEntry) { throw "inspecto-geo-link.jar has no META-INF/services/$($svc[0]) - $($svc[1]) would never be discovered." }
            $reader = New-Object System.IO.StreamReader($spiEntry.Open())
            try { $spiBody = $reader.ReadToEnd() } finally { $reader.Dispose() }
            if ($spiBody -notmatch [regex]::Escape($svc[1])) { throw "inspecto-geo-link.jar registers no $($svc[1]) for $($svc[0]) - the SPI file lists only: $spiBody" }
        }
        Write-Host "  verified: RouteModule, DatasetProvider, CasePort and InvestigationMeasureProbe registrations present in the bridge" -ForegroundColor DarkGray
    } finally { $glZip.Dispose() }
}
if ($exchangeJarSrc) {
    Copy-Item $exchangeJarSrc "$bundleDir\inspecto-exchange.jar"
    Write-Host "Bundled Professional-edition exchange module -> inspecto-exchange.jar" -ForegroundColor Green
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $exZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto-exchange.jar")
    try {
        $spiEntry = $exZip.Entries | Where-Object { $_.FullName -eq 'META-INF/services/com.gamma.spi.http.RouteModule' }
        if (-not $spiEntry) { throw "inspecto-exchange.jar has no META-INF/services/com.gamma.spi.http.RouteModule - ExchangeRoutes would never be discovered and the shared-ref resolver would never install." }
        $reader = New-Object System.IO.StreamReader($spiEntry.Open())
        try { $spiBody = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($spiBody -notmatch 'com\.gamma\.exchange\.ExchangeRoutes') { throw "inspecto-exchange.jar registers no ExchangeRoutes - the SPI file lists only: $spiBody" }
        Write-Host "  verified: RouteModule registration present in the exchange module" -ForegroundColor DarkGray
    } finally { $exZip.Dispose() }
}
if ($obsJarSrc) {
    Copy-Item $obsJarSrc "$bundleDir\inspecto-observability.jar"
    Write-Host "Bundled Professional-edition observability module -> inspecto-observability.jar" -ForegroundColor Green
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $obsZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto-observability.jar")
    try {
        $spiEntry = $obsZip.Entries | Where-Object { $_.FullName -eq 'META-INF/services/com.gamma.spi.http.RouteModule' }
        if (-not $spiEntry) { throw "inspecto-observability.jar has no META-INF/services/com.gamma.spi.http.RouteModule - GET /metrics and every /events* path would 503 on a bundle supposed to serve them." }
        Write-Host "  verified: RouteModule registration present in the observability module" -ForegroundColor DarkGray
    } finally { $obsZip.Dispose() }
}
if ($opsJarSrc) {
    Copy-Item $opsJarSrc "$bundleDir\inspecto-ops.jar"
    Write-Host "Bundled Professional-edition operational-objects module -> inspecto-ops.jar" -ForegroundColor Green
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $opZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto-ops.jar")
    try {
        # TWO seams must be declared, not one: the routes AND the engine provider core resolves
        # ObjectAccess through. A jar with routes but no provider would 503 every path while claiming
        # the feature is installed.
        $routeSpi = $opZip.Entries | Where-Object { $_.FullName -eq 'META-INF/services/com.gamma.spi.http.RouteModule' }
        if (-not $routeSpi) { throw "inspecto-ops.jar has no META-INF/services/com.gamma.spi.http.RouteModule - every /objects* path would 503 on a bundle supposed to serve them." }
        $engineSpi = $opZip.Entries | Where-Object { $_.FullName -eq 'META-INF/services/com.gamma.service.ObjectEngineProvider' }
        if (-not $engineSpi) { throw "inspecto-ops.jar has no META-INF/services/com.gamma.service.ObjectEngineProvider - the routes would load but resolve no ObjectAccess." }
        Write-Host "  verified: RouteModule + ObjectEngineProvider registrations present in the ops module" -ForegroundColor DarkGray
    } finally { $opZip.Dispose() }
}
if ($reconJarSrc) {
    Copy-Item $reconJarSrc "$bundleDir\inspecto-reconciliation.jar"
    Write-Host "Bundled Reconciliation module -> inspecto-reconciliation.jar" -ForegroundColor Green
    # Thin jar: the only way it ships INERT is a missing META-INF/services entry - the /recon paths would 503 and recon.run
    # would be an unknown Job Type on a bundle supposed to have them.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $rcZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto-reconciliation.jar")
    try {
        foreach ($svc in @(@('com.gamma.spi.http.RouteModule', 'com.gamma.recon.ReconRoutes'), @('com.gamma.job.JobTypeProvider', 'com.gamma.recon.ReconRunJobType'), @('com.gamma.spi.http.ComponentDeleteHook', 'com.gamma.recon.ReconComponentDeleteHook'), @('com.gamma.spi.http.ComponentKindValidator', 'com.gamma.recon.ReconKindValidator'))) {
            $rcEntry = $rcZip.Entries | Where-Object { $_.FullName -eq "META-INF/services/$($svc[0])" }
            if (-not $rcEntry) { throw "inspecto-reconciliation.jar has no META-INF/services/$($svc[0]) - $($svc[1]) would never be discovered." }
            $rcReader = New-Object System.IO.StreamReader($rcEntry.Open())
            try { $rcBody = $rcReader.ReadToEnd() } finally { $rcReader.Dispose() }
            if ($rcBody -notmatch [regex]::Escape($svc[1])) { throw "inspecto-reconciliation.jar's $($svc[0]) service file does not list $($svc[1]) - it lists only: $rcBody" }
        }
        Write-Host "  verified: RouteModule, JobTypeProvider, ComponentDeleteHook and ComponentKindValidator registrations present in the Reconciliation module" -ForegroundColor DarkGray
    } finally { $rcZip.Dispose() }
}
if ($scoringJarSrc) {
    Copy-Item $scoringJarSrc "$bundleDir\inspecto-scoring.jar"
    Write-Host "Bundled Scoring module -> inspecto-scoring.jar" -ForegroundColor Green
    # Thin jar: the only way it ships INERT is a missing META-INF/services entry - the /risk-scores paths would 503, risk.score
    # would be an unknown Job Type and a risk-score component would be saved unvalidated on a bundle supposed to have them.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $scZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto-scoring.jar")
    try {
        foreach ($svc in @(@('com.gamma.spi.http.RouteModule', 'com.gamma.risk.RiskScoreRoutes'), @('com.gamma.job.JobTypeProvider', 'com.gamma.risk.RiskScoreJobType'), @('com.gamma.spi.http.ComponentKindValidator', 'com.gamma.risk.RiskScoreKindValidator'))) {
            $scEntry = $scZip.Entries | Where-Object { $_.FullName -eq "META-INF/services/$($svc[0])" }
            if (-not $scEntry) { throw "inspecto-scoring.jar has no META-INF/services/$($svc[0]) - $($svc[1]) would never be discovered." }
            $scReader = New-Object System.IO.StreamReader($scEntry.Open())
            try { $scBody = $scReader.ReadToEnd() } finally { $scReader.Dispose() }
            if ($scBody -notmatch [regex]::Escape($svc[1])) { throw "inspecto-scoring.jar's $($svc[0]) service file does not list $($svc[1]) - it lists only: $scBody" }
        }
        Write-Host "  verified: RouteModule, JobTypeProvider and ComponentKindValidator registrations present in the Scoring module" -ForegroundColor DarkGray
    } finally { $scZip.Dispose() }
}
if ($screeningJarSrc) {
    Copy-Item $screeningJarSrc "$bundleDir\inspecto-screening.jar"
    Write-Host "Bundled Screening module -> inspecto-screening.jar" -ForegroundColor Green
    # Thin jar: the only way it ships INERT is a missing META-INF/services entry - /screening* would 503 and screening.run
    # would be an unknown Job Type on a bundle supposed to have them.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $srZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto-screening.jar")
    try {
        foreach ($svc in @(@('com.gamma.spi.http.RouteModule', 'com.gamma.screening.ScreeningRoutes'), @('com.gamma.job.JobTypeProvider', 'com.gamma.screening.ScreeningJobType'))) {
            $srEntry = $srZip.Entries | Where-Object { $_.FullName -eq "META-INF/services/$($svc[0])" }
            if (-not $srEntry) { throw "inspecto-screening.jar has no META-INF/services/$($svc[0]) - $($svc[1]) would never be discovered." }
            $srReader = New-Object System.IO.StreamReader($srEntry.Open())
            try { $srBody = $srReader.ReadToEnd() } finally { $srReader.Dispose() }
            if ($srBody -notmatch [regex]::Escape($svc[1])) { throw "inspecto-screening.jar's $($svc[0]) service file does not list $($svc[1]) - it lists only: $srBody" }
        }
        Write-Host "  verified: RouteModule and JobTypeProvider registrations present in the Screening module" -ForegroundColor DarkGray
    } finally { $srZip.Dispose() }
}
if ($anomalyJarSrc) {
    Copy-Item $anomalyJarSrc "$bundleDir\inspecto-anomaly.jar"
    Write-Host "Bundled Anomaly Detection module -> inspecto-anomaly.jar" -ForegroundColor Green
    # Thin jar: the only way it ships INERT is a missing META-INF/services entry - anomaly.score would be an unknown Job Type.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $adZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto-anomaly.jar")
    try {
        foreach ($svc in @(@('com.gamma.job.JobTypeProvider', 'com.gamma.anomaly.AnomalyScoreJobType'), @('com.gamma.spi.http.ComponentKindValidator', 'com.gamma.anomaly.AnomalyKindValidator'))) {
            $adEntry = $adZip.Entries | Where-Object { $_.FullName -eq "META-INF/services/$($svc[0])" }
            if (-not $adEntry) { throw "inspecto-anomaly.jar has no META-INF/services/$($svc[0]) - $($svc[1]) would never be discovered." }
            $adReader = New-Object System.IO.StreamReader($adEntry.Open())
            try { $adBody = $adReader.ReadToEnd() } finally { $adReader.Dispose() }
            if ($adBody -notmatch [regex]::Escape($svc[1])) { throw "inspecto-anomaly.jar's $($svc[0]) service file does not list $($svc[1]) - it lists only: $adBody" }
        }
        Write-Host "  verified: JobTypeProvider and ComponentKindValidator registrations present in the Anomaly Detection module" -ForegroundColor DarkGray
    } finally { $adZip.Dispose() }
}
if ($caseMgmtJarSrc) {
    Copy-Item $caseMgmtJarSrc "$bundleDir\inspecto-case-management.jar"
    Write-Host "Bundled Case Management module -> inspecto-case-management.jar" -ForegroundColor Green
    # Thin jar: the only way it ships INERT is a missing META-INF/services entry - the Case routes would 503, caserule.evaluate
    # would be an unknown Job Type and *_caserule.toon would never load on a bundle supposed to have them.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $cmZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto-case-management.jar")
    try {
        foreach ($svc in @(@('com.gamma.spi.http.RouteModule', 'com.gamma.ops.cases.CaseRoutes'), @('com.gamma.job.JobTypeProvider', 'com.gamma.ops.cases.CaseRuleEvaluate'), @('com.gamma.ops.ObjectEngineExtension', 'com.gamma.ops.cases.CaseConfigExtension'))) {
            $cmEntry = $cmZip.Entries | Where-Object { $_.FullName -eq "META-INF/services/$($svc[0])" }
            if (-not $cmEntry) { throw "inspecto-case-management.jar has no META-INF/services/$($svc[0]) - $($svc[1]) would never be discovered." }
            $cmReader = New-Object System.IO.StreamReader($cmEntry.Open())
            try { $cmBody = $cmReader.ReadToEnd() } finally { $cmReader.Dispose() }
            if ($cmBody -notmatch [regex]::Escape($svc[1])) { throw "inspecto-case-management.jar's $($svc[0]) service file does not list $($svc[1]) - it lists only: $cmBody" }
        }
        Write-Host "  verified: RouteModule, JobTypeProvider and ObjectEngineExtension registrations present in the Case Management module" -ForegroundColor DarkGray
    } finally { $cmZip.Dispose() }
}
if ($actionReqJarSrc) {
    Copy-Item $actionReqJarSrc "$bundleDir\inspecto-action-requests.jar"
    Write-Host "Bundled Action Requests module -> inspecto-action-requests.jar" -ForegroundColor Green
    # Thin jar: the only way it ships INERT is a missing META-INF/services entry - /action-requests* would 503 and invoke-api would be unavailable on a bundle supposed to have them.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $arZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto-action-requests.jar")
    try {
        foreach ($svc in @(@('com.gamma.spi.http.RouteModule', 'com.gamma.actionrequests.ActionRequestRoutes'), @('com.gamma.decision.ConsequenceProvider', 'com.gamma.actionrequests.InvokeApiConsequence')))
        {
            $arEntry = $arZip.Entries | Where-Object { $_.FullName -eq "META-INF/services/$($svc[0])" }
            if (-not $arEntry) { throw "inspecto-action-requests.jar has no META-INF/services/$($svc[0]) - $($svc[1]) would never be discovered." }
            $arReader = New-Object System.IO.StreamReader($arEntry.Open())
            try { $arBody = $arReader.ReadToEnd() } finally { $arReader.Dispose() }
            if ($arBody -notmatch [regex]::Escape($svc[1])) { throw "inspecto-action-requests.jar's $($svc[0]) service file does not list $($svc[1]) - it lists only: $arBody" }
        }
        Write-Host "  verified: RouteModule and ConsequenceProvider registrations present in the Action Requests module" -ForegroundColor DarkGray
    } finally { $arZip.Dispose() }
}
if ($regReportJarSrc) {
    Copy-Item $regReportJarSrc "$bundleDir\inspecto-regulatory-reporting.jar"
    Write-Host "Bundled Regulatory Reporting module -> inspecto-regulatory-reporting.jar" -ForegroundColor Green
    # Thin jar: the only way it ships INERT is a missing META-INF/services entry - /regulatory-reports* would 503 on a bundle supposed to have them.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $rrZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto-regulatory-reporting.jar")
    try {
        $rrEntry = $rrZip.Entries | Where-Object { $_.FullName -eq 'META-INF/services/com.gamma.spi.http.RouteModule' }
        if (-not $rrEntry) { throw "inspecto-regulatory-reporting.jar has no META-INF/services/com.gamma.spi.http.RouteModule - RegulatoryReportRoutes would never be discovered." }
        $rrReader = New-Object System.IO.StreamReader($rrEntry.Open())
        try { $rrBody = $rrReader.ReadToEnd() } finally { $rrReader.Dispose() }
        if ($rrBody -notmatch [regex]::Escape('com.gamma.regreporting.RegulatoryReportRoutes')) { throw "inspecto-regulatory-reporting.jar's RouteModule service file does not list RegulatoryReportRoutes - it lists only: $rrBody" }
        Write-Host "  verified: RouteModule registration present in the Regulatory Reporting module" -ForegroundColor DarkGray
    } finally { $rrZip.Dispose() }
}
if ($agentJarSrc) {
    Copy-Item $agentJarSrc "$bundleDir\inspecto-agent.jar"
    Write-Host "Bundled Professional-edition assist agent -> inspecto-agent.jar" -ForegroundColor Green

    # Verify the sidecar is USABLE, not merely present - the lesson CONNECTORS-BUNDLE-1 taught (a
    # module's own tests can never fail for a packaging gap). This runs on the STAGED ARTIFACT.
    $agentZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto-agent.jar")
    try {
        $assistSpi = $agentZip.Entries | Where-Object { $_.FullName -eq 'META-INF/services/com.gamma.assist.spi.AssistAgent' }
        if (-not $assistSpi) { throw "inspecto-agent.jar has no META-INF/services/com.gamma.assist.spi.AssistAgent - the shade lost the ServicesResourceTransformer, so CollectorService.start() would discover nothing and every assist path would 503." }
        # A thin jar is what the -sidecar glob exists to prevent; prove the dependency is really inside.
        $eoi = $agentZip.Entries | Where-Object { $_.FullName.StartsWith('com/eoiagent/') } | Select-Object -First 1
        if (-not $eoi) { throw "inspecto-agent.jar carries no eoiagent classes - it is a THIN jar; the assistant would fail with NoClassDefFoundError at run time." }
        # The core must NOT be inside: inspecto-processor is scoped `provided` precisely so this sidecar
        # does not ship a second copy of the product beside inspecto.jar. Before that scope fix this jar
        # was 98 MB and carried 137 duplicate etl classes; after it, 3.5 MB.
        $leak = $agentZip.Entries | Where-Object { $_.FullName.StartsWith('com/gamma/etl/') } | Select-Object -First 1
        if ($leak) { throw "inspecto-agent.jar contains core classes (com/gamma/etl/*) - inspecto-processor lost its provided scope, so the bundle would carry two copies of the product." }
        # A second SLF4J binding makes backend selection non-deterministic; the core owns it.
        $binding = $agentZip.Entries | Where-Object { $_.FullName -eq 'META-INF/services/org.slf4j.spi.SLF4JServiceProvider' }
        if ($binding) { throw "inspecto-agent.jar registers a second SLF4J binding - the shade excludes were dropped." }
        Write-Host "  verified: AssistAgent registration present, dependencies shaded in, core excluded, no duplicate SLF4J binding" -ForegroundColor DarkGray
    } finally { $agentZip.Dispose() }
}
if ($policyJarSrc) {
    Copy-Item $policyJarSrc "$bundleDir\inspecto-policy.jar"
    Write-Host "Bundled Enterprise-edition policy module → inspecto-policy.jar" -ForegroundColor Green
}
if ($laStorePgJarSrc) {
    Copy-Item $laStorePgJarSrc "$bundleDir\inspecto-la-store-pg.jar"
    Write-Host "Bundled Enterprise-edition PostgreSQL Investigation store -> inspecto-la-store-pg.jar" -ForegroundColor Green
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $laPgZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto-la-store-pg.jar")
    try {
        if (-not ($laPgZip.Entries | Where-Object { $_.FullName -eq 'META-INF/services/com.gamma.la.core.InvestigationStoreProvider' })) { throw "inspecto-la-store-pg.jar has no InvestigationStoreProvider service file - investigations.backend=db would answer 503 on an Enterprise bundle." }
    } finally { $laPgZip.Dispose() }
}
if ($intelligenceJarSrc) {
    Copy-Item $intelligenceJarSrc "$bundleDir\inspecto-intelligence.jar"
    Write-Host "Bundled Enterprise-edition intelligence agent -> inspecto-intelligence.jar" -ForegroundColor Green
    # Same staged-artifact checks as the assist sidecar: registered, shaded, core-free, one SLF4J binding.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $intelZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto-intelligence.jar")
    try {
        if (-not ($intelZip.Entries | Where-Object { $_.FullName -eq 'META-INF/services/com.gamma.intelligence.spi.IntelligenceAgent' })) { throw "inspecto-intelligence.jar has no IntelligenceAgent service file - every /agent/* path would 503 on an Enterprise bundle." }
        if (-not ($intelZip.Entries | Where-Object { $_.FullName.StartsWith('ai/onnxruntime/native/') } | Select-Object -First 1)) { throw "inspecto-intelligence.jar carries no onnxruntime native libraries - it is a THIN jar." }
        if ($intelZip.Entries | Where-Object { $_.FullName.StartsWith('com/gamma/etl/') } | Select-Object -First 1) { throw "inspecto-intelligence.jar contains core classes (com/gamma/etl/*) - inspecto-processor lost its provided scope." }
        if ($intelZip.Entries | Where-Object { $_.FullName -eq 'META-INF/services/org.slf4j.spi.SLF4JServiceProvider' }) { throw "inspecto-intelligence.jar registers a second SLF4J binding - the shade excludes were dropped." }
        Write-Host "  verified: IntelligenceAgent registration + onnxruntime natives present, core excluded, no duplicate SLF4J binding" -ForegroundColor DarkGray
    } finally { $intelZip.Dispose() }
    # NATIVE-LICENCE-TEXTS-1: the sidecar's MinGW-w64 GCC runtime + winpthreads DLLs ship with no licence text;
    # stage GPL-3.0, the GCC Runtime Library Exception 3.1 and winpthreads COPYING beside the jar, then refuse
    # the bundle if any DLL in the jar is unclassified in natives.json or lacks its text.
    $licSrc = Join-Path $sandboxRoot 'compliance\third-party-licenses'
    New-Item -ItemType Directory -Force "$bundleDir\licenses" | Out-Null
    Copy-Item "$licSrc\*" "$bundleDir\licenses\"
    & node (Join-Path $sandboxRoot 'tools\check-native-licences.mjs') --bundle $bundleDir
    if ($LASTEXITCODE -ne 0) { throw "check-native-licences failed - a bundled native library (.dll/.so/.dylib) is unclassified, unpinned, or lacks its licence text." }
}
# Every edition: remote acquisition is a core product capability (EDITIONS SP-ACQ-02 marks SFTP shipped in
# all three), so the sidecar is NOT edition-gated. It is inert until a pipeline names a non-local
# `collector.connector`. NOTE it adds ~32 MB, dominated by BouncyCastle (sshj) - kafka-clients left for inspecto-connectors-kafka (Professional+) in P7 - if
# Personal must stay leaner, gate this copy on $Edition and correct the SP-ACQ rows to match.
Copy-Item $connectorsJarSrc "$bundleDir\inspecto-connectors.jar"
Write-Host "Bundled remote connector sidecar -> inspecto-connectors.jar" -ForegroundColor Green

# Verify the sidecar is actually USABLE, not merely present. CONNECTORS-BUNDLE-1 went unnoticed for 85 days
# because nothing could go red: the connector tests live INSIDE inspecto-connectors, where the classes and
# their META-INF/services file are trivially on the same test classpath, so they can never fail for a
# packaging gap. This check runs on the STAGED ARTIFACT and is the one thing that could have caught it --
# a thin jar, a shade config that dropped the ServicesResourceTransformer, or a lost dependency all fail here.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$connZip = [System.IO.Compression.ZipFile]::OpenRead("$bundleDir\inspecto-connectors.jar")
try {
    $spi = $connZip.Entries | Where-Object { $_.FullName -eq 'META-INF/services/com.gamma.acquire.CollectorConnectorFactory' }
    if (-not $spi) { throw "inspecto-connectors.jar has no CollectorConnectorFactory service file - the shade lost the ServicesResourceTransformer, so no connector would be discovered at run time." }
    $reader = New-Object System.IO.StreamReader($spi.Open())
    $factories = ($reader.ReadToEnd() -split "`n" | Where-Object { $_.Trim() -and -not $_.Trim().StartsWith('#') }).Count
    $reader.Close()
    if ($factories -ne 7) { throw "inspecto-connectors.jar registers $factories CollectorConnectorFactory entries (expected 7: sftp/ftp/ftps/db/s3/azure/gcs - Kafka lives in inspecto-connectors-kafka.jar since MODULE-REORG-1 P7)." }
    # sshj is the marker for "dependencies really came along" - a thin jar has the classes but not these.
    if (-not ($connZip.Entries | Where-Object { $_.FullName -like 'net/schmizz/sshj/*' })) {
        throw "inspecto-connectors.jar carries no sshj classes - it is a THIN jar; SFTP would fail with NoClassDefFoundError at run time."
    }
    # EDG-01 cell 1 (2026-09-07): the javax.mail assertion that used to sit here MOVED to the
    # inspecto-notify-channels check, with the class that needed it. Leaving it would have been the first
    # thing to fail at package time, since the dependency is gone from this module's pom - and keeping it
    # would assert a property this jar is no longer supposed to have.
    Write-Host "  verified: $factories connector factories + sshj present in the sidecar" -ForegroundColor DarkGray
} finally { $connZip.Dispose() }

# -- MODULE-REORG-1 P3a: every staged module jar must carry the SAME Inspecto-Build-Id (plan §2.4 / §5). A jar left over from
# an older build (-NoBuild, a stale sidecar) would otherwise boot as a mismatched module - inert at run time, silent at
# package time. 'dev' (an unstamped local build) is unknown, so a git-less checkout still bundles.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$stamps = @{}
foreach ($stagedJar in Get-ChildItem -Path $bundleDir -Filter 'inspecto*.jar' -File) {
    $z = [System.IO.Compression.ZipFile]::OpenRead($stagedJar.FullName)
    try {
        $mfEntry = $z.GetEntry('META-INF/MANIFEST.MF')
        $stamp = $null
        if ($mfEntry) {
            $mr = New-Object System.IO.StreamReader($mfEntry.Open())
            try { $m = [regex]::Match($mr.ReadToEnd(), '(?m)^Inspecto-Build-Id:\s*(\S+)'); if ($m.Success) { $stamp = $m.Groups[1].Value } } finally { $mr.Close() }
        }
        $stamps[$stagedJar.Name] = $stamp
    } finally { $z.Dispose() }
}
$distinct = @($stamps.Values | Where-Object { $_ -and $_ -ne 'dev' } | Sort-Object -Unique)
if ($distinct.Count -gt 1) {
    throw "bundle jars carry different Inspecto-Build-Id values ($($distinct -join ', ')): $(($stamps.GetEnumerator() | Sort-Object Name | ForEach-Object { $_.Name + '=' + $_.Value }) -join '; '). Rebuild without -NoBuild so every jar is stamped by one build."
}
if (-not $NoBuild -and $buildId -ne 'dev' -and $distinct.Count -eq 1 -and $distinct[0] -ne $buildId) {
    throw "bundle jars carry Inspecto-Build-Id $($distinct[0]) but this build is $buildId."
}
Write-Host "  verified: $($stamps.Count) module jars, build id $(if ($distinct.Count) { $distinct[0] } else { 'dev (unstamped)' })" -ForegroundColor DarkGray

# -- P3h per-jar signing: sign every staged first-party jar NOW - after the last step that may rewrite a jar and BEFORE the SBOM step
# below, which hashes the staged jars: signing changes a jar's bytes, so the per-module SBOM must read the SIGNED bytes (order:
# stage -> sign -> SBOM/modules.list -> verify -> zip -> zip signature). The demo-auth jar is staged later and signed there.
if ($SignJars) {
    Write-Host "Signing first-party jars (jarsigner, alias $JarAlias)..." -ForegroundColor Cyan
    $toSign = @(Get-ChildItem -Path $bundleDir -Filter 'inspecto*.jar' -File)
    foreach ($j in $toSign) { Invoke-JarSign -Jar $j.FullName }
    Write-Host "  signed + verified: $($toSign.Count) jars" -ForegroundColor DarkGray
}

# ── step 3a: Professional/Enterprise — bundle the PostgreSQL JDBC driver as a sidecar (PG-1) ─────────
# The fat JAR and its SBOM stay JDBC-driver-free by design (inspecto/pom.xml, platform/inspecto-engine/pom.xml);
# the driver rides the bundle as postgresql.jar, auto-detected by serve.sh/serve.bat exactly like
# inspecto-oidc.jar. Personal ships DuckDB only — OperationalDb.verifySelectable fails a
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

# ── step 3a-bis: SBOM per PACKAGED BUNDLE — CycloneDX + SPDX from ONE resolution (COMPLY-1, matrix G1) ──
# Generated here, after the jars are staged and BEFORE the zip, so the SBOM ships inside the bundle
# (bundle/sbom/) and is covered by the zip's checksum + signature. Per bundle, never per reactor: the
# reactor resolves the optional AI stack a Personal bundle does not carry (controls-matrix CC9). Both
# documents are two serialisations of one component list — they cannot drift (tools/sbom.mjs). Node is
# already a packaging prerequisite (the UI build); a missing SBOM is a packaging FAILURE, not a warning —
# a bundle without one is a bundle an auditor cannot accept.
Write-Host "Generating SBOM for the $Edition bundle (CycloneDX + SPDX)..." -ForegroundColor Cyan
$sbomVersion = ([xml](Get-Content (Join-Path $sandboxRoot 'pom.xml'))).project.version
Push-Location $sandboxRoot
& node (Join-Path $sandboxRoot 'tools\sbom.mjs') --edition $Edition --bundle $bundleDir --version $sbomVersion --build-id $buildId
$sbomExit = $LASTEXITCODE
Pop-Location
if ($sbomExit -ne 0) { throw "SBOM generation failed (exit $sbomExit) — a bundle ships with its SBOM or not at all" }

# DEMO-AUTH-1 (runs AFTER the SBOM step: sbom.mjs requires every Enterprise jar to be staged, so the SBOM
# describes the Enterprise module set, as DEMO-BUILD.txt says). A demo build signs Demo Users in with no IAM, so the OIDC module must NOT be on its classpath
# (two Authenticators is unsupported, and the OIDC one would refuse to construct without an issuer).
if ($DemoAuth) {
    if (-not $NoBuild) {
        Push-Location $sandboxRoot
        & mvn package "-Dinspecto.build.id=$buildId" -pl :inspecto-demo-auth -am -DskipTests -q
        if ($LASTEXITCODE -ne 0) { throw "mvn build of inspecto-demo-auth failed" }
        Pop-Location
    }
    $demoJar = Get-ChildItem (Join-Path $sandboxRoot 'providers\inspecto-demo-auth\target') -Filter 'inspecto-demo-auth-*.jar' |
        Where-Object { $_.Name -notmatch '(sources|javadoc|tests)\.jar$' } | Select-Object -First 1
    if (-not $demoJar) { throw "no inspecto-demo-auth jar under providers\inspecto-demo-auth\target - build it first or drop -NoBuild" }
    Remove-Item (Join-Path $bundleDir 'inspecto-oidc.jar') -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $bundleDir 'inspecto-secrets.jar') -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $bundleDir 'inspecto-geo-country.jar') -ErrorAction SilentlyContinue
    # P3f: the per-module SBOMs of the removed trio go with them (the combined SBOM keeps describing the Enterprise set)
    foreach ($gone in 'inspecto-oidc','inspecto-secrets','inspecto-geo-country') { Remove-Item (Join-Path $bundleDir "sbom\$gone.sbom.cdx.json") -ErrorAction SilentlyContinue }
    Copy-Item -Path $demoJar.FullName -Destination (Join-Path $bundleDir 'inspecto-demo-auth.jar')
    if ($SignJars) { Invoke-JarSign -Jar (Join-Path $bundleDir 'inspecto-demo-auth.jar') }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $z = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $bundleDir 'inspecto-demo-auth.jar'))
    try {
        foreach ($svc in 'META-INF/services/com.gamma.spi.auth.Authenticator', 'META-INF/services/com.gamma.spi.auth.TokenRelay') {
            if (-not ($z.Entries | Where-Object { $_.FullName -eq $svc })) { throw "inspecto-demo-auth.jar lacks $svc" }
        }
    } finally { $z.Dispose() }
    Write-Host "DEMO BUILD: inspecto-oidc.jar replaced by inspecto-demo-auth.jar (Demo User sign-in, loopback only)" -ForegroundColor Yellow
}

# P3h: with -SignJars the bundle must END with every first-party jar signed by ONE certificate (the demo jar included).
if ($SignJars) {
    & node (Join-Path $sandboxRoot 'tools\check-jar-signatures.mjs') $bundleDir
    if ($LASTEXITCODE -ne 0) { throw "per-jar signature verification failed (see above)" }
}

# ── step 3a-ter: modules.list + edition.properties (MODULE-REORG-P3d stage 1) ─────────────────────────────────
# The ONE statement of what the bundle's classpath is. tools/offering-classpath.mjs resolves the Offering (offerings/<edition>.toon),
# asserts it equals tools/bundle-modules.mjs's set, orders it, FAILS if a jar staged above is missing from the list or a listed
# jar is not staged, and writes `modules.list` (one jar per line, classpath order) and `edition.properties` (edition=<name>) into the bundle.
# run.sh/run.bat/serve.sh/serve.bat/serve-demo.* and the boot smoke below all READ modules.list; none keeps a jar list of its own.
# Runs AFTER the demo swap so the demo bundle's list names inspecto-demo-auth.jar and not the OIDC trio it removed.
$classpathArgs = @('--edition', $Edition, '--bundle', $bundleDir)
if ($DemoAuth) { $classpathArgs += '--demo' }
& node (Join-Path $sandboxRoot 'tools\offering-classpath.mjs') @classpathArgs
if ($LASTEXITCODE -ne 0) { throw "tools/offering-classpath.mjs failed - the staged jars and the Offering's classpath disagree (see above)" }

# P3f: every jar on modules.list has its own per-module SBOM (written by tools/sbom.mjs above); hashes match the staged jars and every
# third-party component is also in the combined SBOM. Not a warning: a bundle whose per-jar bill of materials disagrees with the jars does not ship.
& node (Join-Path $sandboxRoot 'tools\sbom-modules.mjs') --verify $bundleDir
if ($LASTEXITCODE -ne 0) { throw "per-module SBOM verification failed (see above)" }

# ── step 3b: copy the built UI dist → bundle/ui (served by ControlApi via -Dui.dir=./ui) ──
# Angular emits to ui/dist/<app>[/browser]; locate the folder that actually holds index.html.
#
# NOT gated on $uiBuilt (PKG, 2026-07-31): it used to be, so -NoUi meant "don't SHIP the UI" as well
# as "don't rebuild it", and the bundle shipped with no ui/ while a perfectly good dist/ sat on disk.
# The failure was silent and only visible on the deployed server: serve.sh's `[ -d ui ]` test fails,
# no -Dui.dir is passed, and every browser request falls through ControlApi's unversioned-path guard
# to `{"error":"not found — API routes are served under /api/v1"}` while the API itself works fine.
# -NoUi now means exactly "skip the npm build"; whatever dist/ exists is still bundled.
#
# D-5 step 7: the dist folder is picked BY NAME ($Ui), never by "the first index.html under dist/" - with two
# Angular applications there are two index.html files under dist/ and "first" would be whichever sorts first,
# i.e. the wrong SPA in the bundle whenever both have been built.
$uiBundled = $false
$uiDist = Join-Path $uiDistRoot $Ui
if (Test-Path $uiDist) {
    $indexHtml = Get-ChildItem -Path $uiDist -Filter 'index.html' -Recurse -ErrorAction SilentlyContinue |
                 Select-Object -First 1
    if ($indexHtml) {
        $uiOut = "$bundleDir\ui"
        $null = New-Item -ItemType Directory $uiOut -Force
        Copy-Item -Path (Join-Path $indexHtml.DirectoryName '*') -Destination $uiOut -Recurse -Force
        $uiBundled = $true
        $stale = if ($uiBuilt) { '' } else { ' (pre-existing dist — NOT rebuilt this run)' }
        Write-Host "Bundled UI from $($indexHtml.DirectoryName) → $uiOut$stale" -ForegroundColor Green
    } else {
        Write-Host "  (no index.html under $uiDist — skipping UI bundle)" -ForegroundColor Yellow
    }
} else {
    Write-Host "  (no inspecto-ui/dist/$Ui - build it with 'npm run build -- $Ui' in inspecto-ui/; skipping UI bundle)" -ForegroundColor Yellow
}

# ── step 4: stage spaces/ — the Space-template gallery ONLY ───────────────────
# BUNDLES SHIP NO SPACES (operator decision 2026-09-25), in every edition: the bundle's spaces/ holds
# only _templates (the Space-template gallery - an underscore sentinel, never booted as a Space).
# Spaces are ATTACHED at deploy time by dropping the Space folder(s) into spaces\ (or pointing
# SPACES_ROOT / -Dspaces.root at a folder holding them); the server boots with zero Spaces and one can
# be created in Settings -> Spaces. Runtime state is created on first run, never bundled.
#
# ONLY COMMITTED CONTENT SHIPS (BUNDLE-UNTRACKED-SPACES-1, 2026-09-25): the templates are staged from
# `git ls-tree HEAD` by Copy-SpaceTemplates (package-spaces.ps1), never from the working-tree listing
# — that listing shipped a git-excluded client working set and a peer's untracked pilot Space. A
# locally modified tracked file ships as COMMITTED; git is required (no fallback). Nothing is read
# from the working tree, so a running ControlApi's lock on spaces/<id>/duckdb/*.db cannot fail the copy.
. (Join-Path $adjParserDir 'package-spaces.ps1')
$staged = Copy-SpaceTemplates -RepoRoot $sandboxRoot -BundleDir $bundleDir
if ($staged.Templates.Count -gt 0) {
    Write-Host "Bundled Space templates → $($staged.Out)\_templates ($($staged.Files) committed files; templates: $($staged.Templates -join ', '))" -ForegroundColor Green
} else {
    Write-Host "  (no committed template under spaces/_templates — bundle carries an empty spaces\)" -ForegroundColor Yellow
}
if ($staged.NotBundled.Count -gt 0) {
    Write-Host "  spaces/: NOT bundled (bundles ship no Spaces - attach them at deploy time): $($staged.NotBundled -join ', ')" -ForegroundColor DarkGray
}
if ($staged.Modified.Count -gt 0) {
    Write-Warning "spaces/: $($staged.Modified.Count) tracked file(s) modified locally — bundled the COMMITTED version: $($staged.Modified -join ', ')"
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
# Bundles ship no Spaces: say so plainly rather than "no pipeline file found".
if ! ls -d spaces/*/config >/dev/null 2>&1; then
    echo "ERROR: no Space attached under spaces/ -- drop your Space folder(s) (each with a config/ subtree) into spaces/ first" >&2
    exit 1
fi
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
# RUNSH-CP-1 (2026-09-07): `-cp`, never `-jar`. `java -jar` IGNORES -cp and CLASSPATH entirely, and
# inspecto.jar's manifest has no Class-Path, so every sidecar was unreachable on this one-shot ETL
# path -- including inspecto-connectors.jar, whose whole purpose is remote acquisition and whose
# caller (CollectorProcessor, this jar's own Main-Class) is exactly what this script launches.
# serve.sh had the sidecars and run.sh did not; that divergence is what hid it, so both build the
# classpath the same way now. Every entry is inert unless a config asks for it.
# MODULE-REORG-P3d stage 1: the classpath is READ from modules.list - one jar per line, classpath order, written at package time
# by tools/offering-classpath.mjs from the bundle's Offering (the same list serve.sh reads). Nothing here names a jar.
# FALLBACK, for a hand-assembled directory with no modules.list: inspecto.jar first, then every *.jar beside it.
if [ -f modules.list ]; then
    CP="$(tr -d '\r' < modules.list | tr '\n' ':')"; CP="${CP%:}"
else
    echo "[run.sh] modules.list not found - falling back to every *.jar in this directory" >&2
    CP="inspecto.jar"; for _jar in *.jar; do [ "$_jar" = "inspecto.jar" ] || CP="${CP}:${_jar}"; done
fi
# ASSURE-INTELLIGENCE-BUNDLE-1: the intelligence agent's native loaders (onnxruntime, JNA, DJL tokenizers) extract libraries at
# first use. Keep them OFFLINE and out of the shared %TEMP% / ~/.djl.ai: everything lands in ./runtime-natives, created
# owner-only (0700). onnxruntime ALWAYS extracts into java.io.tmpdir (onnxruntime.native.path only names a PRE-extracted
# dir), so java.io.tmpdir itself is pinned. Keyed on the jar being STAGED, not on the classpath list, so a drop-in works too.
if [ -f inspecto-intelligence.jar ]; then
    NATIVES="$(pwd)/runtime-natives"
    ( umask 077; mkdir -p "${NATIVES}/tmp" "${NATIVES}/djl" ) && chmod 700 "${NATIVES}"
    JAVA_OPTS+=("-Dai.djl.offline=true" "-Djava.io.tmpdir=${NATIVES}/tmp" "-Djna.tmpdir=${NATIVES}/tmp" "-DDJL_CACHE_DIR=${NATIVES}/djl" "-DENGINE_CACHE_DIR=${NATIVES}/djl")
fi
exec "$JAVA" "${JAVA_OPTS[@]}" \
          -cp "$CP" com.gamma.inspector.CollectorProcessor \
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
rem Bundles ship no Spaces: say so plainly rather than "no pipeline file found".
set "ANY_SPACE="
for /d %%S in (spaces\*) do if exist "%%S\config\" set "ANY_SPACE=1"
if not defined ANY_SPACE (
    echo ERROR: no Space attached under spaces\ -- drop your Space folder(s^) (each with a config\ subtree^) into spaces\ first
    exit /b 1
)
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
rem -cp (a JVM flag after it would be parsed as a program argument). Not read from JAVA_OPTS:
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
rem RUNSH-CP-1 (2026-09-07): -cp, never -jar. See run.sh for why - `java -jar` ignores the
rem classpath, so every sidecar (connectors above all) was unreachable on this one-shot ETL path.
rem MODULE-REORG-P3d stage 1: the classpath is READ from modules.list - one jar per line, classpath order, written at package
rem time by tools/offering-classpath.mjs from the bundle's Offering (the same list serve.bat reads). Nothing here names a jar.
rem Built WITHOUT delayed expansion (these single-line statements: `call set` re-expands %CP% per jar), for the reason serve.bat
rem states at SERVEBAT-OPTS-1. The list is accumulated as ;a;b;c and the leading ; is stripped. FALLBACK, for a hand-assembled
rem directory with no modules.list: inspecto.jar first, then every *.jar beside it.
set "CP="
if exist modules.list for /f "usebackq delims=" %%J in ("modules.list") do call set "CP=%%CP%%;%%J"
if not exist modules.list echo [run.bat] modules.list not found - falling back to every *.jar in this directory
if not exist modules.list set "CP=;inspecto.jar"
if not exist modules.list for %%J in (*.jar) do if /i not "%%J"=="inspecto.jar" call set "CP=%%CP%%;%%J"
set "CP=%CP:~1%"
rem ASSURE-INTELLIGENCE-BUNDLE-1: native loaders stay OFFLINE and out of %TEMP% / ~/.djl.ai - everything lands in
rem runtime-natives\, whose inherited ACL is replaced by one owner-only grant at first start.
if exist inspecto-intelligence.jar if not exist runtime-natives\tmp mkdir runtime-natives\tmp
if exist inspecto-intelligence.jar if not exist runtime-natives\djl mkdir runtime-natives\djl
if exist inspecto-intelligence.jar for /f "delims=" %%U in ('whoami') do icacls runtime-natives /inheritance:r /grant:r "%%U:(OI)(CI)F" >nul
if exist inspecto-intelligence.jar set "OPTS=%OPTS% -Dai.djl.offline=true -Djava.io.tmpdir=%CD%\runtime-natives\tmp -Djna.tmpdir=%CD%\runtime-natives\tmp -DDJL_CACHE_DIR=%CD%\runtime-natives\djl -DENGINE_CACHE_DIR=%CD%\runtime-natives\djl"
"%JAVA%" %OPTS% ^
     -cp "%CP%" com.gamma.inspector.CollectorProcessor ^
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
#   ./ura.sh search           spaces/<your-space>/config/voucher/voucher_pipeline.toon
#   ./ura.sh copy             spaces/<your-space>/config/voucher/voucher_pipeline.toon
#   ./ura.sh --dry-run backup spaces/<your-space>/config/voucher/voucher_pipeline.toon
#   ./ura.sh prepare-inbox    spaces/<your-space>/config/voucher/voucher_pipeline.toon
#   ./ura.sh create-schema    voucher  samples/voucher_sample.csv  spaces/<your-space>/config/voucher/voucher_gen.toon
# Bundles ship no Spaces: drop your Space folder(s) into spaces/ first (or pass any pipeline path).
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
# MODULE-REORG-P3d stage 2: inspecto.jar is the product jar only; the core libraries are thin jars beside it. The classpath is
# READ from core.list (written at package time by tools/offering-classpath.mjs): inspecto.jar + the core thin jars only (no optional module or sidecar: the pre-ETL utilities never carried them).
if [ -f core.list ]; then
    CP="$(tr -d '\r' < core.list | tr '\n' ':')"; CP="${CP%:}"
else
    CP="inspecto.jar"; for _jar in *.jar; do [ "$_jar" = "inspecto.jar" ] || CP="${CP}:${_jar}"; done
fi
exec "$JAVA" "${JAVA_OPTS[@]}" \
          -cp "$CP" \
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
rem MODULE-REORG-P3d stage 2: inspecto.jar is the product jar only; the core libraries are thin jars beside it. The classpath is
rem READ from core.list (written at package time by tools/offering-classpath.mjs): inspecto.jar + the core thin jars only (no optional module or sidecar). No delayed expansion.
set "CP="
if exist core.list for /f "usebackq delims=" %%J in ("core.list") do call set "CP=%%CP%%;%%J"
if not exist core.list set "CP=;inspecto.jar"
if not exist core.list for %%J in (*.jar) do if /i not "%%J"=="inspecto.jar" call set "CP=%%CP%%;%%J"
set "CP=%CP:~1%"
"%JAVA%" %OPTS% ^
     -cp "%CP%" ^
     com.gamma.inspector.MainApp %*
'@
Write-CrlfScript -Path "$bundleDir\ura.bat" -Content $uraBatContent

# ── step 6b: bundle serve scripts (run the control plane + operator UI) ─────────
# Unlike run.sh (one-shot ETL), serve.sh launches the long-running ControlApi service with the
# HTTP control plane + operator UI. It serves the bundled SPA from ./ui via -Dui.dir.
# NOTE (SCR-9, 2026-09-09): the CONTROL_TOKEN / ASSIST_TOKEN lines were REMOVED from serve.sh,
# serve.bat and the Dockerfile. They translated into -Dcontrol.token / -Dassist.read.token, which
# have had ZERO Java readers since the token plane left the core on 2026-06-16 -- so the scripts
# emitted them inertly while telling the operator CONTROL_TOKEN was "required to use the control
# plane". Someone following that instruction would think they had secured an auth-free service.
# Real authentication is the inspecto-oidc module (Professional+, OIDC); see docs/EDITIONS.md.
$serveShContent = @'
#!/usr/bin/env bash
# Usage: [PORT=8080] [SPACES_ROOT=spaces] ./serve.sh
# The core is AUTH-FREE by design. Real authentication is the `inspecto-oidc` module (Professional+, OIDC) — see docs/EDITIONS.md.
# Extra JVM flags: INSPECTO_JAVA_OPTS="-Dui.static.log=DEBUG" ./serve.sh   (see below)
# Starts the control plane + operator UI over every space under the spaces/ root (discover mode).
# The bundle ships NO Spaces: drop your Space folder(s) into spaces/ (or set SPACES_ROOT) and restart.
# With none attached the server still starts; create one in the UI under Settings -> Spaces.
set -euo pipefail
cd "$(dirname "$0")"
PORT="${PORT:-8080}"
SPACES_ROOT="${SPACES_ROOT:-spaces}"
ls -d "${SPACES_ROOT}"/*/config >/dev/null 2>&1 || echo "[serve.sh] no Space attached under ./${SPACES_ROOT} -- drop your Space folder(s) there (or set SPACES_ROOT) and restart, or create one in Settings -> Spaces"
JAVA_OPTS=(--enable-native-access=ALL-UNNAMED "-Dcontrol.port=${PORT}" "-Dspaces.root=${SPACES_ROOT}")
[ -d ui ] && JAVA_OPTS+=("-Dui.dir=./ui")
# DuckDB excel extension (multiformat X1, frontend: xlsx) — auto-detect the bundled per-platform
# binary; a networked deployment without it still falls back to INSTALL inside ExcelExtension.
[ -d duckdb-extensions/linux_amd64 ] && JAVA_OPTS+=("-Dduckdb.extension.dir=duckdb-extensions/linux_amd64")
[ -n "${CORS_ORIGIN:-}" ]   && JAVA_OPTS+=("-Dcontrol.cors=${CORS_ORIGIN}")
[ -n "${HTTPS_KEYSTORE:-}" ]          && JAVA_OPTS+=("-Dhttps.keystore=${HTTPS_KEYSTORE}")
[ -n "${HTTPS_KEYSTORE_PASSWORD:-}" ] && JAVA_OPTS+=("-Dhttps.keystore.password=${HTTPS_KEYSTORE_PASSWORD}")
# MODULE-REORG-P3d stage 1: the classpath and the edition are READ, not sniffed. Package time writes two files from the bundle's
# Offering (tools/offering-classpath.mjs): `modules.list` - every jar on the classpath, one per line, in classpath order - and
# `edition.properties` - `edition=Personal|Professional|Enterprise|Preview`. Nothing here names a jar or guesses the edition from
# which jars happen to be present, so a jar dropped into the directory is NOT on the classpath until the list says so.
# FALLBACKS for a hand-assembled directory: no modules.list => inspecto.jar first, then every *.jar beside it; no
# edition.properties => the old heuristic (inspecto-oidc.jar present => Professional, + inspecto-policy.jar => Enterprise).
if [ -f modules.list ]; then
    CP="$(tr -d '\r' < modules.list | tr '\n' ':')"; CP="${CP%:}"
else
    echo "[serve.sh] modules.list not found - falling back to every *.jar in this directory" >&2
    CP="inspecto.jar"; for _jar in *.jar; do [ "$_jar" = "inspecto.jar" ] || CP="${CP}:${_jar}"; done
fi
if [ -f edition.properties ]; then
    EDITION="$(sed -n 's/^edition=//p' edition.properties | tr -d '\r')"
elif [ -f inspecto-oidc.jar ] && [ -f inspecto-policy.jar ]; then EDITION="Enterprise"
elif [ -f inspecto-oidc.jar ]; then EDITION="Professional"
else EDITION="Personal"
fi
case "${EDITION}" in
    Personal|Professional|Enterprise|Preview) ;;
    *) echo "[serve.sh] edition.properties names an unknown edition '${EDITION}' (expected Personal, Professional, Enterprise or Preview)" >&2; exit 1 ;;
esac
# Every edition above Personal authenticates (OIDC) and keeps its audit trail; the OIDC module is on the classpath for exactly
# those editions because the Offering puts it there. Personal stays byte-for-byte the historic auth-free flag set.
if [ "${EDITION}" != "Personal" ]; then
    JAVA_OPTS+=("-Dauth.mode=oidc")
    # EVENTS-DURABLE-1 (2026-09-11): Professional+ keeps the API audit trail across restarts. The engine
    # default is the bounded in-memory ring (ServiceStores.openEventStore), which forgets every audited
    # mutation on restart -- correct for Personal's zero-file promise, false for the tamper-evident,
    # append-only audit story Professional+ sells. -Devents.dir is deliberately NOT set: it defaults to
    # SpaceRoot.eventsDir(), so discover mode keeps one trail per space instead of one shared pile.
    JAVA_OPTS+=("-Devents.backend=parquet")
    [ -n "${AUTH_OIDC_ISSUER:-}" ]    && JAVA_OPTS+=("-Dauth.oidc.issuer=${AUTH_OIDC_ISSUER}")
    [ -n "${AUTH_OIDC_JWKS_URI:-}" ]  && JAVA_OPTS+=("-Dauth.oidc.jwksUri=${AUTH_OIDC_JWKS_URI}")
    [ -n "${AUTH_OIDC_AUDIENCE:-}" ]  && JAVA_OPTS+=("-Dauth.oidc.audience=${AUTH_OIDC_AUDIENCE}")
    [ -n "${AUTH_OIDC_CLIENT_ID:-}" ] && JAVA_OPTS+=("-Dauth.oidc.clientId=${AUTH_OIDC_CLIENT_ID}")
    # Confidential-client secret (optional; W6d BFF): pass a SecretResolver REFERENCE, not the value —
    # the backend expands ${ENV:...} at use, so the secret never appears on the process command line.
    [ -n "${AUTH_OIDC_CLIENT_SECRET:-}" ] && JAVA_OPTS+=('-Dauth.oidc.clientSecret=${ENV:AUTH_OIDC_CLIENT_SECRET}')
    # Enterprise (Professional + ABAC): no flag. inspecto-policy.jar is on the classpath list, and the module is found via
    # META-INF/services/com.gamma.spi.auth.AccessDecider, so the classpath entry IS the switch.
fi
# OBJECTS-BACKEND-DEFAULT-MEMORY-1 (operator decision 2026-09-25): Incidents, Cases, notes, links and tags
# survive a restart on every edition. Personal/Professional/Preview keep them in each Space's duckdb/ (the
# engine default, -Dobjects.backend=db). Enterprise REQUIRES PostgreSQL: -Dobjects.backend=postgres refuses
# to boot until INSPECTO_DB_URL (below) points them at one -- there is no fallback to DuckDB or memory.
[ "${EDITION}" = "Enterprise" ] && JAVA_OPTS+=("-Dobjects.backend=postgres")
# The PostgreSQL JDBC driver (PG-1), the remote connector sidecar, the delivery channels, backup, the Link Analysis modules, the
# assistant and the rest are all on the classpath because modules.list names them - see the top of this section. Each is inert
# until its config asks for it (inspecto.db=postgres, a non-local `collector.connector`, ...).
# ASSURE-INTELLIGENCE-BUNDLE-1: the intelligence agent's native loaders (onnxruntime, JNA, DJL tokenizers) extract libraries at
# first use. Keep them OFFLINE and out of the shared %TEMP% / ~/.djl.ai: everything lands in ./runtime-natives, created
# owner-only (0700). onnxruntime ALWAYS extracts into java.io.tmpdir (onnxruntime.native.path only names a PRE-extracted
# dir), so java.io.tmpdir itself is pinned. Keyed on the jar being STAGED, not on the classpath list, so a drop-in works too.
if [ -f inspecto-intelligence.jar ]; then
    NATIVES="$(pwd)/runtime-natives"
    ( umask 077; mkdir -p "${NATIVES}/tmp" "${NATIVES}/djl" ) && chmod 700 "${NATIVES}"
    JAVA_OPTS+=("-Dai.djl.offline=true" "-Djava.io.tmpdir=${NATIVES}/tmp" "-Djna.tmpdir=${NATIVES}/tmp" "-DDJL_CACHE_DIR=${NATIVES}/djl" "-DENGINE_CACHE_DIR=${NATIVES}/djl")
fi
# Operational stores on PostgreSQL (2026-08-31). The three ledgers (status/batches/lineage) are now
# SERVED from a database by default; Personal stays on the bundled DuckDB with zero configuration,
# and Professional/Enterprise move to PostgreSQL here — the edition seam, per the codebase's rule that
# editions differ by what the BUNDLE carries, never by a default baked into the engine.
# ⚠ Driver PRESENCE alone must not select PostgreSQL: OperationalDb.verifySelectable() fails the boot
# when postgres is chosen without a URL, so auto-enabling on the sidecar would break every Professional
# deployment that has not configured one yet. The URL is the signal.
if [ -f postgresql.jar ] && [ -n "${INSPECTO_DB_URL:-}" ]; then
    JAVA_OPTS+=("-Dinspecto.db=postgres" "-Dinspecto.db.url=${INSPECTO_DB_URL}")
    [ -n "${INSPECTO_DB_USER:-}" ] && JAVA_OPTS+=("-Dinspecto.db.user=${INSPECTO_DB_USER}")
    [ -n "${INSPECTO_DB_PASSWORD:-}" ] && JAVA_OPTS+=("-Dinspecto.db.password=${INSPECTO_DB_PASSWORD}")
fi
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
# OBJECTS-BACKEND-DEFAULT-MEMORY-1: a Preview bundle carries Enterprise's exact jars, which the old jar-sniffing launcher
# would have called Enterprise (and demanded PostgreSQL). Preview keeps the objects on DuckDB (operator decision 2026-09-25);
# since P3d the launcher reads `edition=Preview` from edition.properties, so no text substitution is needed any more.
Write-LfScript -Path "$bundleDir\serve.sh" -Content $serveShContent

$serveBatContent = @'
@echo off
rem Usage: serve.bat
rem Optional env: PORT (default 8080), CORS_ORIGIN, SPACES_ROOT (default spaces).
rem The core is AUTH-FREE by design; real auth is the inspecto-oidc module (Professional+, OIDC).
rem Extra JVM flags: set "INSPECTO_JAVA_OPTS=-Dui.static.log=DEBUG"   (see below)
rem Starts the control plane + operator UI over every space under .\spaces (serves bundled .\ui).
rem The bundle ships NO Spaces: drop your Space folder(s) into spaces\ (or set SPACES_ROOT) and restart.
rem With none attached the server still starts; create one in the UI under Settings -> Spaces.
setlocal
cd /d "%~dp0"
if "%PORT%"=="" set "PORT=8080"
if "%SPACES_ROOT%"=="" set "SPACES_ROOT=spaces"
set "ANY_SPACE="
for /d %%S in ("%SPACES_ROOT%\*") do if exist "%%~S\config\" set "ANY_SPACE=1"
if not defined ANY_SPACE echo [serve.bat] no Space attached under .\%SPACES_ROOT% -- drop your Space folder(s) there (or set SPACES_ROOT) and restart, or create one in Settings -^> Spaces
set "OPTS=--enable-native-access=ALL-UNNAMED -Dcontrol.port=%PORT% -Dspaces.root=%SPACES_ROOT%"
if exist ui set "OPTS=%OPTS% -Dui.dir=./ui"
rem DuckDB excel extension (multiformat X1, frontend: xlsx) - auto-detect the bundled
rem per-platform binary; a networked deployment without it still falls back to INSTALL
rem inside ExcelExtension.
if exist "duckdb-extensions\windows_amd64" set "OPTS=%OPTS% -Dduckdb.extension.dir=duckdb-extensions\windows_amd64"
if not "%CORS_ORIGIN%"=="" set "OPTS=%OPTS% -Dcontrol.cors=%CORS_ORIGIN%"
if not "%HTTPS_KEYSTORE%"=="" set "OPTS=%OPTS% -Dhttps.keystore=%HTTPS_KEYSTORE%"
if not "%HTTPS_KEYSTORE_PASSWORD%"=="" set "OPTS=%OPTS% -Dhttps.keystore.password=%HTTPS_KEYSTORE_PASSWORD%"
rem MODULE-REORG-P3d stage 1: the classpath and the edition are READ, not sniffed. Package time writes two files from the
rem bundle's Offering (tools/offering-classpath.mjs): modules.list - every jar on the classpath, one per line, in classpath
rem order - and edition.properties - edition=Personal/Professional/Enterprise/Preview. Nothing here names a jar or guesses
rem the edition from which jars are present. The list is accumulated as ;a;b;c with `call set` (a second expansion of %CP%
rem per jar - NO delayed expansion, for the reason given below) and the leading ; is stripped.
rem FALLBACKS for a hand-assembled directory: no modules.list => inspecto.jar first, then every *.jar beside it; no
rem edition.properties => the old heuristic (inspecto-oidc.jar => Professional, + inspecto-policy.jar => Enterprise).
set "CP="
if exist modules.list for /f "usebackq delims=" %%J in ("modules.list") do call set "CP=%%CP%%;%%J"
if not exist modules.list echo [serve.bat] modules.list not found - falling back to every *.jar in this directory
if not exist modules.list set "CP=;inspecto.jar"
if not exist modules.list for %%J in (*.jar) do if /i not "%%J"=="inspecto.jar" call set "CP=%%CP%%;%%J"
set "CP=%CP:~1%"
set "EDITION=Personal"
if not exist edition.properties if exist inspecto-oidc.jar set "EDITION=Professional"
if not exist edition.properties if exist inspecto-oidc.jar if exist inspecto-policy.jar set "EDITION=Enterprise"
if exist edition.properties for /f "usebackq tokens=1,* delims==" %%A in ("edition.properties") do if /i "%%A"=="edition" set "EDITION=%%B"
set "EDITION_OK="
for %%E in (Personal Professional Enterprise Preview) do if "%EDITION%"=="%%E" set "EDITION_OK=1"
if not defined EDITION_OK echo [serve.bat] edition.properties names an unknown edition "%EDITION%" - expected Personal, Professional, Enterprise or Preview & exit /b 1
rem Every edition above Personal authenticates (OIDC) and keeps its audit trail; the OIDC module is on the classpath for exactly
rem those editions because the Offering puts it there. Personal stays byte-for-byte the historic auth-free flag set.
rem 🔴 SERVEBAT-OPTS-1 (2026-09-11): these are single-line `if`s, NOT a parenthesized block, and that
rem is load-bearing. cmd.exe expands every %OPTS% in a parenthesized block ONCE, when the block is
rem PARSED, so N `set "OPTS=%OPTS% ..."` statements inside one block all expand to the value OPTS had
rem BEFORE the block and only the last one executed survives. This branch used to be such a block, so
rem a Professional/Enterprise Windows bundle dropped -Dauth.mode=oidc (and every OIDC flag but the last)
rem and booted AUTH-FREE while printing "edition: Professional" -- the BUNDLE-1 inert-flag trap again.
rem ⛔ Do NOT "tidy" these back into an if-block, and do NOT reach for `setlocal EnableDelayedExpansion`
rem instead: these values carry operator secrets and keystore passwords, and delayed expansion eats `!`
rem inside them. One statement per line is the only form that is correct for both. serve.sh has no
rem such hazard -- bash expands at execution -- which is why only this half is written out flat.
if not "%EDITION%"=="Personal" set "OPTS=%OPTS% -Dauth.mode=oidc"
rem EVENTS-DURABLE-1 (2026-09-11): Professional+ keeps the API audit trail across restarts; see serve.sh.
if not "%EDITION%"=="Personal" set "OPTS=%OPTS% -Devents.backend=parquet"
if not "%EDITION%"=="Personal" if not "%AUTH_OIDC_ISSUER%"=="" set "OPTS=%OPTS% -Dauth.oidc.issuer=%AUTH_OIDC_ISSUER%"
if not "%EDITION%"=="Personal" if not "%AUTH_OIDC_JWKS_URI%"=="" set "OPTS=%OPTS% -Dauth.oidc.jwksUri=%AUTH_OIDC_JWKS_URI%"
if not "%EDITION%"=="Personal" if not "%AUTH_OIDC_AUDIENCE%"=="" set "OPTS=%OPTS% -Dauth.oidc.audience=%AUTH_OIDC_AUDIENCE%"
if not "%EDITION%"=="Personal" if not "%AUTH_OIDC_CLIENT_ID%"=="" set "OPTS=%OPTS% -Dauth.oidc.clientId=%AUTH_OIDC_CLIENT_ID%"
rem Confidential-client secret (optional; W6d BFF): pass a SecretResolver REFERENCE, not the value.
if not "%EDITION%"=="Personal" if not "%AUTH_OIDC_CLIENT_SECRET%"=="" set "OPTS=%OPTS% -Dauth.oidc.clientSecret=${ENV:AUTH_OIDC_CLIENT_SECRET}"
rem Enterprise (Professional + ABAC) needs no flag: inspecto-policy.jar is on the classpath list, and the module is found via
rem META-INF/services/com.gamma.spi.auth.AccessDecider, so the classpath IS the switch.
rem OBJECTS-BACKEND-DEFAULT-MEMORY-1 (2026-09-25): Enterprise REQUIRES PostgreSQL for Incidents, Cases,
rem notes, links and tags - it refuses to boot until INSPECTO_DB_URL is set; see serve.sh. Every other
rem edition keeps them in each Space's duckdb/ (the engine default, -Dobjects.backend=db).
if "%EDITION%"=="Enterprise" set "OPTS=%OPTS% -Dobjects.backend=postgres"
rem Every other jar (the PostgreSQL driver, the connector sidecar, delivery channels, backup, Link Analysis, the assistant ...) is
rem on the classpath because modules.list names it - see the top of this section. See serve.sh for what each does.
rem ASSURE-INTELLIGENCE-BUNDLE-1: native loaders stay OFFLINE and out of %TEMP% / ~/.djl.ai - everything lands in
rem runtime-natives\, whose inherited ACL is replaced by one owner-only grant at first start.
if exist inspecto-intelligence.jar if not exist runtime-natives\tmp mkdir runtime-natives\tmp
if exist inspecto-intelligence.jar if not exist runtime-natives\djl mkdir runtime-natives\djl
if exist inspecto-intelligence.jar for /f "delims=" %%U in ('whoami') do icacls runtime-natives /inheritance:r /grant:r "%%U:(OI)(CI)F" >nul
if exist inspecto-intelligence.jar set "OPTS=%OPTS% -Dai.djl.offline=true -Djava.io.tmpdir=%CD%\runtime-natives\tmp -Djna.tmpdir=%CD%\runtime-natives\tmp -DDJL_CACHE_DIR=%CD%\runtime-natives\djl -DENGINE_CACHE_DIR=%CD%\runtime-natives\djl"
rem Operational stores on PostgreSQL (2026-08-31) - the edition seam; see serve.sh for the reasoning.
rem The URL is the signal, never the driver's presence: postgres without a URL fails the boot.
rem WARNING: OPTS, never JAVA_OPTS - that name is assigned below and a flag set on it never reaches
rem the JVM (BUNDLE-1). Single-line ifs, not a parenthesised block, so %OPTS% expands per statement.
if exist postgresql.jar if defined INSPECTO_DB_URL set "OPTS=%OPTS% -Dinspecto.db=postgres -Dinspecto.db.url=%INSPECTO_DB_URL%"
if exist postgresql.jar if defined INSPECTO_DB_URL if defined INSPECTO_DB_USER set "OPTS=%OPTS% -Dinspecto.db.user=%INSPECTO_DB_USER%"
if exist postgresql.jar if defined INSPECTO_DB_URL if defined INSPECTO_DB_PASSWORD set "OPTS=%OPTS% -Dinspecto.db.password=%INSPECTO_DB_PASSWORD%"
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
# CORS_ORIGIN/... from the environment, so the Dockerfile adds no configuration surface of its
# own. The JVM is the bundle's OWN jlinked runtime/ (serve.sh prefers runtime/bin/java): at
# release=27 no vendor JRE image exists (Adoptium published no eclipse-temurin:27-*), and the old
# eclipse-temurin:24-jre base could not run the JAR at all. debian:stable-slim supplies only glibc +
# bash. ⚠ So the Dockerfile is valid ONLY in the linux_amd64 bundle; the build-time
# `runtime/bin/java -version` makes a Windows bundle fail at `docker build`, not at container start.
# chmod first: a zip packed on Windows does not carry the executable bit.
# HEALTHCHECK hits /health tokenless — correct, it is in ControlApi's PUBLIC_PATHS. It probes via
# bash /dev/tcp, NOT curl: the slim base ships no curl/wget.
$dockerfileContent = @'
# Build from an unzipped inspecto-deploy bundle:  docker build -t inspecto .
# Run:  docker run -p 8080:8080 inspecto
# All serve.sh env vars pass straight through (-e PORT / SPACES_ROOT /
# CORS_ORIGIN / AUTH_OIDC_* / INSPECTO_JAVA_OPTS ...). Persist data by mounting the spaces
# root:  -v /srv/inspecto/spaces:/app/spaces
# Pinned by DIGEST (the linux/amd64 manifest of debian:stable-slim, approved by the operator 2026-09-29):
# a tag moves under a fixed name; bump the digest deliberately.
FROM debian@sha256:520c7157b4b1c3c46fa56bac0e748ea21d60956c0512b300e3b16d8757f68e2d
WORKDIR /app
COPY . /app
# Non-root (operator 2026-09-29): a dedicated user/group with a FIXED uid/gid 10001, so a volume's
# ownership and the Helm chart's runAsUser/fsGroup agree. The app writes under its own root (spaces/ plus
# the DuckDB/log dirs it creates beside the jar), so /app itself is owned by the user; the jars, the
# runtime and the launcher stay root-owned and read-only to it.
RUN groupadd --system --gid 10001 inspecto \
 && useradd --system --uid 10001 --gid 10001 --home-dir /app --no-create-home --shell /usr/sbin/nologin inspecto \
 && chmod +x serve.sh runtime/bin/* && runtime/bin/java -version \
 && mkdir -p spaces && chown 10001:10001 /app && chown -R 10001:10001 spaces
USER 10001:10001
ENV PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=15s CMD ["bash", "-c", \
  "exec 3<>/dev/tcp/localhost/${PORT} && printf 'GET /health HTTP/1.0\r\n\r\n' >&3 && grep -q '200' <&3"]
ENTRYPOINT ["./serve.sh"]
'@
Write-LfScript -Path "$bundleDir\Dockerfile" -Content $dockerfileContent
$dockerignoreContent = @'
# runtime/ is NOT excluded: the bundle's jlinked runtime is the image's only JVM.
# Windows-only launchers are dead weight in a Linux container.
*.bat
inspecto-deploy*.zip*
'@
Write-LfScript -Path "$bundleDir\.dockerignore" -Content $dockerignoreContent

# -- step 6b-3: OS service wrappers (SCR-3 / DEPLOY-SERVICE-WRAPPER-1) -------------------------
# Until now nothing restarted a dead process: editions.md 3.11 states that recovery from process
# death IS restart, and the thing meant to perform it did not ship. A crashed service stayed down
# until a person noticed.
#
# The backlog row recommended `sc.exe` for Windows on the grounds that it has zero dependencies.
# That recommendation is REFUSED, because sc.exe cannot host a plain JVM at all: a Windows service
# binary must connect to the service control dispatcher shortly after start, and java.exe never
# does, so `sc create binPath= "...java..."` yields a service that fails every start with error
# 1053 ("did not respond to the start or control request in a timely fashion"). Shipping that
# installer would have produced a wrapper that LOOKS installed and has never once restarted
# anything -- the inert-flag trap this codebase keeps paying for.
# What ships instead, both meeting SCR-3's acceptance (survives reboot AND kill -9):
#   * Linux   -- a real systemd unit, exactly as specified (Restart=on-failure, WorkingDirectory=
#     the bundle root, EnvironmentFile=).
#   * Windows -- a Scheduled Task registered at boot as SYSTEM with RestartCount/RestartInterval,
#     via the built-in ScheduledTasks module. Still zero dependencies, and it actually restarts.
#     WinSW is documented in the installer as the alternative for anyone who needs a genuine entry
#     in services.msc; it is a third-party binary, which is why it is not the default.
$serviceUnitContent = @'
# systemd unit for the Inspecto control plane (SCR-3).
# Installed by install-service.sh, which substitutes @BUNDLE_ROOT@ and @RUN_USER@.
# Manual install: copy to /etc/systemd/system/inspecto.service, edit the two placeholders,
# then `systemctl daemon-reload && systemctl enable --now inspecto`.
[Unit]
Description=Inspecto control plane
Documentation=file://@BUNDLE_ROOT@/README.md
# network-online is what it actually waits for: After=network.target alone does not mean an
# address is configured yet.
After=network-online.target
Wants=network-online.target
# The restart rate limit lives in [Unit], NOT [Service]: systemd moved StartLimitIntervalSec=/
# StartLimitBurst= here in v229 and only WARNS about the old placement, so a copy left under
# [Service] is silently ignored and the limit never applies. A config error that makes startup
# fail must end in a visible `failed` state, not an endless respawn loop.
StartLimitBurst=5
StartLimitIntervalSec=120

[Service]
Type=simple
User=@RUN_USER@
WorkingDirectory=@BUNDLE_ROOT@
# serve.sh reads PORT / SPACES_ROOT / CORS_ORIGIN / AUTH_OIDC_* / INSPECTO_JAVA_OPTS from the
# environment -- the same contract as a shell launch, so this file is the ONLY configuration
# surface the unit adds. The leading `-` means an absent file is not a startup error.
EnvironmentFile=-@BUNDLE_ROOT@/inspecto.env
ExecStart=@BUNDLE_ROOT@/serve.sh
# Recovery from process death is RESTART, not failover (editions.md 3.11) -- this line IS that
# mechanism. on-failure covers a kill -9 (systemd treats death by signal as failure) while still
# honouring a clean `systemctl stop`; the rate limit that bounds it is in [Unit] above.
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal
SyslogIdentifier=inspecto
# Modest hardening only. Do NOT add ProtectSystem=strict or PrivateTmp=yes without testing: the
# bundle writes DuckDB files, Parquet events and spaces/ UNDER ITS OWN ROOT, and DuckDB needs a
# usable temp dir. ReadWritePaths keeps the bundle writable if an operator does tighten this.
NoNewPrivileges=yes
ReadWritePaths=@BUNDLE_ROOT@

[Install]
WantedBy=multi-user.target
'@
Write-LfScript -Path "$bundleDir\inspecto.service" -Content $serviceUnitContent

$installServiceShContent = @'
#!/usr/bin/env bash
# Install the Inspecto control plane as a systemd service (SCR-3).
#   sudo ./install-service.sh [--user <account>] [--name <service>] [--uninstall]
# Re-runnable: it rewrites the unit and restarts the service.
set -euo pipefail
cd "$(dirname "$0")"
BUNDLE_ROOT="$(pwd)"
RUN_USER="${SUDO_USER:-$(id -un)}"
SERVICE_NAME="inspecto"
UNINSTALL=0
while [ $# -gt 0 ]; do
    case "$1" in
        --user)      RUN_USER="${2:?--user needs an account}"; shift 2 ;;
        --name)      SERVICE_NAME="${2:?--name needs a service name}"; shift 2 ;;
        --uninstall) UNINSTALL=1; shift ;;
        -h|--help)   sed -n "2,4p" "$0"; exit 0 ;;
        *)           echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done
UNIT_PATH="/etc/systemd/system/${SERVICE_NAME}.service"

if [ "$(id -u)" -ne 0 ]; then
    echo "[install-service] must run as root (writes ${UNIT_PATH}) -- try: sudo $0" >&2
    exit 1
fi
if ! command -v systemctl >/dev/null 2>&1; then
    echo "[install-service] no systemctl on this host. This installer is systemd-only; on a" >&2
    echo "                  non-systemd init, run serve.sh under that init with an equivalent" >&2
    echo "                  restart-on-failure policy. See inspecto.service for the settings." >&2
    exit 1
fi

if [ "$UNINSTALL" -eq 1 ]; then
    systemctl disable --now "${SERVICE_NAME}" 2>/dev/null || true
    rm -f "${UNIT_PATH}"
    systemctl daemon-reload
    echo "[install-service] removed ${UNIT_PATH}"
    exit 0
fi

[ -x serve.sh ] || chmod +x serve.sh
# Seed an env file rather than baking configuration into the unit: secrets belong in a
# root-owned file, never on the process command line or in a world-readable unit.
if [ ! -f inspecto.env ]; then
    cat > inspecto.env <<ENV
# Environment for the Inspecto service. Read by serve.sh; same contract as a shell launch.
# PORT=8080
# SPACES_ROOT=spaces
# AUTH_OIDC_ISSUER=
# AUTH_OIDC_JWKS_URI=
# AUTH_OIDC_AUDIENCE=
# AUTH_OIDC_CLIENT_ID=
# INSPECTO_JAVA_OPTS=-Xmx4g
ENV
    chmod 600 inspecto.env
    echo "[install-service] wrote ${BUNDLE_ROOT}/inspecto.env (mode 600) -- edit it, then restart"
fi

sed -e "s|@BUNDLE_ROOT@|${BUNDLE_ROOT}|g" -e "s|@RUN_USER@|${RUN_USER}|g" \
    inspecto.service > "${UNIT_PATH}"
chmod 644 "${UNIT_PATH}"
systemctl daemon-reload
systemctl enable "${SERVICE_NAME}"
systemctl restart "${SERVICE_NAME}"
echo "[install-service] ${SERVICE_NAME} installed at ${UNIT_PATH}"
echo "[install-service]   user=${RUN_USER}  root=${BUNDLE_ROOT}"
echo "[install-service] status:  systemctl status ${SERVICE_NAME}"
echo "[install-service] logs:    journalctl -u ${SERVICE_NAME} -f"
echo "[install-service] verify recovery (SCR-3 acceptance):"
echo "[install-service]   sudo kill -9 \$(systemctl show -p MainPID --value ${SERVICE_NAME})"
echo "[install-service]   sleep 10 && curl -fsS http://localhost:8080/health"
'@
Write-LfScript -Path "$bundleDir\install-service.sh" -Content $installServiceShContent

$installServicePs1Content = @'
# Install the Inspecto control plane as a Windows boot service (SCR-3).
#   Run from an ELEVATED PowerShell, inside the bundle:
#     .\install-service.ps1 [-Name Inspecto] [-Uninstall]
#
# Implemented as a Scheduled Task running as SYSTEM at boot, with restart-on-failure -- NOT as an
# sc.exe service. sc.exe cannot host this process: a Windows service binary must connect to the
# service control dispatcher shortly after starting, and java.exe never does, so an sc.exe-created
# service fails every start with error 1053. A scheduled task has no such requirement, needs no
# third-party binary, survives reboot, and restarts after a kill.
# Alternative, if you specifically need an entry in services.msc (an operator runbook, or a
# monitoring agent that enumerates services): WinSW (https://github.com/winsw/winsw) wraps any
# executable as a real service. It is a third-party binary, which is why it is not the default.
[CmdletBinding()]
param(
    [string]$Name = "Inspecto",
    [switch]$Uninstall
)
$ErrorActionPreference = "Stop"
$bundleRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

$identity  = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "install-service.ps1 must run from an elevated PowerShell (it registers a boot task as SYSTEM)."
}

if ($Uninstall) {
    Unregister-ScheduledTask -TaskName $Name -Confirm:$false -ErrorAction SilentlyContinue
    Write-Host "[install-service] removed scheduled task $Name"
    return
}

$serveBat = Join-Path $bundleRoot "serve.bat"
if (-not (Test-Path $serveBat)) {
    throw "serve.bat not found beside this script ($bundleRoot) -- run it from inside the bundle."
}

# cmd /c so the task hosts serve.bat itself; WorkingDirectory is the bundle root, matching the
# systemd unit, because every path the launcher resolves is relative to it.
$action  = New-ScheduledTaskAction -Execute "cmd.exe" -Argument "/c ""$serveBat""" -WorkingDirectory $bundleRoot
$trigger = New-ScheduledTaskTrigger -AtStartup
$taskPrincipal = New-ScheduledTaskPrincipal -UserId "SYSTEM" -LogonType ServiceAccount -RunLevel Highest
# RestartInterval/RestartCount are the kill -9 half of SCR-3's acceptance. ExecutionTimeLimit is
# zeroed because this is a long-running service and the default would stop it after three days.
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -RestartInterval (New-TimeSpan -Minutes 1) -RestartCount 999 `
    -ExecutionTimeLimit ([TimeSpan]::Zero)

Register-ScheduledTask -TaskName $Name -Action $action -Trigger $trigger `
    -Principal $taskPrincipal -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName $Name
Write-Host "[install-service] $Name registered (at boot, as SYSTEM) and started"
Write-Host "[install-service]   root=$bundleRoot"
Write-Host "[install-service] status: Get-ScheduledTask -TaskName $Name | Get-ScheduledTaskInfo"
Write-Host "[install-service] verify recovery (SCR-3 acceptance):"
Write-Host "[install-service]   Stop-Process -Name java -Force; Start-Sleep 70"
Write-Host "[install-service]   Invoke-WebRequest http://localhost:8080/health"
Write-Host "[install-service] NOTE: a task restarts on the RestartInterval (1 min), so allow up to"
Write-Host "[install-service]       ~70s before concluding that recovery failed."
'@
Write-CrlfScript -Path "$bundleDir\install-service.ps1" -Content $installServicePs1Content

# -- step 6c: embed a trimmed Java runtime (jlink) so the bundle is self-contained --
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

# The DuckDB-extension platform key (`duckdb-extensions/<key>/`) a runtime can actually load, read from
# the image itself: bin\java.exe = windows_amd64; an ELF x86-64 bin/java = linux_amd64. Anything else is
# a platform this script stages no extensions for, and is refused rather than guessed.
# ⛔ This is the ONLY source of the platform label used to name a zip and pick its extension set —
# never `$env:OS`, never a literal. `tools/check-bundle-platform.mjs` pins that.
function Get-RuntimePlatform {
    param([Parameter(Mandatory)] [string]$RuntimeDir)
    $binDir = Join-Path $RuntimeDir 'bin'
    if (Test-Path (Join-Path $binDir 'java.exe')) { return 'windows_amd64' }
    $java = Join-Path $binDir 'java'
    if (-not (Test-Path $java)) { throw "Get-RuntimePlatform: $RuntimeDir has neither bin/java.exe nor bin/java — not a jlink image" }
    $fs = [System.IO.File]::OpenRead($java)
    try { $hdr = [byte[]]::new(20); [void]$fs.Read($hdr, 0, 20) } finally { $fs.Dispose() }
    # ELF magic 7F 45 4C 46; e_machine (offset 18, little-endian) 0x003E = x86-64
    $isElf = ($hdr[0] -eq 0x7F -and $hdr[1] -eq 0x45 -and $hdr[2] -eq 0x4C -and $hdr[3] -eq 0x46)
    if ($isElf -and $hdr[18] -eq 0x3E -and $hdr[19] -eq 0x00) { return 'linux_amd64' }
    throw "Get-RuntimePlatform: $java is not an ELF x86-64 binary — this script stages DuckDB extensions for windows_amd64 and linux_amd64 only"
}

$builtLinuxRuntime = $false
$hostPlatform      = $null   # set below from the embedded runtime (or the host OS under -NoRuntime)
if (-not $NoRuntime) {
    # Module set = jdeps core for inspecto.jar (java.base, java.compiler, java.desktop,
    # java.naming, java.scripting, java.sql, jdk.httpserver) + runtime-only safety modules that
    # jdeps cannot see in a fat JAR: jdk.crypto.ec (TLS/JDBC ciphers), jdk.unsupported
    # (sun.misc.Unsafe), java.net.http (HttpClient), jdk.zipfs (.zip via NIO), java.management (JMX),
    # jdk.management (com.sun.management: the RAM size behind the DuckDB memory_limit default, GAP-4).
    $runtimeModules = 'java.base,java.compiler,java.desktop,java.naming,java.scripting,java.sql,jdk.httpserver,jdk.crypto.ec,jdk.unsupported,java.net.http,jdk.zipfs,java.management,jdk.management'
    # P3c (MODULE-REORG-P3-THIN-JARS): the set is now ALSO derived - jdeps over every staged jar UNION
    # tools/jlink-runtime-extra.txt, held against tools/jlink-modules.lock. WARN-AND-USE-UNION: the runtime
    # is the hand list above UNION the derived set, so this can only ever ADD a module, never shrink a
    # working runtime. The delta is printed; once a few bundles show it empty the hand list can be retired.
    $derivedOut = & node (Join-Path $sandboxRoot 'tools\jlink-modules.mjs') --edition $Edition --staged-dir $bundleDir --runtime-modules $runtimeModules --emit 2>&1
    $derivedOut | ForEach-Object { Write-Host "  [jlink-modules] $_" -ForegroundColor DarkGray }
    if ($LASTEXITCODE -ne 0) { Write-Warning "jlink-modules: derived set differs from the hand list or the lock (see above) - using the UNION." }
    $derivedLine = $derivedOut | Where-Object { "$_" -like 'JLINK_MODULES=*' } | Select-Object -Last 1
    if ($derivedLine) {
        $runtimeModules = (($runtimeModules -split ',') + (("$derivedLine" -replace '^JLINK_MODULES=', '') -split ',') | Where-Object { $_ } | Sort-Object -Unique) -join ','
        Write-Host "  runtime modules (hand UNION derived): $runtimeModules" -ForegroundColor DarkGray
    } else {
        Write-Warning "jlink-modules produced no module set - using the hand list only."
    }

    # Locate a jlink: prefer the resolved GraalVM cache, then JAVA_HOME, then PATH.
    # 🔴 OPS-07 (2026-09-14): all three probes used to hardcode `jlink.exe`, and the JAVA_HOME one a
    # Windows separator. The tool is `jlink` (no extension) on Linux/macOS, so on a non-Windows HOST this
    # step could only ever throw — which is the real reason `release.yml` passed -NoRuntime on every step
    # and no published bundle ever carried a runtime. ⛔ The recorded cause ("the runner has no GraalVM
    # jmods cache") was a hypothesis and is wrong. Measured 2026-09-14 inside eclipse-temurin:25-jdk, the
    # image `setup-java` gives the runner: that JDK ships **zero** jmods, and jlink STILL links a working
    # 64 MB image, because a modern JDK links from the run-time image itself (JEP 493). ⚠ So do not "fix"
    # this by provisioning a jmods cache in CI — a cache is needed only to CROSS-build (below), never to
    # build for the host. (This sandbox does have one, at the SIBLING path `C:\sandbox\.graalvm-cache`,
    # which is what a local run resolves; that is why a Windows run also emits the Linux image.)
    # ⚠ The host's jlink builds a HOST-platform image unless --module-path points at another platform's
    # jmods; that cross-build still needs the cache, and is still Windows-host-only below.
    $jlinkName  = if ($env:OS -eq 'Windows_NT') { 'jlink.exe' } else { 'jlink' }
    $hostLabel  = if ($env:OS -eq 'Windows_NT') { 'Windows' }   else { 'Linux' }
    $jlink = $null
    if ($graalvmCacheDir) {
        $jlink = Get-ChildItem -Path $graalvmCacheDir -Filter $jlinkName -Recurse -ErrorAction SilentlyContinue |
                 Select-Object -First 1 -ExpandProperty FullName
    }
    if (-not $jlink -and $env:JAVA_HOME) {
        $fromJavaHome = Join-Path $env:JAVA_HOME (Join-Path 'bin' $jlinkName)
        if (Test-Path $fromJavaHome) { $jlink = $fromJavaHome }
    }
    if (-not $jlink) { $jlink = (Get-Command $jlinkName -ErrorAction SilentlyContinue).Source }
    if (-not $jlink) { throw "$jlinkName not found (looked in .graalvm-cache ($graalvmCacheDir), JAVA_HOME, PATH). Re-run with -NoRuntime to skip embedding a JVM." }

    # ⚠ The image lands in bundle/runtime/ whatever the host platform, and that is safe because BOTH
    # launchers are conditional: serve.sh takes runtime/bin/java only when it is executable, serve.bat
    # takes runtime\bin\java.exe only when it exists. So a Linux-built bundle gives Linux targets an
    # embedded JVM while Windows targets fall back to system java exactly as they do today.
    $runtimeOut = Join-Path $bundleDir 'runtime'
    New-JlinkRuntime -JlinkExe $jlink -Modules $runtimeModules -OutputDir $runtimeOut -PlatformLabel $hostLabel
    $hostPlatform = Get-RuntimePlatform -RuntimeDir $runtimeOut
    Write-Host "  embedded runtime platform: $hostPlatform (read from the image, not the host)" -ForegroundColor DarkGray

    # Linux jmods dir: glob for it (don't pin the version string) so a cache refresh doesn't break this.
    # Skipped when the host image IS already linux_amd64 (ubuntu-latest): the cross-build would only
    # duplicate it under the same zip name.
    $linuxJmods = $null
    if ($graalvmCacheDir -and $hostPlatform -ne 'linux_amd64') {
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
            # PKG-LINUX-RUNTIME-WARNS-1: this used to be a Write-Warning, so a release quietly lost a platform.
            if (-not $AllowPartialRuntime) { throw "Linux runtime cross-build failed ($($_.Exception.Message)). Pass -AllowPartialRuntime to ship only the $hostPlatform zip." }
            Write-Warning "Linux runtime build failed ($($_.Exception.Message)) — -AllowPartialRuntime: skipping inspecto-deploy-linux_amd64.zip."
        }
    } elseif ($hostPlatform -ne 'linux_amd64') {
        Write-Host "  (no Linux jmods cache found under .graalvm-cache — skipping inspecto-deploy-linux_amd64.zip)" -ForegroundColor Yellow
    }
} else {
    Write-Host "  (-NoRuntime: skipping embedded JVM; target server must provide Java 24+)" -ForegroundColor Yellow
    # No runtime to read a platform from: the zip is for the HOST platform's system java, so it keeps the
    # host's extension set. This is the one place the host OS decides the label.
    $hostPlatform = if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'windows_amd64' } else { 'linux_amd64' }
}

# ── step 6c-demo: DEMO-AUTH-1 launchers (only with -DemoAuth) ─────────────────────────────────────
# The regular launchers and installers go: with no inspecto-oidc.jar, serve.* would boot an auth-free
# control plane on every interface. serve-demo.* adds the demo module and pins the flags the demo needs:
#   -Dcontrol.bind=127.0.0.1  demo sign-in is unauthenticated; the module refuses to load otherwise
#   -Dauth.mode=demo          the SPA's sign-in page shows the Demo User picker
#   -Dobjects.backend=db      Incidents/Cases/notes persist in the Space's duckdb/ -- stated explicitly: this is an
#                             Enterprise-capability build, and Enterprise's serve.* would demand PostgreSQL
#                             (OBJECTS-BACKEND-DEFAULT-MEMORY-1); a one-folder hand-over has none
#   -Devents.backend=parquet  the audit trail survives a restart, as on Professional+
#   -Djobs.backend=duckdb     run history (Home's Recent Runs, /jobs/runs|metrics) in the Space's duckdb/ (R2-08).
#                             serve.* leaves it unset (default none) -- job reporting is opt-in there; a demo
#                             whose Home says "Run history needs the DuckDB jobs backend" is not a demo
if ($DemoAuth) {
    foreach ($f in 'serve.sh', 'serve.bat', 'Dockerfile', '.dockerignore', 'inspecto.service', 'install-service.sh', 'install-service.ps1') {
        Remove-Item (Join-Path $bundleDir $f) -ErrorAction SilentlyContinue
    }
    # MODULE-REORG-P3d stage 1: the demo launchers READ modules.list too (written above by `offering-classpath.mjs --demo`: the
    # OIDC trio replaced by the demo jar) instead of embedding a jar list of their own. No FALLBACK glob here: a demo bundle is
    # always built by this script, which always writes the list. $demoJars is that list, held here only to assert the demo
    # invariant at package time: never two Authenticators (no OIDC jar beside the demo jar), and the demo jar really is on it.
    $demoJars = @(Get-Content (Join-Path $bundleDir 'modules.list') | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ($demoJars -contains 'inspecto-oidc.jar') { throw "the demo bundle's modules.list names inspecto-oidc.jar next to the demo jar - two Authenticators is unsupported." }
    if ($demoJars -notcontains 'inspecto-demo-auth.jar') { throw "the demo bundle's modules.list does not name inspecto-demo-auth.jar - the Demo User sign-in would not load." }
    $demoFlags = '--enable-native-access=ALL-UNNAMED -Dcontrol.bind=127.0.0.1 -Dauth.mode=demo -Dobjects.backend=db -Devents.backend=parquet -Djobs.backend=duckdb'
    $serveDemoBat = @"
@echo off
rem DEMO BUILD - internal evaluation only (DEMO-AUTH-1). Demo User sign-in, NO real authentication.
rem Listens on 127.0.0.1 only. The bundle ships NO Spaces: drop your Space folder(s) into .\spaces
rem (or set SPACES_ROOT), then open http://127.0.0.1:%PORT%
setlocal
cd /d "%~dp0"
if "%PORT%"=="" set "PORT=8080"
if "%SPACES_ROOT%"=="" set "SPACES_ROOT=spaces"
set "OPTS=$demoFlags -Dcontrol.port=%PORT% -Dspaces.root=%SPACES_ROOT%"
if exist ui set "OPTS=%OPTS% -Dui.dir=./ui"
if exist "duckdb-extensions\windows_amd64" set "OPTS=%OPTS% -Dduckdb.extension.dir=duckdb-extensions\windows_amd64"
set "JAVA=java"
if exist runtime\bin\java.exe set "JAVA=runtime\bin\java.exe"
rem ASSURE-INTELLIGENCE-BUNDLE-1: the same owner-only, offline native dirs as serve.bat.
if exist inspecto-intelligence.jar if not exist runtime-natives\tmp mkdir runtime-natives\tmp
if exist inspecto-intelligence.jar if not exist runtime-natives\djl mkdir runtime-natives\djl
if exist inspecto-intelligence.jar for /f "delims=" %%U in ('whoami') do icacls runtime-natives /inheritance:r /grant:r "%%U:(OI)(CI)F" >nul
if exist inspecto-intelligence.jar set "OPTS=%OPTS% -Dai.djl.offline=true -Djava.io.tmpdir=%CD%\runtime-natives\tmp -Djna.tmpdir=%CD%\runtime-natives\tmp -DDJL_CACHE_DIR=%CD%\runtime-natives\djl -DENGINE_CACHE_DIR=%CD%\runtime-natives\djl"
rem The classpath is READ from modules.list (one jar per line, classpath order), accumulated as ;a;b;c with `call set` and the
rem leading ; stripped - see serve.bat for why there is no delayed expansion.
set "CP="
for /f "usebackq delims=" %%J in ("modules.list") do call set "CP=%%CP%%;%%J"
set "CP=%CP:~1%"
echo [serve-demo] DEMO BUILD on http://127.0.0.1:%PORT%  (spaces: .\%SPACES_ROOT%)
"%JAVA%" %OPTS% -cp "%CP%" com.gamma.control.ControlApi
"@
    Write-CrlfScript -Path "$bundleDir\serve-demo.bat" -Content $serveDemoBat
    $serveDemoSh = @"
#!/usr/bin/env bash
# DEMO BUILD - internal evaluation only (DEMO-AUTH-1). Demo User sign-in, NO real authentication.
# Listens on 127.0.0.1 only. The bundle ships NO Spaces: drop your Space folder(s) into ./spaces
# (or set SPACES_ROOT), then open http://127.0.0.1:`${PORT}
set -euo pipefail
cd "`$(dirname "`$0")"
PORT="`${PORT:-8080}"
SPACES_ROOT="`${SPACES_ROOT:-spaces}"
# An ARRAY, like serve.sh: a SPACES_ROOT (or cwd) containing a space must stay one argument.
JAVA_OPTS=($demoFlags "-Dcontrol.port=`${PORT}" "-Dspaces.root=`${SPACES_ROOT}")
[ -d ui ] && JAVA_OPTS+=("-Dui.dir=./ui")
[ -d duckdb-extensions/linux_amd64 ] && JAVA_OPTS+=("-Dduckdb.extension.dir=duckdb-extensions/linux_amd64")
JAVA=java
[ -x runtime/bin/java ] && JAVA=runtime/bin/java
# ASSURE-INTELLIGENCE-BUNDLE-1: the same owner-only (0700), offline native dirs as serve.sh.
if [ -f inspecto-intelligence.jar ]; then
    NATIVES="`$(pwd)/runtime-natives"
    ( umask 077; mkdir -p "`${NATIVES}/tmp" "`${NATIVES}/djl" ) && chmod 700 "`${NATIVES}"
    JAVA_OPTS+=("-Dai.djl.offline=true" "-Djava.io.tmpdir=`${NATIVES}/tmp" "-Djna.tmpdir=`${NATIVES}/tmp" "-DDJL_CACHE_DIR=`${NATIVES}/djl" "-DENGINE_CACHE_DIR=`${NATIVES}/djl")
fi
# The classpath is READ from modules.list (one jar per line, classpath order); same fallback as serve.sh for a hand-assembled directory.
if [ -f modules.list ]; then
    CP="`$(tr -d '\r' < modules.list | tr '\n' ':')"; CP="`${CP%:}"
else
    echo "[serve-demo] modules.list not found - falling back to every *.jar in this directory" >&2
    CP="inspecto.jar"; for _jar in *.jar; do [ "`$_jar" = "inspecto.jar" ] || CP="`${CP}:`${_jar}"; done
fi
echo "[serve-demo] DEMO BUILD on http://127.0.0.1:`${PORT}  (spaces: ./`${SPACES_ROOT})"
exec "`$JAVA" "`${JAVA_OPTS[@]}" -cp "`$CP" com.gamma.control.ControlApi
"@
    Write-LfScript -Path "$bundleDir\serve-demo.sh" -Content $serveDemoSh
    $demoReadme = @"
INSPECTO DEMO BUILD - INTERNAL EVALUATION ONLY
===============================================
This build signs people in as Demo Users with NO password and NO identity provider (DEMO-AUTH-1).
It is not secure and must never be given to a customer, exposed on a network, or used with real data.

1. This bundle ships NO Spaces. Drop your Space folder(s) (for example telco-assurance/) into .\spaces\
   (or set SPACES_ROOT to a folder holding them). spaces\_templates is the template gallery, not a Space.
2. Run serve-demo.bat (Windows) or ./serve-demo.sh (Linux)
3. Open http://127.0.0.1:8080 and pick a Demo User

Demo Users are defined per Space in config/demo-users.toon; their roles come from that Space's role table.
The server listens on 127.0.0.1 only and refuses to start the demo sign-in on any other address.
The bundled SBOM describes the Enterprise module set: it lists inspecto-oidc (not shipped here) and
omits inspecto-demo-auth (shipped here).
"@
    Write-CrlfScript -Path "$bundleDir\DEMO-BUILD.txt" -Content $demoReadme
    Write-Host "DEMO BUILD: serve-demo.bat / serve-demo.sh / DEMO-BUILD.txt written; serve.* and service installers removed" -ForegroundColor Yellow
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
# AIRGAP-EXTENSIONS-1 (2026-09-11): every extension the product LOADs at run time, not just excel.
# `ducklake` joined the list because DuckLakeRegistrar used to open with an unconditional
# `INSTALL ducklake FROM core` -- a network fetch on an "air-gapped" install the moment a pipeline set
# output.ducklake.enabled. ⛔ Do NOT add `httpfs` here on the strength of the old backlog row: nothing in
# the product loads it, and both SQL guards REFUSE `INSTALL httpfs`/`LOAD httpfs` by name
# (SqlGuardTest, ConsignmentReaderTest) -- staging it would ship a binary the engine is built to reject.
# ✅ `postgres_scanner` ADDED 2026-09-14 (scale-out phase C / D4). This list excluded it on the stated
# grounds that "the Postgres DuckLake catalog does not currently attach at all, so bundling for it would
# be provisioning a path that no deployment can reach". 🔴 That premise is now FALSE and the rule it
# came with -- "add a name here ONLY with a run-time LOAD to point at" -- is now SATISFIED, both measured
# 2026-09-14 against duckdb_jdbc 1.5.2.1 and a live Postgres:
#   (a) the catalog attaches with the spelling `postgres:` + libpq keywords (AIRGAP-DUCKLAKE-PG-1 closed;
#       the old `postgresql://` example was the broken part, not the feature), and
#   (b) `ATTACH 'ducklake:postgres:...'` AUTO-LOADS postgres_scanner -- observed going loaded=false ->
#       loaded=true across the attach, with no explicit LOAD anywhere in this repo.
# ⛔ So without this name an air-gapped Enterprise pod fails EVERY batch: `ducklake` loads from its staged
# file, the attach then reaches for postgres_scanner, finds no cache, and tries a network INSTALL -- and
# D10 makes that failure FATAL in a partitioned topology. The air-gap hole AIRGAP-EXTENSIONS-1 closed for
# `ducklake` reopened one layer down the moment D4 chose a Postgres catalog.
# ⚠ There is still no explicit LOAD to grep for; the run-time load is DuckLake's own, inside ATTACH.
# `httpfs` + `aws` added 2026-09-14 (AIRGAP-S3-EXTENSIONS-1, operator call) for scale-out phase C
# bullet 6, which turns `dirs.database` into an `s3://` URI. Measured against a live MinIO the same day:
#   • an `s3://` COPY and read-back need `httpfs`, and DuckDB AUTOLOADS it -- there is no `LOAD httpfs`
#     in this repo to grep for, and the SQL guard's refusal of that statement never fires, because
#     autoload emits no statement. Staging is therefore the ONLY control.
#   • `httpfs` ALONE is sufficient for an S3-compatible endpoint with EXPLICIT credentials (measured:
#     `SET s3_access_key_id`/`s3_secret_access_key` against MinIO works with `aws` absent). `aws` buys
#     the AWS CREDENTIAL CHAIN -- profiles, environment, IMDS -- which a real AWS deployment wants and a
#     MinIO/on-prem one does not. ⚠ It costs 24 MB per platform on EVERY bundle; drop it first if bundle
#     size ever becomes the binding constraint, and nothing that passes explicit keys will notice.
# ⛔ Staging is NECESSARY, NOT SUFFICIENT, and the difference is invisible: a flat
# `duckdb-extensions/<plat>/<name>.duckdb_extension` is reachable ONLY by `DuckDbExtension`'s explicit
# `LOAD '<file>'`. DuckDB's AUTOLOAD ignores `-Dduckdb.extension.dir` entirely and reads its own
# `extension_directory`, which this product never sets -- measured: autoload against the flat staged dir
# FAILS, against a `<version>/<platform>` tree it succeeds. So every autoloaded extension needs a named
# call site calling `DuckDbExtension.ensureLoaded`, or its staged file is dead weight.
$duckdbExtNames = @('excel', 'ducklake', 'postgres_scanner', 'httpfs', 'aws')
# D-8 (2026-09-24): match the extension ABI directory of the DuckDB this bundle actually ships
# (duckdb.version 1.5.2.1 -> v1.5.2), not "the first file of that name anywhere in the cache". A cache
# holding v1.5.2 AND v1.5.5 (this desk's, measured) otherwise staged whichever the recursive walk met
# first -- and a wrong-ABI binary is refused at LOAD, which since D-8 is a hard failure in a bundle.
$duckdbAbi = 'v' + ((([xml](Get-Content (Join-Path $sandboxRoot 'pom.xml'))).project.properties.'duckdb.version' -split '\.')[0..2] -join '.')
if ($duckdbExtCacheDir) {
    foreach ($plat in @('windows_amd64', 'linux_amd64')) {
        foreach ($extName in $duckdbExtNames) {
            $extFile = "$extName.duckdb_extension"
            # Search EVERY existing candidate, not just the first: ~/.duckdb/extensions (DuckDB's own
            # autoinstall cache, host platform only) otherwise shadows the repo cache that
            # tools/fetch-duckdb-extensions.mjs fills, so fetching never fixed a missing linux_amd64 set.
            $found = Get-ChildItem -Path @($duckdbExtCandidates | Where-Object { Test-Path $_ }) -Recurse -Filter $extFile -ErrorAction SilentlyContinue |
                     Where-Object { $_.FullName -match ('[\\/]' + [regex]::Escape($duckdbAbi) + '[\\/]' + [regex]::Escape($plat) + '[\\/]') } |
                     Select-Object -First 1
            if ($found) {
                $dest = Join-Path $duckdbExtOut $plat
                New-Item -ItemType Directory -Path $dest -Force | Out-Null
                Copy-Item $found.FullName -Destination (Join-Path $dest $extFile) -Force
                Write-Host "Bundled DuckDB $extName extension ($plat) -> duckdb-extensions/$plat/" -ForegroundColor Green
                $bundledAnyExt = $true
            } elseif ($RequireExtensions) {
                throw "-RequireExtensions: no cached $extFile for $plat ($duckdbAbi) under $duckdbExtCacheDir. This bundle would ship without it, and since D-8 the launcher points -Dduckdb.extension.dir at duckdb-extensions/$plat/, where a missing file FAILS that feature outright (no INSTALL fallback). Populate the cache first: node tools/fetch-duckdb-extensions.mjs"
            } else {
                Write-Host "  (no cached $extFile for $plat ($duckdbAbi) under $duckdbExtCacheDir — in a bundle that stages ANY extension for $plat, that feature now FAILS LOUDLY at first use; fetch it: node tools/fetch-duckdb-extensions.mjs)" -ForegroundColor Yellow
            }
        }
    }
} elseif ($RequireExtensions) {
    throw "-RequireExtensions: no DuckDB extension cache resolved, so NOTHING would be staged. Tried: $($duckdbExtCandidates -join ', '). Populate one first: node tools/fetch-duckdb-extensions.mjs"
} else {
    Write-Host "  (skipping DuckDB extension bundling — no cache resolved; xlsx and DuckLake pipelines need network on first run, or a manual -Dduckdb.extension.dir)" -ForegroundColor Yellow
}

# -- step 6e: BOOT SMOKE -- does the FULLY ASSEMBLED bundle actually START? ----------------------
# The only check here that exercises the assembled classpath as a running process. Everything else
# inspects jars. SEC-SIDECAR-BOOT-1 shipped a bundle whose ControlApi threw while resolving the
# Authenticator SPI during startup, with every unit suite green and every jar present -- exactly the
# gap this closes. It runs LAST, after every stage step: an earlier placement failed on 'no spaces'
# because the spaces tree is copied in step 4, and a boot check that dies for a reason unrelated to
# what it tests is worse than none -- it trains you to read its failure as noise.
# Since 2026-09-25 the bundle ships NO Spaces (only spaces/_templates, never booted), so this smoke also
# proves the zero-Space boot: ControlApi.main must start on an empty spaces root and answer /health.
# Professional/Enterprise matter most (they load inspecto-oidc), but Personal is
# smoked too: a broken core is the same class of failure.
if (-not $SkipBootCheck) {
    $java = if (Test-Path "$bundleDir/runtime/bin/java.exe") { "$bundleDir/runtime/bin/java.exe" }
            elseif (Test-Path "$bundleDir/runtime/bin/java") { "$bundleDir/runtime/bin/java" }
            else { 'java' }
    # THE classpath the launchers build: the bundle's own modules.list (written at step 3a-ter by tools/offering-classpath.mjs, which
    # already refused a listed jar that is not staged). Read here exactly as run.sh/serve.sh read it - no list of our own - so
    # the smoke boots the same jars, in the same order, the shipped launchers will. Names only: Test-Path is not needed.
    $cp = @(Get-Content (Join-Path $bundleDir 'modules.list') | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    # CONNECTOR-SIDECAR-SHADES-LOGGING-1: assert exactly ONE SLF4J binding registration across the
    # assembled classpath. The core owns the logging binding; every sidecar's shade config excludes
    # META-INF/services/org.slf4j.spi.SLF4JServiceProvider, org/slf4j/impl/** and ch/qos/logback/** so a
    # sidecar cannot carry a second one. A duplicate here means a sidecar's shade excludes are missing
    # or were dropped, which SLF4J resolves non-deterministically and silently -- exactly the failure
    # mode the excludes exist to prevent.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $providerHits = @()
    foreach ($jarName in $cp) {
        $jarPath = Join-Path $bundleDir $jarName
        $zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
        try {
            if ($zip.Entries | Where-Object { $_.FullName -eq 'META-INF/services/org.slf4j.spi.SLF4JServiceProvider' }) {
                $providerHits += $jarName
            }
        } finally { $zip.Dispose() }
    }
    if ($providerHits.Count -gt 1) {
        throw "PACKAGING SMOKE FAILED: $($providerHits.Count) jars on the bundle classpath carry META-INF/services/org.slf4j.spi.SLF4JServiceProvider ($($providerHits -join ', ')) -- exactly one SLF4J binding may exist on the assembled classpath. Check the shade excludes in the listed sidecars' pom.xml (see features/inspecto-agent/pom.xml for the reference exclude block)."
    }
    Write-Host "  verified: $($providerHits.Count) SLF4JServiceProvider registration(s) on the bundle classpath" -ForegroundColor DarkGray
    # ASSURE-INTELLIGENCE-BUNDLE-1: a VERSION SPLIT between the core jar and a sidecar for the libraries both
    # shade (jackson, commons-*, gson, slf4j) is a classpath conflict that only fails at run time, on whichever
    # copy loads first. Read each jar's META-INF/maven/<g>/<a>/pom.properties and compare against inspecto.jar.
    # ⚠ pom.properties alone over-reports: Nimbus (inspecto-oidc) RELOCATES its gson to
    # com/nimbusds/jose/shaded/gson but keeps gson's pom.properties, so a version counts only when the jar
    # also carries a class under the artifact's own, unrelocated package.
    $sharedFamily = '^META-INF/maven/(com\.fasterxml\.jackson[^/]*|tools\.jackson[^/]*|org\.apache\.commons|commons-[^/]+|com\.google\.code\.gson|org\.slf4j)/([^/]+)/pom\.properties$'
    # artifact -> its unrelocated package; an artifact not listed here is compared on pom.properties alone.
    $sharedPackage = @{
        'com.google.code.gson:gson' = 'com/google/gson/'
        'org.slf4j:slf4j-api' = 'org/slf4j/'
        'com.fasterxml.jackson.core:jackson-core' = 'com/fasterxml/jackson/core/'
        'com.fasterxml.jackson.core:jackson-databind' = 'com/fasterxml/jackson/databind/'
        'com.fasterxml.jackson.core:jackson-annotations' = 'com/fasterxml/jackson/annotation/'
        'tools.jackson.core:jackson-core' = 'tools/jackson/core/'
        'tools.jackson.core:jackson-databind' = 'tools/jackson/databind/'
        'org.apache.commons:commons-lang3' = 'org/apache/commons/lang3/'
        'org.apache.commons:commons-compress' = 'org/apache/commons/compress/'
        'org.apache.commons:commons-text' = 'org/apache/commons/text/'
        'commons-codec:commons-codec' = 'org/apache/commons/codec/'
        'commons-io:commons-io' = 'org/apache/commons/io/'
    }
    function Get-ShadedVersions([string]$jarPath) {
        $found = @{}
        $z = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
        try {
            foreach ($entry in $z.Entries) {
                if ($entry.FullName -match $sharedFamily) {
                    $ga = "$($Matches[1]):$($Matches[2])"
                    $pkg = $sharedPackage[$ga]
                    if ($pkg -and -not ($z.Entries | Where-Object { $_.FullName.StartsWith($pkg) -and $_.FullName.EndsWith('.class') } | Select-Object -First 1)) { continue }
                    $reader = New-Object System.IO.StreamReader($entry.Open())
                    try { $props = $reader.ReadToEnd() } finally { $reader.Dispose() }
                    if ($props -match '(?m)^version=(.+)$') { $found[$ga] = $Matches[1].Trim() }
                }
            }
        } finally { $z.Dispose() }
        return $found
    }
    $coreVersions = Get-ShadedVersions (Join-Path $bundleDir 'inspecto.jar')
    $splits = @()
    foreach ($jarName in ($cp | Where-Object { $_ -ne 'inspecto.jar' })) {
        $side = Get-ShadedVersions (Join-Path $bundleDir $jarName)
        foreach ($ga in $side.Keys) {
            if ($coreVersions.ContainsKey($ga) -and $coreVersions[$ga] -ne $side[$ga]) {
                $splits += "$ga $($coreVersions[$ga]) (inspecto.jar) vs $($side[$ga]) ($jarName)"
            }
        }
    }
    if ($splits.Count -gt 0) {
        throw "PACKAGING SMOKE FAILED: $($splits.Count) shared-library version split(s) between the core jar and a sidecar -- one copy wins at run time, nondeterministically by classpath order:`n  $($splits -join "`n  ")`nAlign the version in the parent pom's dependencyManagement, or exclude the library from the sidecar's shade."
    }
    Write-Host "  verified: no jackson/commons/gson/slf4j version split between inspecto.jar and the sidecars ($($coreVersions.Count) shared artifacts checked)" -ForegroundColor DarkGray
    $sep = if ($IsWindows -or $env:OS -eq 'Windows_NT') { ';' } else { ':' }
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start(); $port = $listener.LocalEndpoint.Port; $listener.Stop()
    # A Professional/Enterprise bundle CANNOT CONSTRUCT ITS AUTHENTICATOR WITHOUT OIDC CONFIG -- the boot
    # smoke discovered this, which is precisely what it is for. ControlApi calls Authenticators.active()
    # at startup and SpiSlot runs ServiceLoader regardless of -Dauth.mode, so the mere PRESENCE of
    # inspecto-oidc.jar makes OidcAuthenticator's constructor mandatory -- and it requires
    # -Dauth.oidc.jwksUri and -Dauth.oidc.issuer, failing closed with a named message otherwise.
    # serve.sh supplies both from AUTH_OIDC_* env vars; a deployment that omits them does not start.
    # These placeholders exist ONLY so the constructor completes: RemoteJWKSet wraps the URL and fetches
    # lazily, so nothing is contacted. Never mistake them for a working auth configuration.
    $oidcArgs = @()
    if (Test-Path (Join-Path $bundleDir 'inspecto-oidc.jar')) {
        $oidcArgs = @('-Dauth.oidc.jwksUri=http://127.0.0.1:1/boot-smoke-never-fetched',
                      '-Dauth.oidc.issuer=http://127.0.0.1:1/boot-smoke')
    }
    $out = Join-Path ([System.IO.Path]::GetTempPath()) "inspecto-boot-$port.log"
    Write-Host "Boot smoke: starting the staged bundle on :$port ..." -ForegroundColor Cyan
    # Build the argument list by CONCATENATION, not by nesting $oidcArgs inside an array literal:
    # PowerShell does not flatten a nested array there, so -ArgumentList (which wants string[]) receives
    # an Object[] element and Start-Process throws before Java is ever launched. `+` does flatten, and an
    # empty @() contributes nothing, so the Personal path stays clean.
    $demoArgs = if ($DemoAuth) { @('-Dcontrol.bind=127.0.0.1', '-Dauth.mode=demo', '-Dobjects.backend=db') } else { @() }
    $argList = @('--enable-native-access=ALL-UNNAMED', "-Dcontrol.port=$port", '-Dspaces.root=spaces') +
               $oidcArgs + $demoArgs +
               @('-cp', ($cp -join $sep), 'com.gamma.control.ControlApi')
    $proc = Start-Process -FilePath $java -WorkingDirectory $bundleDir -PassThru -NoNewWindow -RedirectStandardOutput $out -RedirectStandardError "$out.err" -ArgumentList $argList
    $healthy = $false
    foreach ($i in 1..60) {
        if ($proc.HasExited) { break }
        try {
            $r = Invoke-WebRequest -Uri "http://127.0.0.1:$port/health" -TimeoutSec 2 -UseBasicParsing -ErrorAction Stop
            if ($r.StatusCode -eq 200) { $healthy = $true; break }
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $proc.HasExited) { $proc.Kill(); $proc.WaitForExit(5000) }
    if (-not $healthy) {
        Write-Host "---- boot output ----" -ForegroundColor Yellow
        foreach ($f in @($out, "$out.err")) { if (Test-Path $f) { Get-Content $f -Tail 40 | Write-Host } }
        throw "BOOT SMOKE FAILED: the staged $Edition bundle never answered /health on :$port. The jars are present but the process does not start - this is the SEC-SIDECAR-BOOT-1 shape (a missing transitive on the assembled classpath). Output above."
    }
    Remove-Item $out, "$out.err" -ErrorAction SilentlyContinue
    Write-Host "  verified: the staged $Edition bundle boots and answers /health" -ForegroundColor DarkGray
}

# ── step 7: copy README + docs tree ─────────────────────────────────────────────
# In the repo the README lives in inspecto/ and links to ../docs/. In the
# bundle the README sits at the root, so rewrite ../docs/ → docs/ and ship the
# docs tree alongside it so the links resolve.
$readme = Get-Content "$adjParserDir\README.md" -Raw
$readme = $readme -replace '\.\./docs/', 'docs/'
Set-Content -Path "$bundleDir\README.md" -Value $readme -NoNewline
$docsSrc = Join-Path $sandboxRoot 'docs'

# BUNDLE-SHIPS-THE-ARCHIVE-1 (2026-09-16). Until this date the copy below staged ALL of docs/
# recursively with nothing excluded, so every customer bundle carried the two NON-CURRENT doc
# tiers that CLAUDE.md defines (measured from the entry table of the 2026-09-15 inspecto-deploy.zip:
# 491 docs files, of which 246 were archived-documents/ and 26 were superpower/ - i.e. 55% of the
# shipped documentation was material the project itself declares not current).
#
# The tiers are CLAUDE.md's, not this script's:
#   tier 1  current knowledge  -> okf/ + the root canon + stakeholders/ api/ ui/ ops/ roadmap/ wiki/   SHIPS
#   tier 2  active plans       -> superpower/        "a plan lives here ONLY while its work is in flight"  DOES NOT SHIP
#   tier 3  history            -> archived-documents/ "never maintained, never linked as current"         DOES NOT SHIP
#
# Excluded by NAME, one entry per tree, so the next shift can audit the list by reading it.
# Do NOT replace this with a pattern/glob: the reason each tree is out is a tier decision, and a
# glob records no reason. Adding a tree here is a product call, not a refactor.
$docsExcludedTrees = @(
    'archived-documents',  # CLAUDE.md tier 3 - provenance only, ~570 known-broken internal links,
                           # superseded designs and retracted claims. Shipping it publishes stale
                           # statements under the product's name.
    'superpower'           # CLAUDE.md tier 2 - in-flight plans. These describe work that is NOT
                           # built yet; a customer reading one would read a roadmap as a manual.
)
# Withheld for a DIFFERENT reason than the trees above, and kept in its own list so the two reasons
# are never conflated. These two ARE current-tier by CLAUDE.md's canon list - they are accurate and
# maintained. They are withheld because of AUDIENCE: they are written for the team working ON the
# product, not for the operator running it. Operator decision 2026-09-16.
$docsExcludedFiles = @(
    'BACKLOG.md',       # the internal defect board - it names open P1s, by identifier, in the very
                        # product the customer has just installed, together with their severity.
    'PROJECT_NOTES.md'  # internal working notes, same audience argument.
)
# Trees/files that MUST survive the exclusion. A filter that also drops wanted docs is worse than
# shipping the archive, so this is asserted after the copy rather than trusted.
$docsRequiredEntries = @(
    'okf', 'stakeholders', 'api', 'ui', 'ops', 'roadmap', 'wiki',
    'INDEX.md', 'GLOSSARY.md', 'USER_GUIDE.md', 'ADVANCED_GUIDE.md', 'EDITIONS.md'
)

if (Test-Path $docsSrc) {
    # Copy file-by-file (not one Copy-Item -Recurse) so a single locked/inaccessible
    # file (e.g. held by another process/AV) can't silently truncate the rest of the
    # tree under $ErrorActionPreference = 'Stop' — a whole-tree recursive copy was
    # observed to abort at the first such file and skip every remaining item.
    $docsOut = "$bundleDir\docs"
    $docsSkipped = 0
    Get-ChildItem -Path $docsSrc -Recurse -File | ForEach-Object {
        $srcFile = $_
        $relPath = $srcFile.FullName.Substring($docsSrc.Length + 1)
        # Match the FIRST path segment only: an exclusion is a top-level tree under docs/, never a
        # substring anywhere in the path (which would also drop e.g. okf/.../compliance notes).
        # For a file sitting directly under docs/, the first segment IS the file name, so one
        # comparison serves both lists.
        $topSegment = ($relPath -split '[\\/]')[0]
        if ($docsExcludedTrees -contains $topSegment) { $docsSkipped++; return }
        if ($docsExcludedFiles -contains $topSegment) { $docsSkipped++; return }
        $destPath = Join-Path $docsOut $relPath
        try {
            $destDir = Split-Path $destPath -Parent
            if (-not (Test-Path $destDir)) { New-Item -ItemType Directory -Path $destDir -Force | Out-Null }
            Copy-Item $srcFile.FullName -Destination $destPath -Force -ErrorAction Stop
        } catch {
            Write-Warning "docs copy: skipped '$($srcFile.FullName)' ($($_.Exception.Message))"
        }
    }

    # Fail closed in BOTH directions. The point of the row is auditability, so packaging asserts the
    # outcome it intends instead of leaving it to be re-derived from this source later.
    foreach ($tree in $docsExcludedTrees) {
        if (Test-Path (Join-Path $docsOut $tree)) {
            throw "DOCS TIER LEAK: '$tree' is on the non-current list but was staged into the bundle at docs\$tree. See BUNDLE-SHIPS-THE-ARCHIVE-1."
        }
    }
    foreach ($file in $docsExcludedFiles) {
        if (Test-Path (Join-Path $docsOut $file)) {
            throw "DOCS AUDIENCE LEAK: '$file' is withheld from customer bundles by the 2026-09-16 operator decision but was staged at docs\$file. See BUNDLE-SHIPS-THE-ARCHIVE-1."
        }
    }
    foreach ($entry in $docsRequiredEntries) {
        if (-not (Test-Path (Join-Path $docsOut $entry))) {
            throw "DOCS OVER-FILTERED: the bundle is missing docs\$entry, which is current-tier documentation that MUST ship. The exclusion in step 7 is too wide. See BUNDLE-SHIPS-THE-ARCHIVE-1."
        }
    }
    # BUNDLE-DANGLING-LINKS-1 (2026-09-16, single-sourced 2026-09-24). Withholding the two non-current
    # tiers leaves the SHIPPED docs pointing INTO them (250 links, ~100 in INDEX.md alone - the customer's
    # front door), and relocating README.md / examples/ leaves 15 more pointing at the old place.
    #
    # The rewrite is tools/bundle-doc-rewrite.mjs - ONE implementation, which tools/check-bundle-doc-links.mjs
    # imports and applies in its simulated mode. It used to be a regex here that the guard never modelled,
    # so the guard stayed red at 321 after this fix shipped. Do not re-inline it. Per link (fence-aware):
    #   withheld target  -> `label (internal document - not shipped)`
    #   relocated target -> re-pointed at where it lands in the bundle
    #   repo-only target -> `label (`repo/path` - not shipped)` (source-code citations, compliance/,
    #                       sibling modules above the bundle root; operator decision 2026-09-25)
    #   anything else    -> left alone (a link broken in the repo too), so the guard still reports
    #                       it - hiding that here would hide real rot.
    #
    # Option (b), shipping a marked stub per withheld target, stays REFUSED BY THIS SCRIPT'S OWN
    # ASSERTIONS: every stub would land under docs/archived-documents/ or docs/superpower/, which the
    # DOCS TIER LEAK / DOCS AUDIENCE LEAK throws above exist to forbid.
    Push-Location $sandboxRoot
    & node (Join-Path $sandboxRoot 'tools\bundle-doc-rewrite.mjs') --bundle $bundleDir --withheld-trees ($docsExcludedTrees -join ',') --withheld-files ($docsExcludedFiles -join ',')
    $rewriteExit = $LASTEXITCODE
    Pop-Location
    if ($rewriteExit -ne 0) { throw "docs link rewrite failed (exit $rewriteExit) - the bundle would ship links into withheld docs. See BUNDLE-DANGLING-LINKS-1." }

    $docsShipped = (Get-ChildItem -Path $docsOut -Recurse -File).Count
    Write-Host "  docs: staged $docsShipped files; withheld $docsSkipped (tiers: $($docsExcludedTrees -join ', '); audience: $($docsExcludedFiles -join ', '))" -ForegroundColor DarkGray
}

# ── step 8: zip the host-platform bundle, then swap runtime/ and zip again for a cross-built target ──
# Each zip carries ONLY its own platform's DuckDB extensions. Both platforms are staged into the one
# $bundleDir above (so a single assembly serves both targets), but a bundle can only ever LOAD the
# directory its own launcher probes: run.sh reads duckdb-extensions/linux_amd64, serve.bat reads
# windows_amd64. Shipping both was called "harmless — like run.sh sitting unused in the Windows zip",
# and at three extensions it was. At five it is ~43-45 MB zipped per bundle, about 16% of the
# download, unreachable by construction — httpfs + aws added 33.5 MB of that on 2026-09-14
# (AIRGAP-CROSSPLAT-DEADWEIGHT-1, measured from the built zips' own entry tables).
#
# ⛔ This filters the EXTENSION directory ONLY. The launchers are cross-copied ON PURPOSE — run.sh
# does ship in the Windows zip — so a naive "filter by platform" that swept them out too would break
# a deliberate convenience. ⚠ The boot smoke runs against $bundleDir BEFORE this step and stays green
# whatever the zips contain, so it cannot witness this: verify from the zip entry tables instead.
function Compress-BundleForPlatform {
    param(
        [Parameter(Mandatory)][string] $Platform,
        [Parameter(Mandatory)][string] $DestinationPath
    )
    $extRoot = Join-Path $bundleDir 'duckdb-extensions'
    $parked  = @()
    if (Test-Path $extRoot) {
        foreach ($dir in Get-ChildItem -Path $extRoot -Directory) {
            if ($dir.Name -ne $Platform) {
                $tmp = Join-Path $sandboxRoot ('inspecto-deploy-ext-' + $dir.Name)
                if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
                Move-Item $dir.FullName $tmp
                $parked += , @($tmp, $dir.FullName)
            }
        }
    }
    try {
        if (Test-Path $DestinationPath) { Remove-Item $DestinationPath -Force }
        if ($IsWindows -or $env:OS -eq 'Windows_NT') {
            # RELEASE-LAUNCHERS-NOT-EXECUTABLE-1: Compress-Archive stores NO POSIX mode bits, and on a
            # Windows host there are none to store — NTFS has no exec bit. A linux_amd64 zip cross-built here
            # therefore unzips with 0644 launchers; install-service.sh chmods serve.sh itself (its own
            # `[ -x serve.sh ] || chmod +x serve.sh`), run.sh/ura.sh need a by-hand `chmod +x` on that path.
            Compress-Archive -Path $bundleDir -DestinationPath $DestinationPath
        } else {
            # On a POSIX host (ubuntu-latest) modes CAN be preserved: Info-ZIP `zip` records them in the
            # Unix extra field and `unzip` restores them. The launchers were written with WriteAllText
            # (0644), so set the bit first. release.yml asserts `-rwx` on serve.sh in the published zip.
            $zipExe = (Get-Command zip -ErrorAction SilentlyContinue).Source
            if (-not $zipExe) { throw "Compress-BundleForPlatform: 'zip' (Info-ZIP) not on PATH — it is required on a POSIX host so launcher exec bits survive into $DestinationPath" }
            Get-ChildItem -Path $bundleDir -Recurse -File -Filter '*.sh' | ForEach-Object { & chmod +x $_.FullName }
            Push-Location $sandboxRoot
            try {
                & $zipExe -r -q -X $DestinationPath (Split-Path -Leaf $bundleDir)
                if ($LASTEXITCODE -ne 0) { throw "zip exited $LASTEXITCODE for $DestinationPath" }
            } finally { Pop-Location }
        }
    } finally {
        # Restored even when Compress-Archive throws — a half-stripped $bundleDir would otherwise
        # silently produce a SECOND zip missing extensions it was supposed to carry.
        foreach ($pair in $parked) { Move-Item $pair[0] $pair[1] }
    }
}

# ⛔ The platform passed here is what Get-RuntimePlatform read off the embedded image (or, under
# -NoRuntime, the host OS) — never a literal. On ubuntu-latest this is linux_amd64; the old hard-coded
# 'windows_amd64' paired a Linux JVM with Windows-only extensions in the only zip a tag published.
$zipStem = if ($DemoAuth) { "inspecto-demo" } else { "inspecto-deploy" }
$outZips[$hostPlatform] = Join-Path $sandboxRoot "$zipStem-$hostPlatform.zip"
Compress-BundleForPlatform -Platform $hostPlatform -DestinationPath $outZips[$hostPlatform]

if ($builtLinuxRuntime) {
    # Common bundle content (jar, config, docs, UI, scripts) was already assembled once above;
    # only the runtime/ folder differs per target, so swap it in place and re-zip rather than
    # rebuilding the whole bundle a second time.
    $crossRuntimeSrc = Join-Path $sandboxRoot 'inspecto-deploy-linux-runtime'
    $crossPlatform   = Get-RuntimePlatform -RuntimeDir $crossRuntimeSrc
    if ($crossPlatform -eq $hostPlatform) { throw "cross-built runtime is $crossPlatform, same as the host image — the two zips would collide" }
    $hostRuntimeOut = Join-Path $bundleDir 'runtime'
    $hostRuntimeTmp = Join-Path $sandboxRoot "inspecto-deploy-$hostPlatform-runtime"
    if (Test-Path $hostRuntimeTmp) { Remove-Item $hostRuntimeTmp -Recurse -Force }
    Move-Item $hostRuntimeOut $hostRuntimeTmp
    Move-Item $crossRuntimeSrc $hostRuntimeOut

    $outZips[$crossPlatform] = Join-Path $sandboxRoot "$zipStem-$crossPlatform.zip"
    Compress-BundleForPlatform -Platform $crossPlatform -DestinationPath $outZips[$crossPlatform]

    # Restore the host runtime. ⚠ $bundleDir is now a SUPERSET of either zip — it holds both
    # platforms' extensions, while each zip holds only its own — so anything inspecting it must not
    # treat it as a mirror of any one zip.
    Remove-Item $hostRuntimeOut -Recurse -Force
    Move-Item $hostRuntimeTmp $hostRuntimeOut
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
        # COMPLY-2 (matrix G3): -Sign is a PROMISE, not a preference. It used to downgrade to a warning
        # when gpg or the key was missing, so a release could ship unsigned while the log said "-Sign".
        # A missing .asc must now be impossible to produce by accident — the release workflow relies on
        # it (.github/workflows/release.yml), and so does a verifier reading "no .asc = not signed".
        $gpg = (Get-Command gpg -ErrorAction SilentlyContinue).Source
        if (-not $gpg) {
            throw "-Sign requested but 'gpg' is not on PATH — cannot sign $name (install gpg, or drop -Sign for an explicitly UNSIGNED build)"
        }
        if (-not $SigningKey) {
            throw "-Sign requested but no signing key (pass -SigningKey or set INSPECTO_SIGNING_KEY) — cannot sign $name"
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
foreach ($zipPath in $outZips.Values) { New-ReleaseIntegrity -ArtifactPath $zipPath }

Write-Host ""
Write-Host "Deployment bundle(s) ready:" -ForegroundColor Green
foreach ($plat in $outZips.Keys) { Write-Host "  $($outZips[$plat])  (+ .sha256$sigNote)  [$plat]" }
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
    if ($NoUi)             { Write-Warning "  Cause: -NoUi was passed and inspecto-ui/dist holds no index.html — build the UI first (npm run build -- $Ui in inspecto-ui/)." }
    elseif (-not $uiBuilt) { Write-Warning "  Cause: no inspecto-ui/ project found in this checkout." }
    else                   { Write-Warning "  Cause: the UI build produced no index.html under $uiDist." }
    Write-Host ""
}

Write-Host ""
Write-Host "Deploy to remote server:" -ForegroundColor Cyan
Write-Host "  1. Copy the inspecto-deploy-<platform>.zip matching the server to it"
Write-Host "  2. Expand-Archive inspecto-deploy-windows_amd64.zip   (PowerShell)"
Write-Host "     or:  unzip inspecto-deploy-linux_amd64.zip         (Linux)"
Write-Host "  3. cd inspecto-deploy"
Write-Host "  3b. Attach your Space(s): the bundle ships NO Spaces (only spaces\_templates, the template gallery)."
Write-Host "       Drop your Space folder(s) into spaces\ (or set SPACES_ROOT), or create one in Settings -> Spaces."
Write-Host "  4. ETL pipeline (one-shot):"
Write-Host "       run.bat voucher         (Windows)"
Write-Host "       bash run.sh voucher     (Linux)"
Write-Host "  4b. Control plane + operator UI (long-running service):"
Write-Host "       serve.bat                (Windows)"
Write-Host "       bash serve.sh            (Linux)"
Write-Host "       then open http://localhost:8080/  (UI served from ./ui)"
Write-Host "  4c. Run it as an OS service so a crashed process comes back (SCR-3):"
Write-Host "       .\install-service.ps1    (Windows, ELEVATED PowerShell)"
Write-Host "       sudo ./install-service.sh (Linux, systemd)"
Write-Host "       verify recovery: kill the process, then curl /health -- do not assume it"
Write-Host "  5. Pre-ETL utilities:"
Write-Host "       ura.bat help            (Windows)"
Write-Host "       bash ura.sh help        (Linux)"
Write-Host "       bash ura.sh search  spaces/<your-space>/config/voucher/voucher_pipeline.toon"
Write-Host "       bash ura.sh backup  spaces/<your-space>/config/voucher/voucher_pipeline.toon"
Write-Host "  6. Try the worked feature examples (self-contained, synthetic data):"
Write-Host "       pwsh examples/run-example.ps1 01-ingest/hello-csv     (Windows)"
Write-Host "       bash examples/run-example.sh  01-ingest/hello-csv     (Linux)"
Write-Host "     or run one as a service (poll loop + Control API probes):"
Write-Host "       pwsh examples/serve-example.ps1 06-serve/sequence-gap --demo"
Write-Host "       see examples/README.md for the full catalog"
Write-Host ""
if (-not $NoRuntime) {
    Write-Host "Embedded Java runtime included (bundle\runtime\) — no JVM needed on the target."
    foreach ($plat in $outZips.Keys) { Write-Host "  $($outZips[$plat])  → $plat-native embedded JVM + $plat DuckDB extensions" -ForegroundColor Green }
    if (-not $outZips.Contains('linux_amd64')) {
        Write-Host "  (no Linux GraalVM jmods cache found — inspecto-deploy-linux_amd64.zip not built;" -ForegroundColor Yellow
        Write-Host "   the bundled *.sh launchers fall back to system java on Linux instead.)" -ForegroundColor Yellow
    }
    Write-Host "The run/serve/ura launchers auto-prefer the embedded runtime when present."
} else {
    Write-Host "Java 24+ required on the target server.  No other dependencies needed."
}
