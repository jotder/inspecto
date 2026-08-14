<#
    run-backend.ps1 — launch the Inspecto control plane (ControlApi) from the working tree.

    Why this exists: .claude/launch.json used to carry a hand-written classpath — 9 module
    target/classes dirs plus ~30 .m2 jars, with the checkout root baked in. It went stale
    every time a module's dependencies changed, and it had already rotted to an unbootable
    single module once (rebuilt 2026-07-27). This derives the whole thing at run time, so
    that class of breakage is gone.

    How the classpath is built (the ordering and the exclusions both matter):

      1. `mvn -o dependency:build-classpath -pl inspecto` resolves the core module's RUNTIME
         dependencies. Deliberately WITHOUT -am: build-classpath does not need the siblings
         compiled, and resolving them from .m2 is what lets step 2 discover which ones there are.
      2. Every com.gamma.inspector jar in that list is mapped back to its reactor
         `<module>/target/classes` and PREPENDED, so fresh working-tree code shadows the stale
         installed jars (this also silences the duplicate-logback.xml warning).
      3. Because the module list is derived from inspecto's own dependency tree, an optional
         ServiceLoader module that is NOT a dependency can never leak onto the classpath.
         That is load-bearing: inspecto-connectors carries SmtpEmailChannel, and putting it on
         the classpath makes NotificationService.discoverChannels find a channel whose javax.mail
         dependency was never shipped — boot dies with NoClassDefFoundError: javax/mail/Message.
         See docs/PROJECT_NOTES.md §4.

    Usage:
        pwsh -File tools/run-backend.ps1 [-Port 8080] [-Rebuild]
#>
param(
    [int]$Port = 8080,
    [string]$SpacesRoot,
    [string]$WriteRoot,
    [string]$UiDir,
    [switch]$Rebuild   # compile the reactor first (-pl inspecto -am) before launching
)

$ErrorActionPreference = 'Stop'

$repo = Split-Path $PSScriptRoot -Parent
$mvn  = if ($env:MVN_CMD) { $env:MVN_CMD } else { 'mvn' }

if (-not $SpacesRoot) { $SpacesRoot = Join-Path $repo 'spaces' }
if (-not $WriteRoot)  { $WriteRoot  = Join-Path $repo 'spaces/demo/config' }
if (-not $UiDir)      { $UiDir      = Join-Path $repo 'inspecto-ui/dist/gamma/browser' }

if ($Rebuild) {
    Write-Host "Compiling reactor (-pl inspecto -am)..." -ForegroundColor Cyan
    & $mvn -o -q -f (Join-Path $repo 'pom.xml') -pl inspecto -am -DskipTests compile
    if ($LASTEXITCODE -ne 0) { throw "reactor compile failed" }
}

# ── step 1: resolve inspecto's runtime dependencies ───────────────────────────
$cpFile = Join-Path $repo 'target/backend-cp.txt'
New-Item -ItemType Directory -Force -Path (Split-Path $cpFile) | Out-Null
Write-Host "Deriving classpath (mvn -o dependency:build-classpath)..." -ForegroundColor Cyan
# ⚠ Every -D argument is QUOTED, including the ones with no variable in them. PowerShell parses an
# unquoted native argument that starts with `-` as a parameter token and ENDS THE TOKEN at the `.`,
# so a bare -Dmdep.includeScope=runtime arrives as two arguments (`-Dmdep` and `.includeScope=runtime`)
# and Maven reports the tail as an unknown lifecycle phase. Quoting is what keeps it one argument.
& $mvn -o -q -f (Join-Path $repo 'pom.xml') -pl inspecto dependency:build-classpath `
       "-Dmdep.outputFile=$cpFile" "-Dmdep.includeScope=runtime"
if ($LASTEXITCODE -ne 0) { throw "dependency:build-classpath failed — is the .m2 repository populated?" }

$entries = (Get-Content $cpFile -Raw).Trim() -split ';' | Where-Object { $_ }

# ── step 2: map each reactor artifact back to its working-tree classes dir ────
# .m2 layout: .../com/gamma/inspector/<artifactId>/<version>/<artifactId>-<version>.jar
# Dir == artifactId for every module except inspecto/ -> inspecto-processor.
$moduleDirs = [System.Collections.Generic.List[string]]::new()
$addModule = {
    param($dir)
    $classes = Join-Path $repo "$dir/target/classes"
    if (-not (Test-Path $classes)) {
        throw "$dir/target/classes is missing — run with -Rebuild (or: mvn -o -pl inspecto -am -DskipTests compile)"
    }
    if (-not $moduleDirs.Contains($classes)) { $moduleDirs.Add($classes) }
}

& $addModule 'inspecto'
foreach ($e in $entries) {
    if ($e -match '[\\/]com[\\/]gamma[\\/]inspector[\\/]([^\\/]+)[\\/]') {
        $artifact = $Matches[1]
        $dir = if ($artifact -eq 'inspecto-processor') { 'inspecto' } else { $artifact }
        & $addModule $dir
    }
}

# Working-tree classes FIRST so they shadow the stale installed inspecto-* jars.
$cp = (($moduleDirs + $entries) -join ';')
Write-Host "Classpath: $($moduleDirs.Count) module classes dirs + $($entries.Count) jars" -ForegroundColor Green

# ── step 3: launch ────────────────────────────────────────────────────────────
& java --enable-native-access=ALL-UNNAMED `
    "-Dcontrol.port=$Port" `
    "-Dcontrol.token=dev" `
    "-Dassist.read.token=dev" `
    "-Dassist.write.root=$WriteRoot" `
    "-Dspaces.root=$SpacesRoot" `
    "-Djobs.backend=duckdb" `
    "-Dprovenance.backend=duckdb" `
    "-Dobjects.backend=db" `
    "-Devents.backend=parquet" `
    "-Dui.dir=$UiDir" `
    -cp $cp `
    com.gamma.control.ControlApi
exit $LASTEXITCODE
