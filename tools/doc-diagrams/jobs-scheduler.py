import sys, os; sys.path.insert(0, os.path.dirname(__file__)); from svglib import Svg

s = Svg(940, 320, 'Jobs and scheduler',
        'Four trigger modes reach JobService, which resolves parameters, bounds concurrency and runs a Job Type; '
        'a dry run swaps Platform Services for stand-ins.')
triggers = [('Cron', 'cron: on Scheduler'), ('on_pipeline', 'Consignment committed'),
            ('on_signal', 'Signal + when: guard'), ('Manual', 'POST /jobs/{name}/trigger')]
for i, (t, sub) in enumerate(triggers):
    s.box(20, 30 + i * 68, 180, 56, 'gray', t, sub)
    s.arrow(200, 58 + i * 68, 250, 160)
s.box(250, 120, 190, 80, 'teal', 'JobService', 'ParameterResolver\nrunPermits (default 4)')
s.box(480, 120, 190, 80, 'teal', 'Job Type', 'JobTypeRegistry: builtin,\nclasspath, pack:<owner>')
s.arrow(440, 160, 480, 160)
s.box(720, 40, 200, 60, 'teal', 'Platform Services', 'granted by requires:')
s.box(720, 130, 200, 60, 'coral', 'DryRunServices', 'stand-ins record only')
s.box(720, 220, 200, 60, 'blue', 'Run Log + Artifacts', 'jobs_runs.csv, Signals')
s.arrow(670, 140, 720, 70)
s.arrow(670, 160, 720, 160, dash=True, label='dryRun')
s.arrow(670, 180, 720, 250)
s.save('docs/stakeholders/modules/assets/jobs-scheduler.svg')
