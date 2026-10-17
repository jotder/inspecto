"""Solution diagram for the Link Analysis content pack (SOLUTION-SUITE-DOCS-1).

Grounded in spaces/_templates/la-showcase and la-mobile-money. Run from the repo root: python tools/doc-diagrams/solution-link-analysis.py
"""
import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from svglib import Svg

COLS = [
    ('Data in', 'gray', [('mule_transfers', 'daily account transfers'), ('8 telecom feeds', 'voice_cdr, sms_cdr, hlr_eir ...'), ('wallet_txn + masters', 'la-mobile-money')]),
    ('Pipelines and Jobs', 'teal', [('Pipelines per feed', 'daily partitions'), ('link-folding Jobs', 'telecom_links, mm_links'), ('la_index_build Job', 'on pipeline.commit'), ('la_detect Job', 'every 5 minutes')]),
    ('Outputs', 'coral', [('Link Index', 'index enabled'), ('Saved views, Geo Views', 'mule_layering_ring ...'), ('Typology Alerts', 'irsf / simbox / wangiri'), ('Investigations', 'runtime, seed script')]),
    ('Modules', 'purple', [('link-analysis', ''), ('graph-library', ''), ('identity-access', ''), ('alerts', ''), ('case-management', '')]),
]
BW, BH, GAP, X0, STEP, Y0 = 200, 52, 12, 20, 232, 60
rows = max(len(c[2]) for c in COLS)
H = Y0 + rows * (BH + GAP) + 30
s = Svg(940, H, 'Link Analysis content pack', 'Data in, Pipelines and Jobs, outputs and the modules the Link Analysis pack uses.')
for i, (label, color, items) in enumerate(COLS):
    x = X0 + i * STEP
    s.group(x - 8, 30, BW + 16, H - 40, label)
    for j, (title, sub) in enumerate(items):
        s.box(x, Y0 + j * (BH + GAP), BW, BH, color, title, sub or None)
mid = Y0 + BH / 2
for i in range(3):
    s.arrow(X0 + i * STEP + BW + 2, mid, X0 + (i + 1) * STEP - 2, mid)
s.save('docs/stakeholders/solutions/assets/solution-link-analysis.svg')
