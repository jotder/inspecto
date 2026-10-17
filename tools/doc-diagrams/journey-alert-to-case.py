"""Sequence diagram for docs/stakeholders/solutions/journey-alert-to-case.md (SOLUTION-SUITE-DOCS-1)."""
import sys, os; sys.path.insert(0, os.path.dirname(__file__)); from svglib import Svg

ACTORS = [('Event / data','gray'),('AlertService','teal'),('Incident','purple'),('Case rule','purple'),('Analyst','gray'),('Action Request','coral'),('Checker','gray')]
# (from, to, label, dashed) - dashed = response / asynchronous
MSGS = [(0,1,'Event or Alert Rule sweep',False),(1,1,'raise Alert (dedupe key)',False),(1,2,'error/critical: open Incident, ESCALATED_FROM',False),(3,2,'Case rule groups matching Incidents',False),(3,3,'threshold reached: open Case',False),(4,2,'ack / assign / transition',False),(4,5,'POST /action-requests (rendered once)',False),(6,5,'POST /action-requests/{id}/approve',False),(5,5,'dispatch, same idem. key',False),(4,2,'resolve Incident, transition Case',False),(4,1,'POST /alerts/{id}/resolve',False)]
W, TOP, STEP = 940, 20, 34
col = (W - 40) / len(ACTORS)
xs = [20 + col * i + col / 2 for i in range(len(ACTORS))]
H = TOP + 60 + STEP * len(MSGS) + 30
s = Svg(W, H, "Alert to Case", "Sequence: " + "; ".join(m[2] for m in MSGS))
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
s.save('docs/stakeholders/solutions/assets/journey-alert-to-case.svg')
