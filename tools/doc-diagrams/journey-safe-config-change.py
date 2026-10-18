"""Sequence diagram for docs/okf/product/layer/product/architecture-journey-safe-config-change.md (SOLUTION-SUITE-DOCS-1)."""
import sys, os; sys.path.insert(0, os.path.dirname(__file__)); from svglib import Svg

ACTORS = [('Maker','gray'),('Editor (SPA)','blue'),('Control API','blue'),('SaveGate','purple'),('PendingChanges','purple'),('Checker','gray'),('Config store','teal')]
# (from, to, label, dashed) - dashed = response / asynchronous
MSGS = [(0,1,'edit Pipeline / component',False),(1,2,'POST /pipelines/authored/{id}/dry-run',False),(2,1,'scratch-only run result',True),(1,2,'POST /config/write',False),(2,3,'SaveGate.check incl. ConfigSafetyValidator',False),(3,2,'ERROR finding: 422, nothing written',True),(2,4,'PendingChanges.hold: policy on',False),(4,1,'202 Pending Change',True),(5,4,'GET .../diff, POST .../approve',False),(4,2,'ApiContext.replay as approver, hashes match',False),(2,6,'write; history snapshot',False),(0,6,'POST /pipelines/{id}/history/{hid}/restore',False)]
W, TOP, STEP = 940, 20, 34
col = (W - 40) / len(ACTORS)
xs = [20 + col * i + col / 2 for i in range(len(ACTORS))]
H = TOP + 60 + STEP * len(MSGS) + 30
s = Svg(W, H, "Safe config change", "Sequence: " + "; ".join(m[2] for m in MSGS))
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
s.save('docs/okf/product/assets/journey-safe-config-change.svg')
