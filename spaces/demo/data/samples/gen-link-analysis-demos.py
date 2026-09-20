#!/usr/bin/env python3
"""Regenerate the two Link Analysis demo feeds — SYNTHETIC data, deterministic (seed 20260901).

Writes:
  roaming_tap/TAP_2026090{1,2,3}.csv          — inter-operator roaming (TAP) charge records
  mule_transfers/TRANSFERS_2026090{1,2,3}.csv — account-to-account transfers with a planted mule ring

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
            f"{fid_base}-{tap_seq:05d}", BY_NAME[visited][1], visited, BY_NAME[home][1], home, ev, imsi,
            str(units), f"{charge:.4f}", f"{charge * 0.15:.4f}", day.strftime("%Y-%m-%d"), fmt(ts(day)), status,
        ])
    for visited in ["Northwind Mobile", "Ivory Cellular", "Juniper Mobile", "Quartz Mobile"][: 2 if day == DAYS[0] else 1]:
        tap_seq += 1                                           # story (d): the impossible traveller
        rows.append([
            f"{fid_base}-{tap_seq:05d}", BY_NAME[visited][1], visited, "00101", "Aurora Telecom", "MOC", TRAVELLER,
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
