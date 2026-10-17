"""Solution diagram for the Mobile money content pack (SOLUTION-SUITE-DOCS-1).

Grounded in spaces/_templates/mobile-money. Run from the repo root: python tools/doc-diagrams/solution-mobile-money.py
"""
import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from svglib import Svg

COLS = [
    ('Data in', 'gray', [('wallet_txn, wallets', 'wallet platform'), ('bank_statement', 'trust account'), ('partner_settlement', 'partner file'), ('core_subscribers', 'core-network snapshot')]),
    ('Pipelines and Jobs', 'teal', [('5 Pipelines', 'one per feed'), ('2 recon.run Jobs', 'bank_float,\npartner_settlement'), ('7 sql.template Jobs', 'daily at 03:00'), ('risk / anomaly Jobs', 'mm_risk_score,\nmm_anomaly_score')]),
    ('Outputs', 'coral', [('Reconciliation breaks', 'open Incidents'), ('7 per-entity Alerts', 'agent / wallet keyed'), ('Agent Risk Score', 'mm_agent'), ('Dashboard', 'mobile_money_overview')]),
    ('Modules', 'purple', [('reconciliation', ''), ('alerts, incidents', ''), ('scoring-entity-lists', ''), ('anomaly', ''), ('studio-bi', '')]),
]
BW, BH, GAP, X0, STEP, Y0 = 200, 52, 12, 20, 232, 60
rows = max(len(c[2]) for c in COLS)
H = Y0 + rows * (BH + GAP) + 30
s = Svg(940, H, 'Mobile money content pack', 'Data in, Pipelines and Jobs, outputs and the modules the Mobile money pack uses.')
for i, (label, color, items) in enumerate(COLS):
    x = X0 + i * STEP
    s.group(x - 8, 30, BW + 16, H - 40, label)
    for j, (title, sub) in enumerate(items):
        s.box(x, Y0 + j * (BH + GAP), BW, BH, color, title, sub or None)
mid = Y0 + BH / 2
for i in range(3):
    s.arrow(X0 + i * STEP + BW + 2, mid, X0 + (i + 1) * STEP - 2, mid)
s.save('docs/stakeholders/solutions/assets/solution-mobile-money.svg')
