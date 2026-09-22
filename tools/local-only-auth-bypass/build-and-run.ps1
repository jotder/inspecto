# Local-only: compiles LocalTestAuthenticator.java against inspecto-deploy/inspecto.jar, packages it,
# and launches ControlApi with a classpath that fakes auth and skips Postgres entirely.
# NEVER use this for anything but local feature testing. See
# docs/okf/backend/editions/local-testing-without-iam.md for the full explanation.
param(
    [string]$DeployDir = "$PSScriptRoot\..\..\inspecto-deploy",
    [string]$JavaHome = "C:\sandbox\.graalvm-cache\jdk-27-win",
    [int]$Port = 8080,
    [switch]$Enterprise   # also put inspecto-policy.jar on the classpath if it exists
)

$ErrorActionPreference = "Stop"
$src = "$PSScriptRoot"
$build = "$PSScriptRoot\build"
Remove-Item -Recurse -Force $build -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $build | Out-Null

& "$JavaHome\bin\javac.exe" -cp "$DeployDir\inspecto.jar" -d $build "$src\com\gamma\control\LocalTestAuthenticator.java"
Copy-Item -Recurse "$src\META-INF" "$build\META-INF"
& "$JavaHome\bin\jar.exe" cf "$PSScriptRoot\fake-auth.jar" -C $build .

$cp = @(
    "inspecto.jar", "$PSScriptRoot\fake-auth.jar",
    "inspecto-connectors.jar", "inspecto-notify-channels.jar", "inspecto-backup.jar",
    "inspecto-geo-link.jar", "inspecto-exchange.jar", "inspecto-metrics.jar",
    "inspecto-events.jar", "inspecto-ops.jar", "inspecto-agent.jar"
)
if ($Enterprise -and (Test-Path "$DeployDir\inspecto-policy.jar")) { $cp += "inspecto-policy.jar" }
$cpJoined = ($cp | ForEach-Object { if ($_ -like "*\*") { $_ } else { "$DeployDir\$_" } }) -join ";"

Push-Location $DeployDir
$javaArgs = @(
    "--enable-native-access=ALL-UNNAMED",
    "-Dcontrol.port=$Port",
    "-Dspaces.root=spaces",
    "-Dui.dir=./ui",
    "-Dduckdb.extension.dir=duckdb-extensions\windows_amd64",
    "-Dauth.mode=none",
    "-cp", $cpJoined,
    "com.gamma.control.ControlApi"
)
& "$DeployDir\runtime\bin\java.exe" @javaArgs
Pop-Location
