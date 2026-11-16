#!/usr/bin/env bash
# verify-bundle.sh - check a delivered Inspecto bundle zip before you install or upgrade to it (DEPLOY-EXPOSURE-KIT-1).
#
#   bash bin/verify-bundle.sh <inspecto-deploy-<platform>.zip> [--require-signature]
#
# package.ps1 writes <zip>.sha256 (sha256sum format) next to every zip it builds, and <zip>.asc (a GPG detached
# signature) when the release is signed. This checks the zip against both:
#   sha256     the zip's SHA-256 equals the one in <zip>.sha256: the copy is complete and unchanged.
#   signature  gpg --verify <zip>.asc <zip>: the zip was signed by a key in your keyring. Import the release key
#              first. With no .asc this is a WARN, or a FAIL with --require-signature.
# The .sha256 travels with the zip, so it proves the copy is whole, not who made it; only the signature does.
# Exit 0 when no check failed, 1 otherwise.
set -uo pipefail

ZIP=""
REQUIRE_SIG=""
while [ $# -gt 0 ]; do
    case "$1" in
        --require-signature) REQUIRE_SIG="yes" ;;
        -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
        -*) echo "[verify-bundle] unknown option $1" >&2; exit 2 ;;
        *) ZIP="$1" ;;
    esac
    shift
done
[ -n "$ZIP" ] || { echo "usage: bin/verify-bundle.sh <bundle.zip> [--require-signature]" >&2; exit 2; }

FAILS=0
row() {   # row PASS|WARN|FAIL <check> <detail>
    printf '[verify-bundle] %-4s  %-10s %s\n' "$1" "$2" "$3"
    [ "$1" = FAIL ] && FAILS=$((FAILS + 1))
    return 0
}

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum < "$1" | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then shasum -a 256 < "$1" | cut -d' ' -f1
    else openssl dgst -sha256 -r < "$1" | cut -d' ' -f1
    fi
}

if [ ! -f "$ZIP" ]; then
    row FAIL bundle "no such file: $ZIP"
elif [ ! -f "$ZIP.sha256" ]; then
    row FAIL sha256 "no $ZIP.sha256 beside the zip - ask for the checksum file the release shipped with"
else
    expected="$(tr -d '\r' < "$ZIP.sha256" | awk 'NF { print tolower($1); exit }')"
    actual="$(sha256_of "$ZIP" | tr 'A-F' 'a-f')"
    if [ -z "$expected" ]; then
        row FAIL sha256 "$ZIP.sha256 holds no checksum"
    elif [ "$expected" = "$actual" ]; then
        row PASS sha256 "$actual"
    else
        row FAIL sha256 "the zip does not match its checksum (expected $expected, got $actual) - the copy is damaged or changed"
    fi
fi

if [ -f "$ZIP.asc" ]; then
    if ! command -v gpg >/dev/null 2>&1; then
        row FAIL signature "$ZIP.asc is present but gpg is not installed, so it cannot be checked"
    elif gpg --batch --verify "$ZIP.asc" "$ZIP" >/dev/null 2>&1; then
        row PASS signature "good signature from a key in your keyring"
    else
        row FAIL signature "gpg --verify failed: a bad signature, or the release key is not imported"
    fi
elif [ -n "$REQUIRE_SIG" ]; then
    row FAIL signature "no $ZIP.asc and --require-signature was given"
else
    row WARN signature "no $ZIP.asc: the release is unsigned, so only its integrity is checked"
fi

if [ "$FAILS" -gt 0 ]; then
    echo "[verify-bundle] $FAILS check(s) FAILED - do not install this bundle"
    exit 1
fi
echo "[verify-bundle] OK"
