# verify-bundle.ps1 - check a delivered Inspecto bundle zip before you install or upgrade to it (DEPLOY-EXPOSURE-KIT-1).
#
#   .\bin\verify-bundle.ps1 <inspecto-deploy-<platform>.zip> [-RequireSignature]
#
# package.ps1 writes <zip>.sha256 (sha256sum format) next to every zip it builds, and <zip>.asc (a GPG detached
# signature) when the release is signed. This checks the zip against both:
#   sha256     the zip's SHA-256 equals the one in <zip>.sha256: the copy is complete and unchanged.
#   signature  gpg --verify <zip>.asc <zip>: the zip was signed by a key in your keyring (Gpg4win on Windows). Import
#              the release key first. With no .asc this is a WARN, or a FAIL with -RequireSignature.
# The .sha256 travels with the zip, so it proves the copy is whole, not who made it; only the signature does.
# Exits 0 when no check failed, 1 otherwise.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Zip,
    [switch]$RequireSignature
)
$ErrorActionPreference = "Stop"
$script:fails = 0
function Row([string]$status, [string]$check, [string]$detail) {
    Write-Output ("[verify-bundle] {0,-4}  {1,-10} {2}" -f $status, $check, $detail)
    if ($status -eq "FAIL") { $script:fails++ }
}

if (-not (Test-Path -LiteralPath $Zip -PathType Leaf)) {
    Row "FAIL" "bundle" "no such file: $Zip"
} elseif (-not (Test-Path -LiteralPath "$Zip.sha256" -PathType Leaf)) {
    Row "FAIL" "sha256" "no $Zip.sha256 beside the zip - ask for the checksum file the release shipped with"
} else {
    $expected = ""
    foreach ($l in Get-Content -LiteralPath "$Zip.sha256") { if ($l.Trim()) { $expected = ($l.Trim() -split '\s+')[0].ToLower(); break } }
    $actual = (Get-FileHash -LiteralPath $Zip -Algorithm SHA256).Hash.ToLower()
    if (-not $expected) { Row "FAIL" "sha256" "$Zip.sha256 holds no checksum" }
    elseif ($expected -eq $actual) { Row "PASS" "sha256" $actual }
    else { Row "FAIL" "sha256" "the zip does not match its checksum (expected $expected, got $actual) - the copy is damaged or changed" }
}

if (Test-Path -LiteralPath "$Zip.asc" -PathType Leaf) {
    $gpg = Get-Command gpg -ErrorAction SilentlyContinue
    if (-not $gpg) {
        Row "FAIL" "signature" "$Zip.asc is present but gpg is not installed, so it cannot be checked"
    } else {
        $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
        & $gpg.Source --batch --verify "$Zip.asc" $Zip *> $null
        $code = $LASTEXITCODE
        $ErrorActionPreference = $prev
        if ($code -eq 0) { Row "PASS" "signature" "good signature from a key in your keyring" }
        else { Row "FAIL" "signature" "gpg --verify failed: a bad signature, or the release key is not imported" }
    }
} elseif ($RequireSignature) {
    Row "FAIL" "signature" "no $Zip.asc and -RequireSignature was given"
} else {
    Row "WARN" "signature" "no $Zip.asc: the release is unsigned, so only its integrity is checked"
}

if ($script:fails -gt 0) { Write-Output "[verify-bundle] $($script:fails) check(s) FAILED - do not install this bundle"; exit 1 }
Write-Output "[verify-bundle] OK"
exit 0
