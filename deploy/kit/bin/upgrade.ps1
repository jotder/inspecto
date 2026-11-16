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
#   4. The Scheduled Task bin\install-service.ps1 registered is stopped, with any java still running from this
#      bundle (-NoService: you stop and start the server yourself).
#   5. The previous bundle is kept beside this one as <bundle>.pre-upgrade-<time>\: a copy of the settings
#      (spaces.ref, conf\, each Space's config\ and top-level files) and every folder the new bundle replaces.
#      Everything the new bundle does not ship stays in place: spaces\ (only spaces\_templates is replaced), logs\,
#      spaces.ref, files of your own.
#   6. The new bundle's bin\preflight.ps1 runs (-Direct and -ProxyConfig are passed to it).
#   7. The task starts and the server must answer /health and /ready within -HealthTimeout seconds (default 180).
# A failure at 6 or 7 puts the previous bundle back (and restarts the task); the new one is left unpacked.
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

function Step([string]$m) { Write-Output "[upgrade] == $m" }
function Die([string]$m) { Write-Output "[upgrade] FAIL  $m"; exit 1 }
function Edition([string]$dir) {
    $f = Join-Path $dir "lib\edition.properties"
    if (-not (Test-Path $f)) { return "" }
    foreach ($l in Get-Content $f) { if ($l -match '^edition=(.*)$') { return $Matches[1].Trim() } }
    return ""
}
# preflight.ps1 runs in a child of this same PowerShell, so its Set-Location cannot pull this script back into the bundle.
$psExe = (Get-Process -Id $PID).Path
function Endpoint { return (& $psExe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root "bin\preflight.ps1") -Endpoint | Select-Object -Last 1) }
function Healthy {
    $base = Endpoint
    $deadline = (Get-Date).AddSeconds($HealthTimeout)
    while ((Get-Date) -lt $deadline) {
        & curl.exe -fsSk -o NUL "$base/health" 2>$null
        $h = $LASTEXITCODE
        & curl.exe -fsSk -o NUL "$base/ready" 2>$null
        if ($h -eq 0 -and $LASTEXITCODE -eq 0) { Write-Output "[upgrade] PASS  $base/health and /ready answer"; return $true }
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
    # Stopping the task ends cmd.exe; a java.exe it started can outlive it. Any process still running from this bundle
    # holds its files open, and Windows then refuses to move them.
    $prefix = $root.TrimEnd('\') + '\'
    for ($i = 0; $i -lt 30; $i++) {
        $running = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
            ($_.ExecutablePath -and $_.ExecutablePath.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) -or
            ($_.CommandLine -and $_.CommandLine.IndexOf($root, [StringComparison]::OrdinalIgnoreCase) -ge 0) } |
            Where-Object { $_.ProcessId -ne $PID })
        if ($running.Count -eq 0) { return }
        if (-not $NoService) { $running | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue } }
        Start-Sleep -Seconds 1
    }
    Die "a process still runs from $root after the stop; nothing was swapped"
}
$script:swapped = New-Object System.Collections.ArrayList
function Rollback([string]$why) {
    Write-Output "[upgrade] FAIL  $why - putting the previous bundle back"
    if (-not $NoService) { Stop-ScheduledTask -TaskName $Name -ErrorAction SilentlyContinue; Start-Sleep -Seconds 2 }
    for ($i = $script:swapped.Count - 1; $i -ge 0; $i--) {
        $e = $script:swapped[$i]
        $target = Join-Path $new $e
        New-Item -ItemType Directory -Force (Split-Path -Parent $target) | Out-Null
        if (Test-Path (Join-Path $root $e)) { Move-Item (Join-Path $root $e) $target }
        if (Test-Path (Join-Path $pre "product\$e")) { Move-Item (Join-Path $pre "product\$e") (Join-Path $root $e) }
    }
    if (-not $NoService) {
        Start-ScheduledTask -TaskName $Name
        if (Healthy) { Write-Output "[upgrade] the previous bundle is running again" }
    }
    Write-Output "[upgrade] rolled back; the new bundle is left unpacked in $stage, the settings copy in $pre"
    exit 1
}
function Swap([string]$e) {
    $dest = Join-Path $pre "product\$e"
    New-Item -ItemType Directory -Force (Split-Path -Parent $dest) | Out-Null
    try {
        if (Test-Path (Join-Path $root $e)) { Move-Item (Join-Path $root $e) $dest }
        [void]$script:swapped.Add($e)
        Move-Item (Join-Path $new $e) (Join-Path $root $e)
    } catch { Rollback "could not swap $e ($($_.Exception.Message))" }
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
New-Item -ItemType Directory -Force (Join-Path $pre "settings"), (Join-Path $pre "product") | Out-Null
try {
    foreach ($f in "spaces.ref", "conf") {
        if (Test-Path (Join-Path $root $f)) { Copy-Item (Join-Path $root $f) (Join-Path $pre "settings") -Recurse }
    }
    if (Test-Path (Join-Path $root "spaces")) {
        foreach ($s in Get-ChildItem (Join-Path $root "spaces") | Where-Object { $_.Name -ne "_templates" }) {
            $dest = Join-Path $pre "settings\spaces\$($s.Name)"
            if ($s.PSIsContainer) {
                New-Item -ItemType Directory -Force $dest | Out-Null
                if (Test-Path (Join-Path $s.FullName "config")) { Copy-Item (Join-Path $s.FullName "config") $dest -Recurse }
                Get-ChildItem -LiteralPath $s.FullName -File | ForEach-Object { Copy-Item $_.FullName $dest }
            } else {
                New-Item -ItemType Directory -Force (Split-Path -Parent $dest) | Out-Null
                Copy-Item $s.FullName $dest
            }
        }
    }
} catch { Die "copying the settings to $pre failed ($($_.Exception.Message)); nothing was swapped - start the server again" }
foreach ($item in Get-ChildItem -LiteralPath $new -Force) {
    switch ($item.Name) {
        "logs" { if (-not (Test-Path (Join-Path $root "logs"))) { Swap "logs" } }
        "spaces" {
            New-Item -ItemType Directory -Force (Join-Path $root "spaces") | Out-Null
            if (Test-Path (Join-Path $new "spaces\_templates")) { Swap "spaces\_templates" }
        }
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
    Write-Output "[upgrade] -NoService: start the server yourself, then check $(Endpoint)/health"
} else {
    Start-ScheduledTask -TaskName $Name
    if (-not (Healthy)) { Rollback "the server did not answer /health and /ready in time" }
}
Remove-Item -LiteralPath $stage -Recurse -Force
Write-Output "[upgrade] DONE: $oldEd -> $newEd. Remove $pre once you are satisfied."
