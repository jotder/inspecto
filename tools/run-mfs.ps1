# Starts the Inspecto MFS console (demo sign-in, MFS Space) on port 8091 from the rebuilt bundle.
Set-Location C:\sandbox\inspecto-clean\inspecto-demo
$cp = (Get-Content modules.list) -join ';'
$jvm = @(
    '--enable-native-access=ALL-UNNAMED',
    '-Dcontrol.bind=127.0.0.1', '-Dauth.mode=demo', '-Dobjects.backend=db', '-Devents.backend=parquet', '-Djobs.backend=duckdb',
    '-Dcontrol.port=8091', '-Dspaces.root=C:\sandbox\inspecto-mfs-root', '-Dui.dir=C:\sandbox\inspecto-clean\inspecto-demo\ui',
    '-Dduckdb.extension.dir=duckdb-extensions\windows_amd64',
    '-cp', $cp, 'com.gamma.control.ControlApi'
)
& java @jvm
