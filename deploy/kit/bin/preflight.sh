#!/usr/bin/env bash
# preflight.sh - check this host before Inspecto starts (DEPLOY-EXPOSURE-KIT-1). One PASS / WARN / FAIL line per
# check; exit 1 when any check FAILs. Run it as the account the service runs as:  sudo -u inspecto bash bin/preflight.sh
#
#   bash bin/preflight.sh [--direct] [--proxy-config <file>] [--env-file <file>]
#   bash bin/preflight.sh --endpoint        print the base URL the server will answer on locally, and exit
#   bash bin/preflight.sh --spaces-root     print the spaces root the server will use, and exit
#
# Default: Inspecto runs behind a reverse proxy on this host (deploy/proxy/). The server must then listen on
# loopback only, because /metrics and /health/details answer without sign-in and the proxy is their only fence.
# --direct: no proxy; Inspecto serves TLS itself (HTTPS_KEYSTORE). A host firewall is then the only fence.
# --proxy-config: the proxy's config file; checked to exist and to fence /metrics and /health/details.
#
# Settings are read the way bin/serve.sh reads them - PORT, SPACES_ROOT (or ./spaces.ref), SPACES_DATA_ROOT,
# HTTPS_KEYSTORE, INSPECTO_JAVA_OPTS (or EXTRA_JAVA_OPTS), AUTH_OIDC_*, INSPECTO_DB_URL - from the environment, then
# from inspecto.env in the bundle folder when it exists (the systemd service reads that file, not your shell), or
# from --env-file. MIN_FREE_GB (default 5) is the free space each writable folder needs.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 2
ROOT="$(pwd)"

MODE="proxy"
PROXY_CONFIG=""
ENV_FILE=""
ENDPOINT_ONLY=""
SPACES_ONLY=""
while [ $# -gt 0 ]; do
    case "$1" in
        --direct) MODE="direct" ;;
        --proxy-config) PROXY_CONFIG="${2:-}"; shift ;;
        --env-file) ENV_FILE="${2:-}"; shift ;;
        --endpoint) ENDPOINT_ONLY="yes" ;;
        --spaces-root) SPACES_ONLY="yes" ;;
        -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
        *) echo "[preflight] unknown option $1" >&2; exit 2 ;;
    esac
    shift
done

# KEY=VALUE lines, as systemd's EnvironmentFile reads them; nothing in the file is executed.
[ -z "$ENV_FILE" ] && [ -f inspecto.env ] && ENV_FILE="inspecto.env"
if [ -n "$ENV_FILE" ]; then
    [ -f "$ENV_FILE" ] || { echo "[preflight] no such env file: $ENV_FILE" >&2; exit 2; }
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%$'\r'}"
        case "$line" in ''|'#'*) continue ;; esac
        key="${line%%=*}"; val="${line#*=}"
        case "$key" in *[!A-Za-z0-9_]*|'') continue ;; esac
        case "$val" in \"*\") val="${val#\"}"; val="${val%\"}" ;; \'*\') val="${val#\'}"; val="${val%\'}" ;; esac
        export "$key=$val"
    done < "$ENV_FILE"
fi

PORT="${PORT:-8080}"
EXTRA_OPTS="${INSPECTO_JAVA_OPTS:-${EXTRA_JAVA_OPTS:-}}"
read -r -a OPTS <<< "$EXTRA_OPTS"
# The value of the LAST -D<name>= in the extra options (the JVM keeps the last one), or empty.
opt() {
    local v="" t
    for t in "${OPTS[@]+"${OPTS[@]}"}"; do case "$t" in "-D$1="*) v="${t#-D$1=}" ;; esac; done
    printf '%s' "$v"
}
BIND="$(opt control.bind)"
KEYSTORE="$(opt https.keystore)"; KEYSTORE="${KEYSTORE:-${HTTPS_KEYSTORE:-}}"
is_loopback() { case "$1" in 127.*|localhost|::1|'[::1]'|0:0:0:0:0:0:0:1) return 0 ;; *) return 1 ;; esac; }
# Where the server answers on this host: its bind address when that is a specific one, else loopback.
LOCAL_HOST="127.0.0.1"
[ -n "$BIND" ] && [ "$BIND" != "0.0.0.0" ] && [ "$BIND" != "::" ] && LOCAL_HOST="$BIND"
case "$LOCAL_HOST" in *:*) case "$LOCAL_HOST" in \[*) ;; *) LOCAL_HOST="[$LOCAL_HOST]" ;; esac ;; esac
SCHEME="http"; [ -n "$KEYSTORE" ] && SCHEME="https"
# The spaces root, resolved as the server resolves it: SPACES_ROOT, else ./spaces.ref, else ./spaces.
abs() { case "$1" in /*|[A-Za-z]:*) printf '%s' "$1" ;; *) printf '%s/%s' "$ROOT" "$1" ;; esac; }
if [ -n "${SPACES_ROOT:-}" ]; then SPACES_DIR="$(abs "$SPACES_ROOT")"
elif [ -f spaces.ref ]; then SPACES_DIR="$(abs "$(tr -d '\r\n' < spaces.ref | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')")"
else SPACES_DIR="$ROOT/spaces"; fi
if [ -n "$ENDPOINT_ONLY" ]; then echo "${SCHEME}://${LOCAL_HOST}:${PORT}"; exit 0; fi
if [ -n "$SPACES_ONLY" ]; then echo "$SPACES_DIR"; exit 0; fi

FAILS=0; WARNS=0; PASSES=0
row() {   # row PASS|WARN|FAIL <check> <detail>
    printf '[preflight] %-4s  %-15s %s\n' "$1" "$2" "$3"
    case "$1" in FAIL) FAILS=$((FAILS + 1)) ;; WARN) WARNS=$((WARNS + 1)) ;; PASS) PASSES=$((PASSES + 1)) ;; esac
    return 0
}
echo "[preflight] bundle $ROOT, mode: $MODE, user: $(id -un 2>/dev/null || echo unknown)${ENV_FILE:+, settings from $ENV_FILE}"

# -- runtime: the bundled Java runtime, else java on PATH; 27 or later -----------------------------------------------
JAVA="java"; [ -x runtime/bin/java ] && JAVA="runtime/bin/java"
if ! command -v "$JAVA" >/dev/null 2>&1 && [ ! -x "$JAVA" ]; then
    row FAIL runtime "no runtime/bin/java in the bundle and no java on PATH"
else
    jv="$("$JAVA" -version 2>&1 | sed -n 's/.*version "\([^"]*\)".*/\1/p' | head -n 1)"
    major="${jv%%[.+-]*}"
    case "$major" in
        ''|*[!0-9]*) row FAIL runtime "$JAVA -version did not report a version" ;;
        *) if [ "$major" -ge 27 ]; then row PASS runtime "$JAVA $jv"
           else row FAIL runtime "$JAVA is $jv; Inspecto needs Java 27 or later (the bundle's runtime/ has it)"; fi ;;
    esac
fi

# -- edition, and what it needs to start -----------------------------------------------------------------------------
EDITION=""
[ -f lib/edition.properties ] && EDITION="$(sed -n 's/^edition=//p' lib/edition.properties | tr -d '\r')"
case "$EDITION" in
    '') row FAIL edition "no lib/edition.properties - is this a bundle folder?" ;;
    Basic)
        row PASS edition "Basic"
        row WARN sign-in "Basic has no sign-in: everyone the proxy lets through has full access. Allow only trusted networks at the proxy, or use Standard" ;;
    Standard|Advanced|Preview)
        row PASS edition "$EDITION"
        if [ -n "${AUTH_OIDC_DISCOVERY_URL:-}" ] || { [ -n "${AUTH_OIDC_ISSUER:-}" ] && [ -n "${AUTH_OIDC_JWKS_URI:-}" ]; }; then
            row PASS sign-in "OIDC ${AUTH_OIDC_DISCOVERY_URL:-${AUTH_OIDC_ISSUER:-}}"
        else
            row FAIL sign-in "$EDITION does not start without AUTH_OIDC_DISCOVERY_URL, or AUTH_OIDC_ISSUER and AUTH_OIDC_JWKS_URI"
        fi
        if [ "$EDITION" = Advanced ]; then
            if [ -n "${INSPECTO_DB_URL:-}" ]; then row PASS database "INSPECTO_DB_URL is set"
            else row FAIL database "Advanced does not start without INSPECTO_DB_URL (PostgreSQL)"; fi
        fi ;;
    *) row FAIL edition "lib/edition.properties names an unknown edition '$EDITION'" ;;
esac

# -- the listen address -----------------------------------------------------------------------------------------------
if [ "$MODE" = proxy ]; then
    if [ -z "$BIND" ]; then
        row FAIL bind "-Dcontrol.bind is not set, so the server listens on every interface and /metrics and /health/details are reachable around the proxy. Add -Dcontrol.bind=127.0.0.1 to INSPECTO_JAVA_OPTS"
    elif is_loopback "$BIND"; then
        row PASS bind "-Dcontrol.bind=$BIND (loopback: only the proxy reaches the server)"
    else
        row FAIL bind "-Dcontrol.bind=$BIND is not a loopback address, so the server is reachable around the proxy. Use -Dcontrol.bind=127.0.0.1"
    fi
    TP="$(opt control.trustedProxies)"
    case ",${TP// /}," in
        *,127.0.0.1,*|*,127.0.0.1/*|*,::1,*) row PASS trusted-proxies "-Dcontrol.trustedProxies=$TP" ;;
        *) row WARN trusted-proxies "-Dcontrol.trustedProxies does not name 127.0.0.1: the audit trail and the per-address rate limit see every caller as the proxy. Add -Dcontrol.trustedProxies=127.0.0.1" ;;
    esac
else
    if [ -n "$BIND" ] && is_loopback "$BIND"; then
        row WARN bind "-Dcontrol.bind=$BIND is loopback: with --direct nothing outside this host can reach the server"
    else
        where="every interface"; [ -n "$BIND" ] && where="-Dcontrol.bind=$BIND"
        row WARN bind "$where: /metrics and /health/details answer anyone who reaches port $PORT. Fence the port with the host firewall, or use a proxy"
    fi
fi

# -- TLS: the keystore Inspecto serves, or the proxy that ends TLS ----------------------------------------------------
if [ -n "$KEYSTORE" ]; then
    if [ -f "$KEYSTORE" ] && [ -r "$KEYSTORE" ]; then row PASS tls-keystore "$KEYSTORE"
    else row FAIL tls-keystore "HTTPS_KEYSTORE / -Dhttps.keystore names $KEYSTORE, which does not exist or cannot be read: the server will not start"; fi
elif [ "$MODE" = direct ]; then
    row FAIL tls-keystore "--direct without HTTPS_KEYSTORE: the server would serve plain HTTP to the network. Set HTTPS_KEYSTORE (PKCS12) and HTTPS_KEYSTORE_PASSWORD"
fi
# The fence as a rule, not the words: comments are dropped, then nginx needs a location naming both paths whose block
# says `deny all` or `return 403`, and Caddy a named matcher whose path_regexp names both, answered `respond @it 403`.
fenced() {
    awk '
        { sub(/#.*/, "") }
        /location/ && /metrics/ && /health\/details/ { inloc = 1 }
        inloc && (/deny[ \t]+all/ || /return[ \t]+403/) { ok = 1 }
        inloc && /}/ { inloc = 0 }
        /^[ \t]*@[A-Za-z0-9_-]+/ { m = $1; if (/[{]/) inm = m }
        /path_regexp/ && /metrics/ && /health\/details/ { f[inm != "" ? inm : m] = 1 }
        inm != "" && /}/ { inm = "" }
        /^[ \t]*(respond|error)[ \t]+@/ && /[ \t]403/ { r[$2] = 1 }
        END { for (k in f) if (k in r) ok = 1; exit ok ? 0 : 1 }
    ' "$1"
}
if [ "$MODE" = proxy ]; then
    if [ -n "$PROXY_CONFIG" ]; then
        if [ ! -r "$PROXY_CONFIG" ]; then
            row FAIL proxy "no readable proxy config at $PROXY_CONFIG"
        elif fenced "$PROXY_CONFIG"; then
            row PASS proxy "$PROXY_CONFIG refuses /metrics and /health/details"
        else
            row FAIL proxy "$PROXY_CONFIG has no rule refusing /metrics and /health/details (nginx: a location naming both with deny all or return 403; Caddy: a matcher naming both with respond <matcher> 403) - start from deploy/proxy/"
        fi
    else
        found=""
        for p in nginx caddy; do command -v "$p" >/dev/null 2>&1 && { found="$(command -v "$p")"; break; }; done
        if [ -n "$found" ]; then row PASS proxy "$found found (pass --proxy-config <file> to check its fence)"
        else row FAIL proxy "no nginx or caddy on this host: install one with a config from deploy/proxy/, or pass --proxy-config <file>"; fi
    fi
fi

# -- the folders the server writes, and their free space -------------------------------------------------------------
MIN_FREE_GB="${MIN_FREE_GB:-5}"
check_dir() {   # check_dir <label> <dir>
    local d="$2" probe free_kb need_kb
    if [ ! -d "$d" ]; then row FAIL "$1" "$d does not exist"; return; fi
    probe="$d/.preflight-$$"
    if ! ( : > "$probe" ) 2>/dev/null; then row FAIL "$1" "$d is not writable by $(id -un 2>/dev/null || echo this user)"; return; fi
    rm -f "$probe"
    free_kb="$(df -Pk "$d" 2>/dev/null | awk 'NR==2 { print $4 }')"
    need_kb=$((MIN_FREE_GB * 1024 * 1024))
    if [ -z "$free_kb" ]; then row WARN "$1" "$d is writable; free space unknown"
    elif [ "$free_kb" -lt "$need_kb" ]; then row FAIL "$1" "$d has $((free_kb / 1024 / 1024)) GB free, under MIN_FREE_GB=$MIN_FREE_GB"
    else row PASS "$1" "$d writable, $((free_kb / 1024 / 1024)) GB free"; fi
}
check_dir spaces-root "$SPACES_DIR"
[ -n "${SPACES_DATA_ROOT:-}" ] && check_dir data-root "$(abs "$SPACES_DATA_ROOT")"
check_dir logs "$ROOT/logs"

# -- the port ---------------------------------------------------------------------------------------------------------
# A listener on the port at any address (ss, else netstat), else a connect to loopback.
port_listening() {
    local pat="[:.]$1[[:space:]]"
    if command -v ss >/dev/null 2>&1; then ss -ltn 2>/dev/null | grep -Eq "$pat"
    elif command -v netstat >/dev/null 2>&1; then netstat -an 2>/dev/null | grep -E 'LISTEN' | grep -Eq "$pat"
    else (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; fi
}
if port_listening "$PORT"; then
    row FAIL port "something already listens on port $PORT - is Inspecto already running? Stop it, or set PORT"
else
    row PASS port "port $PORT is free"
fi

echo "[preflight] $PASSES PASS, $WARNS WARN, $FAILS FAIL"
if [ "$FAILS" -gt 0 ]; then echo "[preflight] NOT READY - fix every FAIL before starting Inspecto"; exit 1; fi
echo "[preflight] READY"
