#!/usr/bin/env bash
# upgrade.sh - upgrade this Inspecto bundle in place from a new bundle zip (DEPLOY-EXPOSURE-KIT-1).
#
#   sudo bin/upgrade.sh <inspecto-deploy-linux_amd64.zip> [--backup-dir <backup_dir>] [--service inspecto]
#        [--require-signature] [--allow-edition-change] [--direct] [--proxy-config <file>] [--no-service]
#
# In order, stopping at the first failure:
#   1. bin/verify-bundle.sh checks the zip against its .sha256 (and .asc). Nothing is touched before this passes.
#   2. --backup-dir: the newest backup there must verify against its manifest (bin/backup-offsite.sh --verify-only).
#      Take one with the backup Job first; without --backup-dir this is a WARN.
#   3. The zip is unpacked beside the bundle and checked: a bundle shape, and the same edition (--allow-edition-change).
#   4. The systemd service is stopped (--no-service: you stop and start the server yourself).
#   5. The previous bundle is kept beside this one as <bundle>.pre-upgrade-<time>/: a copy of the settings
#      (inspecto.env, spaces.ref, conf/, each Space's config/ and top-level files) and every folder the new bundle
#      replaces. Everything the new bundle does not ship stays in place: spaces/ (only spaces/_templates is
#      replaced), logs/, inspecto.env, spaces.ref, files of your own.
#   6. The new bundle's bin/preflight.sh runs (--direct and --proxy-config are passed to it).
#   7. The service starts and must answer /health and /ready within HEALTH_TIMEOUT seconds (default 180).
# A failure at 6 or 7 puts the previous bundle back (and restarts the service); the new one is left unpacked.
set -uo pipefail

main() {
    ROOT="$(cd "$(dirname "$0")/.." && pwd)"
    PARENT="$(dirname "$ROOT")"
    STAMP="$(date +%Y%m%d-%H%M%S)"
    ZIP=""; BACKUP_DIR=""; SERVICE="inspecto"; NO_SERVICE=""; ALLOW_EDITION=""
    VERIFY_ARGS=(); PREFLIGHT_ARGS=()
    while [ $# -gt 0 ]; do
        case "$1" in
            --backup-dir) BACKUP_DIR="${2:-}"; shift ;;
            --service) SERVICE="${2:-}"; shift ;;
            --no-service) NO_SERVICE="yes" ;;
            --require-signature) VERIFY_ARGS+=(--require-signature) ;;
            --allow-edition-change) ALLOW_EDITION="yes" ;;
            --direct) PREFLIGHT_ARGS+=(--direct) ;;
            --proxy-config) PREFLIGHT_ARGS+=(--proxy-config "${2:-}"); shift ;;
            -h|--help) sed -n '2,21p' "$0"; exit 0 ;;
            -*) die "unknown option $1" ;;
            *) ZIP="$1" ;;
        esac
        shift
    done
    [ -n "$ZIP" ] || die "usage: bin/upgrade.sh <bundle.zip> [options] (see --help)"
    case "$ZIP" in /*|[A-Za-z]:*) ;; *) ZIP="$(pwd)/$ZIP" ;; esac

    step "1/7 verify the new bundle"
    bash "$ROOT/bin/verify-bundle.sh" "$ZIP" "${VERIFY_ARGS[@]+"${VERIFY_ARGS[@]}"}" || die "the bundle did not verify; nothing was changed"

    step "2/7 check the newest backup"
    if [ -n "$BACKUP_DIR" ]; then
        bash "$ROOT/bin/backup-offsite.sh" --from "$BACKUP_DIR" --verify-only || die "no verified backup in $BACKUP_DIR; nothing was changed"
    else
        echo "[upgrade] WARN  no --backup-dir: the Space data is not checked to have a verified backup"
    fi

    step "3/7 unpack and check the new bundle"
    STAGE="$PARENT/.inspecto-upgrade-$STAMP"
    mkdir -p "$STAGE" || die "cannot create $STAGE"
    if command -v unzip >/dev/null 2>&1; then unzip -q "$ZIP" -d "$STAGE" || die "unzip failed"
    elif command -v python3 >/dev/null 2>&1; then python3 -m zipfile -e "$ZIP" "$STAGE" || die "unpacking failed"
    else die "neither unzip nor python3 is installed"; fi
    NEW="$(find "$STAGE" -mindepth 2 -maxdepth 3 -path '*/lib/edition.properties' | head -n 1)"
    [ -n "$NEW" ] || die "the zip holds no bundle (no lib/edition.properties); left unpacked in $STAGE"
    NEW="$(dirname "$(dirname "$NEW")")"
    for f in bin/serve.sh bin/preflight.sh lib/modules.list; do
        [ -f "$NEW/$f" ] || die "the new bundle has no $f; left unpacked in $STAGE"
    done
    # A zip built on Windows carries no executable bits.
    chmod +x "$NEW"/bin/*.sh 2>/dev/null
    [ -d "$NEW/runtime/bin" ] && chmod +x "$NEW"/runtime/bin/*
    [ -f "$NEW/runtime/lib/jspawnhelper" ] && chmod +x "$NEW/runtime/lib/jspawnhelper"
    # Run as root, the unpacked files are root's; give them the owner of the bundle they replace.
    owner="$(stat -c '%u:%g' "$ROOT/lib" 2>/dev/null)"
    [ "$(id -u)" = 0 ] && [ -n "$owner" ] && chown -R "$owner" "$NEW"
    old_ed="$(edition "$ROOT")"; new_ed="$(edition "$NEW")"
    if [ "$old_ed" != "$new_ed" ] && [ -z "$ALLOW_EDITION" ]; then
        die "the new bundle is $new_ed, this one is $old_ed; pass --allow-edition-change if that is intended. Left unpacked in $STAGE"
    fi
    echo "[upgrade] new bundle: $new_ed, unpacked in $STAGE"

    step "4/7 stop the server"
    stop_server

    step "5/7 keep the previous bundle and swap in the new one"
    PRE="$ROOT.pre-upgrade-$STAMP"
    mkdir -p "$PRE/settings" "$PRE/product" || die "cannot create $PRE"
    ( cd "$ROOT" || exit 1
      for f in inspecto.env spaces.ref conf; do [ -e "$f" ] && cp -a "$f" "$PRE/settings/"; done
      for s in spaces/*; do
          case "$s" in spaces/_templates|spaces/\*) continue ;; esac
          if [ -d "$s" ]; then
              mkdir -p "$PRE/settings/$s"
              [ -d "$s/config" ] && cp -a "$s/config" "$PRE/settings/$s/"
              find "$s" -maxdepth 1 -type f -exec cp -a {} "$PRE/settings/$s/" \;
          else
              mkdir -p "$PRE/settings/spaces" && cp -a "$s" "$PRE/settings/spaces/"
          fi
      done ) || die "copying the settings to $PRE failed; nothing was swapped - start the server again"
    : > "$PRE/swapped.list"
    for e in $(cd "$NEW" && ls -A); do
        case "$e" in
            logs) [ -e "$ROOT/logs" ] && continue; swap logs ;;
            spaces) mkdir -p "$ROOT/spaces"; [ -e "$NEW/spaces/_templates" ] && swap spaces/_templates ;;
            *) swap "$e" ;;
        esac
    done
    echo "[upgrade] previous bundle kept in $PRE"

    step "6/7 preflight the new bundle"
    if ! bash "$ROOT/bin/preflight.sh" "${PREFLIGHT_ARGS[@]+"${PREFLIGHT_ARGS[@]}"}"; then
        rollback "the new bundle's preflight failed"
    fi

    step "7/7 start and check health"
    if [ -n "$NO_SERVICE" ]; then
        echo "[upgrade] --no-service: start the server yourself, then check $(bash "$ROOT/bin/preflight.sh" --endpoint 2>/dev/null)/health"
    else
        systemctl start "$SERVICE" || rollback "systemctl start $SERVICE failed"
        healthy || rollback "the server did not answer /health and /ready in time"
    fi
    rm -rf "$STAGE"
    echo "[upgrade] DONE: $old_ed -> $new_ed. Remove $PRE once you are satisfied."
}

die() { echo "[upgrade] FAIL  $*" >&2; exit 1; }
step() { echo "[upgrade] == $*"; }
edition() { sed -n 's/^edition=//p' "$1/lib/edition.properties" 2>/dev/null | tr -d '\r'; }

# swap <entry>: the old entry moves into $PRE/product, the new one into the bundle; recorded for rollback.
swap() {
    mkdir -p "$(dirname "$PRE/product/$1")"
    if [ -e "$ROOT/$1" ]; then mv "$ROOT/$1" "$PRE/product/$1" || rollback "could not move $1 aside"; fi
    echo "$1" >> "$PRE/swapped.list"
    mv "$NEW/$1" "$ROOT/$1" || rollback "could not move the new $1 in"
}

rollback() {
    echo "[upgrade] FAIL  $1 - putting the previous bundle back" >&2
    [ -z "$NO_SERVICE" ] && systemctl stop "$SERVICE" 2>/dev/null
    local e
    for e in $(sort -r "$PRE/swapped.list" 2>/dev/null); do
        mkdir -p "$(dirname "$NEW/$e")"
        [ -e "$ROOT/$e" ] && mv "$ROOT/$e" "$NEW/$e"
        [ -e "$PRE/product/$e" ] && mv "$PRE/product/$e" "$ROOT/$e"
    done
    if [ -z "$NO_SERVICE" ]; then
        systemctl start "$SERVICE" && healthy && echo "[upgrade] the previous bundle is running again" >&2
    fi
    echo "[upgrade] rolled back; the new bundle is left unpacked in $STAGE, the settings copy in $PRE" >&2
    exit 1
}

stop_server() {
    if [ -n "$NO_SERVICE" ]; then
        echo "[upgrade] --no-service: the server must already be stopped"
    else
        command -v systemctl >/dev/null 2>&1 || die "no systemctl: stop the server yourself and pass --no-service"
        systemctl cat "$SERVICE" >/dev/null 2>&1 || die "no systemd service '$SERVICE' (bin/install-service.sh installs it; or pass --service <name> or --no-service)"
        systemctl stop "$SERVICE" || die "systemctl stop $SERVICE failed"
    fi
    local port i pat
    port="$(bash "$ROOT/bin/preflight.sh" --endpoint 2>/dev/null)"; port="${port##*:}"; pat="[:.]$port[[:space:]]"
    for i in $(seq 1 30); do
        if command -v ss >/dev/null 2>&1; then ss -ltn 2>/dev/null | grep -Eq "$pat" || return 0
        elif command -v netstat >/dev/null 2>&1; then netstat -an 2>/dev/null | grep -E 'LISTEN' | grep -Eq "$pat" || return 0
        else (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null || return 0; fi
        sleep 1
    done
    die "something still listens on port $port after the stop; nothing was swapped - start the server again"
}

healthy() {
    local base deadline
    base="$(bash "$ROOT/bin/preflight.sh" --endpoint 2>/dev/null)"
    deadline=$(( $(date +%s) + ${HEALTH_TIMEOUT:-180} ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if curl -fsSk -o /dev/null "$base/health" 2>/dev/null && curl -fsSk -o /dev/null "$base/ready" 2>/dev/null; then
            echo "[upgrade] PASS  $base/health and /ready answer"
            return 0
        fi
        sleep 3
    done
    return 1
}

main "$@"
