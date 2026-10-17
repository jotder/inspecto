"""Events module diagram: emitters -> EventLog (scrub, chain) -> EventStore -> readers."""
import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from svglib import Svg

s = Svg(940, 400, 'Event log flow',
        'Emitters call EventLog.emit; the event is scrubbed, AUDIT and ACCESS_DENIED are hash-chained, '
        'appended to the configured EventStore, then handed to subscribers; routes read the store.')
s.group(20, 30, 200, 330, 'Emitters')
s.box(35, 55, 170, 54, 'gray', 'Domain emitters', 'Collectors, jobs, alerts')
s.box(35, 125, 170, 54, 'gray', 'EventStoreAppender', 'SLF4J INFO and above')
s.box(35, 195, 170, 54, 'coral', 'Audited mutations', 'AUDIT, ACCESS_DENIED')
s.group(250, 30, 260, 330, 'EventLog.emit (per Space)')
s.box(265, 55, 230, 54, 'teal', 'SecretScrubber.scrub', 'redact secrets in message/attrs')
s.box(265, 135, 230, 54, 'purple', 'AuditChainLinker', 'seq, prevHash, SHA-256 hash')
s.box(265, 215, 230, 54, 'teal', 'EventStore.append', 'then inspecto_events_total')
s.box(265, 290, 230, 54, 'teal', 'Subscribers and taps', 'alerts, notifications, probes')
s.arrow(380, 109, 380, 135, label='chained types')
s.arrow(380, 189, 380, 215)
s.arrow(380, 269, 380, 290)
for y in (82, 152, 222):
    s.arrow(205, y, 265, 82)
s.group(540, 30, 180, 330, 'events.backend')
s.box(555, 55, 150, 54, 'teal', 'memory', 'InMemoryEventStore')
s.box(555, 135, 150, 54, 'teal', 'parquet', 'ParquetEventStore')
s.box(555, 215, 150, 54, 'teal', 'db / jdbc:', 'DbEventStore')
s.arrow(495, 242, 555, 162)
s.group(750, 30, 170, 330, 'Readers')
s.box(762, 55, 146, 54, 'blue', '/events*', 'EventRoutes')
s.box(762, 135, 146, 54, 'blue', '/audit/*', 'AuditLogRoutes')
s.box(762, 215, 146, 54, 'gray', 'event_prune job', 'retention_days')
s.arrow(705, 162, 762, 82)
s.arrow(705, 162, 762, 162)
s.arrow(705, 162, 762, 242, dash=True)
s.save('docs/stakeholders/modules/assets/events.svg')
