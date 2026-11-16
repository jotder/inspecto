#!/usr/bin/env bash
# upgrade.sh - upgrade this Inspecto bundle in place from a new bundle zip (DEPLOY-EXPOSURE-KIT-1).
#
#   sudo bash bin/upgrade.sh <inspecto-deploy-linux_amd64.zip> [--backup-dir <backup_dir>] [--service inspecto]
#        [--require-signature] [--allow-edition-change] [--direct] [--proxy-config <file>] [--no-service]
#
# In order, stopping at the first failure:
#   1. bin/verify-bundle.sh checks the zip against its .sha256 (and .asc). Nothing is touched before this passes.
#   2. --backup-dir: the newest backup there must verify against its manifest (bin/backup-offsite.sh --verify-only).
#      Take one with the backup Job first; without --backup-dir this is a WARN.
#   3. The zip is unpacked beside the bundle and checked: a bundle shape, and the same edition (--allow-edition-change).
#   4. The systemd service is stopped (--no-service: you stop and start the server yourself).
#   5. The previous bundle is kept beside this one as <bundle>.pre-upgrade-<time>/: a copy of the settings
#      (inspecto.env, spaces.ref, conf/, and each Space's config/ and top-level files from the spaces root the server
#      uses, following <id>.ref files) and every entry the new bundle replaces. Everything the new bundle does not ship
#      stays in place: Space folders, logs/, inspecto.env, spaces.ref, apps/, files of your own. In the bundle's
#      spaces/_templates only the templates the new bundle ships are replaced, retired shipped ones are moved aside,
#      and the templates installed Apps put there (marked .installed-by) stay.
#   6. The new bundle's bin/preflight.sh runs (--direct and --proxy-config are passed to it).
#   7. The service starts; /health and /ready must answer within HEALTH_TIMEOUT seconds (default 180), from the
#      service's own process, started from this folder after the start was issued.
# A failure at 6 or 7 puts the previous bundle back (and restarts the service); the new one is left unpacked.
set -uo pipefail

main() {
    # -P: the resolved path, as /proc/<pid>/cwd reports it, so a bundle reached through a symlink still matches healthy()
    ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
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
            -h|--help) sed -n '2,23p' "$0"; exit 0 ;;
            -*) die "unknown option $1" ;;
            *) ZIP="$1" ;;
        esac
        shift
    done
    [ -n "$ZIP" ] || die "usage: sudo bash bin/upgrade.sh <bundle.zip> [options] (see --help)"
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
    # The spaces root as the server resolves it (SPACES_ROOT, inspecto.env, spaces.ref), read before anything moves.
    SPACES_DIR="$(bash "$ROOT/bin/preflight.sh" --spaces-root 2>/dev/null)"
    [ -n "$SPACES_DIR" ] || SPACES_DIR="$ROOT/spaces"
    BASE="$(bash "$ROOT/bin/preflight.sh" --endpoint 2>/dev/null)"

    step "4/7 stop the server"
    stop_server

    step "5/7 keep the previous bundle and swap in the new one"
    PRE="$ROOT.pre-upgrade-$STAMP"
    mkdir -p "$PRE/settings/spaces" "$PRE/product" || die "cannot create $PRE"
    copy_settings || die "copying the settings to $PRE failed; nothing was swapped - start the server again"
    : > "$PRE/swapped.list"
    for e in $(cd "$NEW" && ls -A); do
        case "$e" in
            logs) [ -e "$ROOT/logs" ] && continue; swap logs ;;
            spaces) swap_templates ;;
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
        echo "[upgrade] --no-service: start the server yourself, then check $BASE/health"
    else
        since="$(date +%s)"
        systemctl start "$SERVICE" || rollback "systemctl start $SERVICE failed"
        healthy "$since" || rollback "the new bundle did not answer /health and /ready in time"
    fi
    rm -rf "$STAGE"
    if [ -f "$PRE/product/runtime/lib/security/cacerts" ] && ! cmp -s "$PRE/product/runtime/lib/security/cacerts" "$ROOT/runtime/lib/security/cacerts"; then
        echo "[upgrade] NOTE  the Java runtime was replaced: CA certificates you imported into runtime/lib/security/cacerts are"
        echo "                not in the new one. The old file is $PRE/product/runtime/lib/security/cacerts; re-import with keytool."
    fi
    echo "[upgrade] DONE: $old_ed -> $new_ed. Remove $PRE once you are satisfied."
}

die() { echo "[upgrade] FAIL  $*" >&2; exit 1; }
step() { echo "[upgrade] == $*"; }
edition() { sed -n 's/^edition=//p' "$1/lib/edition.properties" 2>/dev/null | tr -d '\r'; }

# The settings, copied (never moved) to $PRE/settings: the bundle's own files, then per Space its config/ and top-level
# files. An <id>.ref Space is followed to the folder it names. Space data is not copied: that is the backup Job's.
copy_settings() {
    local f s n target
    for f in inspecto.env spaces.ref conf; do
        if [ -e "$ROOT/$f" ]; then cp -a "$ROOT/$f" "$PRE/settings/" || return 1; fi
    done
    for s in "$SPACES_DIR"/*; do
        [ -e "$s" ] || continue
        n="$(basename "$s")"
        [ "$n" = _templates ] && continue
        target="$s"
        if [ -f "$s" ]; then
            cp -a "$s" "$PRE/settings/spaces/" || return 1
            case "$n" in *.ref) n="${n%.ref}"; target="$(tr -d '\r\n' < "$s")" ;; *) continue ;; esac
        fi
        [ -d "$target" ] || continue
        mkdir -p "$PRE/settings/spaces/$n" || return 1
        if [ -d "$target/config" ]; then cp -a "$target/config" "$PRE/settings/spaces/$n/" || return 1; fi
        find "$target" -maxdepth 1 -type f -exec cp -a {} "$PRE/settings/spaces/$n/" \; || return 1
    done
}

# swap <entry>: the old entry moves into $PRE/product and the new one (if the new bundle has it) into the bundle;
# recorded for rollback.
swap() {
    mkdir -p "$(dirname "$PRE/product/$1")"
    if [ -e "$ROOT/$1" ]; then mv "$ROOT/$1" "$PRE/product/$1" || rollback "could not move $1 aside"; fi
    echo "$1" >> "$PRE/swapped.list"
    if [ -e "$NEW/$1" ]; then mv "$NEW/$1" "$ROOT/$1" || rollback "could not move the new $1 in"; fi
}

# The bundle's spaces/_templates is merged, not replaced: each template the new bundle ships replaces its namesake; a
# template only the old bundle shipped is moved aside; a template an App installed at run time (InstalledApps.MARKER,
# .installed-by) is the install's and stays. Space folders beside _templates are never touched.
swap_templates() {
    local shipped t
    mkdir -p "$ROOT/spaces/_templates"
    shipped=" $(cd "$NEW/spaces/_templates" 2>/dev/null && ls -A | tr '\n' ' ')"
    for t in $(cd "$ROOT/spaces/_templates" && ls -A); do
        case "$shipped" in *" $t "*) continue ;; esac
        [ -e "$ROOT/spaces/_templates/$t/.installed-by" ] || swap "spaces/_templates/$t"
    done
    for t in $shipped; do swap "spaces/_templates/$t"; done
}

rollback() {
    echo "[upgrade] FAIL  $1 - putting the previous bundle back" >&2
    if [ -z "$NO_SERVICE" ]; then
        # systemd stops every process of the unit (KillMode=control-group), so the new bundle's java is gone too.
        systemctl stop "$SERVICE" 2>/dev/null
        wait_port_free || echo "[upgrade] WARN  something still listens on the port after the stop" >&2
    fi
    local e since
    for e in $(sort -r "$PRE/swapped.list" 2>/dev/null); do
        mkdir -p "$(dirname "$NEW/$e")"
        [ -e "$ROOT/$e" ] && mv "$ROOT/$e" "$NEW/$e"
        [ -e "$PRE/product/$e" ] && mv "$PRE/product/$e" "$ROOT/$e"
    done
    if [ -z "$NO_SERVICE" ]; then
        since="$(date +%s)"
        if systemctl start "$SERVICE" && healthy "$since"; then echo "[upgrade] the previous bundle is running again" >&2
        else echo "[upgrade] FAIL  the previous bundle did not come back either: check journalctl -u $SERVICE" >&2; fi
    fi
    echo "[upgrade] rolled back; the new bundle is left unpacked in $STAGE, the settings copy in $PRE" >&2
    exit 1
}

# Returns 0 once nothing listens on the server's port (ss, else netstat, else a connect), 1 after 30 s.
wait_port_free() {
    local port i pat
    port="${BASE##*:}"; pat="[:.]$port[[:space:]]"
    for i in $(seq 1 30); do
        if command -v ss >/dev/null 2>&1; then ss -ltn 2>/dev/null | grep -Eq "$pat" || return 0
        elif command -v netstat >/dev/null 2>&1; then netstat -an 2>/dev/null | grep -E 'LISTEN' | grep -Eq "$pat" || return 0
        else (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null || return 0; fi
        sleep 1
    done
    return 1
}

stop_server() {
    if [ -n "$NO_SERVICE" ]; then
        echo "[upgrade] --no-service: the server must already be stopped"
    else
        command -v systemctl >/dev/null 2>&1 || die "no systemctl: stop the server yourself and pass --no-service"
        systemctl cat "$SERVICE" >/dev/null 2>&1 || die "no systemd service '$SERVICE' (bin/install-service.sh installs it; or pass --service <name> or --no-service)"
        systemctl stop "$SERVICE" || die "systemctl stop $SERVICE failed"
    fi
    wait_port_free || die "something still listens on the server's port after the stop; nothing was swapped - start the server again"
}

# healthy <epoch>: /health and /ready answer, AND the answering server is the service's main process, running from this
# folder and started at or after <epoch> - so a leftover process, or the bundle that was just moved aside, cannot pass.
healthy() {
    local base="$BASE" deadline pid cwd age
    deadline=$(( $(date +%s) + ${HEALTH_TIMEOUT:-180} ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if curl -fsSk -o /dev/null "$base/health" 2>/dev/null && curl -fsSk -o /dev/null "$base/ready" 2>/dev/null; then
            pid="$(systemctl show -p MainPID --value "$SERVICE" 2>/dev/null)"
            cwd="$(readlink -f "/proc/$pid/cwd" 2>/dev/null)"
            age="$(ps -o etimes= -p "$pid" 2>/dev/null | tr -d ' ')"
            if [ -n "$age" ] && [ "$cwd" = "$ROOT" ] && [ $(( $(date +%s) - age )) -ge $(( $1 - 2 )) ]; then
                echo "[upgrade] PASS  $base/health and /ready answer from PID $pid, started from $ROOT"
                return 0
            fi
            echo "[upgrade] WARN  $base answers, but not from the service's new process (PID ${pid:-none}, cwd ${cwd:-unknown})" >&2
        fi
        sleep 3
    done
    return 1
}

main "$@"
