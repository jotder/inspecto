# package-demo-bake.ps1 - bake the `la-showcase` Space INTO the Link Analysis demo bundle.
#
# Dot-sourced by package.ps1, and ONLY inside `if ($DemoAuth -and $Ui -eq 'la-app' ...)` (tools/check-bundle-spaces.mjs fails
# the build if the call moves). OPERATOR DECISION 2026-10-12: a deliberate, narrow reversal of "BUNDLES SHIP NO SPACES"
# (2026-09-25) - the demo-auth la-app bundle ships exactly ONE pre-seeded Space so a presenter unzips, runs serve-demo,
# signs in and has the whole demo, with no node and no seed step. Every other bundle still ships spaces/_templates only.
#
# HOW: scaffold la-showcase into a TEMP spaces root (tools/seed-la-showcase.mjs --scaffold), boot the STAGED bundle on a loopback
# port against it with the serve-demo flags, run the seed script (--apply) over HTTP as the Demo Users, stop the server
# gracefully (Ctrl+C -> the JVM shutdown hook closes the Space: DuckDB checkpoints, the audit log flushes), then copy the
# Space folder into the bundle's spaces\. Any failure THROWS: a half-seeded demo must never ship silently.
#
# RELOCATABILITY: the baked folder is scanned for the temp root it was built under; any hit THROWS (the bundle gets unzipped
# anywhere). Persisted state that is regenerable is dropped before the copy (see $regenerable).

# ROOT CAUSE of "the packaging pwsh dies right after 'seeding la-showcase'": Start-Process (-WindowStyle Hidden, redirected output) does NOT
# give the child its own console - it inherits the packaging pwsh's. The stop helper then attached to THAT console and sent
# GenerateConsoleCtrlEvent(0,0) (Ctrl+C to every process on it), killing the packaging script itself. So the server is now created with
# CREATE_NEW_CONSOLE (own, hidden console): a Ctrl+C there reaches only the server, never the parent.
function Start-BakeServer {
    param([Parameter(Mandatory)][string]$Java, [Parameter(Mandatory)][string[]]$JvmArgs, [Parameter(Mandatory)][string]$WorkDir, [Parameter(Mandatory)][string]$Log)
    if (-not ('Inspecto.NC' -as [type])) {
        Add-Type -Namespace Inspecto -Name NC -MemberDefinition @"
[StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] public struct SI { public int cb; public string r; public string d; public string t; public int x, y, xs, ys, xc, yc, fa, fl; public short sw, cr; public IntPtr rr, si, so, se; }
[StructLayout(LayoutKind.Sequential)] public struct PI { public IntPtr hp, ht; public uint pid, tid; }
[DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] public static extern bool CreateProcessW(string app, System.Text.StringBuilder cmd, IntPtr pa, IntPtr ta, bool inh, uint flags, IntPtr env, string cwd, ref SI si, out PI pi);
[DllImport("kernel32.dll")] public static extern bool SetHandleInformation(IntPtr h, uint mask, uint flags);
[DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr h);
"@
    }
    $Java = (Get-Command $Java -ErrorAction Stop).Source   # absolute: CreateProcess resolves a bare/relative name against the NEW cwd
    $q = { param($a) '"' + $a + '"' }
    # java is created DIRECTLY (not via cmd) so the returned pid is the JVM's; stdout/stderr go to inheritable file handles.
    $so = [System.IO.File]::Open($Log, 'Create', 'Write', 'ReadWrite'); $se = [System.IO.File]::Open("$Log.err", 'Create', 'Write', 'ReadWrite')
    try {
        foreach ($f in @($so, $se)) { [void][Inspecto.NC]::SetHandleInformation($f.SafeFileHandle.DangerousGetHandle(), 1, 1) }
        $si = New-Object Inspecto.NC+SI; $si.cb = [Runtime.InteropServices.Marshal]::SizeOf($si); $si.fl = 0x101; $si.sw = 0   # STARTF_USESHOWWINDOW|USESTDHANDLES, SW_HIDE
        $si.so = $so.SafeFileHandle.DangerousGetHandle(); $si.se = $se.SafeFileHandle.DangerousGetHandle(); $si.si = [IntPtr]::Zero
        $pi = New-Object Inspecto.NC+PI
        $cmd = [System.Text.StringBuilder]::new((@($Java) + $JvmArgs | ForEach-Object { & $q $_ }) -join ' ')
        $ok = [Inspecto.NC]::CreateProcessW($Java, $cmd, [IntPtr]::Zero, [IntPtr]::Zero, $true, 0x10, [IntPtr]::Zero, $WorkDir, [ref]$si, [ref]$pi)   # 0x10 = CREATE_NEW_CONSOLE
        $err = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
    } finally { $so.Dispose(); $se.Dispose() }
    if (-not $ok) { throw "demo bake: CreateProcess failed for '$Java' (Win32 error $err)" }
    [void][Inspecto.NC]::CloseHandle($pi.ht); [void][Inspecto.NC]::CloseHandle($pi.hp)
    return [System.Diagnostics.Process]::GetProcessById([int]$pi.pid)
}

function Stop-BakeServer {
    param([Parameter(Mandatory)][System.Diagnostics.Process]$Proc)
    if ($Proc.HasExited) { return $true }
    # Ctrl+C from a helper process that attaches to the server's OWN console (see Start-BakeServer; Process.Kill is
    # TerminateProcess: no shutdown hook). The event reaches only that private console's processes.
    $helper = Join-Path ([System.IO.Path]::GetTempPath()) "inspecto-bake-stop-$($Proc.Id).ps1"
    Set-Content -Path $helper -Value @'
param([uint32]$TargetPid)
Add-Type -Namespace Inspecto -Name K -MemberDefinition @"
[DllImport("kernel32.dll")] public static extern bool FreeConsole();
[DllImport("kernel32.dll")] public static extern bool AttachConsole(uint pid);
[DllImport("kernel32.dll")] public static extern bool SetConsoleCtrlHandler(IntPtr h, bool add);
[DllImport("kernel32.dll")] public static extern bool GenerateConsoleCtrlEvent(uint ev, uint grp);
"@
[void][Inspecto.K]::FreeConsole()
if (-not [Inspecto.K]::AttachConsole($TargetPid)) { exit 2 }
[void][Inspecto.K]::SetConsoleCtrlHandler([IntPtr]::Zero, $true)
[void][Inspecto.K]::GenerateConsoleCtrlEvent(0, 0)
Start-Sleep -Milliseconds 500
'@
    try {
        & pwsh -NoProfile -NonInteractive -File $helper -TargetPid $Proc.Id | Out-Null
        return $Proc.WaitForExit(45000)
    } finally { Remove-Item $helper -ErrorAction SilentlyContinue }
}

function Invoke-DemoSpaceBake {
    param(
        [Parameter(Mandatory)][string]$RepoRoot,      # holds tools/seed-la-showcase.mjs
        [Parameter(Mandatory)][string]$BundleDir,     # the staged bundle (spaces/ is written here)
        [Parameter(Mandatory)][string]$Java,          # the bundle's java (embedded runtime when present)
        [Parameter(Mandatory)][string[]]$ModuleJars,  # modules.list entries, classpath order
        [Parameter(Mandatory)][string[]]$DemoFlags,   # the serve-demo JVM flags
        [int]$Port = 0                                # 0 = any free loopback port
    )
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw "demo bake: node is required (tools/seed-la-showcase.mjs) - not found on PATH. Pass -NoBakeDemo to package without the pre-seeded Space." }
    $seed = Join-Path $RepoRoot 'tools/seed-la-showcase.mjs'
    if (-not (Test-Path $seed)) { throw "demo bake: $seed not found" }

    $work = Join-Path ([System.IO.Path]::GetTempPath()) ("inspecto-bake-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
    $spacesRoot = Join-Path $work 'spaces'
    $cwd = $work   # the server runs here with a RELATIVE -Dspaces.root=spaces, exactly as serve-demo does from the bundle dir: recorded paths stay relative
    New-Item -ItemType Directory -Path $spacesRoot -Force | Out-Null
    $proc = $null
    $keep = $false   # set when the relocation scan fails: the temp root is then left for diagnosis
    try {
        & node $seed --scaffold $spacesRoot | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "demo bake: seed --scaffold failed (exit $LASTEXITCODE)" }
        # Only la-showcase is baked; the -off variant and the gallery (the bundle already carries its own) must not boot here.
        Remove-Item (Join-Path $spacesRoot 'la-showcase-off'), (Join-Path $spacesRoot '_templates') -Recurse -Force -ErrorAction SilentlyContinue

        if ($Port -le 0) {
            $l = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0); $l.Start(); $Port = $l.LocalEndpoint.Port; $l.Stop()
        }
        $sep = if ($IsWindows -or $env:OS -eq 'Windows_NT') { ';' } else { ':' }
        $plat = if ($sep -eq ';') { 'windows_amd64' } else { 'linux_amd64' }
        $cp = ($ModuleJars | ForEach-Object { Join-Path $BundleDir $_ }) -join $sep
        $jvm = @($DemoFlags) + @("-Dcontrol.port=$Port", "-Dspaces.root=spaces")
        $ext = Join-Path $BundleDir "duckdb-extensions/$plat"
        if (Test-Path $ext) { $jvm += "-Dduckdb.extension.dir=$ext" }
        $log = Join-Path $work 'server.log'
        Write-Host "Demo bake: booting the staged bundle on 127.0.0.1:$Port with a scaffolded la-showcase ..." -ForegroundColor Cyan
        $proc = Start-BakeServer -Java $Java -JvmArgs ($jvm + @('-cp', $cp, 'com.gamma.control.ControlApi')) -WorkDir $cwd -Log $log
        $healthy = $false
        foreach ($i in 1..120) {
            if ($proc.HasExited) { break }
            try { if ((Invoke-WebRequest -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 2 -UseBasicParsing -ErrorAction Stop).StatusCode -eq 200) { $healthy = $true; break } }
            catch { Start-Sleep -Milliseconds 500 }
        }
        if (-not $healthy) { foreach ($f in @($log, "$log.err")) { if (Test-Path $f) { Get-Content $f -Tail 40 | Write-Host } }; throw "demo bake: the staged bundle never answered /health on :$Port" }

        # The seed script's extra stages (pending four-eyes request, fired Alert, Draft) run by default; its --no-pending /
        # --no-alert / --no-draft flags opt out, so the bake passes nothing extra.
        $extra = @()
        Write-Host "Demo bake: seeding la-showcase (node tools/seed-la-showcase.mjs --apply $($extra -join ' ')) ..." -ForegroundColor Cyan
        & node $seed --base "http://127.0.0.1:$Port" --space la-showcase --apply @extra | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "demo bake: the seed script failed (exit $LASTEXITCODE) - refusing to ship a half-seeded demo. Server log: $log" }

        if (-not (Stop-BakeServer -Proc $proc)) {
            & taskkill /F /T /PID $proc.Id 2>&1 | Out-Null; $proc.WaitForExit(5000)
            throw "demo bake: the server did not stop gracefully (Ctrl+C) within 45 s - refusing to copy a Space whose DuckDB files may not be checkpointed."
        }
        $proc = $null
        $wal = @(Get-ChildItem -Path $spacesRoot -Recurse -Filter '*.wal' -File -ErrorAction SilentlyContinue)
        if ($wal.Count -gt 0) { throw "demo bake: $($wal.Count) .wal file(s) left after a clean stop ($($wal.FullName -join ', ')) - the checkpoint did not run." }

        # RELOCATABILITY. The engine records ABSOLUTE paths in its ingest ledgers (status/lineage CSVs, consignment manifests as JSON, and
        # the DuckDB inspecto-status.db / inspecto-consignment-outputs.db) even when -Dspaces.root is relative. Rebase the build-time
        # `<temp>\spaces` prefix to the RELATIVE `spaces`: serve-demo starts from the bundle directory with SPACES_ROOT=spaces, so the
        # recorded paths then resolve wherever the bundle is unzipped. Bytes are handled as Latin-1 (lossless 1:1) so one Replace serves
        # CSV and JSON; DuckDB files go through package-demo-rebase.java (an UPDATE on every VARCHAR column + CHECKPOINT).
        $lat = [System.Text.Encoding]::Latin1
        $toLat = { param($t) $lat.GetString([System.Text.Encoding]::UTF8.GetBytes($t)) }
        # old -> new, in the three spellings the ledgers use: backslash, forward slash, JSON-escaped backslash.
        $oldBs = & $toLat ($work + '\spaces')
        $oldFs = & $toLat (($work + '\spaces').Replace('\', '/'))
        $oldEs = & $toLat (($work + '\spaces').Replace('\', '\\'))
        $pairs = [System.Collections.Generic.List[string[]]]::new()
        foreach ($o in @($oldBs, $oldFs, $oldEs)) { $pairs.Add([string[]]@($o, 'spaces')) }
        $needles = @($work, $work.Replace('\', '/'), $work.Replace('\', '\\')) | ForEach-Object { & $toLat $_ }
        $findHits = { param($root) foreach ($f in Get-ChildItem -Path $root -Recurse -File) { $s = $lat.GetString([System.IO.File]::ReadAllBytes($f.FullName)); foreach ($n in $needles) { if ($s.Contains($n)) { $f; break } } } }
        $baked = Join-Path $spacesRoot 'la-showcase'
        $rebaseJava = Join-Path $PSScriptRoot 'package-demo-rebase.java'
        # NOT shipped: the event log. It is the server's own log (every line names the build-time absolute path, and the build machine's
        # user), not Space content; it restarts empty at first boot, and the file-gap Events are re-derived on the first poll.
        Remove-Item (Join-Path $baked 'data/events'), (Join-Path $baked 'data/.duckdb_tmp') -Recurse -Force -ErrorAction SilentlyContinue
        # EVERY DuckDB file goes through the rebase (their strings are compressed, so a byte scan cannot decide which ones need it).
        $pairArgs = $pairs | ForEach-Object { $_[0]; $_[1] }
        foreach ($f in Get-ChildItem -Path $baked -Recurse -File | Where-Object { $_.Extension -in '.db', '.duckdb' }) {
            & java --enable-native-access=ALL-UNNAMED -cp (Join-Path $BundleDir 'inspecto.jar') $rebaseJava $f.FullName @pairArgs | Out-Host
            if ($LASTEXITCODE -ne 0) { $keep = $true; throw "demo bake: rebasing $($f.Name) failed (exit $LASTEXITCODE); temp root kept: $work" }
        }
        foreach ($f in @(& $findHits $baked)) {
            if ($f.Extension -in '.csv', '.json', '.log', '.toon', '.tsv', '.txt') {
                $s = $lat.GetString([System.IO.File]::ReadAllBytes($f.FullName))
                foreach ($p in $pairs) { $s = $s.Replace($p[0], $p[1]) }
                [System.IO.File]::WriteAllBytes($f.FullName, $lat.GetBytes($s))
            }
        }
        $dest = Join-Path $BundleDir 'spaces/la-showcase'
        Remove-Item $dest -Recurse -Force -ErrorAction SilentlyContinue
        Copy-Item $baked -Destination $dest -Recurse -Force
        # PROOF: nothing in the shipped Space (text or binary) still names the build-time root.
        $hits = @(& $findHits $dest | ForEach-Object { $_.FullName.Substring($dest.Length + 1) })
        if ($hits.Count -gt 0) { $keep = $true; throw "demo bake: the baked Space is not relocatable - $($hits.Count) file(s) still embed the build-time path ${work} (kept for diagnosis):`n  $($hits -join "`n  ")" }
        $n = @(Get-ChildItem -Path $dest -Recurse -File).Count
        Write-Host "Demo bake: spaces\la-showcase baked ($n files, no build-time path embedded)" -ForegroundColor Green
    } finally {
        if ($proc -and -not $proc.HasExited) { & taskkill /F /T /PID $proc.Id 2>&1 | Out-Null; $proc.WaitForExit(5000) }
        if (-not $keep) { Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue }
    }
}
