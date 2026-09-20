# Seed the demo space inboxes from the pristine samples (the poll dirs are consumed by the engine)
# and pre-create every directory the orders + payments + shipments pipelines expect
# (all dirs.* must exist on disk).
$ErrorActionPreference = 'Stop'
$data = Split-Path -Parent $PSScriptRoot
foreach ($d in 'inbox/orders','orders/database','orders/backup','orders/temp','orders/errors',
               'orders/quarantine','orders/markers','orders/status','orders/logs',
               'inbox/payments','payments/database','payments/backup','payments/temp','payments/errors',
               'payments/quarantine','payments/markers','payments/status','payments/logs',
               'inbox/shipments','shipments/database','shipments/backup','shipments/temp','shipments/errors',
               'shipments/quarantine','shipments/markers','shipments/status','shipments/logs',
               'inbox/roaming_tap','roaming_tap/database','roaming_tap/backup','roaming_tap/temp','roaming_tap/errors',
               'roaming_tap/quarantine','roaming_tap/markers','roaming_tap/status','roaming_tap/logs',
               'inbox/mule_transfers','mule_transfers/database','mule_transfers/backup','mule_transfers/temp','mule_transfers/errors',
               'mule_transfers/quarantine','mule_transfers/markers','mule_transfers/status','mule_transfers/logs',
               'reports/orders_daily','ref') {
  New-Item -ItemType Directory -Force -Path (Join-Path $data $d) | Out-Null
}
foreach ($f in 'orders','payments','shipments','roaming_tap','mule_transfers') {
  Copy-Item -Path (Join-Path $PSScriptRoot "$f/*") -Destination (Join-Path $data "inbox/$f") -Force
}
Copy-Item -Path (Join-Path $PSScriptRoot 'ref/*') -Destination (Join-Path $data 'ref') -Force
Write-Host "Seeded orders + payments + shipments + roaming_tap + mule_transfers inboxes + ref/ - restart the server or wait for the next poll cycle."
