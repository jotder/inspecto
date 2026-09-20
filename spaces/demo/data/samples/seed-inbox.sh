#!/usr/bin/env bash
# Seed the demo space inboxes from the pristine samples (the poll dirs are consumed by the engine)
# and pre-create every directory the orders + payments + shipments pipelines expect
# (all dirs.* must exist on disk).
set -euo pipefail
cd "$(dirname "$0")"
for d in inbox/orders orders/database orders/backup orders/temp orders/errors \
         orders/quarantine orders/markers orders/status orders/logs \
         inbox/payments payments/database payments/backup payments/temp payments/errors \
         payments/quarantine payments/markers payments/status payments/logs \
         inbox/shipments shipments/database shipments/backup shipments/temp shipments/errors \
         shipments/quarantine shipments/markers shipments/status shipments/logs \
         inbox/roaming_tap roaming_tap/database roaming_tap/backup roaming_tap/temp roaming_tap/errors          roaming_tap/quarantine roaming_tap/markers roaming_tap/status roaming_tap/logs          inbox/mule_transfers mule_transfers/database mule_transfers/backup mule_transfers/temp mule_transfers/errors          mule_transfers/quarantine mule_transfers/markers mule_transfers/status mule_transfers/logs          reports/orders_daily ref; do
  mkdir -p "../$d"
done
cp orders/* ../inbox/orders/
cp payments/* ../inbox/payments/
cp shipments/* ../inbox/shipments/
cp roaming_tap/*.csv ../inbox/roaming_tap/
cp mule_transfers/*.csv ../inbox/mule_transfers/
cp ref/* ../ref/
echo "Seeded orders + payments + shipments + roaming_tap + mule_transfers inboxes + ref/ - restart the server or wait for the next poll cycle."
