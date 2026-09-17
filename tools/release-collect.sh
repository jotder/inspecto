#!/usr/bin/env bash
# Collect every per-platform bundle package.ps1 produced for one edition into release/, and FAIL the
# release when the per-platform set is short (RELEASE-LINUX-ZIP-NEVER-PUBLISHED-1, 2026-09-17).
#
# package.ps1 emits `inspecto-deploy-<platform>.zip` (+ .sha256, + .asc under -Sign), one per target
# platform, named for the runtime it actually embeds. release.yml used to copy only the fixed
# `inspecto-deploy.zip*` trio, so a platform the script built but did not name there was never
# published — and the one it did name was the HOST image under a Windows label.
#
# Usage: bash tools/release-collect.sh <personal|professional|enterprise> [required-platform ...]
#   Default required set: the runner's own platform (linux_amd64 on a Linux runner, windows_amd64 on
#   Windows) — the one jlink can always produce with no jmods cache. Pass more names to demand a
#   cross-built platform too.
set -euo pipefail
edition="${1:?edition (personal|professional|enterprise) required}"; shift || true
case "$(uname -s)" in
  Linux)                  host=linux_amd64 ;;
  MINGW*|MSYS*|CYGWIN*)   host=windows_amd64 ;;
  *) echo "::error::release-collect: unsupported host $(uname -s)"; exit 2 ;;
esac
required=("$host" "$@")

mkdir -p release
shopt -s nullglob
zips=(inspecto-deploy-*.zip)
[ "${#zips[@]}" -gt 0 ] || { echo "::error::no inspecto-deploy-<platform>.zip produced for $edition"; exit 1; }

for zip in "${zips[@]}"; do
  plat="${zip#inspecto-deploy-}"; plat="${plat%.zip}"
  for f in "$zip" "$zip.sha256" "$zip.asc"; do
    test -f "$f" || { echo "::error::$f missing — the $plat bundle was not signed/checksummed"; exit 1; }
    cp "$f" "release/inspecto-deploy-$edition-$plat${f#"$zip"}"
  done
  echo "collected $edition/$plat"
done

for plat in "${required[@]}"; do
  test -f "inspecto-deploy-$plat.zip" || { echo "::error::$edition: required platform $plat was not produced (have: ${zips[*]})"; exit 1; }
done

# One package step's zips must not be mistaken for the next edition's: remove them now that they are in release/.
for zip in "${zips[@]}"; do rm -f "$zip" "$zip.sha256" "$zip.asc"; done
