"""Solution diagram for the Revenue assurance content pack (SOLUTION-SUITE-DOCS-1).

Grounded in spaces/_templates/telco-ra. Run from the repo root: python tools/doc-diagrams/solution-revenue-assurance.py
"""
import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from svglib import Svg

COLS = [
    ('Data in', 'gray', [('switch / mediated xDR', 'switch_xdr, mediated_xdr'), ('rated usage, invoices', 'rated_usage, billed_invoice'), ('tariff, balance ledger', 'tariff, balance_ledger'), ('interconnect feeds', 'ic_rates, ic_statement')]),
    ('Pipelines and Jobs', 'teal', [('8 Pipelines', 'one per feed, Parquet out'), ('2 recon.run Jobs', 'xdr_completeness,\nrated_vs_billed'), ('sql.template controls', 'rerating, rollforward,\nsettlement, xdr_lost'), ('leakage / data quality', 'chained on job.run.completed')]),
    ('Outputs', 'coral', [('Leakage findings', 'ra_leakage Dataset'), ('Data-quality findings', 'ra_data_quality Dataset'), ('2 Alert Rules', 'leakage_found, data_quality'), ('ra_overview dashboard', '8 tiles, 3 KPIs')]),
    ('Modules', 'purple', [('pipelines-steps', ''), ('reconciliation', ''), ('jobs-scheduler', ''), ('alerts, incidents', ''), ('studio-bi', '')]),
]
BW, BH, GAP, X0, STEP, Y0 = 200, 52, 12, 20, 232, 60
rows = max(len(c[2]) for c in COLS)
H = Y0 + rows * (BH + GAP) + 30
s = Svg(940, H, 'Revenue assurance content pack', 'Data in, Pipelines and Jobs, outputs and the modules the Revenue assurance pack uses.')
for i, (label, color, items) in enumerate(COLS):
    x = X0 + i * STEP
    s.group(x - 8, 30, BW + 16, H - 40, label)
    for j, (title, sub) in enumerate(items):
        s.box(x, Y0 + j * (BH + GAP), BW, BH, color, title, sub or None)
mid = Y0 + BH / 2
for i in range(3):
    s.arrow(X0 + i * STEP + BW + 2, mid, X0 + (i + 1) * STEP - 2, mid)
s.save('docs/okf/product/assets/solution-revenue-assurance.svg')
