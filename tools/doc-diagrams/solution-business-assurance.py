"""Solution diagram for the Business assurance content pack (SOLUTION-SUITE-DOCS-1).

Grounded in spaces/_templates/business-assurance. Run from the repo root: python tools/doc-diagrams/solution-business-assurance.py
"""
import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from svglib import Svg

COLS = [
    ('Data in', 'gray', [('Synthetic corpus', 'derived views, no Collector'), ('daily_revenue (yours)', 'for the disabled Jobs'), ('margin_lines (yours)', 'for the disabled Jobs')]),
    ('Pipelines and Jobs', 'teal', [('3 derived views', 'forecast, margin lines,\nmargin erosion'), ('2 sql.template Jobs', 'ship disabled, 06:00')]),
    ('Outputs', 'coral', [('Forecast + band', 'ba_revenue_forecast'), ('Margin erosion', 'ba_margin_erosion'), ('5 Alert Rules', 'band, regime, drift,\nerosion, data quality'), ('ba_overview dashboard', '3 KPIs')]),
    ('Modules', 'purple', [('analytics-kernel', ''), ('jobs-scheduler', ''), ('alerts', ''), ('studio-bi', '')]),
]
BW, BH, GAP, X0, STEP, Y0 = 200, 52, 12, 20, 232, 60
rows = max(len(c[2]) for c in COLS)
H = Y0 + rows * (BH + GAP) + 30
s = Svg(940, H, 'Business assurance content pack', 'Data in, Pipelines and Jobs, outputs and the modules the Business assurance pack uses.')
for i, (label, color, items) in enumerate(COLS):
    x = X0 + i * STEP
    s.group(x - 8, 30, BW + 16, H - 40, label)
    for j, (title, sub) in enumerate(items):
        s.box(x, Y0 + j * (BH + GAP), BW, BH, color, title, sub or None)
mid = Y0 + BH / 2
for i in range(3):
    s.arrow(X0 + i * STEP + BW + 2, mid, X0 + (i + 1) * STEP - 2, mid)
s.save('docs/stakeholders/solutions/assets/solution-business-assurance.svg')
