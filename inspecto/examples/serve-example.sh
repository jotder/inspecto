#!/usr/bin/env bash
# Run one serve-mode Inspecto example: start the engine as a service (Control API), seed its
# inbox, probe the API, then stay running so you can play with it.
#
# Unlike run-example.sh (one-shot batch), this starts com.gamma.control.ControlApi over the
# example's config dir on a short poll interval, waits for GET /health, seeds a fresh out/inbox/
# from the pristine samples/, waits a couple of poll cycles, then probes the Control API: generic
# probes (/pipelines, /events) plus any paths listed in the example's probes.txt. By default the
# server keeps running for exploration (press Enter to stop); --demo prints the probes once and
# stops (non-interactive, self-checking).
#
# Serve-mode examples use the *_pipeline.toon / *_enrich.toon / *_job.toon naming the engine scans
# for, and the engine runs with CWD = the example dir, so relative paths (schema_file, dirs.poll)
# resolve exactly as in one-shot mode. Everything is written under the example's own out/.
#
# JAR resolution: $INSPECTO_JAR -> ../inspecto.jar (bundle) -> ../target/inspecto-processor-*.jar.
#
# Usage: bash serve-example.sh 06-serve/sequence-gap [--demo] [--check-jobs] [--port N] [--poll N] [--wait N] [--clean]
set -euo pipefail
EXAMPLES_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT=18080; POLL=3; WAIT=0; DEMO=0; CLEAN=0; CHECK_JOBS=0; EX=""
USAGE="usage: serve-example.sh <example-dir> [--demo] [--check-jobs] [--port N] [--poll N] [--wait N] [--clean]"
while [ $# -gt 0 ]; do
  case "$1" in
    --port)  PORT="$2"; shift 2;;
    --poll)  POLL="$2"; shift 2;;
    --wait)  WAIT="$2"; shift 2;;
    --demo)  DEMO=1; shift;;
    --check-jobs) CHECK_JOBS=1; DEMO=1; shift;;
    --clean) CLEAN=1; shift;;
    -h|--help) echo "$USAGE"; exit 0;;
    *) EX="$1"; shift;;
  esac
done
[ -n "$EX" ] || { echo "$USAGE" >&2; exit 2; }

resolve_jar() {
  if [ -n "${INSPECTO_JAR:-}" ] && [ -f "$INSPECTO_JAR" ]; then echo "$INSPECTO_JAR"; return; fi
  if [ -f "$EXAMPLES_ROOT/../inspecto.jar" ]; then echo "$EXAMPLES_ROOT/../inspecto.jar"; return; fi
  local t; t="$(ls "$EXAMPLES_ROOT"/../target/inspecto-processor-*.jar 2>/dev/null | grep -Ev 'sources|javadoc' | head -1 || true)"
  if [ -n "$t" ]; then echo "$t"; return; fi
  echo "Engine JAR not found. Set \$INSPECTO_JAR or build it (mvn -o clean package)." >&2; exit 1
}
JAR="$(resolve_jar)"
DIR="$EXAMPLES_ROOT/$EX"
[ -d "$DIR" ] || { echo "No such example dir: $DIR" >&2; exit 1; }
PIPE="$(ls "$DIR"/*_pipeline.toon 2>/dev/null | head -1 || true)"
[ -n "$PIPE" ] || { echo "No *_pipeline.toon under $DIR (serve examples use the *_pipeline.toon convention)." >&2; exit 1; }

cd "$DIR"
[ "$CLEAN" = 1 ] && rm -rf out || true
mkdir -p out/inbox out/database out/backup out/temp out/errors out/quarantine out/markers out/status out/logs out/write
# Optional write/ dir: seeded into out/write (the engine's -Dassist.write.root) before boot, so
# examples can ship pre-authored write-root artifacts (e.g. flows/<id>.toon for type:pipeline jobs).
[ -d write ] && cp -r write/* out/write/ 2>/dev/null || true
BASE="http://localhost:$PORT"
SRV_PID=""
cleanup(){ if [ -n "$SRV_PID" ] && kill -0 "$SRV_PID" 2>/dev/null; then kill "$SRV_PID" 2>/dev/null || true; echo "Server stopped."; fi; }
trap cleanup EXIT INT TERM

echo "Starting Inspecto serve mode on $BASE (poll ${POLL}s)"
echo "  jar:    $JAR"
echo "  config: $DIR"
# Path-jail roots (PKG-6): single-tenant serve over "." registers no space base, so
# -Dassist.safety.roots is the only source of allowed roots; the example dir IS the root.
java --enable-native-access=ALL-UNNAMED -Dcontrol.port="$PORT" -Dservice.poll.seconds="$POLL" \
     "-Dassist.safety.roots=$(pwd)" \
     -Dassist.write.root=out/write -Djobs.audit.dir=out/jobs_audit -cp "$JAR" com.gamma.control.ControlApi . \
     >out/logs/serve.out.log 2>out/logs/serve.err.log &
SRV_PID=$!

up=0
for _ in $(seq 1 60); do
  sleep 0.5
  if ! kill -0 "$SRV_PID" 2>/dev/null; then echo "Server exited early:"; tail -n 30 out/logs/serve.err.log out/logs/serve.out.log 2>/dev/null || true; exit 1; fi
  if curl -fsS "$BASE/health" >/dev/null 2>&1; then up=1; break; fi
done
[ "$up" = 1 ] || { echo "Server did not become healthy on $BASE."; tail -n 30 out/logs/serve.err.log 2>/dev/null || true; exit 1; }

# Optional mtimes.txt: "<filename> <ISO-8601 or anything `touch -d` accepts>" per line (blank/# ignored).
# Applied to seeded inbox files after each drop, so examples can demonstrate mtime-sensitive features
# (incremental high-watermark, metadata dedup) deterministically — git does not preserve mtimes.
apply_mtimes(){
  [ -f mtimes.txt ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    case "$(printf '%s' "$line" | tr -d '[:space:]')" in ''|\#*) continue;; esac
    f="$(printf '%s' "$line" | awk '{print $1}')"
    ts="$(printf '%s' "$line" | sed 's/^[^[:space:]]*[[:space:]]*//')"
    [ -e "out/inbox/$f" ] && touch -d "$ts" "out/inbox/$f" 2>/dev/null || true
  done < mtimes.txt
}

echo "Healthy. Seeding out/inbox/ from samples/ ..."
[ -d samples ] && cp -r samples/* out/inbox/ 2>/dev/null || true
apply_mtimes
W="$WAIT"; [ "$W" -gt 0 ] || W=$((POLL * 2 + 3))
echo "Waiting ${W}s for the poll loop to ingest..."; echo
sleep "$W"

# Optional second drop: re-present files (changed/duplicate content, same names) so examples can
# demonstrate the acquisition re-presentation family (checksum/metadata change, dedup, watermark),
# which is inherently a two-cycle scenario. Engaged only when the example ships a phase2/ dir.
if [ -d phase2 ]; then
  echo "Second drop: seeding out/inbox/ from phase2/ ..."
  cp -r phase2/* out/inbox/ 2>/dev/null || true
  apply_mtimes
  echo "Waiting ${W}s for the poll loop to process the second drop..."; echo
  sleep "$W"
fi

# API-5: business routes are served only under /api/v1, so the version is applied here (the single
# choke point) and probes.txt keeps listing version-free route paths. /health stays unversioned.
# ⚠ probes.txt is PRINTED, never asserted — a failed probe prints "(request failed)" and the script
# still exits 0. That is deliberate and was re-confirmed 2026-09-17 rather than "fixed": the generic
# /events probe legitimately answers 503 CAPABILITY_UNAVAILABLE on a Personal bundle (the feed lives in
# the optional inspecto-events module, Standard and above), so a fatal probe would make the release
# smoke edition-dependent — a check that gets disabled beats no check only in the wrong direction.
# ⛔ So do NOT read probes.txt as a gate. The exit code below is decided by --check-jobs alone.
FAILURES=0
probe(){ echo "# GET $1"; curl -fsS "$BASE/api/v1$1" 2>/dev/null || echo "  (request failed)"; echo; echo; }
probe "/pipelines"
probe "/events?limit=20"
if [ -f probes.txt ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    case "$(printf '%s' "$line" | tr -d '[:space:]')" in ''|\#*) continue;; esac
    probe "$(printf '%s' "$line" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
  done < probes.txt
fi

# ── --check-jobs: the only thing that exercises a job's CONFIG ────────────────────────────────────
# 🔴 Measured 2026-09-17, and it is the whole reason this mode exists: with `compact_job.toon` carrying
# the pre-fix `dir: out/database`, GET /jobs is BYTE-IDENTICAL to the healthy run — all jobs registered,
# enabled, lastStatus "". The mis-pointed path is only discovered when the task RESOLVES it, i.e. when
# the job runs. These jobs are on weekly/daily crons, so a demo never fires one. ⛔ Therefore listing
# jobs (what probes.txt does) proves nothing; the run is the evidence, and it has to be triggered.
#
# jq is NOT assumed — it is absent from this project's Git-Bash sandbox, so parsing is grep/sed only.
if [ "$CHECK_JOBS" = 1 ]; then
  echo "── Checking jobs: trigger every registered job, require a non-FAILED run ──"
  jobs_json="$(curl -fsS "$BASE/api/v1/jobs" 2>/dev/null || true)"
  # Every element of /jobs carries "name"; the metadata/links/diagnostics envelopes carry none.
  names="$(printf '%s' "$jobs_json" | grep -o '"name":"[^"]*"' | sed 's/^"name":"//;s/"$//' || true)"
  if [ -z "$names" ]; then
    # A sweep that finds nothing must fail loudly rather than pass vacuously.
    echo "  !! /api/v1/jobs registered NO job — this example carries none, or the route failed."
    FAILURES=$((FAILURES + 1))
  fi
  for j in $names; do
    trig="$(curl -fsS -X POST "$BASE/api/v1/jobs/$j/trigger" 2>/dev/null || true)"
    run="$(printf '%s' "$trig" | grep -o '"runId":"[^"]*"' | sed 's/^"runId":"//;s/"$//' | head -1 || true)"
    if [ -z "$run" ]; then
      echo "  !! $j: trigger returned no runId — $trig"; FAILURES=$((FAILURES + 1)); continue
    fi
    status=""; body=""
    for _ in $(seq 1 60); do
      body="$(curl -fsS "$BASE/api/v1/jobs/runs/$run" 2>/dev/null || true)"
      status="$(printf '%s' "$body" | grep -o '"status":"[^"]*"' | head -1 | sed 's/^"status":"//;s/"$//' || true)"
      case "$status" in SUCCESS|FAILED|SKIPPED) break;; esac
      sleep 1
    done
    case "$status" in
      SUCCESS|SKIPPED) echo "  ok   $j -> $status";;
      *)
        echo "  FAIL $j -> ${status:-<no terminal status within 60s>}"
        # The message is the diagnosis (e.g. a job path that no longer denotes the directory it names).
        printf '%s' "$body" | grep -o '"message":"[^"]*"' | sed 's/^/       /'
        FAILURES=$((FAILURES + 1));;
    esac
  done
  echo
fi

if [ "$DEMO" = 1 ]; then
  if [ "$FAILURES" -gt 0 ]; then
    echo "JOB CHECK FAILED: $FAILURES job(s) did not reach a non-FAILED run; stopping server."
    exit 1
  fi
  echo "Demo complete; stopping server."
else
  echo "--- Server is running at $BASE ---"
  echo "  Explore:  curl $BASE/api/v1/pipelines   |   curl \"$BASE/api/v1/events?limit=20\""
  echo "  Drop more files into:  $(pwd)/out/inbox"
  echo "  Press Enter to stop."
  read -r _
fi
