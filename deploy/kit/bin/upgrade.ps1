# upgrade.ps1 - upgrade this Inspecto bundle in place from a new bundle zip (DEPLOY-EXPOSURE-KIT-1).
#   From an ELEVATED PowerShell:
#     .\bin\upgrade.ps1 <inspecto-deploy-windows_amd64.zip> [-BackupDir <backup_dir>] [-Name Inspecto]
#         [-RequireSignature] [-AllowEditionChange] [-Direct] [-ProxyConfig <file>] [-NoService]
#
# In order, stopping at the first failure:
#   1. bin\verify-bundle.ps1 checks the zip against its .sha256 (and .asc). Nothing is touched before this passes.
#   2. -BackupDir: the newest backup there must verify against its manifest (bin\backup-offsite.ps1 -VerifyOnly).
#      Take one with the backup Job first; without -BackupDir this is a WARN.
#   3. The zip is unpacked beside the bundle and checked: a bundle shape, and the same edition (-AllowEditionChange).
#   4. The Scheduled Task bin\install-service.ps1 registered is stopped, then every process running from this bundle
#      (its path under the bundle folder, or its command line naming a file in it) and any java still listening on the
#      port (-NoService: you stop and start the server yourself; the script only waits for them to be gone).
#   5. The previous bundle is kept beside this one as <bundle>.pre-upgrade-<time>\: a copy of the settings
#      (spaces.ref, conf\, and each Space's config\ and top-level files from the spaces root the server uses, following
#      <id>.ref files) and every entry the new bundle replaces. Everything the new bundle does not ship stays in place:
#      Space folders, logs\, spaces.ref, apps\, files of your own. In the bundle's spaces\_templates only the templates
#      the new bundle ships are replaced, retired shipped ones are moved aside, and the templates installed Apps put
#      there (marked .installed-by) stay.
#   6. The new bundle's bin\preflight.ps1 runs (-Direct and -ProxyConfig are passed to it).
#   7. The task starts; /health and /ready must answer within -HealthTimeout seconds (default 180), from a process of
#      this bundle started after the start was issued.
# A failure at 6 or 7 stops whatever runs from the bundle, puts the previous bundle back (and restarts the task); the
# new one is left unpacked.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Zip,
    [string]$BackupDir = "",
    [string]$Name = "Inspecto",
    [switch]$RequireSignature,
    [switch]$AllowEditionChange,
    [switch]$Direct,
    [string]$ProxyConfig = "",
    [switch]$NoService,
    [int]$HealthTimeout = 180
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$parent = Split-Path -Parent $root
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$Zip = (Resolve-Path -LiteralPath $Zip).Path
# Out of the bundle folder: Windows cannot rename a folder that is a working directory.
Set-Location $parent
# preflight.ps1 runs in a child of this same PowerShell, so its Set-Location cannot pull this script back into the bundle.
$psExe = (Get-Process -Id $PID).Path

function Step([string]$m) { Write-Output "[upgrade] == $m" }
function Die([string]$m) { Write-Output "[upgrade] FAIL  $m"; exit 1 }
function Edition([string]$dir) {
    $f = Join-Path $dir "lib\edition.properties"
    if (-not (Test-Path $f)) { return "" }
    foreach ($l in Get-Content $f) { if ($l -match '^edition=(.*)$') { return $Matches[1].Trim() } }
    return ""
}
function Preflight([string[]]$a) { return (& $psExe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root "bin\preflight.ps1") @a | Select-Object -Last 1) }
# Read from the PREVIOUS bundle before anything moves: the settings do not change with the upgrade.
$base = Preflight @("-Endpoint")
$port = [int]($base -replace '^.*:', '')
$spacesDir = Preflight @("-SpacesRoot")

# "Runs from this bundle": its executable is under "<root>\", or its command line names a path under "<root>\" or
# "<root>/" - a path boundary, so C:\apps\inspecto-deploy-staging\... is not C:\apps\inspecto-deploy. Plus any java
# listening on the port: serve.bat started with a java from PATH runs `-cp lib\...`, which names no absolute path.
# This script and the shell that started it are never counted.
function Get-BundleProcesses {
    $mine = @($PID)
    $me = Get-CimInstance Win32_Process -Filter "ProcessId=$PID" -ErrorAction SilentlyContinue
    if ($me) { $mine += $me.ParentProcessId }
    $prefixes = @(($root.TrimEnd('\') + '\'), ($root.TrimEnd('\').Replace('\', '/') + '/'))
    $listening = @()
    try { $listening = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction Stop | ForEach-Object { [int]$_.OwningProcess }) } catch { }
    return @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
        $p = $_
        if ($mine -contains [int]$p.ProcessId) { return $false }
        if ($p.ExecutablePath -and $p.ExecutablePath.StartsWith($prefixes[0], [StringComparison]::OrdinalIgnoreCase)) { return $true }
        foreach ($x in $prefixes) { if ($p.CommandLine -and $p.CommandLine.IndexOf($x, [StringComparison]::OrdinalIgnoreCase) -ge 0) { return $true } }
        return ($listening -contains [int]$p.ProcessId) -and ($p.Name -match '^javaw?\.exe$')
    })
}
function PortFree {
    $l = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
    return (@($l | Where-Object { $_.Port -eq $port }).Count -eq 0)
}
# Waits up to 30 s for no bundle process and a free port; with -Kill, stops each bundle process it finds.
function Clear-Bundle([switch]$Kill) {
    for ($i = 0; $i -lt 30; $i++) {
        $running = Get-BundleProcesses
        if ($running.Count -eq 0 -and (PortFree)) { return $true }
        if ($Kill) { $running | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue } }
        Start-Sleep -Seconds 1
    }
    $left = (Get-BundleProcesses | ForEach-Object { "PID $($_.ProcessId) ($($_.Name))" }) -join ', '
    Write-Host "[upgrade] still running from $root or on port ${port}: $left"
    return $false
}
# /health and /ready answer, AND the process listening on the port belongs to this bundle (itself, or the cmd.exe that
# runs bin\serve.bat) and started at or after $since - so a leftover process, or the bundle just moved aside, cannot pass.
function Healthy([datetime]$since) {
    $deadline = (Get-Date).AddSeconds($HealthTimeout)
    while ((Get-Date) -lt $deadline) {
        & curl.exe -fsSk -o NUL "$base/health" 2>$null
        $h = $LASTEXITCODE
        & curl.exe -fsSk -o NUL "$base/ready" 2>$null
        if ($h -eq 0 -and $LASTEXITCODE -eq 0) {
            $owner = $null
            try { $owner = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction Stop)[0].OwningProcess } catch { }
            $proc = if ($owner) { Get-CimInstance Win32_Process -Filter "ProcessId=$owner" -ErrorAction SilentlyContinue }
            $ours = @(Get-BundleProcesses | ForEach-Object { [int]$_.ProcessId })
            $parentOurs = $proc -and ($ours -contains [int]$proc.ParentProcessId)
            if ($proc -and (($ours -contains [int]$proc.ProcessId) -or $parentOurs) -and $proc.CreationDate -ge $since.AddSeconds(-2)) {
                Write-Host "[upgrade] PASS  $base/health and /ready answer from PID $($proc.ProcessId), started from $root"
                return $true
            }
            Write-Host "[upgrade] WARN  $base answers, but not from a process of this bundle started after $since"
        }
        Start-Sleep -Seconds 3
    }
    return $false
}
function StopServer {
    if ($NoService) {
        Write-Output "[upgrade] -NoService: the server must already be stopped"
    } else {
        if (-not (Get-ScheduledTask -TaskName $Name -ErrorAction SilentlyContinue)) { Die "no Scheduled Task '$Name' (bin\install-service.ps1 registers it; or pass -Name <task> or -NoService)" }
        Stop-ScheduledTask -TaskName $Name
    }
    # Stopping the task ends cmd.exe; a java.exe it started can outlive it, hold the bundle's files open (Windows then
    # refuses to move them) and keep the port.
    if (-not (Clear-Bundle -Kill:(-not $NoService))) { Die "the server is still running; nothing was swapped" }
}
$script:swapped = New-Object System.Collections.ArrayList
function Rollback([string]$why) {
    Write-Output "[upgrade] FAIL  $why - putting the previous bundle back"
    if (-not $NoService) { Stop-ScheduledTask -TaskName $Name -ErrorAction SilentlyContinue }
    # Whatever runs from the bundle now runs the NEW bundle's files, which are about to move: stop it, whoever started it.
    $clear = Clear-Bundle -Kill
    $failed = @()
    for ($i = $script:swapped.Count - 1; $i -ge 0; $i--) {
        $e = $script:swapped[$i]
        $target = Join-Path $new $e
        try {
            New-Item -ItemType Directory -Force (Split-Path -Parent $target) | Out-Null
            if (Test-Path (Join-Path $root $e)) { Move-Item (Join-Path $root $e) $target }
            if (Test-Path (Join-Path $pre "product\$e")) { Move-Item (Join-Path $pre "product\$e") (Join-Path $root $e) }
        } catch { $failed += "$e ($($_.Exception.Message))" }
    }
    if ($failed.Count -gt 0) {
        Write-Output "[upgrade] FAIL  could not put back: $($failed -join '; '). The previous files are in $pre\product$(if (-not $clear) { '; a process of the bundle is still running' })"
        exit 1
    }
    if (-not $NoService) {
        $since = Get-Date
        Start-ScheduledTask -TaskName $Name
        if (Healthy $since) { Write-Output "[upgrade] the previous bundle is running again" }
        else { Write-Output "[upgrade] FAIL  the previous bundle did not come back either: check $root\logs\service.log" }
    }
    Write-Output "[upgrade] rolled back; the new bundle is left unpacked in $stage, the settings copy in $pre"
    exit 1
}
# Swap <entry>: the old entry moves into $pre\product and the new one (if the new bundle has it) into the bundle.
function Swap([string]$e) {
    $dest = Join-Path $pre "product\$e"
    New-Item -ItemType Directory -Force (Split-Path -Parent $dest) | Out-Null
    try {
        if (Test-Path (Join-Path $root $e)) { Move-Item (Join-Path $root $e) $dest }
        [void]$script:swapped.Add($e)
        if (Test-Path (Join-Path $new $e)) { Move-Item (Join-Path $new $e) (Join-Path $root $e) }
    } catch { Rollback "could not swap $e ($($_.Exception.Message))" }
}
# The bundle's spaces\_templates is merged, not replaced: each template the new bundle ships replaces its namesake; a
# template only the old bundle shipped is moved aside; a template an App installed at run time (InstalledApps.MARKER,
# .installed-by) is the install's and stays. Space folders beside _templates are never touched.
function Swap-Templates {
    $tpl = Join-Path $root "spaces\_templates"
    New-Item -ItemType Directory -Force $tpl | Out-Null
    $shipped = @()
    if (Test-Path (Join-Path $new "spaces\_templates")) { $shipped = @(Get-ChildItem -LiteralPath (Join-Path $new "spaces\_templates") -Force | ForEach-Object { $_.Name }) }
    foreach ($t in @(Get-ChildItem -LiteralPath $tpl -Force | ForEach-Object { $_.Name })) {
        if ($shipped -contains $t) { continue }
        if (-not (Test-Path (Join-Path $tpl "$t\.installed-by"))) { Swap "spaces\_templates\$t" }
    }
    foreach ($t in $shipped) { Swap "spaces\_templates\$t" }
}

Step "1/7 verify the new bundle"
$verifyArgs = @($Zip)
if ($RequireSignature) { $verifyArgs += "-RequireSignature" }
& (Join-Path $root "bin\verify-bundle.ps1") @verifyArgs
if ($LASTEXITCODE -ne 0) { Die "the bundle did not verify; nothing was changed" }

Step "2/7 check the newest backup"
if ($BackupDir) {
    & (Join-Path $root "bin\backup-offsite.ps1") -From $BackupDir -VerifyOnly
    if ($LASTEXITCODE -ne 0) { Die "no verified backup in $BackupDir; nothing was changed" }
} else {
    Write-Output "[upgrade] WARN  no -BackupDir: the Space data is not checked to have a verified backup"
}

Step "3/7 unpack and check the new bundle"
$stage = Join-Path $parent ".inspecto-upgrade-$stamp"
Expand-Archive -LiteralPath $Zip -DestinationPath $stage
$props = Get-ChildItem -LiteralPath $stage -Recurse -Depth 2 -Filter "edition.properties" | Where-Object { $_.Directory.Name -eq "lib" } | Select-Object -First 1
if (-not $props) { Die "the zip holds no bundle (no lib\edition.properties); left unpacked in $stage" }
$new = $props.Directory.Parent.FullName
foreach ($f in "bin\serve.bat", "bin\preflight.ps1", "lib\modules.list") {
    if (-not (Test-Path (Join-Path $new $f))) { Die "the new bundle has no $f; left unpacked in $stage" }
}
$oldEd = Edition $root
$newEd = Edition $new
if ($oldEd -ne $newEd -and -not $AllowEditionChange) { Die "the new bundle is $newEd, this one is $oldEd; pass -AllowEditionChange if that is intended. Left unpacked in $stage" }
Write-Output "[upgrade] new bundle: $newEd, unpacked in $stage"

Step "4/7 stop the server"
StopServer

Step "5/7 keep the previous bundle and swap in the new one"
$pre = "$root.pre-upgrade-$stamp"
$settings = Join-Path $pre "settings"
New-Item -ItemType Directory -Force (Join-Path $settings "spaces"), (Join-Path $pre "product") | Out-Null
# The settings, copied (never moved): the bundle's own files, then per Space its config\ and top-level files from the
# spaces root the server uses; an <id>.ref Space is followed to the folder it names. Space data is the backup Job's.
try {
    foreach ($f in "spaces.ref", "conf") {
        if (Test-Path (Join-Path $root $f)) { Copy-Item (Join-Path $root $f) $settings -Recurse }
    }
    if ($spacesDir -and (Test-Path -LiteralPath $spacesDir)) {
        foreach ($s in Get-ChildItem -LiteralPath $spacesDir | Where-Object { $_.Name -ne "_templates" }) {
            $n = $s.Name
            $target = $s.FullName
            if (-not $s.PSIsContainer) {
                Copy-Item -LiteralPath $s.FullName (Join-Path $settings "spaces")
                if ($n -notlike "*.ref") { continue }
                $n = $n.Substring(0, $n.Length - 4)
                $target = (Get-Content -Raw -LiteralPath $s.FullName).Trim()
            }
            if (-not (Test-Path -LiteralPath $target -PathType Container)) { continue }
            $dest = Join-Path $settings "spaces\$n"
            New-Item -ItemType Directory -Force $dest | Out-Null
            if (Test-Path (Join-Path $target "config")) { Copy-Item (Join-Path $target "config") $dest -Recurse }
            Get-ChildItem -LiteralPath $target -File | ForEach-Object { Copy-Item -LiteralPath $_.FullName $dest }
        }
    }
} catch { Die "copying the settings to $pre failed ($($_.Exception.Message)); nothing was swapped - start the server again" }
foreach ($item in Get-ChildItem -LiteralPath $new -Force) {
    switch ($item.Name) {
        "logs" { if (-not (Test-Path (Join-Path $root "logs"))) { Swap "logs" } }
        "spaces" { Swap-Templates }
        default { Swap $item.Name }
    }
}
Write-Output "[upgrade] previous bundle kept in $pre"

Step "6/7 preflight the new bundle"
$pfArgs = @()
if ($Direct) { $pfArgs += "-Direct" }
if ($ProxyConfig) { $pfArgs += @("-ProxyConfig", $ProxyConfig) }
& $psExe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root "bin\preflight.ps1") @pfArgs
if ($LASTEXITCODE -ne 0) { Rollback "the new bundle's preflight failed" }

Step "7/7 start and check health"
if ($NoService) {
    Write-Output "[upgrade] -NoService: start the server yourself, then check $base/health"
} else {
    $since = Get-Date
    Start-ScheduledTask -TaskName $Name
    if (-not (Healthy $since)) { Rollback "the new bundle did not answer /health and /ready in time" }
}
Remove-Item -LiteralPath $stage -Recurse -Force
$oldCa = Join-Path $pre "product\runtime\lib\security\cacerts"
$newCa = Join-Path $root "runtime\lib\security\cacerts"
if ((Test-Path $oldCa) -and (Test-Path $newCa) -and ((Get-FileHash $oldCa).Hash -ne (Get-FileHash $newCa).Hash)) {
    Write-Output "[upgrade] NOTE  the Java runtime was replaced: CA certificates you imported into runtime\lib\security\cacerts are"
    Write-Output "                not in the new one. The old file is $oldCa; re-import with keytool."
}
Write-Output "[upgrade] DONE: $oldEd -> $newEd. Remove $pre once you are satisfied."
exit 0
