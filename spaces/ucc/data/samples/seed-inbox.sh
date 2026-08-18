#!/usr/bin/env bash
# Seed the ucc space inboxes from the pristine samples (the poll dirs are consumed by the engine)
# and pre-create every directory the voucher + topups + sites pipelines expect
# (all dirs.* must exist on disk).
# The voucher files exercise the multi-schema dispatch: *main* -> 116 cols, *other* -> 76 cols, default -> 537 cols.
set -euo pipefail
cd "$(dirname "$0")"
for d in inbox/voucher/unknown voucher/database voucher/backup voucher/temp voucher/errors \
         voucher/quarantine voucher/markers voucher/status voucher/logs \
         inbox/topups topups/database topups/backup topups/temp topups/errors \
         topups/quarantine topups/markers topups/status topups/logs \
         inbox/sites sites/database sites/backup sites/temp sites/errors \
         sites/quarantine sites/markers sites/status sites/logs; do
  mkdir -p "../$d"
done
cp voucher/* ../inbox/voucher/unknown/
cp topups/* ../inbox/topups/
cp sites/* ../inbox/sites/
echo "Seeded voucher + topups + sites inboxes - restart the server or wait for the next poll cycle."
