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
         reports/orders_daily ref; do
  mkdir -p "../$d"
done
cp orders/* ../inbox/orders/
cp payments/* ../inbox/payments/
cp shipments/* ../inbox/shipments/
cp ref/* ../ref/
echo "Seeded orders + payments + shipments inboxes + ref/ - restart the server or wait for the next poll cycle."
