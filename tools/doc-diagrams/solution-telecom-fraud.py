"""Solution diagram for the Telecom fraud content pack (SOLUTION-SUITE-DOCS-1).

Grounded in spaces/_templates/telco-fraud. Run from the repo root: python tools/doc-diagrams/solution-telecom-fraud.py
"""
import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from svglib import Svg

COLS = [
    ('Data in', 'gray', [('cdr', 'voice / SMS / data records'), ('subscriber_events', 'SIM swap, activations'), ('payments', 'recharge, reversal')]),
    ('Pipelines and Jobs', 'teal', [('3 Pipelines', '7 Expectations,\n2 Decision Rules'), ('14 sql.template Jobs', 'one per typology, 02:00'), ('alert.evaluate Job', '02:30, 14 Alert Rules'), ('anomaly.score Job', 'tf_subscriber_usage')]),
    ('Outputs', 'coral', [('14 typology Datasets', 'fraud_irsf, fraud_simbox ...'), ('Per-offender Alerts', 'keyed on the offender'), ('Case Rule', 'fraud_critical_burst'), ('Dashboard + CSV export', 'telco_fraud_overview')]),
    ('Modules', 'purple', [('pipelines-steps', ''), ('decision-kernel', ''), ('alerts, incidents', ''), ('case-management', ''), ('anomaly', '')]),
]
BW, BH, GAP, X0, STEP, Y0 = 200, 52, 12, 20, 232, 60
rows = max(len(c[2]) for c in COLS)
H = Y0 + rows * (BH + GAP) + 30
s = Svg(940, H, 'Telecom fraud content pack', 'Data in, Pipelines and Jobs, outputs and the modules the Telecom fraud pack uses.')
for i, (label, color, items) in enumerate(COLS):
    x = X0 + i * STEP
    s.group(x - 8, 30, BW + 16, H - 40, label)
    for j, (title, sub) in enumerate(items):
        s.box(x, Y0 + j * (BH + GAP), BW, BH, color, title, sub or None)
mid = Y0 + BH / 2
for i in range(3):
    s.arrow(X0 + i * STEP + BW + 2, mid, X0 + (i + 1) * STEP - 2, mid)
s.save('docs/okf/product/assets/solution-telecom-fraud.svg')
