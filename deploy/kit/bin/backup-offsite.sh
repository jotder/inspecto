#!/usr/bin/env bash
# backup-offsite.sh - copy the newest verified Inspecto backup off this host (DEPLOY-EXPOSURE-KIT-1).
#
#   bash bin/backup-offsite.sh --from <backup_dir> --to <folder>                   a mounted share (NFS, SMB)
#   bash bin/backup-offsite.sh --from <backup_dir> --to <host>:<path>              rsync over ssh
#   bash bin/backup-offsite.sh --from <backup_dir> --to <url> --cmd "<command>"    an object-store CLI, run as
#                                                                                  <command> <file> <url> per file
#       e.g.  --to s3://bucket/inspecto/ --cmd "aws s3 cp"      --to remote:inspecto --cmd "rclone copy"
#   bash bin/backup-offsite.sh --from <backup_dir> --verify-only                   check, copy nothing
#
# <backup_dir> is the `backup_dir` of your backup Job. The backup Job keeps it inside the Space (the path jail), so
# this script, run by the operator outside the server, is what moves a copy off the host. It takes the newest archive
# the way backup_verify does (archive names sort by time), checks its SHA-256 against the archiveSha256 in its sidecar
# <archive>.manifest.json, and copies the archive, then the sidecar, so a target holding the sidecar holds the whole
# backup. A folder target is re-hashed after the copy. Schedule it after the backup Job (cron, a systemd timer).
# Exit 0 when the backup verified and every copy succeeded, 1 otherwise. Nothing is copied when the check fails.
set -uo pipefail

FROM=""; TO=""; CMD=""; VERIFY_ONLY=""
while [ $# -gt 0 ]; do
    case "$1" in
        --from) FROM="${2:-}"; shift ;;
        --to) TO="${2:-}"; shift ;;
        --cmd) CMD="${2:-}"; shift ;;
        --verify-only) VERIFY_ONLY="yes" ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        *) echo "[backup-offsite] unknown option $1" >&2; exit 2 ;;
    esac
    shift
done
[ -n "$FROM" ] || { echo "usage: bash bin/backup-offsite.sh --from <backup_dir> (--to <target> [--cmd <command>] | --verify-only)" >&2; exit 2; }
[ -n "$VERIFY_ONLY" ] || [ -n "$TO" ] || { echo "[backup-offsite] --to <target> is required (or --verify-only)" >&2; exit 2; }

say() { printf '[backup-offsite] %-4s  %-8s %s\n' "$1" "$2" "$3"; }
fail() { say FAIL "$1" "$2"; exit 1; }
sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum < "$1" | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then shasum -a 256 < "$1" | cut -d' ' -f1
    else openssl dgst -sha256 -r < "$1" | cut -d' ' -f1
    fi
}

[ -d "$FROM" ] || fail backup "no such folder: $FROM"
ZIP="$(ls -1 "$FROM"/*.zip 2>/dev/null | sort -r | head -n 1)"
[ -n "$ZIP" ] || fail backup "no backup archive (*.zip) in $FROM - has the backup Job run?"
SIDECAR="$ZIP.manifest.json"
[ -f "$SIDECAR" ] || fail verify "$(basename "$ZIP") has no sidecar $(basename "$SIDECAR"); it cannot be verified"
expected="$(tr -d '\r\n' < "$SIDECAR" | sed -n 's/.*"archiveSha256"[[:space:]]*:[[:space:]]*"\([0-9A-Fa-f]*\)".*/\1/p' | tr 'A-F' 'a-f')"
[ -n "$expected" ] || fail verify "$(basename "$SIDECAR") records no archiveSha256"
actual="$(sha256_of "$ZIP" | tr 'A-F' 'a-f')"
[ "$expected" = "$actual" ] || fail verify "$(basename "$ZIP") does not match its manifest (expected $expected, got $actual) - the backup is damaged; run backup_verify and take a new backup"
say PASS verify "$(basename "$ZIP") sha256 $actual"
[ -n "$VERIFY_ONLY" ] && exit 0

# An existing folder (a mounted share) is copied to; host:path goes to rsync; anything else needs --cmd.
KIND="folder"
if [ -n "$CMD" ]; then KIND="cmd"
elif [ ! -d "$TO" ]; then
    case "$TO" in
        *://*) fail copy "$TO is a URL: pass --cmd with your object-store CLI, for example --cmd \"aws s3 cp\"" ;;
        [!/]*:*) KIND="rsync"; command -v rsync >/dev/null 2>&1 || fail copy "rsync is not installed" ;;
        *) fail copy "no such folder: $TO (mount the share first)" ;;
    esac
fi
for f in "$ZIP" "$SIDECAR"; do
    name="$(basename "$f")"
    case "$KIND" in
        cmd)
            read -r -a cmd <<< "$CMD"
            "${cmd[@]}" "$f" "$TO" || fail copy "$CMD $name $TO failed" ;;
        rsync)
            rsync -a --checksum "$f" "$TO/" || fail copy "rsync $name to $TO failed" ;;
        folder)
            { cp "$f" "$TO/$name.part" && mv -f "$TO/$name.part" "$TO/$name"; } || fail copy "cp $name to $TO failed"
            [ "$(sha256_of "$TO/$name")" = "$(sha256_of "$f")" ] || fail copy "$TO/$name differs from the original after the copy" ;;
    esac
    say PASS copy "$name -> $TO"
done
