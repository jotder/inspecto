"""Collectors module diagram (SOLUTION-SUITE-DOCS-1): a Collector's acquire cycle, phases A-F."""
import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from svglib import Svg

s = Svg(940, 400, 'Collector acquire cycle',
        'A Collector bound to one Connection discovers, gates, dedups, fetches to staging, lands into the inbox, then ingest takes over.')
s.box(20, 30, 200, 60, 'gray', 'Remote system', 'SFTP / FTP / DB / S3 / Kafka')
s.box(20, 130, 200, 60, 'blue', 'Connection', '*_connection.toon')
s.box(20, 230, 200, 60, 'blue', 'Collector', 'collector: block in Pipeline')
s.arrow(120, 230, 120, 190)
s.arrow(120, 130, 120, 90)
s.group(250, 20, 670, 290, 'CollectorProcessor.acquire (inspecto-acquire SPI + connectors)')
steps = [('A. Discover', 'include / exclude'), ('B. Stability', 'StabilityGate'),
         ('C. Dedup', 'AcquisitionLedger'), ('D. Gaps', 'GapDetector')]
for i, (t, sub) in enumerate(steps):
    x = 265 + i * 162
    s.box(x, 50, 148, 60, 'teal', t, sub)
    if i:
        s.arrow(x - 14, 80, x, 80)
s.box(265, 150, 310, 60, 'teal', 'E. Fetch to staging', 'RetrievalPlanner, IntegrityChecker')
s.box(595, 150, 310, 60, 'teal', 'Land (atomic rename)', 'staging_dir -> dirs.poll inbox')
s.arrow(575, 180, 595, 180)
s.path('M 832 110 L 832 130 L 420 130 L 420 150')
s.box(265, 235, 640, 60, 'coral', 'F. Retry, circuit breaker, post_action', 'RetryPolicy, CircuitBreaker; RETAIN / DELETE / MOVE / RENAME / TAG after land')
s.arrow(220, 60, 265, 80, dash=True)
s.box(595, 330, 310, 50, 'gray', 'Ingest (parsing-ingest)', None)
s.arrow(750, 210, 750, 235)
s.arrow(750, 295, 750, 330)
s.save('docs/okf/product/assets/collectors.svg')
