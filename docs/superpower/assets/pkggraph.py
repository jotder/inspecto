"""Package-edge graph for com.gamma.*, comments stripped, with SCC detection.

Usage:  python docs/superpower/assets/pkggraph.py <src-root>
  e.g.  python docs/superpower/assets/pkggraph.py .                       (whole reactor)
        python docs/superpower/assets/pkggraph.py inspecto-engine/src/main/java

Used to measure and verify the C1/C2 cycle cuts (2026-08-27) and the whole-reactor
census recorded in docs/okf/backend/modules/reactor.md.

Deliberate rules (each one exists because a naive version got it wrong here before):
  - strip block comments, line comments AND text blocks BEFORE scanning, so a
    javadoc {@link} is never counted as a compile edge;
  - count BOTH `import com.gamma.x.Y;` and inline fully-qualified `com.gamma.x.Y`;
  - a file's own package never counts as an edge to itself.
"""
import os, re, sys, json
from collections import defaultdict

ROOT = sys.argv[1] if len(sys.argv) > 1 else '.'
MOVES = json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}   # {"Simple.java": "com.gamma.new"}

def strip(src):
    out, i, n = [], 0, len(src)
    while i < n:
        if src.startswith('"""', i):                      # text block
            j = src.find('"""', i + 3)
            i = n if j < 0 else j + 3
        elif src.startswith('//', i):
            j = src.find('\n', i);  i = n if j < 0 else j
        elif src.startswith('/*', i):
            j = src.find('*/', i + 2); i = n if j < 0 else j + 2
        elif src[i] == '"':
            i += 1
            while i < n and src[i] != '"':
                i += 2 if src[i] == chr(92) else 1
            i += 1
        else:
            out.append(src[i]); i += 1
    return ''.join(out)

PKG  = re.compile(r'^\s*package\s+([\w.]+)\s*;', re.M)
IMP  = re.compile(r'^\s*import\s+(?:static\s+)?(com\.gamma\.[\w.]+)\s*;', re.M)
FQN  = re.compile(r'\b(com\.gamma\.(?:[a-z][\w]*\.)*)[A-Z]\w*')

def pkg_of(fqn):        # com.gamma.a.b.Type -> com.gamma.a.b
    return fqn.rsplit('.', 1)[0]

files, edges = {}, defaultdict(set)
for dirpath, _, names in os.walk(ROOT):
    if 'worktrees' in dirpath.replace(chr(92), '/') or (os.sep + 'test' + os.sep) in dirpath:
        continue
    for nm in names:
        if not nm.endswith('.java'):
            continue
        p = os.path.join(dirpath, nm)
        raw = open(p, encoding='utf-8', errors='replace').read()
        m = PKG.search(raw)
        if not m:
            continue
        own = MOVES.get(nm, m.group(1))
        body = strip(raw)
        tgts = {pkg_of(x) for x in IMP.findall(body)}
        tgts |= {g.rstrip('.') for g in FQN.findall(body)}
        # a moved type keeps its identity: rewrite edges that pointed at its old home
        files[nm] = own
        for t in tgts:
            if t and t != own and t.startswith('com.gamma'):
                edges[own].add(t)

# a move changes where OTHER files must import from
for nm, newpkg in MOVES.items():
    pass

def sccs(g):
    idx, low, on, stack, out, counter = {}, {}, set(), [], [], [0]
    def go(v):
        work = [(v, iter(g.get(v, ())))]
        idx[v] = low[v] = counter[0]; counter[0] += 1
        stack.append(v); on.add(v)
        while work:
            node, it = work[-1]
            adv = False
            for w in it:
                if w not in idx:
                    idx[w] = low[w] = counter[0]; counter[0] += 1
                    stack.append(w); on.add(w)
                    work.append((w, iter(g.get(w, ())))); adv = True; break
                elif w in on:
                    low[node] = min(low[node], idx[w])
            if adv:
                continue
            work.pop()
            if work:
                low[work[-1][0]] = min(low[work[-1][0]], low[node])
            if low[node] == idx[node]:
                comp = []
                while True:
                    w = stack.pop(); on.discard(w); comp.append(w)
                    if w == node: break
                if len(comp) > 1:
                    out.append(sorted(comp))
    for v in list(g):
        if v not in idx:
            go(v)
    return out

comps = sccs(edges)
print(f"packages: {len(edges)}   files: {len(files)}   cycles(SCC>1): {len(comps)}")
for c in sorted(comps, key=len, reverse=True):
    print(f"\n  SCC ({len(c)}): {', '.join(s.replace('com.gamma.','') for s in c)}")
    inside = set(c)
    for a in c:
        for b in sorted(edges[a] & inside):
            print(f"      {a.replace('com.gamma.','')} -> {b.replace('com.gamma.','')}")
