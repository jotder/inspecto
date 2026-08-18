# Seed the default space inboxes from the pristine samples (the poll dirs are consumed by the engine)
# and pre-create every directory the subscriber + events + cdr + gwlog pipelines expect
# (all dirs.* must exist on disk).
$ErrorActionPreference = 'Stop'
$data = Split-Path -Parent $PSScriptRoot
foreach ($d in 'inbox/subscriber','subscriber/database','subscriber/backup','subscriber/temp','subscriber/errors',
               'subscriber/quarantine','subscriber/markers','subscriber/status','subscriber/logs',
               'inbox/events','events_etl/database','events_etl/backup','events_etl/temp','events_etl/errors',
               'events_etl/quarantine','events_etl/markers','events_etl/status','events_etl/logs',
               'inbox/cdr','cdr/database','cdr/backup','cdr/temp','cdr/errors',
               'cdr/quarantine','cdr/markers','cdr/status','cdr/logs',
               'inbox/gwlog','gwlog/database','gwlog/backup','gwlog/temp','gwlog/errors',
               'gwlog/quarantine','gwlog/markers','gwlog/status','gwlog/logs',
               'reports/events_daily','ref') {
  New-Item -ItemType Directory -Force -Path (Join-Path $data $d) | Out-Null
}
foreach ($f in 'subscriber','events','cdr','gwlog') {
  Copy-Item -Path (Join-Path $PSScriptRoot "$f/*") -Destination (Join-Path $data "inbox/$f") -Force
}
Copy-Item -Path (Join-Path $PSScriptRoot 'ref/*') -Destination (Join-Path $data 'ref') -Force
Write-Host "Seeded subscriber + events + cdr + gwlog inboxes + ref/ - restart the server or wait for the next poll cycle."
