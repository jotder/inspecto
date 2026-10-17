import sys, os; sys.path.insert(0, os.path.dirname(__file__)); from svglib import Svg

s = Svg(940, 320, 'Observability and backup',
        'Engine counters, the event ledger and run reports feed the Ops screens; backup and restore run as maintenance tasks.')
s.box(20, 85, 200, 60, 'gray', 'Engine + Jobs', 'CollectorProcessor, JobService')
s.box(270, 20, 200, 50, 'teal', 'MetricRegistry', 'counters, histograms')
s.box(270, 85, 200, 50, 'teal', 'EventLog, EventStore', 'memory, parquet, db')
s.box(270, 150, 200, 50, 'teal', 'StatusStore', 'ledgers, ReportService')
for y in (45, 110, 175):
    s.arrow(220, 115, 270, y)
s.box(520, 20, 190, 50, 'blue', 'GET /metrics', 'inspecto-observability')
s.box(520, 85, 190, 50, 'blue', '/events, /signals', 'EventRoutes, SignalRoutes')
s.box(520, 150, 190, 50, 'blue', '/status, /report', '/health, /ready')
for y in (45, 110, 175):
    s.arrow(470, y, 520, y)
s.box(750, 85, 170, 50, 'gray', 'Ops screens', 'Overview, Events, Runs')
s.arrow(710, 110, 750, 110)
s.group(20, 220, 900, 90, 'maintenance Job Type: BackupTaskProvider in inspecto-backup (Professional+)')
s.box(40, 245, 250, 50, 'coral', 'backup', 'zip + SHA-256 sidecar')
s.box(340, 245, 250, 50, 'coral', 'backup_verify', 'hash-first, fail-closed')
s.box(640, 245, 260, 50, 'coral', 'restore', 'manifest check, zip-slip jail')
s.arrow(290, 270, 340, 270)
s.arrow(590, 270, 640, 270)
s.save('docs/stakeholders/modules/assets/observability-backup.svg')
