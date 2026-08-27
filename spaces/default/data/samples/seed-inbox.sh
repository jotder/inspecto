#!/usr/bin/env bash
# Seed the default space inboxes from the pristine samples (the poll dirs are consumed by the engine)
# and pre-create every directory the pipelines expect -- PipelineConfig.prepare() creates only the
# status dir, so every other dirs.* entry must exist on disk before a run.
#
# The format-example pack (csv/fixedwidth/excel/json) is the set of pipelines this space actually
# ships; each has one sample beside this script.
# NOTE: the subscriber / events / cdr / gwlog arms below seed pipelines RETIRED 2026-08-20
# (FEATURE_INVENTORY.md 2). They are left in place because their sample corpora are still
# committed; retiring the arms and the corpora together is an operator call.
set -euo pipefail
cd "$(dirname "$0")"
for d in inbox/subscriber subscriber/database subscriber/backup subscriber/temp subscriber/errors \
         subscriber/quarantine subscriber/markers subscriber/status subscriber/logs \
         inbox/events events_etl/database events_etl/backup events_etl/temp events_etl/errors \
         events_etl/quarantine events_etl/markers events_etl/status events_etl/logs \
         inbox/cdr cdr/database cdr/backup cdr/temp cdr/errors \
         cdr/quarantine cdr/markers cdr/status cdr/logs \
         inbox/gwlog gwlog/database gwlog/backup gwlog/temp gwlog/errors \
         gwlog/quarantine gwlog/markers gwlog/status gwlog/logs \
         reports/events_daily ref; do
  mkdir -p "../$d"
done
cp subscriber/* ../inbox/subscriber/
cp events/* ../inbox/events/
cp cdr/* ../inbox/cdr/
cp gwlog/* ../inbox/gwlog/
cp ref/* ../ref/

# The format-example pack: one pipeline per DuckDB-native parser frontend.
for f in csv_example fixedwidth_example excel_example json_example; do
  mkdir -p "../inbox/$f"
  for sub in database backup temp errors quarantine markers status logs; do
    mkdir -p "../$f/$sub"
  done
  cp "$f"/* "../inbox/$f/"
done
echo "Seeded csv/fixedwidth/excel/json example inboxes (+ the retired subscriber/events/cdr/gwlog corpora) + ref/ - restart the server or wait for the next poll cycle."
