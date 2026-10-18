import sys, os; sys.path.insert(0, os.path.dirname(__file__)); from svglib import Svg
s = Svg(940, 300, 'Pending Change lifecycle', 'An authoring route holds a governed write as a Pending Change; a different approver replays it or it closes.')
s.box(20, 40, 150, 60, 'gray', 'Maker', 'authoring route')
s.box(210, 40, 170, 60, 'teal', 'PendingChanges.hold', 'after all gates')
s.box(420, 40, 150, 60, 'purple', 'pending', '202, nothing written')
s.box(610, 40, 150, 60, 'gray', 'Checker', 'canApproveChanges')
s.box(780, 40, 140, 60, 'blue', 'replay', 'every gate reruns')
s.arrow(170, 70, 210, 70); s.arrow(380, 70, 420, 70); s.arrow(570, 70, 610, 70); s.arrow(760, 70, 780, 70)
s.box(210, 170, 170, 50, 'teal', 'no rule for kind', None)
s.path('M295 100 L295 170'); s.text(300, 140, 'policy off: write now', size=10)
for i, (t, c) in enumerate([('approved', 'teal'), ('stale (409)', 'coral'), ('declined', 'gray'), ('withdrawn', 'gray'), ('expired', 'gray')]):
    s.box(420 + i * 102, 230, 94, 40, c, t)
s.path('M850 100 L850 140 L467 140 L467 230'); s.text(560, 134, 'base version unchanged / changed', size=10)
s.save('docs/okf/product/assets/maker-checker.svg')
