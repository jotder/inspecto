# preflight.ps1 - check this host before Inspecto starts (DEPLOY-EXPOSURE-KIT-1). One PASS / WARN / FAIL line per
# check; exits 1 when any check FAILs. Run it as the account the service runs as (the Windows service runs as SYSTEM).
#
#   .\bin\preflight.ps1 [-Direct] [-ProxyConfig <file>]
#   .\bin\preflight.ps1 -Endpoint        print the base URL the server will answer on locally, and exit
#   .\bin\preflight.ps1 -SpacesRoot      print the spaces root the server will use, and exit
#
# Default: Inspecto runs behind a reverse proxy on this host (deploy\proxy\; Caddy runs on Windows). The server must
# then listen on loopback only, because /metrics and /health/details answer without sign-in and the proxy is their
# only fence. -Direct: no proxy; Inspecto serves TLS itself (HTTPS_KEYSTORE). A host firewall is then the only fence.
# -ProxyConfig: the proxy's config file; checked to exist and to fence /metrics and /health/details.
#
# Settings are read from the environment the way bin\serve.bat reads them: PORT, SPACES_ROOT (or .\spaces.ref),
# SPACES_DATA_ROOT, HTTPS_KEYSTORE, INSPECTO_JAVA_OPTS (or EXTRA_JAVA_OPTS), AUTH_OIDC_*, INSPECTO_DB_URL. The service
# (a Scheduled Task running as SYSTEM) sees the machine-wide variables: set them there, then open a new shell.
# MIN_FREE_GB (default 5) is the free space each writable folder needs.
[CmdletBinding()]
param(
    [switch]$Direct,
    [string]$ProxyConfig = "",
    [switch]$Endpoint,
    [switch]$SpacesRoot
)
$ErrorActionPreference = "Stop"
# This script lives in bin\; the bundle ROOT is its parent (where serve.bat cd's to as well).
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

function EnvOr([string]$name, [string]$default) {
    $v = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrEmpty($v)) { return $default }
    return $v
}
$port = EnvOr "PORT" "8080"
$extra = EnvOr "INSPECTO_JAVA_OPTS" (EnvOr "EXTRA_JAVA_OPTS" "")
$opts = @($extra -split '\s+' | Where-Object { $_ })
# The value of the LAST -D<name>= in the extra options (the JVM keeps the last one), or "".
function Opt([string]$name) {
    $v = ""
    foreach ($t in $opts) { if ($t.StartsWith("-D$name=")) { $v = $t.Substring($name.Length + 3) } }
    return $v
}
$bind = Opt "control.bind"
$keystore = Opt "https.keystore"
if (-not $keystore) { $keystore = EnvOr "HTTPS_KEYSTORE" "" }
function IsLoopback([string]$h) { return ($h -like "127.*") -or (@("localhost", "::1", "[::1]", "0:0:0:0:0:0:0:1") -contains $h) }
$localHost = "127.0.0.1"
if ($bind -and $bind -ne "0.0.0.0" -and $bind -ne "::") { $localHost = $bind.Trim("[", "]") }
$urlHost = $localHost
if ($urlHost.Contains(":")) { $urlHost = "[$urlHost]" }
$scheme = "http"
if ($keystore) { $scheme = "https" }
# The spaces root, resolved as the server resolves it: SPACES_ROOT, else .\spaces.ref, else .\spaces.
function AbsPath([string]$p) { if ([System.IO.Path]::IsPathRooted($p)) { return $p } return (Join-Path $root $p) }
$spacesEnv = EnvOr "SPACES_ROOT" ""
if ($spacesEnv) { $spacesDir = AbsPath $spacesEnv }
elseif (Test-Path "spaces.ref") { $spacesDir = AbsPath ((Get-Content -Raw "spaces.ref").Trim()) }
else { $spacesDir = Join-Path $root "spaces" }
if ($Endpoint) { Write-Output "${scheme}://${urlHost}:$port"; exit 0 }
if ($SpacesRoot) { Write-Output $spacesDir; exit 0 }

$script:fails = 0; $script:warns = 0; $script:passes = 0
function Row([string]$status, [string]$check, [string]$detail) {
    Write-Output ("[preflight] {0,-4}  {1,-15} {2}" -f $status, $check, $detail)
    switch ($status) { "FAIL" { $script:fails++ } "WARN" { $script:warns++ } "PASS" { $script:passes++ } }
}
$mode = "proxy"
if ($Direct) { $mode = "direct" }
Write-Output "[preflight] bundle $root, mode: $mode, user: $([Environment]::UserName)"

# -- runtime: the bundled Java runtime, else java on PATH; 27 or later --------------------------------------------
$java = $null
if (Test-Path "runtime\bin\java.exe") { $java = (Resolve-Path "runtime\bin\java.exe").Path }
elseif (Get-Command java -ErrorAction SilentlyContinue) { $java = (Get-Command java).Source }
if (-not $java) {
    Row "FAIL" "runtime" "no runtime\bin\java.exe in the bundle and no java on PATH"
} else {
    $ver = ""
    try {
        $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
        $out = (& $java -version 2>&1 | ForEach-Object { "$_" }) -join "`n"
        $ErrorActionPreference = $prev
        if ($out -match 'version "([^"]+)"') { $ver = $Matches[1] }
    } catch { $ver = "" }
    $major = 0
    if ($ver -match '^(\d+)') { $major = [int]$Matches[1] }
    if (-not $ver) { Row "FAIL" "runtime" "$java -version did not report a version" }
    elseif ($major -ge 27) { Row "PASS" "runtime" "$java $ver" }
    else { Row "FAIL" "runtime" "$java is $ver; Inspecto needs Java 27 or later (the bundle's runtime\ has it)" }
}

# -- edition, and what it needs to start --------------------------------------------------------------------------
$edition = ""
if (Test-Path "lib\edition.properties") {
    foreach ($l in Get-Content "lib\edition.properties") { if ($l -match '^edition=(.*)$') { $edition = $Matches[1].Trim() } }
}
if (-not $edition) {
    Row "FAIL" "edition" "no lib\edition.properties - is this a bundle folder?"
} elseif ($edition -eq "Basic") {
    Row "PASS" "edition" "Basic"
    Row "WARN" "sign-in" "Basic has no sign-in: everyone the proxy lets through has full access. Allow only trusted networks at the proxy, or use Standard"
} elseif (@("Standard", "Advanced", "Preview") -contains $edition) {
    Row "PASS" "edition" $edition
    $disc = EnvOr "AUTH_OIDC_DISCOVERY_URL" ""
    $iss = EnvOr "AUTH_OIDC_ISSUER" ""
    $jwks = EnvOr "AUTH_OIDC_JWKS_URI" ""
    if ($disc -or ($iss -and $jwks)) { Row "PASS" "sign-in" "OIDC $(if ($disc) { $disc } else { $iss })" }
    else { Row "FAIL" "sign-in" "$edition does not start without AUTH_OIDC_DISCOVERY_URL, or AUTH_OIDC_ISSUER and AUTH_OIDC_JWKS_URI" }
    if ($edition -eq "Advanced") {
        if (EnvOr "INSPECTO_DB_URL" "") { Row "PASS" "database" "INSPECTO_DB_URL is set" }
        else { Row "FAIL" "database" "Advanced does not start without INSPECTO_DB_URL (PostgreSQL)" }
    }
} else {
    Row "FAIL" "edition" "lib\edition.properties names an unknown edition '$edition'"
}

# -- the listen address -------------------------------------------------------------------------------------------
if ($mode -eq "proxy") {
    if (-not $bind) {
        Row "FAIL" "bind" "-Dcontrol.bind is not set, so the server listens on every interface and /metrics and /health/details are reachable around the proxy. Add -Dcontrol.bind=127.0.0.1 to INSPECTO_JAVA_OPTS"
    } elseif (IsLoopback $bind) {
        Row "PASS" "bind" "-Dcontrol.bind=$bind (loopback: only the proxy reaches the server)"
    } else {
        Row "FAIL" "bind" "-Dcontrol.bind=$bind is not a loopback address, so the server is reachable around the proxy. Use -Dcontrol.bind=127.0.0.1"
    }
    $tp = Opt "control.trustedProxies"
    $tpList = @($tp -split ',' | ForEach-Object { $_.Trim() })
    if (($tpList -contains "127.0.0.1") -or ($tpList -contains "::1") -or @($tpList | Where-Object { $_ -like "127.0.0.1/*" }).Count -gt 0) {
        Row "PASS" "trusted-proxies" "-Dcontrol.trustedProxies=$tp"
    } else {
        Row "WARN" "trusted-proxies" "-Dcontrol.trustedProxies does not name 127.0.0.1: the audit trail and the per-address rate limit see every caller as the proxy. Add -Dcontrol.trustedProxies=127.0.0.1"
    }
} else {
    if ($bind -and (IsLoopback $bind)) {
        Row "WARN" "bind" "-Dcontrol.bind=$bind is loopback: with -Direct nothing outside this host can reach the server"
    } else {
        $where = "every interface"
        if ($bind) { $where = "-Dcontrol.bind=$bind" }
        Row "WARN" "bind" "${where}: /metrics and /health/details answer anyone who reaches port $port. Fence the port with the Windows firewall, or use a proxy"
    }
}

# -- TLS: the keystore Inspecto serves, or the proxy that ends TLS ------------------------------------------------
if ($keystore) {
    if (Test-Path -LiteralPath $keystore -PathType Leaf) { Row "PASS" "tls-keystore" $keystore }
    else { Row "FAIL" "tls-keystore" "HTTPS_KEYSTORE / -Dhttps.keystore names $keystore, which does not exist: the server will not start" }
} elseif ($mode -eq "direct") {
    Row "FAIL" "tls-keystore" "-Direct without HTTPS_KEYSTORE: the server would serve plain HTTP to the network. Set HTTPS_KEYSTORE (PKCS12) and HTTPS_KEYSTORE_PASSWORD"
}
# The fence as a rule, not the words (as preflight.sh): comments are dropped, then nginx needs a location naming both paths
# whose block says deny all or return 403, and Caddy a named matcher whose path_regexp names both, answered respond @it 403.
function Test-Fenced([string]$file) {
    $inLoc = $false; $inM = ""; $m = ""; $ok = $false
    $fenced = @{}; $refused = @{}
    foreach ($raw in Get-Content -LiteralPath $file) {
        $l = $raw -replace '#.*$', ''
        $both = ($l -match 'metrics') -and ($l -match 'health/details')
        if ($l -match 'location' -and $both) { $inLoc = $true }
        if ($inLoc -and ($l -match 'deny\s+all' -or $l -match 'return\s+403')) { $ok = $true }
        if ($inLoc -and $l.Contains('}')) { $inLoc = $false }
        if ($l -match '^\s*(@[A-Za-z0-9_-]+)') { $m = $Matches[1]; if ($l.Contains('{')) { $inM = $m } }
        if ($both -and $l -match 'path_regexp') { if ($inM) { $fenced[$inM] = $true } else { $fenced[$m] = $true } }
        if ($inM -and $l.Contains('}')) { $inM = "" }
        if ($l -match '^\s*(respond|error)\s+(@[A-Za-z0-9_-]+)\s+403\b') { $refused[$Matches[2]] = $true }
    }
    foreach ($k in $fenced.Keys) { if ($refused.ContainsKey($k)) { $ok = $true } }
    return $ok
}
if ($mode -eq "proxy") {
    if ($ProxyConfig) {
        if (-not (Test-Path -LiteralPath $ProxyConfig -PathType Leaf)) {
            Row "FAIL" "proxy" "no proxy config at $ProxyConfig"
        } else {
            if (Test-Fenced $ProxyConfig) { Row "PASS" "proxy" "$ProxyConfig refuses /metrics and /health/details" }
            else { Row "FAIL" "proxy" "$ProxyConfig has no rule refusing /metrics and /health/details (nginx: a location naming both with deny all or return 403; Caddy: a matcher naming both with respond <matcher> 403) - start from deploy\proxy\" }
        }
    } else {
        $found = $null
        foreach ($p in "caddy", "nginx") { $c = Get-Command $p -ErrorAction SilentlyContinue; if ($c) { $found = $c.Source; break } }
        if ($found) { Row "PASS" "proxy" "$found found (pass -ProxyConfig <file> to check its fence)" }
        else { Row "FAIL" "proxy" "no caddy or nginx on PATH: install one with a config from deploy\proxy\, or pass -ProxyConfig <file>" }
    }
}

# -- the folders the server writes, and their free space ----------------------------------------------------------
$minFreeGb = [int](EnvOr "MIN_FREE_GB" "5")
function CheckDir([string]$label, [string]$dir) {
    if (-not (Test-Path -LiteralPath $dir -PathType Container)) { Row "FAIL" $label "$dir does not exist"; return }
    $probe = Join-Path $dir ".preflight-$PID"
    try { [System.IO.File]::WriteAllText($probe, ""); Remove-Item -LiteralPath $probe -Force }
    catch { Row "FAIL" $label "$dir is not writable by $([Environment]::UserName)"; return }
    try {
        $full = (Resolve-Path -LiteralPath $dir).ProviderPath
        $free = [System.IO.DriveInfo]::new([System.IO.Path]::GetPathRoot($full)).AvailableFreeSpace
    } catch { Row "WARN" $label "$dir is writable; free space unknown"; return }
    $gb = [math]::Floor($free / 1GB)
    if ($free -lt ([int64]$minFreeGb * 1GB)) { Row "FAIL" $label "$dir has $gb GB free, under MIN_FREE_GB=$minFreeGb" }
    else { Row "PASS" $label "$dir writable, $gb GB free" }
}
CheckDir "spaces-root" $spacesDir
$dataRoot = EnvOr "SPACES_DATA_ROOT" ""
if ($dataRoot) { CheckDir "data-root" (AbsPath $dataRoot) }
CheckDir "logs" (Join-Path $root "logs")

# -- the port -----------------------------------------------------------------------------------------------------
# A listener on the port at any address.
$listeners = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
if (@($listeners | Where-Object { $_.Port -eq [int]$port }).Count -gt 0) { Row "FAIL" "port" "something already listens on port $port - is Inspecto already running? Stop it, or set PORT" }
else { Row "PASS" "port" "port $port is free" }

Write-Output "[preflight] $($script:passes) PASS, $($script:warns) WARN, $($script:fails) FAIL"
if ($script:fails -gt 0) { Write-Output "[preflight] NOT READY - fix every FAIL before starting Inspecto"; exit 1 }
Write-Output "[preflight] READY"
exit 0
