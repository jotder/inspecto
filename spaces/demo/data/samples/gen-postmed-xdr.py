#!/usr/bin/env python3
"""Generate the synthetic post-mediated xDR sample stream for the `postmed_xdr` pipeline.

Post-mediation output: a mediation platform has already normalised switch-native CDRs
(voice / SMS / data) into one uniform pipe-delimited record layout, rated them, and emitted
one file per collection interval. This generator reproduces that shape.

Everything here is SYNTHETIC and deterministic (fixed seeds). PLMNs are in the ITU test range
(001xx), MSISDNs in the +99 reserved range, IMSIs built from the test PLMNs, IMEIs in a TAC
range that is not allocated and with no valid check digit — no real operator names, numbers,
handsets or traffic. See the repo rule: sample data is hand-generated, never trimmed from a
capture.

TIMEZONE CONTRACT
-----------------
**Every timestamp in this feed is UTC.** The mediation platform normalises switch-local time to
UTC before it writes the record, so START_AT is a UTC wall clock and EVENT_DATE is the UTC
calendar date of START_AT. The wire format carries no offset (`%Y-%m-%d %H:%M:%S`, fixed by the
pipeline's `csv_settings.timestamp_formats`), and the schema types START_AT as a naive TIMESTAMP —
so the value is stored exactly as written and is NEVER re-interpreted against a session zone.
This matters: DuckDB's session `TimeZone` defaults to the HOST zone, not UTC, so any query that
casts START_AT to TIMESTAMPTZ, or compares it to `now()`, silently shifts it by the host offset.
Treat START_AT as UTC and convert explicitly (`START_AT AT TIME ZONE 'UTC'`) — never implicitly.
The generator itself builds every timestamp in UTC and emits no local time anywhere.

COUNTERPARTY ON DATA RECORDS (deliberate)
-----------------------------------------
DATA records carry OTHER_PARTY = 'N/A' and DIRECTION = 'NA'. This is deliberate, not a gap: a
packet session is subscriber-to-network, so there is no B-party to name — the session's far end is
the APN, which the row already carries. Inventing a peer MSISDN for a GPRS session would fabricate
an edge that no real mediation feed can produce, and would poison a link-analysis demo with edges
that cannot be corroborated. The pipeline's `csv_settings.null_strings` already maps 'N/A' to NULL,
so DATA rows land with a NULL counterparty and are naturally excluded from any A→B projection;
they remain useful as subscriber attributes (volume, APN, cell) rather than as graph edges.
Voice and SMS records — ~70 % of rows — carry a real counterparty drawn from the same subscriber
population, so they DO form a connected graph.

PLANTED STORIES the analyst should find (all in the three clean .psv files):
  (a) BURNER ROTATION — one handset, IMEI 000420000042000, is used with FIVE different IMSI/MSISDN
      pairs across the three days (2 on day 1, 2 on day 2, 1 on day 3), 4 calls each = 20 rows.
      Grouping by IMEI and counting DISTINCT IMSI finds it; grouping by IMSI alone cannot — this is
      exactly why the feed carries a device identity separate from the subscriber identity.
  (b) ONE-WAY HUB — subscriber MSISDN HUB places one short MOC call to each of 45 distinct
      counterparties (15 per day) and is never called back: out-degree 45, in-degree 0. A
      degree/reciprocity view isolates it; a total-volume view does not (45 short calls is not
      much traffic).
  (c) REPEATING PAIR — two subscribers exchange calls at exactly 02:15, 07:45 and 21:05 UTC every
      day, direction alternating MOC/MTC: 9 rows on ONE edge, the heaviest edge in the corpus, with
      a cadence no random traffic reproduces.
  (d) SIM MOVED BETWEEN HANDSETS — the inverse of (a): one IMSI/MSISDN appears on THREE different
      IMEIs, one per day, 3 calls each = 9 rows. Both directions of the IMSI↔IMEI join therefore
      have a ground truth to recover.
Background traffic is drawn from a fixed population of 120 subscribers (stable IMSI + MSISDN +
IMEI), so ordinary rows form a connected graph instead of ~1200 disconnected one-off edges.

Determinism: four independent seeded streams — the population, the background traffic, the planted
stories and the defect file each draw from their own RNG, so changing one story does not reshuffle
the others.

Usage:
    python gen-postmed-xdr.py            # rewrite the sample files next to this script
    python gen-postmed-xdr.py --seed-inbox   # also copy them into the pipeline poll dir
"""

import argparse
import datetime as dt
import pathlib
import random
import shutil

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE / "postmed_xdr"
INBOX = HERE.parents[1] / "data" / "inbox" / "postmed_xdr"

HEADER = [
    "REC_SEQ", "MED_FILE_ID", "SWITCH_ID", "REC_TYPE", "IMSI", "MSISDN",
    "OTHER_PARTY", "CELL_ID", "APN", "START_AT", "EVENT_DATE", "DURATION_SEC",
    "BYTES_UP", "BYTES_DOWN", "RATED_AMOUNT", "CURRENCY", "ROAM_FLAG", "MED_STATUS",
    "IMEI", "DIRECTION",
]

SWITCHES = ["MSC-NORTH-01", "MSC-NORTH-02", "SGSN-CENTRAL-01", "MME-SOUTH-03"]
APNS = ["internet.test", "ims.test", "mms.test"]
ROAM = ["HOME", "HOME", "HOME", "NATIONAL", "INTL"]
DAYS = ["2026-09-01", "2026-09-02", "2026-09-03"]
ROWS_PER_DAY = 400
POP_SIZE = 120

# DIRECTION: MOC = mobile-originated (MSISDN → OTHER_PARTY), MTC = mobile-terminated
# (OTHER_PARTY → MSISDN), NA = no second party (DATA). Without this column the row cannot say
# which way the edge points, and a directed graph is not recoverable.
MOC, MTC, NA = "MOC", "MTC", "NA"


def imsi(rng):
    return "001{:02d}{:010d}".format(rng.choice([3, 6, 19]), rng.randrange(10**9))


def msisdn(rng):
    # +99 is reserved by ITU-T E.164 for trials — never routable.
    return "99{:010d}".format(rng.randrange(10**10))


def imei(rng):
    # Deliberately NOT a valid IMEI. A real one is TAC(8) + serial(6) + Luhn check digit; these
    # start "00" (no TAC is allocated there) and end in a fixed 0 that is not a check digit, so no
    # value emitted here can collide with a real handset.
    return "00{:06d}{:06d}0".format(rng.randrange(10**6), rng.randrange(10**6))


def subscriber(rng):
    """A stable identity triple: (IMSI, MSISDN, IMEI)."""
    return (imsi(rng), msisdn(rng), imei(rng))


# ── fixed subscriber population ───────────────────────────────────────────────────────────────
_pop_rng = random.Random(20260921)
POPULATION = [subscriber(_pop_rng) for _ in range(POP_SIZE)]

# ── planted-story actors (their own stream, so the stories are stable across edits elsewhere) ──
_story_rng = random.Random(20260923)
BURNER_IMEI = "000420000042000"
BURNER_SIMS = [(imsi(_story_rng), msisdn(_story_rng)) for _ in range(5)]
BURNER_DAY_SIMS = [[0, 1], [2, 3], [4]]          # which SIMs appear on which day
HUB = subscriber(_story_rng)
HUB_CONTACTS = [msisdn(_story_rng) for _ in range(45)]
PAIR_A = subscriber(_story_rng)
PAIR_B = subscriber(_story_rng)
PAIR_TIMES = [(2, 15), (7, 45), (21, 5)]
SWAP_SUB = subscriber(_story_rng)
SWAP_IMEIS = [imei(_story_rng) for _ in range(3)]


def record(rng, seq, day, file_id):
    sub_imsi, sub_msisdn, sub_imei = rng.choice(POPULATION)
    kind = rng.choices(["VOICE", "SMS", "DATA"], weights=[45, 25, 30])[0]
    # All timestamps are UTC (see TIMEZONE CONTRACT above).
    start = dt.datetime.fromisoformat(day) + dt.timedelta(seconds=rng.randrange(86400))
    roam = rng.choice(ROAM)
    status = rng.choices(["RATED", "RATED", "RATED", "UNRATED", "SUSPENSE"],
                         weights=[70, 10, 10, 6, 4])[0]

    if kind == "VOICE":
        switch = rng.choice(SWITCHES[:2])
        dur, up, down, apn = rng.randrange(5, 3600), "", "", "NULL"
        other = peer(rng, sub_msisdn)
        direction = rng.choice([MOC, MTC])
        amount = round(dur / 60 * rng.uniform(0.02, 0.35), 4)
    elif kind == "SMS":
        switch = rng.choice(SWITCHES[:2])
        dur, up, down, apn = 0, "", "", "NULL"
        other = peer(rng, sub_msisdn)
        direction = rng.choice([MOC, MTC])
        amount = round(rng.uniform(0.01, 0.08), 4)
    else:
        switch = rng.choice(SWITCHES[2:])
        dur = rng.randrange(30, 7200)
        up, down = rng.randrange(1024, 5_000_000), rng.randrange(4096, 80_000_000)
        apn, other = rng.choice(APNS), "N/A"     # no B-party on a packet session — deliberate
        direction = NA
        amount = round((up + down) / 1_048_576 * rng.uniform(0.001, 0.02), 4)

    # An UNRATED record leaves mediation with no money on it — that is the real shape,
    # and the reason RATED_AMOUNT must be nullable downstream.
    amount_s = "" if status == "UNRATED" else "{:.4f}".format(amount)

    return [
        "{}-{:06d}".format(day.replace("-", ""), seq),
        file_id,
        switch,
        kind,
        sub_imsi,
        sub_msisdn,
        other,
        "{:05d}-{:03d}".format(rng.randrange(65536), rng.randrange(256)),
        apn,
        start.strftime("%Y-%m-%d %H:%M:%S"),
        day,
        str(dur),
        str(up),
        str(down),
        amount_s,
        "XTS",
        roam,
        status,
        sub_imei,
        direction,
    ]


def peer(rng, own_msisdn):
    """Another subscriber's MSISDN — the counterparty of a voice/SMS record."""
    while True:
        other = rng.choice(POPULATION)[1]
        if other != own_msisdn:
            return other


def planted(seq, day, file_id, sub, other, direction, at, dur,
            imei_override=None, kind="VOICE"):
    sub_imsi, sub_msisdn, sub_imei = sub
    amount = round(dur / 60 * 0.12, 4) if kind == "VOICE" else 0.05
    return [
        "{}-{:06d}".format(day.replace("-", ""), seq),
        file_id,
        _story_rng.choice(SWITCHES[:2]),
        kind,
        sub_imsi,
        sub_msisdn,
        other,
        "{:05d}-{:03d}".format(_story_rng.randrange(65536), _story_rng.randrange(256)),
        "NULL",
        at.strftime("%Y-%m-%d %H:%M:%S"),          # UTC
        day,
        str(dur),
        "",
        "",
        "{:.4f}".format(amount),
        "XTS",
        "HOME",
        "RATED",
        imei_override or sub_imei,
        direction,
    ]


def story_rows(day_ix, day, file_id, seq):
    """The four planted stories, for one day. Returns (rows, next_seq)."""
    rows = []
    base = dt.datetime.fromisoformat(day)

    def at(h_lo, h_hi):
        return base + dt.timedelta(seconds=_story_rng.randrange(h_lo * 3600, h_hi * 3600))

    # (a) burner rotation — one IMEI, a different SIM every day or two
    for sim_ix in BURNER_DAY_SIMS[day_ix]:
        b_imsi, b_msisdn = BURNER_SIMS[sim_ix]
        for _ in range(4):
            rows.append(planted(seq, day, file_id, (b_imsi, b_msisdn, BURNER_IMEI),
                                _story_rng.choice(POPULATION)[1], MOC, at(1, 5),
                                _story_rng.randrange(20, 180)))
            seq += 1

    # (b) one-way hub — 15 fresh contacts a day, always MOC, never called back
    for contact in HUB_CONTACTS[day_ix * 15:(day_ix + 1) * 15]:
        rows.append(planted(seq, day, file_id, HUB, contact, MOC, at(8, 20),
                            _story_rng.randrange(8, 45)))
        seq += 1

    # (c) repeating pair — same two parties, same three minutes, every day
    for i, (h, m) in enumerate(PAIR_TIMES):
        rows.append(planted(seq, day, file_id, PAIR_A, PAIR_B[1],
                            MOC if i % 2 == 0 else MTC,
                            base + dt.timedelta(hours=h, minutes=m), 300 + i * 60))
        seq += 1

    # (d) one SIM moved between three handsets — the inverse of (a)
    for _ in range(3):
        rows.append(planted(seq, day, file_id, SWAP_SUB,
                            _story_rng.choice(POPULATION)[1], MTC, at(10, 23),
                            _story_rng.randrange(30, 900),
                            imei_override=SWAP_IMEIS[day_ix]))
        seq += 1

    return rows, seq


def write(path, rows):
    with path.open("w", encoding="utf-8", newline="\n") as fh:
        fh.write("# post-mediated xDR export — synthetic, generated by gen-postmed-xdr.py"
                 " — all timestamps are UTC\n")
        fh.write("|".join(HEADER) + "\n")
        for row in rows:
            fh.write("|".join(row) + "\n")
    print("wrote {} ({} rows)".format(path.name, len(rows)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed-inbox", action="store_true",
                    help="copy the generated files into the pipeline poll directory")
    args = ap.parse_args()

    rng = random.Random(20260922)
    OUT.mkdir(parents=True, exist_ok=True)

    for day_ix, day in enumerate(DAYS):
        file_id = "MED-{}-001".format(day.replace("-", ""))
        rows = [record(rng, i + 1, day, file_id) for i in range(ROWS_PER_DAY)]
        story, _ = story_rows(day_ix, day, file_id, ROWS_PER_DAY + 1)
        rows.extend(story)
        write(OUT / "PMXDR_{}_001.psv".format(day.replace("-", "")), rows)

    # A deliberately defective interval file. Mediation output is not always clean, and a
    # pipeline that has never seen a bad row has not been tested. Kept as a separate file so
    # the happy path stays reproducible.
    bad = [record(rng, i + 1, "2026-09-04", "MED-20260904-001") for i in range(20)]
    bad[3][11] = "not-a-number"          # DURATION_SEC — unparseable integer
    bad[7][10] = "2026-13-45"            # EVENT_DATE — impossible calendar date
    bad[11] = bad[10][:]                 # exact duplicate record
    bad[15] = bad[15][:9]                # short row — truncated mid-write
    write(OUT / "PMXDR_20260904_001.psv.defect", bad)

    if args.seed_inbox:
        INBOX.mkdir(parents=True, exist_ok=True)
        for src in sorted(OUT.glob("*.psv")):
            shutil.copy2(src, INBOX / src.name)
        print("seeded inbox: {}".format(INBOX))


if __name__ == "__main__":
    main()
