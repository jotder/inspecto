#!/usr/bin/env python3
"""Regenerate the `la-showcase` Space Template's mule-transfer corpus - SYNTHETIC, deterministic.

Writes under spaces/_templates/la-showcase/data/:
  inbox/mule_transfers/TRANSFERS_20260901..03.csv   the three LANDED days (ingested when the Space boots)
  staged/mule_transfers/TRANSFERS_20260904.csv      the NEXT day  (copy into data/inbox/mule_transfers)
  staged/mule_transfers/TRANSFERS_20260906.csv      a GAP day     (20260905 is missing -> a delivery-gap signal)

Derived from spaces/demo/data/samples/gen-link-analysis-demos.py (same planted stories: MULE-HUB-01 structuring and
pass-through, the SHELL-A->B->C->D->A circular loop, the TILL-06 cash-out and the SKIMMER-01 benefit skim) with ONE
deliberate difference - the ordinary traffic is ACYCLIC and TRIANGLE-FREE (accounts 1000-1034 pay accounts 1035-1069, never the
reverse) and small, so the demo's two promises hold and are pinned by LaShowcaseGoldenTest: MULE-HUB-01 ranks in the top 3 of the
Suspicion score, and the planted ring is the ONLY (hence shortest) directed cycle. Every value is invented.
"""
from __future__ import annotations

import csv
import random
from datetime import datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "spaces" / "_templates" / "la-showcase" / "data"
rng = random.Random(20261010)
LANDED = [datetime(2026, 9, 1), datetime(2026, 9, 2), datetime(2026, 9, 3)]
NEXT_DAY = datetime(2026, 9, 4)
GAP_DAY = datetime(2026, 9, 6)

N_NORMAL, N_ORDINARY_PER_DAY, N_CASHOUT_PER_DAY = 70, 50, 10
HALF = N_NORMAL // 2
NORMAL = [f"ACC-{1000 + i}" for i in range(N_NORMAL)]
SMURFS = [f"SMURF-{i:02d}" for i in range(1, 13)]
HUB, RELAYS, OFFSHORE = "MULE-HUB-01", ["RELAY-01", "RELAY-02"], "OFFSHORE-77"
SHELLS = ["SHELL-A", "SHELL-B", "SHELL-C", "SHELL-D"]
TILLS = [f"TILL-{i:02d}" for i in range(1, 7)]
CASHERS = [f"CASHER-{i:02d}" for i in range(1, 9)]
BENEFICIARIES = [f"BENEFICIARY-{i:02d}" for i in range(1, 7)]
HUB2, GOV, SKIMMER = "MULE-HUB-02", "GOV-BENEFITS", "SKIMMER-01"
ALL = NORMAL + SMURFS + [HUB, OFFSHORE] + RELAYS + SHELLS + TILLS + CASHERS + BENEFICIARIES + [HUB2, GOV, SKIMMER]
COUNTRY = {a: rng.choice(["DE", "NL", "FR", "ES", "IT", "PL"]) for a in ALL}
COUNTRY[OFFSHORE] = "KY"
HEADER = ["TRANSFER_ID", "PAYER_ACCOUNT", "PAYEE_ACCOUNT", "CHANNEL", "AMOUNT", "CURRENCY", "PAYER_COUNTRY",
          "PAYEE_COUNTRY", "BOOKED_DATE", "BOOKED_AT"]
seq = 0


def ts(day, lo=0, hi=24):
    return day + timedelta(seconds=rng.randint(lo * 3600, hi * 3600 - 1))


def tx(rows, payer, payee, channel, amount, when):
    global seq
    seq += 1
    rows.append([f"TX-{seq:06d}", payer, payee, channel, f"{amount:.2f}", "EUR", COUNTRY[payer], COUNTRY[payee],
                 when.strftime("%Y-%m-%d"), when.strftime("%Y-%m-%d %H:%M:%S")])


def day_rows(day, structuring):
    rows = []
    for _ in range(N_ORDINARY_PER_DAY):                    # ordinary: first half pays second half
        i, j = rng.randrange(HALF), rng.randrange(HALF, N_NORMAL)
        tx(rows, NORMAL[i], NORMAL[j], rng.choices(["wire", "card", "crypto", "cash_deposit"], [6, 5, 1, 2])[0],
           round(rng.lognormvariate(5.0, 0.9), 2), ts(day))
    if structuring:                                        # 12 smurfs x 4 deposits just under 1 000 -> the hub
        for s in SMURFS:
            for _ in range(4):
                tx(rows, s, HUB, "cash_deposit", round(rng.uniform(900, 990), 2), ts(day, 9, 18))
        intake = 12 * 4 * 945
        for r in RELAYS:                                   # pass-through: ~98 % forwarded within hours
            when = ts(day, 19, 22)
            tx(rows, HUB, r, "wire", round(intake * 0.49, 2), when)
            tx(rows, r, OFFSHORE, "crypto", round(intake * 0.49 * 0.985, 2), when + timedelta(hours=rng.randint(1, 3)))
    amt = 24000.0                                          # the ring: 2.5 % decay per hop, two laps a day
    for lap in range(2):
        for k in range(4):
            amt = round(amt * 0.975, 2)
            tx(rows, SHELLS[k], SHELLS[(k + 1) % 4], "wire", amt, ts(day, 10 + lap * 5, 12 + lap * 5))
    for _ in range(N_CASHOUT_PER_DAY):                     # ordinary cash-out across tills 1-5
        tx(rows, rng.choice(NORMAL[:HALF]), rng.choice(TILLS[:5]), "cash_out", round(rng.lognormvariate(4.5, 0.6), 2),
           ts(day, 8, 20))
    for c in CASHERS:                                      # cash-out through TILL-06
        amount = round(rng.uniform(3800, 4200), 2)
        when = ts(day, 8, 11)
        tx(rows, HUB2, c, "wire", amount, when)
        tx(rows, c, TILLS[5], "cash_out", round(amount * rng.uniform(0.94, 0.97), 2),
           when + timedelta(hours=rng.randint(1, 4)))
    for b in BENEFICIARIES:                                # benefit skim
        amount = round(rng.uniform(420, 480), 2)
        when = ts(day, 6, 8)
        tx(rows, GOV, b, "benefit", amount, when)
        tx(rows, b, SKIMMER, "wire", round(amount * 0.60, 2), when + timedelta(hours=rng.randint(2, 24)))
    return rows


def write(sub, day, rows):
    out = ROOT / sub / "mule_transfers"
    out.mkdir(parents=True, exist_ok=True)
    f = out / f"TRANSFERS_{day:%Y%m%d}.csv"
    with f.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(HEADER)
        w.writerows(rows)
    print(f"{f.relative_to(ROOT)}: {len(rows)} rows")


for n, day in enumerate(LANDED):
    write("inbox", day, day_rows(day, structuring=n < 2))
write("staged", NEXT_DAY, day_rows(NEXT_DAY, structuring=False))
write("staged", GAP_DAY, day_rows(GAP_DAY, structuring=False))
