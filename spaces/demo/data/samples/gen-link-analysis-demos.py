#!/usr/bin/env python3
"""Regenerate the two Link Analysis demo feeds — SYNTHETIC data, deterministic (seed 20260901).

Writes:
  roaming_tap/TAP_2026090{1,2,3}.csv          — inter-operator roaming (TAP) charge records
  mule_transfers/TRANSFERS_2026090{1,2,3}.csv — account-to-account transfers with a planted mule ring,
                                                 plus the LA-18 cash-out and benefit-skim stories

Every value is invented: operators are named after minerals/weather, PLMNs use the 001 test MCC,
IMSIs are 001-prefixed and sequential, accounts are ACC-1xxx. Nothing here is trimmed from a capture.
The CSVs are committed (the samples/ exception in .gitignore); run this only to change the story.
"""
from __future__ import annotations

import csv
import random
from datetime import datetime, timedelta
from pathlib import Path

HERE = Path(__file__).resolve().parent
rng = random.Random(20260901)
DAYS = [datetime(2026, 9, 1), datetime(2026, 9, 2), datetime(2026, 9, 3)]


def ts(day: datetime, lo_h=0, hi_h=24) -> datetime:
    return day + timedelta(seconds=rng.randint(lo_h * 3600, hi_h * 3600 - 1))


def fmt(t: datetime) -> str:
    return t.strftime("%Y-%m-%d %H:%M:%S")


# ── 1. Roaming (TAP) between partner operators ────────────────────────────────────────────────────
OPERATORS = [  # (name, plmn, country) — synthetic; 001 is the ITU test MCC
    ("Aurora Telecom", "00101", "AT"), ("Borealis Mobile", "00102", "BM"), ("Cinder Wireless", "00103", "CW"),
    ("Delta Cell", "00104", "DC"), ("Ember Networks", "00105", "EN"), ("Fjord Telecom", "00106", "FT"),
    ("Granite Mobile", "00107", "GM"), ("Harbor Wireless", "00108", "HW"), ("Ivory Cellular", "00111", "IC"),
    ("Juniper Mobile", "00112", "JM"), ("Kestrel Telecom", "00113", "KT"), ("Lumen Wireless", "00114", "LW"),
    ("Northwind Mobile", "00116", "NM"), ("Quartz Mobile", "00119", "QM"),
]
BY_NAME = {o[0]: o for o in OPERATORS}

# Story (e): partners do not agree on how to spell one operator's name. Real interconnect feeds are like
# this — the PLMN is the contract key and the name is free text typed by whoever built the file. Only the
# NAME varies; 00103 is always 00103, so the ground truth stays recoverable.
SPELLING_VARIANTS = {
    "Cinder Wireless": [
        ("Cinder Wireless", 60),    # canonical
        ("CINDER WIRELESS", 20),    # a partner that upper-cases every name field
        ("Cinder Wireless.", 12),   # a trailing period from a legal-name field
        ("Cinder  Wireless", 8),    # a double space nobody ever noticed
    ],
}
_variant_rng = random.Random(20260903)


def spell(name: str) -> str:
    """How this operator's name is WRITTEN in a given record — canonical unless it is the story-(e) one."""
    variants = SPELLING_VARIANTS.get(name)
    if not variants:
        return name
    return _variant_rng.choices([v for v, _ in variants], weights=[w for _, w in variants])[0]
# event type → (unit label, base SDR per unit, typical units range)
EVENTS = {
    "MOC": (60, 0.42, (1, 40)),        # mobile-originated call, units = minutes
    "MTC": (60, 0.18, (1, 40)),        # mobile-terminated call
    "SMS_MO": (1, 0.06, (1, 12)),
    "GPRS": (1, 0.011, (5, 900)),      # units = MB
}
# Bilateral agreements: who roams where, with a relative volume weight. Quartz↔Fjord is the big pair.
AGREEMENTS = [
    ("Aurora Telecom", "Northwind Mobile", 4), ("Aurora Telecom", "Ivory Cellular", 3), ("Aurora Telecom", "Juniper Mobile", 2),
    ("Borealis Mobile", "Ivory Cellular", 3), ("Borealis Mobile", "Kestrel Telecom", 2), ("Borealis Mobile", "Lumen Wireless", 2),
    ("Cinder Wireless", "Juniper Mobile", 3), ("Cinder Wireless", "Northwind Mobile", 1), ("Cinder Wireless", "Quartz Mobile", 2),
    ("Delta Cell", "Kestrel Telecom", 4), ("Delta Cell", "Lumen Wireless", 2),
    ("Ember Networks", "Ivory Cellular", 2), ("Ember Networks", "Northwind Mobile", 2), ("Ember Networks", "Granite Mobile", 1),
    ("Fjord Telecom", "Quartz Mobile", 9), ("Fjord Telecom", "Lumen Wireless", 2),
    ("Granite Mobile", "Harbor Wireless", 3), ("Granite Mobile", "Juniper Mobile", 1),
    ("Harbor Wireless", "Granite Mobile", 3), ("Harbor Wireless", "Kestrel Telecom", 1),
    ("Quartz Mobile", "Fjord Telecom", 8), ("Ivory Cellular", "Aurora Telecom", 2), ("Kestrel Telecom", "Delta Cell", 2),
    ("Northwind Mobile", "Aurora Telecom", 1), ("Lumen Wireless", "Borealis Mobile", 1), ("Juniper Mobile", "Cinder Wireless", 1),
]
# Planted stories the analyst should find:
#  (a) Northwind Mobile bills Aurora Telecom GPRS at 3.2× the agreed rate → one edge's Σ CHARGE_SDR is far off.
#  (b) Kestrel Telecom's TAP files are REJECTED ~40 % of the time → a settlement-status filter isolates it.
#  (c) Quartz↔Fjord carry half of all traffic → the backbone/cut-point tools find the dependency.
#  (d) IMSI 001010000000042 appears on FOUR visited networks in 3 days → an impossible-travel roamer.
#  (e) Cinder Wireless is SPELLED FOUR WAYS across partners — its PLMN (00103) never varies. Projecting
#      on SENDER_NAME/RECIPIENT_NAME splits one partner into four nodes with four degree counts and four
#      community memberships; projecting on SENDER_PLMN does not. This is decision D-S4's risk made
#      visible: the display string is not an identity, and the feed itself proves it by carrying the key
#      alongside. Before this, every entity in the corpus came from a canonical literal, so the split
#      could not occur at all and the Link Analysis split-identity notice always read zero.
imsi_pool = {home: [f"{BY_NAME[home][1]}{n:010d}" for n in range(1, 16)] for home, _, _ in AGREEMENTS}
TRAVELLER = "001010000000042"
weights = [w for _, _, w in AGREEMENTS]
tap_rows: dict[str, list[list[str]]] = {}
tap_seq = 0
for day in DAYS:
    fid_base = f"TAP-{day:%Y%m%d}"
    rows = []
    for _ in range(620):
        home, visited, _w = rng.choices(AGREEMENTS, weights=weights)[0]
        ev = rng.choices(list(EVENTS), weights=[5, 4, 3, 6])[0]
        _unit, rate, (lo, hi) = EVENTS[ev]
        units = rng.randint(lo, hi)
        if home == "Aurora Telecom" and visited == "Northwind Mobile" and ev == "GPRS":
            rate *= 3.2                                        # story (a)
        charge = round(units * rate * rng.uniform(0.92, 1.08), 4)
        status = "ACCEPTED"
        if visited == "Kestrel Telecom" and rng.random() < 0.4:
            status = "REJECTED"                                # story (b)
        elif rng.random() < 0.03:
            status = "DISPUTED"
        imsi = rng.choice(imsi_pool[home])
        tap_seq += 1
        rows.append([
            f"{fid_base}-{tap_seq:05d}", BY_NAME[visited][1], spell(visited), BY_NAME[home][1], spell(home),
            ev, imsi,
            str(units), f"{charge:.4f}", f"{charge * 0.15:.4f}", day.strftime("%Y-%m-%d"), fmt(ts(day)), status,
        ])
    for visited in ["Northwind Mobile", "Ivory Cellular", "Juniper Mobile", "Quartz Mobile"][: 2 if day == DAYS[0] else 1]:
        tap_seq += 1                                           # story (d): the impossible traveller
        rows.append([
            f"{fid_base}-{tap_seq:05d}", BY_NAME[visited][1], spell(visited), "00101", "Aurora Telecom",
            "MOC", TRAVELLER,
            "12", "5.0400", "0.7560", day.strftime("%Y-%m-%d"), fmt(ts(day, 8, 20)), "ACCEPTED",
        ])
    tap_rows[f"TAP_{day:%Y%m%d}.csv"] = rows

TAP_HEADER = ["TAP_FILE_ID", "SENDER_PLMN", "SENDER_NAME", "RECIPIENT_PLMN", "RECIPIENT_NAME", "EVENT_TYPE", "IMSI",
              "CHARGED_UNITS", "CHARGE_SDR", "TAX_SDR", "EVENT_DATE", "EVENT_AT", "SETTLEMENT_STATUS"]

# ── 2. Fraud: a money-mule layering ring inside ordinary transfers ────────────────────────────────
NORMAL = [f"ACC-{1000 + i}" for i in range(150)]
SMURFS = [f"SMURF-{i:02d}" for i in range(1, 13)]
HUB, RELAYS, OFFSHORE = "MULE-HUB-01", ["RELAY-01", "RELAY-02"], "OFFSHORE-77"
SHELLS = ["SHELL-A", "SHELL-B", "SHELL-C", "SHELL-D"]
COUNTRY = {a: rng.choice(["DE", "NL", "FR", "ES", "IT", "PL"]) for a in NORMAL + SMURFS + [HUB] + RELAYS + SHELLS}
COUNTRY[OFFSHORE] = "KY"
CHANNELS = ["wire", "card", "crypto", "cash_deposit"]
tx_rows: dict[str, list[list[str]]] = {}
tx_seq = 0


def tx(rows, day, payer, payee, channel, amount, when=None):
    global tx_seq
    tx_seq += 1
    when = when or ts(day)
    rows.append([
        f"TX-{tx_seq:06d}", payer, payee, channel, f"{amount:.2f}", "EUR", COUNTRY[payer], COUNTRY[payee],
        when.strftime("%Y-%m-%d"), fmt(when),
    ])
    return when


for di, day in enumerate(DAYS):
    rows = []
    for _ in range(600):                                       # ordinary traffic: a long tail of small transfers
        a, b = rng.sample(NORMAL, 2)
        tx(rows, day, a, b, rng.choices(CHANNELS, weights=[6, 5, 1, 2])[0], round(rng.lognormvariate(5.0, 0.9), 2))
    if di < 2:                                                 # structuring: 12 smurfs × 4/day, each just under 1 000
        for s in SMURFS:
            for _ in range(4):
                tx(rows, day, s, HUB, "cash_deposit", round(rng.uniform(900, 990), 2), ts(day, 9, 18))
        intake = 12 * 4 * 945
        for r in RELAYS:                                       # pass-through: 98 % forwarded within hours
            when = ts(day, 19, 22)
            tx(rows, day, HUB, r, "wire", round(intake * 0.49, 2), when)
            tx(rows, day, r, OFFSHORE, "crypto", round(intake * 0.49 * 0.985, 2), when + timedelta(hours=rng.randint(1, 3)))
    amt = 24000.0                                              # circular financing among four shells, 10 % decay per lap
    for lap in range(2):
        for i in range(4):
            amt = round(amt * 0.975, 2)
            tx(rows, day, SHELLS[i], SHELLS[(i + 1) % 4], "wire", amt, ts(day, 10 + lap * 5, 12 + lap * 5))
    tx_rows[f"TRANSFERS_{day:%Y%m%d}.csv"] = rows

# ── Story (f)+(g), LA-18 value Measures (2026-09-30) — APPENDED after every earlier row, from its OWN RNG and its
# own id sequence (TXV-…), so every row above is byte-identical to the pre-LA-18 corpus (as story (e) did for D-S4).
#  (f) cash-out through one till: 8 CASHER accounts each take a ~4 000 wire from MULE-HUB-02 every morning and cash
#      out ~95 % at TILL-06 within 1–4 h; ordinary accounts cash out small sums across TILL-01…05. Exercises
#      timeToCashOut, cashOutConcentration (TILL-06 ≈ 80 % of all cash-out, from 8 payers) and passThrough.
#  (g) benefit skim: GOV-BENEFITS pays 6 BENEFICIARY accounts (and 20 ordinary ones) ~450 a day; each BENEFICIARY
#      forwards 60 % to SKIMMER-01 within 2–24 h. Exercises benefitTransfer (6 recipients → one counterparty).
vrng = random.Random(20260930)
TILLS = [f"TILL-{i:02d}" for i in range(1, 7)]
CASHERS = [f"CASHER-{i:02d}" for i in range(1, 9)]
BENEFICIARIES = [f"BENEFICIARY-{i:02d}" for i in range(1, 7)]
HUB2, GOV, SKIMMER = "MULE-HUB-02", "GOV-BENEFITS", "SKIMMER-01"
for acct in TILLS + CASHERS + BENEFICIARIES + [HUB2, GOV, SKIMMER]:
    COUNTRY[acct] = vrng.choice(["DE", "NL", "FR", "ES", "IT", "PL"])
vseq = 0


def vts(day, lo_h=0, hi_h=24):
    return day + timedelta(seconds=vrng.randint(lo_h * 3600, hi_h * 3600 - 1))


def vtx(rows, day, payer, payee, channel, amount, when):
    global vseq
    vseq += 1
    rows.append([
        f"TXV-{vseq:05d}", payer, payee, channel, f"{amount:.2f}", "EUR", COUNTRY[payer], COUNTRY[payee],
        when.strftime("%Y-%m-%d"), fmt(when),
    ])


for day in DAYS:
    rows = tx_rows[f"TRANSFERS_{day:%Y%m%d}.csv"]
    for _ in range(60):                                        # ordinary cash-out: small sums, five tills
        vtx(rows, day, vrng.choice(NORMAL), vrng.choice(TILLS[:5]), "cash_out",
            round(vrng.lognormvariate(4.5, 0.6), 2), vts(day, 8, 20))
    for c in CASHERS:                                          # (f) wire in each morning, cash out at TILL-06
        amount = round(vrng.uniform(3800, 4200), 2)
        when = vts(day, 8, 11)
        vtx(rows, day, HUB2, c, "wire", amount, when)
        vtx(rows, day, c, TILLS[5], "cash_out", round(amount * vrng.uniform(0.94, 0.97), 2),
            when + timedelta(hours=vrng.randint(1, 4)))
    for b in BENEFICIARIES:                                    # (g) benefit paid, 60 % skimmed on
        amount = round(vrng.uniform(420, 480), 2)
        when = vts(day, 6, 8)
        vtx(rows, day, GOV, b, "benefit", amount, when)
        vtx(rows, day, b, SKIMMER, "wire", round(amount * 0.60, 2), when + timedelta(hours=vrng.randint(2, 24)))
    for n in vrng.sample(NORMAL, 20):                          # benefit to ordinary accounts: not skimmed
        vtx(rows, day, GOV, n, "benefit", round(vrng.uniform(420, 480), 2), vts(day, 6, 8))

TX_HEADER = ["TRANSFER_ID", "PAYER_ACCOUNT", "PAYEE_ACCOUNT", "CHANNEL", "AMOUNT", "CURRENCY", "PAYER_COUNTRY",
             "PAYEE_COUNTRY", "BOOKED_DATE", "BOOKED_AT"]


def write(folder: str, header: list[str], files: dict[str, list[list[str]]]) -> None:
    out = HERE / folder
    out.mkdir(parents=True, exist_ok=True)
    for name, rows in files.items():
        with (out / name).open("w", newline="", encoding="utf-8") as fh:
            w = csv.writer(fh)
            w.writerow(header)
            w.writerows(rows)
        print(f"{folder}/{name}: {len(rows)} rows")


write("roaming_tap", TAP_HEADER, tap_rows)
write("mule_transfers", TX_HEADER, tx_rows)
