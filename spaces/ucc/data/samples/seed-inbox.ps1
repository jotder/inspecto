# Seed the ucc space inboxes from the pristine samples (the poll dirs are consumed by the engine)
# and pre-create every directory the voucher + topups + sites pipelines expect
# (all dirs.* must exist on disk).
# The voucher files exercise the multi-schema dispatch: *main* -> 116 cols, *other* -> 76 cols, default -> 537 cols.
$ErrorActionPreference = 'Stop'
$data = Split-Path -Parent $PSScriptRoot
foreach ($d in 'inbox/voucher/unknown','voucher/database','voucher/backup','voucher/temp','voucher/errors',
               'voucher/quarantine','voucher/markers','voucher/status','voucher/logs',
               'inbox/topups','topups/database','topups/backup','topups/temp','topups/errors',
               'topups/quarantine','topups/markers','topups/status','topups/logs',
               'inbox/sites','sites/database','sites/backup','sites/temp','sites/errors',
               'sites/quarantine','sites/markers','sites/status','sites/logs') {
  New-Item -ItemType Directory -Force -Path (Join-Path $data $d) | Out-Null
}
Copy-Item -Path (Join-Path $PSScriptRoot 'voucher/*') -Destination (Join-Path $data 'inbox/voucher/unknown') -Force
foreach ($f in 'topups','sites') {
  Copy-Item -Path (Join-Path $PSScriptRoot "$f/*") -Destination (Join-Path $data "inbox/$f") -Force
}
Write-Host "Seeded voucher + topups + sites inboxes - restart the server or wait for the next poll cycle."
