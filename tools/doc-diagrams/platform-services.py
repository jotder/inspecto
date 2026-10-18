import sys, os; sys.path.insert(0, os.path.dirname(__file__)); from svglib import Svg
s = Svg(940, 300, 'Grant and dry-run path', 'Boot registry, declared grant on JobContext.services(), dry-run stand-ins in find order.')
s.box(20, 40, 190, 60, 'teal', 'PlatformServiceRegistry', 'ten ids bound at boot')
s.box(250, 40, 170, 60, 'purple', 'requires: [...]', 'checked at registration')
s.box(460, 40, 200, 60, 'teal', 'JobContext.services()', 'granted ids only')
s.box(700, 40, 220, 60, 'blue', 'Job / pack Step', 'find(Class)')
s.arrow(210, 70, 250, 70); s.arrow(420, 70, 460, 70); s.arrow(660, 70, 700, 70)
s.group(20, 140, 900, 140, 'DryRunServices.find order (dry run only)')
steps = [('not granted', 'empty'), ('built-in', 'notif/incid/\nalerts/link-index'), ('pack stand-in', 'ServiceProvider\n.dryRun'), ('built-in', 'mail, objects'), ('real service', 'read-only')]
for i, (t, sub) in enumerate(steps):
    s.box(40 + i * 176, 170, 160, 90, 'coral' if i in (1, 2, 3) else 'gray', t, sub)
    if i: s.arrow(40 + i * 176 - 16, 215, 40 + i * 176, 215)
s.save('docs/okf/product/assets/platform-services.svg')
