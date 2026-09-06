# Seed the default space inboxes from the pristine samples (the poll dirs are consumed by the engine)
# and pre-create every directory the pipelines expect -- PipelineConfig.prepare() creates only the
# status dir, so every other dirs.* entry must exist on disk before a run.
#
# The format-example pack (csv/fixedwidth/excel/json) is the set of pipelines this space actually
# ships; each has one sample beside this script.
# The subscriber / events / cdr / gwlog corpora (pipelines RETIRED 2026-08-20) were removed 2026-09-06
# (SAMPLE-1-REMOVE); only ref/ is seeded outside the example packs below.
$ErrorActionPreference = 'Stop'
$data = Split-Path -Parent $PSScriptRoot
New-Item -ItemType Directory -Force -Path (Join-Path $data 'ref') | Out-Null
Copy-Item -Path (Join-Path $PSScriptRoot 'ref/*') -Destination (Join-Path $data 'ref') -Force

# The format-example pack: one pipeline per DuckDB-native parser frontend.
foreach ($f in 'csv_example','fixedwidth_example','excel_example','json_example') {
  New-Item -ItemType Directory -Force -Path (Join-Path $data "inbox/$f") | Out-Null
  foreach ($sub in 'database','backup','temp','errors','quarantine','markers','status','logs') {
    New-Item -ItemType Directory -Force -Path (Join-Path $data "$f/$sub") | Out-Null
  }
  Copy-Item -Path (Join-Path $PSScriptRoot "$f/*") -Destination (Join-Path $data "inbox/$f") -Force
}
# The Step catalog (one pipeline per Step kind, 2026-09-06) + the asn1 / xml parse frontends. The
# five at-rest Steps (filter/dedup/join/summarize/sql) also have a config/jobs/<name>_rollup_job.toon
# that runs their steps[] chain over the landed store on every commit. collect_step's samples keep
# their sub-directory: recursion is what that example shows.
foreach ($f in 'filter_step','dedup_step','join_step','summarize_step','sql_step','route_step','collect_step','sink_step','asn1_example','xml_example') {
  New-Item -ItemType Directory -Force -Path (Join-Path $data "inbox/$f") | Out-Null
  foreach ($sub in 'database','backup','temp','errors','quarantine','markers','status','logs') {
    New-Item -ItemType Directory -Force -Path (Join-Path $data "$f/$sub") | Out-Null
  }
  Copy-Item -Path (Join-Path $PSScriptRoot "$f/*") -Destination (Join-Path $data "inbox/$f") -Recurse -Force
}
Write-Host "Seeded csv/fixedwidth/excel/json example inboxes, the Step catalog (*_step) + asn1/xml examples + ref/ - restart the server or wait for the next poll cycle."
