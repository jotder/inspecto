import sys, os; sys.path.insert(0, os.path.dirname(__file__)); from svglib import Svg

s = Svg(940, 310, 'Storage classes',
        'Three data classes per Space: business data as Parquet, configuration as TOON with history, '
        'operational data in DuckDB or Postgres.')
s.box(330, 20, 280, 50, 'gray', 'Space root (DirSpaceRoot)', 'spaces/<id>/')
s.box(20, 110, 280, 80, 'teal', 'Business data', 'data/<store>/database/**\nHive-partitioned Parquet')
s.box(330, 110, 280, 80, 'teal', 'Configuration', 'config/**/*.toon\n.history/ snapshots')
s.box(640, 110, 280, 80, 'teal', 'Operational data', 'duckdb/<family>.db\nor Postgres by selection')
for x in (160, 470, 780):
    s.arrow(470, 70, x, 110)
s.box(20, 230, 135, 60, 'blue', 'Sinks', 'sinks[], DuckLake')
s.box(165, 230, 135, 60, 'blue', 'Reads', 'SqlViews')
s.box(330, 230, 280, 60, 'blue', 'ComponentStore', 'ConfigSafetyValidator on write')
s.box(640, 230, 280, 60, 'blue', 'ServiceStores, OperationalDb', 'one file per family')
s.arrow(87, 230, 87, 190)
s.arrow(232, 190, 232, 230)
s.arrow(470, 230, 470, 190)
s.arrow(780, 230, 780, 190)
s.save('docs/okf/product/assets/storage.svg')
