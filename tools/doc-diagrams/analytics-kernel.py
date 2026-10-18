import sys, os; sys.path.insert(0, os.path.dirname(__file__)); from svglib import Svg

s = Svg(940, 320, 'Analytics kernel',
        'platform/inspecto-analytics: pure statistics and SQL builders, consumed by the engine, Steps and add-ons.')
s.group(250, 20, 440, 285, 'platform/inspecto-analytics (inspecto-util only)')
cells = [('Stats / StatsSql', 'mean, quantile, MAD, z'), ('ColumnProfiler', 'one row per column'),
         ('time', 'TimeGrain, TimeSeriesSql'), ('baseline', 'Seasonal, Peer, Robust'),
         ('score', 'ScoreColumns, ScoreScale'), ('forecast', 'Holt-Winters, naive'),
         ('similarity', 'Jaro-Winkler, Levenshtein'), ('accuracy / split', 'Backtest, HoldoutSplit')]
for i, (t, sub) in enumerate(cells):
    s.box(265 + (i % 2) * 210, 40 + (i // 2) * 64, 200, 54, 'teal', t, sub)
s.box(20, 60, 200, 56, 'gray', 'Engine', 'ReportService, VolumeBaseline')
s.box(20, 140, 200, 56, 'gray', 'Steps', 'transform.profile, ...')
s.box(20, 220, 200, 56, 'gray', 'Platform Service', 'analytics (AnalyticsAccess)')
s.box(720, 60, 200, 56, 'purple', 'Anomaly / Risk Scores', 'score output contract')
s.box(720, 140, 200, 56, 'purple', 'Screening', 'similarity kit')
s.box(720, 220, 200, 56, 'purple', 'Link Analysis', 'profile, quantiles')
for y in (88, 168, 248):
    s.arrow(220, y, 265, y)
    s.arrow(720, y, 675, y)
s.save('docs/okf/product/assets/analytics-kernel.svg')
