"""Which FILE holds each edge inside a given set of packages?

Usage: python docs/superpower/assets/edgeholders.py com.gamma.etl com.gamma.etl.unpack

Reuses pkggraph's stripper (comments, text blocks and string literals removed
before scanning) so a javadoc {@link} is never reported as a compile edge --
that phantom has produced a false blocker in this repo more than once.
"""
import os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
_src = open(os.path.join(HERE, 'pkggraph.py'), encoding='utf-8').read()
# exec ONLY the strip() function body -- pkggraph's module header reads sys.argv,
# so exec'ing the whole head from another script breaks on this script's argv.
_fn = _src[_src.index('def strip'):_src.index('PKG  = re.compile')]
_ns = {}
exec(_fn, {}, _ns)
strip = _ns['strip']

PKG = re.compile(r'^\s*package\s+([\w.]+)\s*;', re.M)
targets = set(sys.argv[1:])
if not targets:
    sys.exit(__doc__)

rows = {}
for dp, _, names in os.walk('.'):
    q = dp.replace(chr(92), '/')
    if 'worktrees' in q or '/target/' in q or '/src/test/' in q:
        continue
    for nm in names:
        if not nm.endswith('.java'):
            continue
        path = os.path.join(dp, nm)
        raw = open(path, encoding='utf-8', errors='replace').read()
        m = PKG.search(raw)
        if not m or m.group(1) not in targets:
            continue
        own, body = m.group(1), strip(raw)
        for t in targets:
            if t == own:
                continue
            hits = [l.strip() for l in body.split('\n') if re.search(re.escape(t) + r'\.[A-Z]', l)]
            if hits:
                rows.setdefault((own, t), []).append((nm, len(hits), hits[0][:88]))

for (a, b), fs in sorted(rows.items()):
    print(f"\n{a}  ->  {b}   ({len(fs)} file(s))")
    for nm, n, first in sorted(fs, key=lambda x: -x[1]):
        print(f"    {nm:38s} {n:2d}x   {first}")
