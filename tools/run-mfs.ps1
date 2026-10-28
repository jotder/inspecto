# Starts the Inspecto MFS console (demo sign-in, MFS Space) on port 8091 from its own bundle.
# The bundle lives in C:\sandbox\inspecto-mfs-bundle (a -DemoAuth console build, outside every checkout) so other
# sessions' demo servers never hold its jars. Rebuild: package.ps1 -Edition Advanced -DemoAuth -NoBakeDemo in a worktree,
# then replace the folder with that worktree's inspecto-demo while this server is stopped.
$bundle = 'C:\sandbox\inspecto-mfs-bundle'
Set-Location $bundle
$cp = (Get-Content modules.list) -join ';'
$jvm = @(
    '--enable-native-access=ALL-UNNAMED',
    '-Dcontrol.bind=127.0.0.1', '-Dauth.mode=demo', '-Dobjects.backend=db', '-Devents.backend=parquet', '-Djobs.backend=duckdb',
    '-Dcontrol.port=8091', '-Dspaces.root=C:\sandbox\inspecto-mfs-root', "-Dui.dir=$bundle\ui",
    '-Dduckdb.extension.dir=duckdb-extensions\windows_amd64',
    '-cp', $cp, 'com.gamma.control.ControlApi'
)
& java @jvm
