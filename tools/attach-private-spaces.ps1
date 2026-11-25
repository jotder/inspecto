<#
.SYNOPSIS
  Attach or detach the private (real-data) Spaces to the canonical spaces root, on demand (SPACES-CONSOLIDATION-1 P4, D-SC11).

.DESCRIPTION
  A dev backend boots every Space under the repository's `spaces/`. The private Spaces hold real operator extracts (tens of GB), and
  booting one starts its Collectors and Pipelines, so they are NOT attached by default. Their `<id>.ref` pointers live in
  `C:\sandbox\inspecto_spaces\_refs`; -Attach copies them into `spaces/` (git-ignored: `/spaces/*.ref`), -Detach removes them again.
  Stop the server first: a server discovers its Spaces at boot.

  Only `<id>.ref` files are touched. A real Space folder of the same id, or a ref that names something else, is left alone and reported.

.PARAMETER Action   Status (default), Attach or Detach.
.PARAMETER Only     Space ids to act on (default: every ref in the refs folder).
.PARAMETER SpacesRoot  The spaces root (default: the repository's spaces/).
.PARAMETER RefsDir  Where the pointers are kept (default: C:\sandbox\inspecto_spaces\_refs).
#>
[CmdletBinding()]
param(
    [ValidateSet('Status', 'Attach', 'Detach')][string]$Action = 'Status',
    [string[]]$Only = @(),
    [string]$SpacesRoot = (Join-Path (Split-Path $PSScriptRoot -Parent) 'spaces'),
    [string]$RefsDir = 'C:\sandbox\inspecto_spaces\_refs'
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $RefsDir)) { throw "no refs folder at $RefsDir" }
if (-not (Test-Path -LiteralPath $SpacesRoot)) { throw "no spaces root at $SpacesRoot" }

function Read-Ref([string]$file) { (Get-Content -LiteralPath $file -Raw).Trim() }

$refs = @(Get-ChildItem -LiteralPath $RefsDir -Filter '*.ref' -File | Sort-Object Name)
if ($Only.Count -gt 0) { $refs = @($refs | Where-Object { $Only -contains $_.BaseName }) }
if ($refs.Count -eq 0) { Write-Host "no matching refs in $RefsDir"; return }

$problems = 0
foreach ($ref in $refs) {
    $id = $ref.BaseName
    $target = Read-Ref $ref.FullName
    $link = Join-Path $SpacesRoot "$id.ref"
    $folder = Join-Path $SpacesRoot $id
    $state =
        if (Test-Path -LiteralPath $folder) { 'conflict' }
        elseif (Test-Path -LiteralPath $link) { if ((Read-Ref $link) -eq $target) { 'attached' } else { 'other-ref' } }
        else { 'detached' }
    if (-not (Test-Path -LiteralPath (Join-Path $target 'space.toon'))) { $state = "$state, TARGET MISSING ($target)"; $problems++ }

    switch ($Action) {
        'Status' { }
        'Attach' {
            if ($state -eq 'detached') { Copy-Item -LiteralPath $ref.FullName -Destination $link; $state = 'attached (now)' }
            elseif ($state -like 'conflict*') { Write-Warning "$id : a Space folder of that id is already in $SpacesRoot; not attached"; $problems++ }
            elseif ($state -like 'other-ref*') { Write-Warning "$id : $link names a different folder; not touched"; $problems++ }
        }
        'Detach' {
            if ($state -like 'attached*') { Remove-Item -LiteralPath $link -Force; $state = 'detached (now)' }
            elseif ($state -like 'other-ref*') { Write-Warning "$id : $link names a different folder; not removed"; $problems++ }
        }
    }
    '{0,-18} {1,-24} -> {2}' -f $id, $state, $target
}
if ($Action -eq 'Attach') { Write-Host 'Start (or restart) the server: it finds its Spaces at boot. Their Collectors and Pipelines run on real data.' -ForegroundColor Yellow }
if ($problems -gt 0) { exit 1 }
