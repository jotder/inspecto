#!/usr/bin/env bash
# Seed the default space inboxes from the pristine samples (the poll dirs are consumed by the engine)
# and pre-create every directory the pipelines expect -- PipelineConfig.prepare() creates only the
# status dir, so every other dirs.* entry must exist on disk before a run.
#
# The format-example pack (csv/fixedwidth/excel/json) is the set of pipelines this space actually
# ships; each has one sample beside this script.
# The subscriber / events / cdr / gwlog corpora (pipelines RETIRED 2026-08-20) were removed 2026-09-06
# (SAMPLE-1-REMOVE); only ref/ is seeded outside the example packs below.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p ../ref
cp ref/* ../ref/

# The format-example pack: one pipeline per DuckDB-native parser frontend.
for f in csv_example fixedwidth_example excel_example json_example; do
  mkdir -p "../inbox/$f"
  for sub in database backup temp errors quarantine markers status logs; do
    mkdir -p "../$f/$sub"
  done
  cp "$f"/* "../inbox/$f/"
done
# The Step catalog (one pipeline per Step kind, 2026-09-06) + the asn1 / xml parse frontends. The
# five at-rest Steps (filter/dedup/join/summarize/sql) also have a config/jobs/<name>_rollup_job.toon
# that runs their steps[] chain over the landed store on every commit. collect_step's samples keep
# their sub-directory: recursion is what that example shows.
for f in filter_step dedup_step join_step summarize_step sql_step route_step collect_step sink_step asn1_example xml_example; do
  mkdir -p "../inbox/$f"
  for sub in database backup temp errors quarantine markers status logs; do
    mkdir -p "../$f/$sub"
  done
  cp -r "$f"/. "../inbox/$f/"
done
echo "Seeded csv/fixedwidth/excel/json example inboxes, the Step catalog (*_step) + asn1/xml examples + ref/ - restart the server or wait for the next poll cycle."
