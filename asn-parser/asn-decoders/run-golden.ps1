# Phase 0 golden-corpus capture (REDESIGN.md §6). Re-runnable: rebuilds corpus/ from the
# committed config/ + data/ samples by driving the LEGACY decoder, one case per JVM —
# the legacy transformer has static config and can OOM on cartesian joins, so isolation
# turns a blown case into a report entry instead of a dead run.
#
# Usage:  .\run-golden.ps1            (from asn-decoders/)
param([string]$BaseDir = (Resolve-Path "$PSScriptRoot\.."))

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

mvn -o -q install -DskipTests
if ($LASTEXITCODE -ne 0) { throw "maven build failed" }
mvn -o -q dependency:build-classpath "-Dmdep.outputFile=target\cp.txt" -pl asn-golden
if ($LASTEXITCODE -ne 0) { throw "classpath resolution failed" }

$cp = (Get-Content asn-golden\target\cp.txt) + ";asn-golden\target\classes"
$cases = java -cp $cp com.gamma.asn.golden.GoldenCapture $BaseDir --list

foreach ($case in $cases) {
    java -Xmx1024m -cp $cp com.gamma.asn.golden.GoldenCapture $BaseDir $case 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "case $case exited $LASTEXITCODE (see corpus/$case/report.json if present)"
    }
}
Write-Host "done: $BaseDir\corpus"
