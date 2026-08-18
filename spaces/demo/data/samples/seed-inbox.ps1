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
               'reports/orders_daily','ref') {
  New-Item -ItemType Directory -Force -Path (Join-Path $data $d) | Out-Null
}
foreach ($f in 'orders','payments','shipments') {
  Copy-Item -Path (Join-Path $PSScriptRoot "$f/*") -Destination (Join-Path $data "inbox/$f") -Force
}
Copy-Item -Path (Join-Path $PSScriptRoot 'ref/*') -Destination (Join-Path $data 'ref') -Force
Write-Host "Seeded orders + payments + shipments inboxes + ref/ - restart the server or wait for the next poll cycle."
