# backup-offsite.ps1 - copy the newest verified Inspecto backup off this host (DEPLOY-EXPOSURE-KIT-1).
#
#   .\bin\backup-offsite.ps1 -From <backup_dir> -To <folder or \\server\share\path>      robocopy
#   .\bin\backup-offsite.ps1 -From <backup_dir> -To <url> -Command "<command>"          an object-store CLI, run as
#                                                                                       <command> <file> <url> per file
#       e.g.  -To s3://bucket/inspecto/ -Command "aws s3 cp"      -To remote:inspecto -Command "rclone copy"
#   .\bin\backup-offsite.ps1 -From <backup_dir> -VerifyOnly                             check, copy nothing
#
# <backup_dir> is the `backup_dir` of your backup Job. The backup Job keeps it inside the Space (the path jail), so
# this script, run by the operator outside the server, is what moves a copy off the host. It takes the newest archive
# the way backup_verify does (archive names sort by time), checks its SHA-256 against the archiveSha256 in its sidecar
# <archive>.manifest.json, and copies the archive, then the sidecar, so a target holding the sidecar holds the whole
# backup. A folder target is re-hashed after the copy. Schedule it after the backup Job (a Scheduled Task).
# Exits 0 when the backup verified and every copy succeeded, 1 otherwise. Nothing is copied when the check fails.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$From,
    [string]$To = "",
    [string]$Command = "",
    [switch]$VerifyOnly
)
$ErrorActionPreference = "Stop"
function Say([string]$status, [string]$check, [string]$detail) { Write-Output ("[backup-offsite] {0,-4}  {1,-8} {2}" -f $status, $check, $detail) }
function Fail([string]$check, [string]$detail) { Say "FAIL" $check $detail; exit 1 }
function Sha([string]$p) { return (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLower() }

if (-not $VerifyOnly -and -not $To) { Write-Output "[backup-offsite] -To <target> is required (or -VerifyOnly)"; exit 2 }
if (-not (Test-Path -LiteralPath $From -PathType Container)) { Fail "backup" "no such folder: $From" }
$zip = Get-ChildItem -LiteralPath $From -File | Where-Object { $_.Name.EndsWith(".zip") } | Sort-Object Name -Descending | Select-Object -First 1
if (-not $zip) { Fail "backup" "no backup archive (*.zip) in $From - has the backup Job run?" }
$sidecar = "$($zip.FullName).manifest.json"
if (-not (Test-Path -LiteralPath $sidecar -PathType Leaf)) { Fail "verify" "$($zip.Name) has no sidecar $($zip.Name).manifest.json; it cannot be verified" }
$expected = ""
if ((Get-Content -Raw -LiteralPath $sidecar) -match '"archiveSha256"\s*:\s*"([0-9A-Fa-f]+)"') { $expected = $Matches[1].ToLower() }
if (-not $expected) { Fail "verify" "$($zip.Name).manifest.json records no archiveSha256" }
$actual = Sha $zip.FullName
if ($expected -ne $actual) { Fail "verify" "$($zip.Name) does not match its manifest (expected $expected, got $actual) - the backup is damaged; run backup_verify and take a new backup" }
Say "PASS" "verify" "$($zip.Name) sha256 $actual"
if ($VerifyOnly) { exit 0 }

foreach ($f in @($zip.FullName, $sidecar)) {
    $name = Split-Path -Leaf $f
    if ($Command) {
        $parts = @($Command -split '\s+' | Where-Object { $_ })
        $exe = $parts[0]
        $rest = @()
        if ($parts.Count -gt 1) { $rest = $parts[1..($parts.Count - 1)] }
        & $exe @rest $f $To
        if ($LASTEXITCODE -ne 0) { Fail "copy" "$Command $name $To exited $LASTEXITCODE" }
    } else {
        if ($To -match '^[a-z][a-z0-9+.-]*://') { Fail "copy" "$To is a URL: pass -Command with your object-store CLI, for example -Command `"aws s3 cp`"" }
        if (-not (Test-Path -LiteralPath $To -PathType Container)) { Fail "copy" "no such folder: $To (map or create the share first)" }
        # robocopy exit codes below 8 are success (1 = copied).
        & robocopy.exe (Split-Path -Parent $f) $To $name /R:2 /W:5 /NJH /NJS /NP | Out-Null
        if ($LASTEXITCODE -ge 8) { Fail "copy" "robocopy $name to $To exited $LASTEXITCODE" }
        if ((Sha (Join-Path $To $name)) -ne (Sha $f)) { Fail "copy" "$(Join-Path $To $name) differs from the original after the copy" }
    }
    Say "PASS" "copy" "$name -> $To"
}
exit 0
