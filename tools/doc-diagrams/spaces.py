import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from svglib import Svg

s = Svg(940, 400, 'Spaces: request path and module gates',
        'A request is bound to a Space by the /spaces/{id} prefix, then passes Installed, Enabled and Permitted gates.')
s.box(20, 30, 180, 56, 'blue', 'HTTP request', '/api/v1/spaces/{id}/...')
s.box(240, 30, 200, 56, 'blue', 'ControlApi.dispatch', 'strip prefix, bind space MDC')
s.box(480, 30, 200, 56, 'teal', 'SpaceManager', 'id -> SpaceContext')
s.box(720, 30, 200, 56, 'teal', 'SpaceContext', 'SpaceRoot + CollectorService')
s.arrow(200, 58, 240, 58); s.arrow(440, 58, 480, 58); s.arrow(680, 58, 720, 58)
s.group(20, 120, 900, 120, 'Three gates (outside feature code)')
s.box(40, 150, 260, 74, 'gray', 'Installed', 'module jar on class path?\nno: 503 CAPABILITY_UNAVAILABLE')
s.box(340, 150, 260, 74, 'purple', 'Enabled (per Space)', 'modules.toon disabled list\nyes: 404 MODULE_DISABLED')
s.box(640, 150, 260, 74, 'coral', 'Permitted (RBAC)', 'capability e.g. canAdminister\nno: 403 (no Subject: no-op)')
s.arrow(300, 187, 340, 187); s.arrow(600, 187, 640, 187)
s.group(20, 270, 900, 110, 'Space root on disk (-Dspaces.root)')
s.box(40, 300, 260, 64, 'teal', 'spaces/<id>/', 'config/ data/ audit/ duckdb/\nspace.toon')
s.box(340, 300, 260, 64, 'purple', 'spaces/_templates/<id>/', 'template.toon + config/ (+ data/)')
s.box(640, 300, 260, 64, 'gray', 'SpaceRoot.legacy()', 'no -Dspaces.root: flat CWD layout')
s.arrow(340, 332, 300, 332)
s.save('docs/okf/product/assets/spaces.svg')
