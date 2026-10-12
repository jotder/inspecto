#!/usr/bin/env python3
"""Regenerate the `la-showcase` Space Template's mule-transfer corpus - SYNTHETIC, deterministic.

Writes under spaces/_templates/la-showcase/data/:
  inbox/mule_transfers/TRANSFERS_20260901..03.csv   the three LANDED days (ingested when the Space boots)
  staged/mule_transfers/TRANSFERS_20260904.csv      the NEXT day  (copy into data/inbox/mule_transfers)
  staged/mule_transfers/TRANSFERS_20260906.csv      a GAP day     (20260905 is missing -> a delivery-gap signal)

Also writes the GEO scene (inbox/mule_account_locations, inbox/mule_geo_legs): synthetic city-level coordinates for every
account and the planted stories' legs, geocoded. Drawn from a SEPARATE generator, so the transfer files never change.

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


landed_rows = []
for n, day in enumerate(LANDED):
    rows = day_rows(day, structuring=n < 2)
    landed_rows.extend(rows)
    write("inbox", day, rows)
write("staged", NEXT_DAY, day_rows(NEXT_DAY, structuring=False))
write("staged", GAP_DAY, day_rows(GAP_DAY, structuring=False))

# --- GEO scene (operator 2026-10-12): SYNTHETIC account locations + the planted stories' legs, geocoded -------------
# Uses its OWN generator (never `rng`), so the transfer corpus above is byte-identical with or without this block.
# Every place is a CITY centroid plus a tiny deterministic jitter; no real person or address is implied. Each account
# sits in the country the feed already gives it (COUNTRY), so PAYER_COUNTRY and the map never contradict.
geo = random.Random(20261012)
CITY = {                                   # name: (lat, lon)
    "Madrid": (40.4168, -3.7038), "Barcelona": (41.3874, 2.1686), "Valencia": (39.4699, -0.3763),
    "Soria": (41.7664, -2.4790), "Teruel": (40.3456, -1.1065), "Zamora": (41.5033, -5.7446), "Cuenca": (40.0704, -2.1374),
    "Rome": (41.9028, 12.4964), "Milan": (45.4642, 9.1900), "Turin": (45.0703, 7.6869), "Naples": (40.8518, 14.2681),
    "Matera": (40.6664, 16.6043), "Potenza": (40.6404, 15.8056), "Campobasso": (41.5603, 14.6627),
    "Warsaw": (52.2297, 21.0122), "Krakow": (50.0647, 19.9450), "Gdansk": (54.3520, 18.6466), "Wroclaw": (51.1079, 17.0385),
    "Suwalki": (54.1118, 22.9308), "Zamosc": (50.7231, 23.2519), "Przemysl": (49.7838, 22.7678),
    "Paris": (48.8566, 2.3522), "Lyon": (45.7640, 4.8357), "Marseille": (43.2965, 5.3698), "Toulouse": (43.6047, 1.4442),
    "Aurillac": (44.9300, 2.4400), "Gueret": (46.1700, 1.8700), "Mende": (44.5180, 3.5010),
    "Rotterdam": (51.9244, 4.4777), "Amsterdam": (52.3676, 4.9041), "Utrecht": (52.0907, 5.1214), "Eindhoven": (51.4416, 5.4697),
    "Leeuwarden": (53.2012, 5.7999), "Emmen": (52.7792, 6.9069), "Middelburg": (51.4988, 3.6110),
    "Berlin": (52.5200, 13.4050), "Hamburg": (53.5511, 9.9937), "Munich": (48.1351, 11.5820), "Cologne": (50.9375, 6.9603),
    "Cottbus": (51.7563, 14.3329), "Hof": (50.3127, 11.9124), "Flensburg": (54.7937, 9.4469),
    "George Town": (19.2866, -81.3744),
}
METRO = {"ES": ["Madrid", "Barcelona", "Valencia"], "IT": ["Rome", "Milan", "Turin", "Naples"],
         "PL": ["Warsaw", "Krakow", "Gdansk", "Wroclaw"], "FR": ["Paris", "Lyon", "Marseille", "Toulouse"],
         "NL": ["Rotterdam", "Amsterdam", "Utrecht", "Eindhoven"], "DE": ["Berlin", "Hamburg", "Munich", "Cologne"]}
RURAL = {"ES": ["Soria", "Teruel", "Zamora", "Cuenca"], "IT": ["Matera", "Potenza", "Campobasso"],
         "PL": ["Suwalki", "Zamosc", "Przemysl"], "FR": ["Aurillac", "Gueret", "Mende"],
         "NL": ["Leeuwarden", "Emmen", "Middelburg"], "DE": ["Cottbus", "Hof", "Flensburg"]}
PLACE = {HUB: ("Rotterdam", "metro_branch"), RELAYS[0]: ("Madrid", "metro_branch"), RELAYS[1]: ("Warsaw", "metro_branch"),
         OFFSHORE: ("George Town", "offshore"), HUB2: ("Marseille", "metro_branch"), GOV: ("Warsaw", "metro_branch"),
         SKIMMER: ("Berlin", "online"), SHELLS[0]: ("Valencia", "online"), SHELLS[1]: ("Milan", "online"),
         SHELLS[2]: ("Lyon", "online"), SHELLS[3]: ("Turin", "online"), TILLS[0]: ("Amsterdam", "till"),
         TILLS[1]: ("Rome", "till"), TILLS[2]: ("Utrecht", "till"), TILLS[3]: ("Barcelona", "till"),
         TILLS[4]: ("Krakow", "till"), TILLS[5]: ("Paris", "till")}
for i, a in enumerate(SMURFS):             # cash-in at small-town branches, spread over their own country's rural list
    PLACE[a] = (RURAL[COUNTRY[a]][i % len(RURAL[COUNTRY[a]])], "rural_branch")
for i, a in enumerate(CASHERS):
    PLACE[a] = (METRO[COUNTRY[a]][i % len(METRO[COUNTRY[a]])], "metro_branch")
for i, a in enumerate(BENEFICIARIES):
    PLACE[a] = (RURAL[COUNTRY[a]][i % len(RURAL[COUNTRY[a]])], "rural_branch")
for a in NORMAL:
    PLACE[a] = (geo.choice(METRO[COUNTRY[a]]), geo.choice(["online", "online", "metro_branch", "atm"]))
LOC = {}
for a in ALL:
    city, site = PLACE[a]
    lat, lon = CITY[city]
    LOC[a] = (city, round(lat + geo.uniform(-0.03, 0.03), 4), round(lon + geo.uniform(-0.03, 0.03), 4), site)


def write_csv(sub, name, header, rows):
    out = ROOT / sub
    out.mkdir(parents=True, exist_ok=True)
    f = out / name
    with f.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)
    print(f"{f.relative_to(ROOT)}: {len(rows)} rows")


write_csv("inbox/mule_account_locations", "MULE_ACCOUNT_LOCATIONS_20260901.csv",
          ["ACCOUNT", "CITY", "COUNTRY", "LAT", "LON", "SITE_TYPE"],
          [[a, LOC[a][0], COUNTRY[a], f"{LOC[a][1]:.4f}", f"{LOC[a][2]:.4f}", LOC[a][3]] for a in ALL])

# The geo routes read origin AND destination coordinates from ONE relation, and a virtual Dataset reads one store, so the
# stories' legs are pre-joined here: one row per distinct (scene, payer, payee, channel) over the three landed days.
ORDINARY_CASHOUT = set(TILLS[:5])
legs = {}
for r in landed_rows:
    payer, payee, channel, amount = r[1], r[2], r[3], float(r[4])
    if payee == HUB and payer in SMURFS:
        scene, role = "ring", "structuring"
    elif payer == HUB and payee in RELAYS:
        scene, role = "ring", "pass_through"
    elif payer in RELAYS and payee == OFFSHORE:
        scene, role = "ring", "offshore"
    elif payer in SHELLS and payee in SHELLS:
        scene, role = "ring", "circular"
    elif payer == HUB2 and payee in CASHERS:
        scene, role = "cashout", "funding"
    elif payee == TILLS[5]:
        scene, role = "cashout", "till_06"
    elif payee in ORDINARY_CASHOUT:
        scene, role = "cashout", "ordinary_till"
    else:
        continue
    k = (scene, role, payer, payee, channel)
    n, total = legs.get(k, (0, 0.0))
    legs[k] = (n + 1, total + amount)
write_csv("inbox/mule_geo_legs", "MULE_GEO_LEGS_20260901.csv",
          ["SCENE", "ROLE", "FROM_ACCOUNT", "TO_ACCOUNT", "CHANNEL", "FROM_CITY", "TO_CITY", "FROM_LAT", "FROM_LON",
           "TO_LAT", "TO_LON", "TRANSFERS", "TOTAL_AMOUNT"],
          [[sc, ro, p, q, ch, LOC[p][0], LOC[q][0], f"{LOC[p][1]:.4f}", f"{LOC[p][2]:.4f}", f"{LOC[q][1]:.4f}",
            f"{LOC[q][2]:.4f}", n, f"{t:.2f}"] for (sc, ro, p, q, ch), (n, t) in sorted(legs.items())])
