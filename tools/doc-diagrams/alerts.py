"""Alert Rule evaluation flow (SOLUTION-SUITE-DOCS-1, module page `alerts`)."""
import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from svglib import Svg

s = Svg(940, 330, 'Alert Rule evaluation', 'Triggers feed AlertService, which evaluates Alert Rules and raises Alerts, events and Incidents.')
s.group(10, 10, 250, 310, 'Triggers')
s.box(25, 35, 220, 50, 'gray', 'Terminal batch', 'ConsignmentEvent (onEvent)')
s.box(25, 100, 220, 50, 'blue', 'POST /alerts/evaluate', 'canOperateRuns')
s.box(25, 165, 220, 50, 'teal', 'alert.evaluate Job', 'requires: [alerts], rule / scope')
s.box(25, 230, 220, 50, 'teal', 'FreshnessSweep', 'minute cadence, scope freshness')
s.box(320, 110, 270, 110, 'teal', 'AlertService', 'ledger metric / Dataset measure /\nfreshness / Investigation passes\ncooldown + heal streaks')
s.box(320, 250, 270, 50, 'blue', 'Alert Rules (alert-rule)', '/alerts/rules  canAuthorAlertRules')
s.arrow(455, 250, 455, 222)
for y in (60, 125, 190, 255):
    s.arrow(247, y, 318, 165)
s.box(660, 30, 260, 50, 'teal', 'AlertStore', 'GET /alerts  ack / resolve')
s.box(660, 110, 260, 50, 'gray', 'ALERT_FIRED / ALERT_CLEARED', 'EventLog')
s.box(660, 190, 260, 50, 'purple', 'Incident (CRITICAL)', 'incidents Platform Service')
s.box(660, 260, 260, 50, 'gray', 'EventAlertBridge', 'gap / imbalance Events -> Alert')
s.arrow(592, 140, 658, 55)
s.arrow(592, 160, 658, 135)
s.arrow(592, 185, 658, 215)
s.path("M920 285 L932 285 L932 55 L922 55", dash=True)
s.save('docs/stakeholders/modules/assets/alerts.svg')
