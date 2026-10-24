# Steps 5-6 of bake-all.ps1 for an already seeded and cleanly stopped temp root: rebase build paths, relocatability scan, carry over.
param(
    [Parameter(Mandatory)][string]$Work,
    [string]$Repo = 'C:\sandbox\preview-la',
    [string]$Bundle = 'C:\sandbox\preview-la\inspecto-demo',
    [string]$Target = 'C:\sandbox\inspecto-preview\inspecto-la\spaces'
)
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\.jdks\jdk-27-win'; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$root = Join-Path $Work 'spaces'
$wal = @(Get-ChildItem -Path $root -Recurse -Filter '*.wal' -File -ErrorAction SilentlyContinue)
if ($wal.Count -gt 0) { throw ("$($wal.Count) .wal file(s) present: " + ($wal.FullName -join ', ')) }

$lat = [System.Text.Encoding]::Latin1
$toLat = { param($t) $lat.GetString([System.Text.Encoding]::UTF8.GetBytes($t)) }
$pairs = [System.Collections.Generic.List[string[]]]::new()
foreach ($o in @(($Work + '\spaces'), (($Work + '\spaces').Replace('\', '/')), (($Work + '\spaces').Replace('\', '\\')))) { $pairs.Add([string[]]@((& $toLat $o), 'spaces')) }
$needles = @($Work, $Work.Replace('\', '/'), $Work.Replace('\', '\\')) | ForEach-Object { & $toLat $_ }
$findHits = { param($r) foreach ($f in Get-ChildItem -Path $r -Recurse -File) { $s = $lat.GetString([System.IO.File]::ReadAllBytes($f.FullName)); foreach ($n in $needles) { if ($s.Contains($n)) { $f; break } } } }
$rebase = Join-Path $Repo 'inspecto\package-demo-rebase.java'
$pairArgs = $pairs | ForEach-Object { $_[0]; $_[1] }
foreach ($sp in Get-ChildItem $root -Directory) {
    Remove-Item (Join-Path $sp.FullName 'data\events'), (Join-Path $sp.FullName 'data\.duckdb_tmp') -Recurse -Force -ErrorAction SilentlyContinue
    foreach ($f in Get-ChildItem -Path $sp.FullName -Recurse -File | Where-Object { $_.Extension -in '.db', '.duckdb' }) {
        & java --enable-native-access=ALL-UNNAMED -cp (Join-Path $Bundle 'inspecto.jar') $rebase $f.FullName @pairArgs | Out-Null
        if ($LASTEXITCODE -ne 0) { throw ("rebasing " + $f.Name + " failed") }
    }
    foreach ($f in @(& $findHits $sp.FullName)) {
        if ($f.Extension -in '.csv', '.json', '.log', '.toon', '.tsv', '.txt') {
            $s = $lat.GetString([System.IO.File]::ReadAllBytes($f.FullName))
            foreach ($p in $pairs) { $s = $s.Replace($p[0], $p[1]) }
            [System.IO.File]::WriteAllBytes($f.FullName, $lat.GetBytes($s))
        }
    }
    $hits = @(& $findHits $sp.FullName | ForEach-Object { $_.FullName.Substring($sp.FullName.Length + 1) })
    if ($hits.Count -gt 0) { throw ("$($sp.Name) is not relocatable: " + ($hits -join ', ')) }
    Write-Host ("rebased " + $sp.Name) -ForegroundColor Green
}

if (Test-Path $Target) {
    $park = 'C:\sandbox\inspecto-preview\parked\spaces-old-' + (Get-Date -Format 'yyyyMMdd-HHmm')
    New-Item -ItemType Directory $park -Force | Out-Null
    foreach ($sp in Get-ChildItem $Target -Directory | Where-Object { $_.Name -ne '_templates' }) { Move-Item -LiteralPath $sp.FullName -Destination (Join-Path $park $sp.Name) }
    Write-Host "old Spaces parked in $park" -ForegroundColor Yellow
}
foreach ($sp in Get-ChildItem $root -Directory) { Copy-Item $sp.FullName (Join-Path $Target $sp.Name) -Recurse -Force }
Write-Host ("Target Spaces: " + ((Get-ChildItem $Target -Directory | ForEach-Object Name) -join ', ')) -ForegroundColor Green
Remove-Item $Work -Recurse -Force -ErrorAction SilentlyContinue
