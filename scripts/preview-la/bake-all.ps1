# Bake EVERY Link Analysis demo Space onto one demo-auth server, then carry the baked Spaces into a target spaces folder.
# Run from the PowerShell tool (the graceful Ctrl+C stop needs it). Local helper, not product code.
param(
    [string]$Repo = 'C:\sandbox\preview-la',
    [string]$Bundle = 'C:\sandbox\preview-la\inspecto-demo',
    [string]$Target = 'C:\sandbox\inspecto-preview\inspecto-la\spaces'
)
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\.jdks\jdk-27-win'; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
. (Join-Path $Repo 'inspecto\package-demo-bake.ps1')   # Start-BakeServer / Stop-BakeServer

# Six Spaces take longer than the helper's fixed 45 s to checkpoint on a clean stop: same Ctrl+C method, a longer wait.
function Stop-BakeServer {
    param([Parameter(Mandatory)][System.Diagnostics.Process]$Proc)
    if ($Proc.HasExited) { return $true }
    $helper = Join-Path ([System.IO.Path]::GetTempPath()) "la-bake-stop-$($Proc.Id).ps1"
    Set-Content -Path $helper -Value @'
param([uint32]$TargetPid)
Add-Type -Namespace Inspecto -Name K2 -MemberDefinition @"
[DllImport("kernel32.dll")] public static extern bool FreeConsole();
[DllImport("kernel32.dll")] public static extern bool AttachConsole(uint pid);
[DllImport("kernel32.dll")] public static extern bool SetConsoleCtrlHandler(IntPtr h, bool add);
[DllImport("kernel32.dll")] public static extern bool GenerateConsoleCtrlEvent(uint ev, uint grp);
"@
[void][Inspecto.K2]::FreeConsole()
if (-not [Inspecto.K2]::AttachConsole($TargetPid)) { exit 2 }
[void][Inspecto.K2]::SetConsoleCtrlHandler([IntPtr]::Zero, $true)
[void][Inspecto.K2]::GenerateConsoleCtrlEvent(0, 0)
Start-Sleep -Milliseconds 500
'@
    try {
        & pwsh -NoProfile -NonInteractive -File $helper -TargetPid $Proc.Id | Out-Null
        return $Proc.WaitForExit(300000)
    } finally { Remove-Item $helper -ErrorAction SilentlyContinue }
}

$work = Join-Path ([System.IO.Path]::GetTempPath()) ('la-bake-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
$root = Join-Path $work 'spaces'
New-Item -ItemType Directory -Path $root -Force | Out-Null
$proc = $null; $keep = $false
try {
    # 1. scaffold: each seeder lays down its own Space folders (the showcase one also lays la-showcase-off)
    foreach ($s in 'seed-la-showcase', 'seed-la-claims', 'seed-la-mm', 'seed-la-audit') {
        & node (Join-Path $Repo "tools\$s.mjs") --scaffold $root | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "scaffold failed: $s" }
    }
    Remove-Item (Join-Path $root '_templates') -Recurse -Force -ErrorAction SilentlyContinue
    # la-daily-feed has no seeder: a plain Space from its template
    $df = Join-Path $root 'la-daily-feed'; New-Item -ItemType Directory $df | Out-Null
    Copy-Item (Join-Path $Repo 'spaces\_templates\la-daily-feed\config') (Join-Path $df 'config') -Recurse
    Copy-Item (Join-Path $Repo 'spaces\_templates\la-daily-feed\data') (Join-Path $df 'data') -Recurse -ErrorAction SilentlyContinue
    Set-Content (Join-Path $df 'space.toon') "display_name: Link Analysis daily feed`ndescription: Scaffolded from the la-daily-feed Space Template`ncreated_at: $((Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ'))`n"
    Write-Host ("Spaces to bake: " + ((Get-ChildItem $root -Directory | ForEach-Object Name) -join ', ')) -ForegroundColor Cyan

    # 2. boot the demo bundle against that one spaces root
    $l = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0); $l.Start(); $Port = $l.LocalEndpoint.Port; $l.Stop()
    $mods = @(Get-Content (Join-Path $Bundle 'modules.list') | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    $cp = ($mods | ForEach-Object { Join-Path $Bundle $_ }) -join ';'
    $jvm = @('--enable-native-access=ALL-UNNAMED', '-Dcontrol.bind=127.0.0.1', '-Dauth.mode=demo', '-Dobjects.backend=db', '-Devents.backend=parquet', '-Djobs.backend=duckdb', '-Dla.embed.enabled=true', "-Dcontrol.port=$Port", '-Dspaces.root=spaces')
    $ext = Join-Path $Bundle 'duckdb-extensions\windows_amd64'; if (Test-Path $ext) { $jvm += "-Dduckdb.extension.dir=$ext" }
    $java = Join-Path $Bundle 'runtime\bin\java.exe'
    $log = Join-Path $work 'server.log'
    $proc = Start-BakeServer -Java $java -JvmArgs ($jvm + @('-cp', $cp, 'com.gamma.control.ControlApi')) -WorkDir $work -Log $log
    $healthy = $false
    foreach ($i in 1..180) {
        if ($proc.HasExited) { break }
        try { if ((Invoke-WebRequest -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 2 -UseBasicParsing -ErrorAction Stop).StatusCode -eq 200) { $healthy = $true; break } } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $healthy) { foreach ($f in @($log, "$log.err")) { if (Test-Path $f) { Get-Content $f -Tail 40 | Write-Host } }; throw "the demo bundle never answered /health on :$Port" }

    # 3. seed each Space as its Demo Users
    $managers = @{ 'la-showcase' = 'fm.manager'; 'la-claims' = 'siu.manager'; 'la-mobile-money' = 'mm.manager'; 'la-audit' = 'audit.manager' }
    $here = Split-Path -Parent $MyInvocation.MyCommand.Path
    foreach ($pair in @(@('seed-la-showcase', 'la-showcase'), @('seed-la-claims', 'la-claims'), @('seed-la-mm', 'la-mobile-money'), @('seed-la-audit', 'la-audit'))) {
        # The data-build jobs are cron (*/10), so the first build would land minutes after boot; run them once now, as the Space's manager.
        $jobs = @(Get-ChildItem (Join-Path $Repo ("spaces\_templates\" + $pair[1] + "\config\jobs")) -Filter '*_job.toon' | ForEach-Object { $_.Name -replace '_job\.toon$', '' } | Where-Object { $_ -notin 'la_detect', 'la_index_build' })
        if ($jobs.Count) { & node (Join-Path $here 'trigger-jobs.mjs') "http://127.0.0.1:$Port" $pair[1] $managers[$pair[1]] @jobs | Out-Host }
        Write-Host ("seeding " + $pair[1]) -ForegroundColor Cyan
        # The seeds are idempotent: a first attempt can race the job that builds the Dataset, so give it up to three goes.
        $seeded = $false
        foreach ($attempt in 1..3) {
            & node (Join-Path $Repo ("tools\" + $pair[0] + '.mjs')) --base "http://127.0.0.1:$Port" --space $pair[1] --apply | Out-Host
            if ($LASTEXITCODE -eq 0) { $seeded = $true; break }
            Write-Host ("seed attempt $attempt for " + $pair[1] + " incomplete; waiting 30 s and re-running") -ForegroundColor Yellow
            Start-Sleep -Seconds 30
        }
        if (-not $seeded) { $keep = $true; throw ("seed failed: " + $pair[1] + " (server log: $log)") }
    }

    # 4. stop gracefully so DuckDB checkpoints
    if (-not (Stop-BakeServer -Proc $proc)) { $keep = $true; Copy-Item $log "$env:TEMP\la-bake-lastlog.txt" -Force; & jcmd $proc.Id Thread.print 2>&1 | Out-File "$env:TEMP\la-bake-threads.txt"; & taskkill /F /T /PID $proc.Id 2>&1 | Out-Null; throw 'the server did not stop gracefully; the DuckDB files may not be checkpointed' }
    $proc = $null
    $wal = @(Get-ChildItem -Path $root -Recurse -Filter '*.wal' -File -ErrorAction SilentlyContinue)
    if ($wal.Count -gt 0) { throw ("$($wal.Count) .wal file(s) left after a clean stop: " + ($wal.FullName -join ', ')) }

    # 5. relocatability: rebase the build-time temp root to the relative `spaces` (same method as package-demo-bake.ps1)
    $lat = [System.Text.Encoding]::Latin1
    $toLat = { param($t) $lat.GetString([System.Text.Encoding]::UTF8.GetBytes($t)) }
    $pairs = [System.Collections.Generic.List[string[]]]::new()
    foreach ($o in @(($work + '\spaces'), (($work + '\spaces').Replace('\', '/')), (($work + '\spaces').Replace('\', '\\')))) { $pairs.Add([string[]]@((& $toLat $o), 'spaces')) }
    $needles = @($work, $work.Replace('\', '/'), $work.Replace('\', '\\')) | ForEach-Object { & $toLat $_ }
    $findHits = { param($r) foreach ($f in Get-ChildItem -Path $r -Recurse -File) { $s = $lat.GetString([System.IO.File]::ReadAllBytes($f.FullName)); foreach ($n in $needles) { if ($s.Contains($n)) { $f; break } } } }
    $rebase = Join-Path $Repo 'inspecto\package-demo-rebase.java'
    $pairArgs = $pairs | ForEach-Object { $_[0]; $_[1] }
    foreach ($sp in Get-ChildItem $root -Directory) {
        Remove-Item (Join-Path $sp.FullName 'data\events'), (Join-Path $sp.FullName 'data\.duckdb_tmp') -Recurse -Force -ErrorAction SilentlyContinue
        foreach ($f in Get-ChildItem -Path $sp.FullName -Recurse -File | Where-Object { $_.Extension -in '.db', '.duckdb' }) {
            & (Join-Path $Bundle 'runtime\bin\java.exe') --enable-native-access=ALL-UNNAMED -cp (Join-Path $Bundle 'inspecto.jar') $rebase $f.FullName @pairArgs | Out-Null
            if ($LASTEXITCODE -ne 0) { $keep = $true; throw ("rebasing " + $f.Name + " failed") }
        }
        foreach ($f in @(& $findHits $sp.FullName)) {
            if ($f.Extension -in '.csv', '.json', '.log', '.toon', '.tsv', '.txt') {
                $s = $lat.GetString([System.IO.File]::ReadAllBytes($f.FullName))
                foreach ($p in $pairs) { $s = $s.Replace($p[0], $p[1]) }
                [System.IO.File]::WriteAllBytes($f.FullName, $lat.GetBytes($s))
            }
        }
        $hits = @(& $findHits $sp.FullName | ForEach-Object { $_.FullName.Substring($sp.FullName.Length + 1) })
        if ($hits.Count -gt 0) { $keep = $true; throw ("$($sp.Name) is not relocatable: " + ($hits -join ', ')) }
    }

    # 6. carry over: park the old Spaces (nothing deleted), then copy the baked ones in
    if (Test-Path $Target) {
        $park = 'C:\sandbox\inspecto-preview\parked\spaces-old-' + (Get-Date -Format 'yyyyMMdd-HHmm')
        New-Item -ItemType Directory $park -Force | Out-Null
        foreach ($sp in Get-ChildItem $Target -Directory | Where-Object { $_.Name -ne '_templates' }) { Move-Item -LiteralPath $sp.FullName -Destination (Join-Path $park $sp.Name) }
        Write-Host "old Spaces parked in $park" -ForegroundColor Yellow
    }
    foreach ($sp in Get-ChildItem $root -Directory) { Copy-Item $sp.FullName (Join-Path $Target $sp.Name) -Recurse -Force }
    Write-Host ("Target Spaces: " + ((Get-ChildItem $Target -Directory | ForEach-Object Name) -join ', ')) -ForegroundColor Green
}
finally {
    if ($proc -and -not $proc.HasExited) { & taskkill /F /T /PID $proc.Id 2>&1 | Out-Null; $proc.WaitForExit(5000) }
    if (-not $keep) { Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue } else { Write-Host "temp root kept: $work" }
}
