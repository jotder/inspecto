"""Solution diagram for the Payment fraud content pack (SOLUTION-SUITE-DOCS-1).

Grounded in spaces/_templates/payment-fraud. Run from the repo root: python tools/doc-diagrams/solution-payment-fraud.py
"""
import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from svglib import Svg

COLS = [
    ('Data in', 'gray', [('payment_attempts', 'card / wallet attempts'), ('sim_changes', 'SIM change events'), ('disputes', 'chargeback labels')]),
    ('Pipelines and Jobs', 'teal', [('3 Pipelines', 'refusal_scan: card_number'), ('7 sql.template Jobs', 'hourly feature windows'), ('pf_risk_score Job', 'risk.score, hourly at :30')]),
    ('Outputs', 'coral', [('Feature Datasets', 'velocity, BIN, device ...'), ('4 typology Alert Rules', 'card testing, BIN attack,\nvelocity, SIM swap'), ('Account Risk Score', 'payment_account'), ('Dashboard', '4 KPIs')]),
    ('Modules', 'purple', [('pipelines-steps', ''), ('jobs-scheduler', ''), ('alerts', ''), ('scoring-entity-lists', ''), ('studio-bi', '')]),
]
BW, BH, GAP, X0, STEP, Y0 = 200, 52, 12, 20, 232, 60
rows = max(len(c[2]) for c in COLS)
H = Y0 + rows * (BH + GAP) + 30
s = Svg(940, H, 'Payment fraud content pack', 'Data in, Pipelines and Jobs, outputs and the modules the Payment fraud pack uses.')
for i, (label, color, items) in enumerate(COLS):
    x = X0 + i * STEP
    s.group(x - 8, 30, BW + 16, H - 40, label)
    for j, (title, sub) in enumerate(items):
        s.box(x, Y0 + j * (BH + GAP), BW, BH, color, title, sub or None)
mid = Y0 + BH / 2
for i in range(3):
    s.arrow(X0 + i * STEP + BW + 2, mid, X0 + (i + 1) * STEP - 2, mid)
s.save('docs/stakeholders/solutions/assets/solution-payment-fraud.svg')
