<#
    bench-duckdb.ps1 -Label <name> -OutDir <dir> [-DuckDbVersion <v>] [-Workloads a,b] [-Warmup 1] [-Reps 3] [-Scale 1.0]

    Like-for-like DuckDB benchmark: runs the SAME workloads against one DuckDB version and writes ONE JSON
    (<OutDir>/<Label>.json). Compare two runs with `node tools/bench-duckdb-compare.mjs a.json b.json`.
    Docs: docs/okf/backend/build-run/performance.md ("DuckDB version comparison harness").

    Workloads (default = all but la100m):
      scan readers csv store      tools/bench/DuckDbBench.java (single-file launch, pure DuckDB JDBC; peak RSS recorded)
      la1m la10m la100m           IndexScaleBench via mvn (build + one-hop + depth-2); la100m is READ-ONLY on the existing index
      engine                      PluginIngestBenchmark via mvn (the engine's real ingest -> transform -> partitioned parquet path)

    -DuckDbVersion defaults to ${duckdb.version} in pom.xml; the jar must be in ~/.m2. Run on an otherwise idle machine.
    -CorpusDir (default %TEMP%\inspecto-bench-corpus) holds generated inputs shared by every label; -ScaleDir (default
    %LOCALAPPDATA%\Temp\scale) is the IndexScaleBench corpus (data-N/ idx-N/) and is never modified: la1m/la10m build a
    per-label index under <OutDir>\idx-<Label>, la100m only reads the existing idx-100000000.
#>
param(
    [Parameter(Mandatory)][string]$Label,
    [Parameter(Mandatory)][string]$OutDir,
    [string]$DuckDbVersion,
    [string[]]$Workloads = @('scan', 'readers', 'csv', 'store', 'la1m', 'la10m', 'engine'),
    [int]$Warmup = 1,
    [int]$Reps = 3,
    [double]$Scale = 1.0,
    [string]$CorpusDir = (Join-Path $env:TEMP 'inspecto-bench-corpus'),
    [string]$ScaleDir = (Join-Path $env:LOCALAPPDATA 'Temp\scale')
)
$ErrorActionPreference = 'Stop'
$Workloads = @($Workloads | ForEach-Object { $_ -split ',' })   # `pwsh -File ... -Workloads a,b` passes one string
$root = Split-Path $PSScriptRoot -Parent
$java = Join-Path $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\latest\jdk-27' }) 'bin\java.exe'
if (-not $DuckDbVersion) { $DuckDbVersion = ([regex]::Match((Get-Content "$root\pom.xml" -Raw), '<duckdb\.version>([^<]+)<')).Groups[1].Value }
$jar = Join-Path $HOME ".m2\repository\org\duckdb\duckdb_jdbc\$DuckDbVersion\duckdb_jdbc-$DuckDbVersion.jar"
if (-not (Test-Path $jar)) { throw "DuckDB jar not in ~/.m2: $jar" }
New-Item -ItemType Directory -Force $OutDir | Out-Null
$OutDir = (Resolve-Path $OutDir).Path

function Add-Sample($metrics, $name, $value) {
    if (-not $metrics.Contains($name)) { $metrics[$name] = [System.Collections.Generic.List[double]]::new() }
    $metrics[$name].Add([double]$value)
}
function Num($s) { [double]($s -replace ',', '') }

# --- parsers for the two mvn-driven benchmarks (pure functions of the console text; unit-checkable) ---
function Read-IndexScale([string[]]$lines, $metrics) {
    foreach ($l in $lines) {
        if ($l -match 'FULL build ([\d.,]+) s') { Add-Sample $metrics 'build_ms' ((Num $Matches[1]) * 1000) }
        elseif ($l -match 'SCALE .*?(one-hop \(1 key\)|one-hop \(20 keys\)|hub key|depth-2 walk).*?p50 ([\d.,]+) ms\s+p95 ([\d.,]+) ms') {
            $k = switch -Wildcard ($Matches[1]) { 'one-hop (1*' { 'hop1' } 'one-hop (2*' { 'hop20' } 'hub*' { 'hub' } default { 'walk2' } }
            Add-Sample $metrics "${k}_p50_ms" (Num $Matches[2])
            Add-Sample $metrics "${k}_p95_ms" (Num $Matches[3])
        }
    }
}
function Read-PluginIngest([string[]]$lines, $metrics) {
    foreach ($l in $lines) {
        if ($l -match '^streaming (union|gen)\b.*?\s([\d.,]+)s\s+([\d,]+)\s+\d+\s+\d+\s*$') {
            Add-Sample $metrics "$($Matches[1])_ms" ((Num $Matches[2]) * 1000)
            Add-Sample $metrics "$($Matches[1])_rps" (Num $Matches[3])
        }
    }
}
if ($env:BENCH_SELFTEST) {   # parser self-check, no benchmark run
    $m = [ordered]@{}
    Read-IndexScale @('SCALE N=1,000,000 base (0 deltas)  one-hop (1 key)   p50 1.25 ms  p95 3.10 ms  max 9.00 ms', 'SCALE edges=1,000,000 FULL build 12 s, 8 buckets', 'SCALE x  depth-2 walk (undirected, 5 served, 0 over the 20-key cap) p50 20.50 ms  p95 40.00 ms  max 1 ms') $m
    Read-PluginIngest @('streaming union (1 batch)       1.62s      617,284          30        12') $m
    $m.GetEnumerator() | ForEach-Object { "$($_.Key) = $($_.Value -join ',')" }
    return
}

function Invoke-Mvn($module, $testClass, $props, $logName) {
    $log = Join-Path $OutDir "$Label-$logName.log"
    $a = @('-o', '-pl', $module, 'test', "-Dtest=$testClass", '-Dsurefire.failIfNoSpecifiedTests=false', "-Dduckdb.version=$DuckDbVersion") + $props
    Push-Location $root
    try { & mvn @a 2>&1 | Tee-Object -FilePath $log | Out-Null; $code = $LASTEXITCODE } finally { Pop-Location }
    if ($code -ne 0) { throw "mvn failed for $testClass (exit $code), see $log" }
    return , (Get-Content $log)
}

$result = [ordered]@{
    label = $Label; startedAt = (Get-Date).ToString('o'); duckdbRequested = $DuckDbVersion; duckdbVersion = $null
    java = $null; host = "$env:COMPUTERNAME $([Environment]::OSVersion.VersionString) cpus=$([Environment]::ProcessorCount)"
    params = [ordered]@{ warmup = $Warmup; reps = $Reps; scale = $Scale }
    gitHead = (git -C $root rev-parse --short HEAD); workloads = [ordered]@{}
}

foreach ($w in $Workloads) {
    Write-Host "== $Label / $w"
    $metrics = [ordered]@{}; $rss = $null
    if ($w -in 'scan', 'readers', 'csv', 'store') {
        $tmp = Join-Path $OutDir "$Label-$w.part.json"
        $p = Start-Process -FilePath $java -PassThru -NoNewWindow -RedirectStandardOutput (Join-Path $OutDir "$Label-$w.log") -ArgumentList @(
            '--enable-native-access=ALL-UNNAMED', '-cp', "`"$jar`"", "`"$root\tools\bench\DuckDbBench.java`"", '--workload', $w, '--warmup', $Warmup,
            '--reps', $Reps, '--scale', $Scale, '--corpus', "`"$CorpusDir`"", '--out', "`"$tmp`"")
        while (-not $p.HasExited) { try { $p.Refresh(); $rss = [Math]::Max([double]$rss, $p.PeakWorkingSet64) } catch {}; Start-Sleep -Milliseconds 200 }
        if ($p.ExitCode -ne 0 -or -not (Test-Path $tmp)) { throw "DuckDbBench $w failed (exit $($p.ExitCode)), see $OutDir\$Label-$w.log" }
        $r = Get-Content $tmp -Raw | ConvertFrom-Json
        $result.duckdbVersion = $r.duckdbVersion; $result.java = $r.java
        foreach ($prop in $r.metrics.PSObject.Properties) { foreach ($v in $prop.Value) { Add-Sample $metrics $prop.Name $v } }
        Remove-Item $tmp
    }
    elseif ($w -in 'la1m', 'la10m', 'la100m') {
        $edges = @{ la1m = 1000000; la10m = 10000000; la100m = 100000000 }[$w]
        $props = @("-Dinspecto.bench.dir=$ScaleDir", "-Dinspecto.bench.edges=$edges", '-Dinspecto.bench.skipDeltas=true')
        if ($w -eq 'la100m') {
            if (-not (Test-Path "$ScaleDir\idx-100000000")) { throw "la100m is read-only and needs $ScaleDir\idx-100000000" }
        } else {
            $idx = Join-Path $OutDir "idx-$Label"
            if (Test-Path "$idx\idx-$edges") { Remove-Item -Recurse -Force "$idx\idx-$edges" }
            $props += "-Dinspecto.bench.idxDir=$idx"
        }
        for ($i = 0; $i -lt $Warmup + $Reps; $i++) {
            $m = [ordered]@{}
            Read-IndexScale (Invoke-Mvn 'inspecto-la-storage' 'IndexScaleBench' $props "$w-$i") $m
            foreach ($k in $m.Keys) { if ($i -ge $Warmup -or $k -eq 'build_ms') { foreach ($v in $m[$k]) { Add-Sample $metrics $k $v } } }   # build happens once, in rep 0
        }
    }
    elseif ($w -eq 'engine') {
        $props = @('-Dbench.run=true', "-Dbench.rows=$([int](1000000 * $Scale))", '-Dbench.days=30', '-Dbench.format=PARQUET')
        for ($i = 0; $i -lt $Warmup + $Reps; $i++) {
            $m = [ordered]@{}
            Read-PluginIngest (Invoke-Mvn 'inspecto-engine' 'PluginIngestBenchmark' $props "$w-$i") $m
            if ($i -ge $Warmup) { foreach ($k in $m.Keys) { foreach ($v in $m[$k]) { Add-Sample $metrics $k $v } } }
        }
    }
    else { throw "unknown workload $w" }
    if ($metrics.Count -eq 0) { throw "workload $w produced no metrics" }
    $result.workloads[$w] = [ordered]@{ metrics = $metrics; peakRssMb = $(if ($rss) { [Math]::Round($rss / 1MB) } else { $null }) }
}
if (-not $result.duckdbVersion) { $result.duckdbVersion = "requested $DuckDbVersion (mvn workloads only)" }
$out = Join-Path $OutDir "$Label.json"
$result | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 $out
Write-Host "wrote $out  (DuckDB $($result.duckdbVersion))"
