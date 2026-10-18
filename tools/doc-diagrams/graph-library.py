import sys, os; sys.path.insert(0, os.path.dirname(__file__)); from svglib import Svg

s = Svg(940, 300, 'Graph library',
        'platform/inspecto-graph: plain-JDK graph algorithms over node and edge ids, guarded by RunControl.')
s.box(20, 50, 210, 60, 'purple', 'Link Analysis', 'InMemoryGraphEngine,\nSqlGraphEngine')
s.box(20, 140, 210, 60, 'teal', 'Entity store', 'EntityRegistry')
s.box(20, 230, 210, 50, 'gray', 'SPA twin', 'graph-analysis.ts')
s.group(270, 20, 420, 270, 'platform/inspecto-graph (no inspecto-* dependency)')
cells = [('GraphPaths', 'shortestPath, maxFlow'), ('GraphCentrality', 'betweenness, closeness'),
         ('GraphIterative', 'pageRank, louvain'), ('GraphStructure', 'kCore, bridges, cliques'),
         ('GraphPropagation', 'propagatedRisk'), ('GraphSuspicion', 'suspicionScore'),
         ('TemporalMotifMatcher', 'fan-in / fan-out'), ('UnionFind', 'disjoint sets')]
for i, (t, sub) in enumerate(cells):
    s.box(285 + (i % 2) * 200, 40 + (i // 2) * 62, 190, 52, 'teal', t, sub)
s.box(730, 110, 190, 70, 'coral', 'RunControl', 'cancel, deadline, budget\n-> GraphAborted')
s.arrow(230, 80, 285, 80)
s.arrow(230, 170, 485, 250)
s.arrow(230, 255, 285, 140, dash=True, label='parity')
s.arrow(730, 145, 675, 145)
s.save('docs/okf/product/assets/graph-library.svg')
