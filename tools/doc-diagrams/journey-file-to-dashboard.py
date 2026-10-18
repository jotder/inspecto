"""Sequence diagram for docs/okf/product/layer/product/user-guide-journey-file-to-dashboard.md (SOLUTION-SUITE-DOCS-1)."""
import sys, os; sys.path.insert(0, os.path.dirname(__file__)); from svglib import Svg

ACTORS = [('Remote store','gray'),('Collector','teal'),('Stage-1 ingest','teal'),('Lakehouse','teal'),('Pipeline Run','teal'),('Dataset','blue'),('Dashboard','blue')]
# (from, to, label, dashed) - dashed = response / asynchronous
MSGS = [(1,0,'discover + stability gate',False),(0,1,'fetch to staging, integrity check',True),(1,1,'dedup in ledger, land in dirs.poll',False),(1,2,'Consignment planned',False),(2,2,'parse (grammar) + Expectations',False),(2,3,'commit: DuckLake register, manifest, markers, ledger',False),(4,3,'read committed Parquet',False),(4,3,'write Pipeline output',False),(5,3,'Dataset bound to output table',False),(6,5,'POST /bi/query',False),(5,6,'Result Set rendered in Widget',True)]
W, TOP, STEP = 940, 20, 34
col = (W - 40) / len(ACTORS)
xs = [20 + col * i + col / 2 for i in range(len(ACTORS))]
H = TOP + 60 + STEP * len(MSGS) + 30
s = Svg(W, H, "File to dashboard", "Sequence: " + "; ".join(m[2] for m in MSGS))
for x, (name, color) in zip(xs, ACTORS):
    s.box(x - col / 2 + 6, TOP, col - 12, 40, color, name)
    s.o.append(f'<line x1="{x:g}" y1="{TOP + 40}" x2="{x:g}" y2="{H - 10}" stroke="#B4B2A9" stroke-width="0.6" stroke-dasharray="3 3"/>')
for i, (a, b, label, dash) in enumerate(MSGS):
    y = TOP + 75 + STEP * i
    x1, x2 = xs[a], xs[b]
    if a == b:
        s.path(f'M{x1:g} {y - 8} h28 v14 h-24', dash)
        s.text(x1 + 34, y + 2, f'{i + 1}. {label}')
    else:
        s.arrow(x1, y, x2 - (6 if x2 > x1 else -6), y, dash)
        s.text(min(x1, x2) + 6, y - 5, f'{i + 1}. {label}', size=10.5)
s.save('docs/okf/product/assets/journey-file-to-dashboard.svg')
