#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Run one bundled Inspecto example end-to-end (self-contained, no external services).

.DESCRIPTION
    Resolves the engine JAR, creates the example's output dirs, and runs the example's
    pipeline.toon with the mandatory DuckDB native-access flag. Output lands under the
    example's own out/ directory — nothing else on disk is touched.

    Works both from the source tree (inspecto/examples/) and the release bundle (examples/).
    JAR resolution order: $env:INSPECTO_JAR → ../inspecto.jar (bundle) →
    ../target/inspecto-processor-*.jar (source tree).

.EXAMPLE
    pwsh run-example.ps1 01-ingest/hello-csv
.EXAMPLE
    $env:INSPECTO_JAR="C:\path\inspecto.jar"; pwsh run-example.ps1 02-parsing/tsv-pipe
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Example,
    [switch]$Clean
)
$ErrorActionPreference = 'Stop'
$examplesRoot = $PSScriptRoot

function Resolve-Jar {
    if ($env:INSPECTO_JAR -and (Test-Path $env:INSPECTO_JAR)) { return (Resolve-Path $env:INSPECTO_JAR).Path }
    # P3d stage 2: inspecto.jar is the product jar only; the core libraries are thin jars. This returns a CLASSPATH (the callers use -cp).
    $bundle = Join-Path $examplesRoot '..\inspecto.jar'
    $list = Join-Path $examplesRoot '..\core.list'
    if ((Test-Path $bundle) -and (Test-Path $list)) {
        $bundleRoot = (Resolve-Path (Join-Path $examplesRoot '..')).Path
        return ((Get-Content $list | ForEach-Object { $_.Trim() } | Where-Object { $_ } | ForEach-Object { Join-Path $bundleRoot $_ }) -join [IO.Path]::PathSeparator)
    }
    if (Test-Path $bundle) { return (Resolve-Path $bundle).Path }   # hand-assembled directory with no core.list
    $tree = Get-ChildItem (Join-Path $examplesRoot '..\target') -Filter 'inspecto-processor-*.jar' -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
    if ($tree) {
        $repo = Join-Path $examplesRoot '..\..'
        $libs = Get-ChildItem -Path (Join-Path $repo 'platform\*\target'), (Join-Path $repo 'spi\*\target') -Filter 'inspecto-*.jar' -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -notmatch 'sources|javadoc|tests|original' } | ForEach-Object { $_.FullName }
        return (@($tree.FullName) + @($libs)) -join [IO.Path]::PathSeparator
    }
    throw "Engine JAR not found. Set `$env:INSPECTO_JAR, or build it (mvn -o clean package)."
}

$jar = Resolve-Jar
$dir = Join-Path $examplesRoot $Example
$pipeline = Join-Path $dir 'pipeline.toon'
if (-not (Test-Path $pipeline)) { throw "No pipeline.toon under '$dir'. Pass an example path like 01-ingest/hello-csv." }

Push-Location $dir
try {
    if ($Clean -and (Test-Path 'out')) { Remove-Item -Recurse -Force 'out' }
    foreach ($d in 'inbox','database','backup','temp','errors','quarantine','markers','status','logs') {
        New-Item -ItemType Directory -Force -Path (Join-Path 'out' $d) | Out-Null
    }
    # Seed a fresh working inbox from the pristine, committed samples/ (the engine consumes the poll dir).
    if (Test-Path 'samples') { Copy-Item -Recurse -Force 'samples\*' 'out\inbox\' -ErrorAction SilentlyContinue }
    Write-Host "Running '$Example'" -ForegroundColor Cyan
    Write-Host "  jar:    $jar"
    Write-Host "  config: $pipeline`n"
    # Path-jail roots (PKG-6): the one-shot CollectorProcessor runs no space discovery, so
    # -Dassist.safety.roots is the ONLY source of allowed roots; the example dir IS the root.
    & java --enable-native-access=ALL-UNNAMED "-Dassist.safety.roots=$((Get-Location).Path)" -cp $jar com.gamma.inspector.CollectorProcessor 'pipeline.toon'
    $code = $LASTEXITCODE
    Write-Host "`nExit code: $code"
    if (Test-Path 'out/database') {
        Write-Host "Output (out/database):"
        Get-ChildItem -Recurse 'out/database' -File -ErrorAction SilentlyContinue |
            Select-Object -First 20 | ForEach-Object { Write-Host "  $($_.FullName.Substring($dir.Length+1))" }
    }
    exit $code
}
finally { Pop-Location }
