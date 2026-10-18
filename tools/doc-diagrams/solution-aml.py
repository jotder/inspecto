"""Solution diagram for the AML content pack (SOLUTION-SUITE-DOCS-1).

Grounded in spaces/_templates/aml. Run from the repo root: python tools/doc-diagrams/solution-aml.py
"""
import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from svglib import Svg

COLS = [
    ('Data in', 'gray', [('aml_txn', 'core-banking ledger'), ('aml_parties', 'party register')]),
    ('Pipelines and Jobs', 'teal', [('2 Pipelines', 'Parquet per feed'), ('4 sql.template Jobs', 'structuring, threshold,\nfan-in, high-risk traffic'), ('risk / anomaly Jobs', 'aml_risk_score,\naml_anomaly_score'), ('aml_screening Job', 'screening.run, disabled')]),
    ('Outputs', 'coral', [('4 per-account Alerts', 'one Alert Rule per Job'), ('Account Risk Score', 'aml_account'), ('Screening Hits', 'aml_sanctions, aml_pep'), ('STR template, LA view', 'aml-str, 4 pattern packs')]),
    ('Modules', 'purple', [('alerts, incidents', ''), ('scoring-entity-lists', ''), ('anomaly', ''), ('screening', ''), ('regulatory-reporting', ''), ('link-analysis', '')]),
]
BW, BH, GAP, X0, STEP, Y0 = 200, 52, 12, 20, 232, 60
rows = max(len(c[2]) for c in COLS)
H = Y0 + rows * (BH + GAP) + 30
s = Svg(940, H, 'AML content pack', 'Data in, Pipelines and Jobs, outputs and the modules the AML pack uses.')
for i, (label, color, items) in enumerate(COLS):
    x = X0 + i * STEP
    s.group(x - 8, 30, BW + 16, H - 40, label)
    for j, (title, sub) in enumerate(items):
        s.box(x, Y0 + j * (BH + GAP), BW, BH, color, title, sub or None)
mid = Y0 + BH / 2
for i in range(3):
    s.arrow(X0 + i * STEP + BW + 2, mid, X0 + (i + 1) * STEP - 2, mid)
s.save('docs/okf/product/assets/solution-aml.svg')
