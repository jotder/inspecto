#!/usr/bin/env python3
"""Regenerate the `la-mobile-money` Space Template's corpus - SYNTHETIC, deterministic, self-contained.

Writes under spaces/_templates/la-mobile-money/data/:
  inbox/wallet_txn/WALLET_TXN_20261001..14.csv     the 14 LANDED days (ingested when the Space boots)
  inbox/wallet_master/WALLET_MASTER_20261014.csv   420 wallets: msisdn, national id, handset, KYC tier, opened, prior lifetime volume
  inbox/agent_master/AGENT_MASTER_20261014.csv     40 agents
  staged/wallet_txn/WALLET_TXN_20261015.csv        the NEXT day (copy into data/inbox/wallet_txn)
  staged/wallet_txn/WALLET_TXN_20261017.csv        a GAP day (20261016 is missing -> a delivery-gap signal)

The plan is docs/superpower/la-multi-vertical-showcase-plan.md, vertical A ("the agent who never sleeps"). Seven planted
stories, each pinned by LaMobileMoneyGoldenTest (platform/inspecto-graph):

  1 agent-assisted structuring   AG-18 cashes in for 25 wallets (STRUCT-01..25) inside 36 h, every amount in the top 5 %
                                 band under the Tier-1 limit 5 000 (4 750 <= amount < 5 000); each wallet pays the hub
  2 mule chain                   HUB-01 forwards 98 % within 10 min to RELAY-1 / RELAY-2 -> RELAY-3 -> cash-out at AG-39
  3 device farm                  FARM-1..9 share ONE handset (DEV-FARM-01) and hold NINE different national ids
  4 round-trip loop              LOOP-1 -> 2 -> 3 -> 4 -> 1, the same 1 500.00 five times: the ONLY directed cycle
  5 dormant awakening            DORMANT-1 (idle since 2025-11, prior lifetime volume 150) moves 6 000 = 40 x in two days
  6 benign hub                   MER-ELECTRIC: 300 payers, one-way (no outflow)
  7 alert story                  the structuring Measure over the Dataset fires on HUB-01 (25 legs, 25 payers)

Design rules that make the promises hold (and keep the golden test honest):
  * the ordinary traffic is a LAYERED DAG: cash-in agents (AG-01..17) -> group-A wallets -> group-B wallets -> cash-out
    agents (AG-19..38, 40) and merchants. A wallet never both receives from and pays the same agent, so there is no
    accidental cycle, and the round-trip loop is the only directed cycle (shared_device / shared_id links are materialised
    ONE direction, lower id -> higher id, so they add none either);
  * every ordinary amount stays under 4 400, so nothing ordinary sits in the structuring band;
  * the planted stories are the ONLY use of AG-18 (rogue cash-in) and AG-39 gets ordinary cash-outs only from 3 wallets;
  * placeholder handsets/ids (UNKNOWN, blank, 000000000000000, PENDING) are held by 5-8 wallets each, under the pair cap, so
    ONLY the Job's explicit exclusion keeps them out of the shared links (pinned);
  * benign look-alikes: a household of 3 wallets sharing one national id, a family phone shared by 4 wallets.
Every value is invented. The staged days carry ordinary traffic only (no plant), so they add no cycle.
"""
from __future__ import annotations

import csv
import random
from datetime import datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "spaces" / "_templates" / "la-mobile-money" / "data"
rng = random.Random(20261016)

FIRST = datetime(2026, 10, 1)
LANDED = [FIRST + timedelta(days=i) for i in range(14)]
NEXT_DAY, GAP_DAY = datetime(2026, 10, 15), datetime(2026, 10, 17)

TIER_LIMIT = 5000.0
BAND_LO = TIER_LIMIT * 0.95

# ---- entities ---------------------------------------------------------------------------------------------------------
N_A, N_B = 190, 187
A_WALLETS = [f"W-{1000 + i}" for i in range(N_A)]
B_WALLETS = [f"W-{1000 + N_A + i}" for i in range(N_B)]
ORDINARY = A_WALLETS + B_WALLETS
STRUCT = [f"STRUCT-{i:02d}" for i in range(1, 26)]
HUB = "HUB-01"
RELAYS = ["RELAY-1", "RELAY-2"]
COLLECTOR = "RELAY-3"
FARM = [f"FARM-{i}" for i in range(1, 10)]
LOOP = [f"LOOP-{i}" for i in range(1, 5)]
DORMANT = "DORMANT-1"
PLANTED = STRUCT + [HUB] + RELAYS + [COLLECTOR] + FARM + LOOP + [DORMANT]
WALLETS = ORDINARY + PLANTED                                   # 377 + 43 = 420
AGENTS = [f"AG-{i:02d}" for i in range(1, 41)]
IN_AGENTS = AGENTS[:17]                                        # AG-01..17 ordinary cash-in
ROGUE = "AG-18"
OUT_AGENTS = AGENTS[18:38] + [AGENTS[39]]                      # AG-19..38 and AG-40 ordinary cash-out
MULE_AGENT = "AG-39"
BILLER = "MER-ELECTRIC"
MERCHANTS = [f"MER-{i:02d}" for i in range(1, 41)]
HEADER = ["txn_id", "src_id", "dst_id", "txn_type", "amount", "currency", "txn_date", "txn_ts"]
STAMP = "%Y-%m-%d %H:%M:%S"


def at(day, h, m=0, s=0):
    return day + timedelta(hours=h, minutes=m, seconds=s)


def amount(mu, sigma, cap=4400.0):
    while True:
        v = round(rng.lognormvariate(mu, sigma), 2)
        if 1.0 <= v < cap:
            return v


def clock(day, lo=6, hi=22):
    return day + timedelta(seconds=rng.randint(lo * 3600, hi * 3600 - 1))


# ---- the planted events ------------------------------------------------------------------------------------------------
planted = []                                                    # (ts, src, dst, type, amount)


def ev(ts, src, dst, typ, amt):
    planted.append((ts, src, dst, typ, round(amt, 2)))


# 1 + 2: structuring through the hub, the 98 % pass-through, the relays and the cash-out agent
start = at(LANDED[5], 18)                                       # 2026-10-06 18:00; last leg lands 2026-10-08 04:24
for i, w in enumerate(STRUCT):
    t = start + timedelta(minutes=86 * i)
    a = round(rng.uniform(BAND_LO, TIER_LIMIT - 0.01), 2)
    ev(t, ROGUE, w, "cash_in", a)
    t2 = t + timedelta(minutes=12)
    ev(t2, w, HUB, "p2p", a)
    t3 = t2 + timedelta(minutes=rng.randint(2, 8))
    out = round(a * 0.98, 2)
    ev(t3, HUB, RELAYS[i % 2], "p2p", out)
    t4 = t3 + timedelta(minutes=rng.randint(2, 9))
    ev(t4, RELAYS[i % 2], COLLECTOR, "p2p", out)
    ev(t4 + timedelta(minutes=rng.randint(2, 9)), COLLECTOR, MULE_AGENT, "cash_out", out)

# 4: round-trip loop, the same 1 500.00 around the circle five times
for w in LOOP:
    ev(at(LANDED[2], 8, rng.randint(0, 20)), "AG-03", w, "cash_in", 1500.00)
for lap in range(5):
    for k in range(4):
        ev(at(LANDED[2 + lap], 9, 20 * k, rng.randint(0, 59)), LOOP[k], LOOP[(k + 1) % 4], "p2p", 1500.00)

# 5: dormant awakening, 6 000 out in two days against a prior lifetime volume of 150
ev(at(LANDED[10], 7, 10), "AG-02", DORMANT, "cash_in", 3000.00)
ev(at(LANDED[11], 8, 20), "AG-02", DORMANT, "cash_in", 3000.00)
for k, dst in enumerate(B_WALLETS[:4]):
    ev(at(LANDED[10 + k // 2], 9, 30 * (k % 2)), DORMANT, dst, "p2p", 1500.00)

# 3: device farm, ordinary-looking spend through the same agents and merchants
for w in FARM:
    for _ in range(3):
        ev(clock(rng.choice(LANDED[1:12])), "AG-05", w, "cash_in", amount(5.6, 0.4))
    for m in ("MER-03", "MER-05", "MER-03", "MER-05"):
        ev(clock(rng.choice(LANDED)), w, m, "merchant_pay", amount(4.4, 0.5, 900))
    for _ in range(2):
        ev(clock(rng.choice(LANDED[2:])), w, "AG-30", "cash_out", amount(5.0, 0.4))

# 6: the benign hub: 300 payers, one-way
BILLER_PAYERS = rng.sample(ORDINARY, 300)
for w in BILLER_PAYERS:
    ev(clock(rng.choice(LANDED)), w, BILLER, "merchant_pay", amount(3.5, 0.5, 400))
for _ in range(100):
    ev(clock(rng.choice(LANDED)), rng.choice(BILLER_PAYERS), BILLER, "merchant_pay", amount(3.5, 0.5, 400))

# three wallets cash out at the mule agent for real (it is a busy agent, not an empty one)
for w in B_WALLETS[10:13]:
    ev(clock(rng.choice(LANDED)), w, MULE_AGENT, "cash_out", amount(4.8, 0.5, 600))


# ---- ordinary traffic: a layered DAG ----------------------------------------------------------------------------------
# Real wallets repeat their counterparties: each group-A wallet has a home cash-in agent, three regular payees in group B and
# a home merchant; each group-B wallet a home cash-out agent and a home merchant. This keeps the ordinary graph SPARSE (about
# six neighbours a wallet), so the planted hub (28 neighbours) is not lost in it and the Suspicion score ranks it near the top.
HOME_IN = {w: rng.choice(IN_AGENTS) for w in A_WALLETS}
PAYEES = {w: rng.sample(B_WALLETS, 3) for w in A_WALLETS}
HOME_OUT = {w: rng.choice(OUT_AGENTS) for w in B_WALLETS}
HOME_MER = {w: rng.choice(MERCHANTS) for w in ORDINARY}


def ordinary(day):
    rows = []
    for _ in range(rng.randint(90, 110)):
        w = rng.choice(A_WALLETS)
        rows.append((clock(day), HOME_IN[w], w, "cash_in", amount(5.5, 0.8)))
    for _ in range(rng.randint(140, 160)):
        w = rng.choice(A_WALLETS)
        rows.append((clock(day), w, rng.choice(PAYEES[w]), "p2p", amount(5.0, 0.9)))
    for _ in range(rng.randint(55, 65)):
        w = rng.choice(B_WALLETS)
        rows.append((clock(day), w, HOME_OUT[w], "cash_out", amount(5.0, 0.7)))
    for _ in range(rng.randint(50, 60)):
        w = rng.choice(ORDINARY)
        rows.append((clock(day), w, HOME_MER[w], "merchant_pay", amount(4.0, 0.7, 900)))
    return rows


def write_day(sub, day, rows):
    rows = sorted(rows, key=lambda r: (r[0], r[1], r[2]))
    out = ROOT / sub / "wallet_txn"
    out.mkdir(parents=True, exist_ok=True)
    f = out / f"WALLET_TXN_{day:%Y%m%d}.csv"
    with f.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(HEADER)
        for n, (ts, src, dst, typ, amt) in enumerate(rows, 1):
            w.writerow([f"TXN-{day:%Y%m%d}-{n:05d}", src, dst, typ, f"{amt:.2f}", "USD", ts.strftime("%Y-%m-%d"), ts.strftime(STAMP)])
    print(f"{f.relative_to(ROOT)}: {len(rows)} rows")
    return len(rows)


total = 0
for day in LANDED:
    rows = ordinary(day) + [p for p in planted if p[0].date() == day.date()]
    total += write_day("inbox", day, rows)
print(f"landed total: {total} rows")
for sub, day in (("staged", NEXT_DAY), ("staged", GAP_DAY)):
    rows = ordinary(day)
    for _ in range(28):                                          # the biller keeps being paid
        rows.append((clock(day), rng.choice(BILLER_PAYERS), BILLER, "merchant_pay", amount(3.5, 0.5, 400)))
    write_day(sub, day, rows)


# ---- masters -----------------------------------------------------------------------------------------------------------
def write_csv(sub, name, header, rows):
    out = ROOT / sub
    out.mkdir(parents=True, exist_ok=True)
    f = out / name
    with f.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)
    print(f"{f.relative_to(ROOT)}: {len(rows)} rows")


rng_m = random.Random(20261017)                                  # masters never move the transaction bytes
order = list(ORDINARY)
rng_m.shuffle(order)
household = sorted(rng_m.sample(A_WALLETS, 3))                   # benign: one national id, three wallets
family_phone = sorted(rng_m.sample(B_WALLETS, 4))                # benign: one handset, four wallets
rest = [w for w in order if w not in household and w not in family_phone]
unknown_dev, blank_dev, zero_dev = rest[:8], rest[8:14], rest[14:19]
blank_nid, pending_nid = rest[19:25], rest[25:30]

wallet_rows = []
used_msisdn = set()
for i, w in enumerate(WALLETS):
    while True:
        msisdn = f"888{rng_m.randint(10_000_000, 99_999_999)}"
        if msisdn not in used_msisdn:
            used_msisdn.add(msisdn)
            break
    nid = f"NID-{rng_m.randint(10_000_000, 99_999_999)}-{i:03d}"
    dev = f"DEV-{rng_m.randint(0, 0xFFFFFF):06X}-{i:03d}"
    tier = rng_m.choices(["T1", "T2", "T3"], [90, 8, 2])[0]
    opened = datetime(2022, 1, 1) + timedelta(days=rng_m.randint(0, 1700))
    last = datetime(2026, 9, 14) + timedelta(days=rng_m.randint(0, 16))
    prior = round(rng_m.lognormvariate(8.0, 1.0), 2)
    if w in FARM:
        dev = "DEV-FARM-01"                                      # ONE handset, nine different national ids
    if w == HUB:
        tier = "T3"
    if w in STRUCT or w in LOOP or w in FARM:
        tier = "T1"
    if w == DORMANT:
        opened, last, prior, tier = datetime(2025, 11, 2), datetime(2025, 11, 20), 150.00, "T1"
    if w in household:
        nid = "NID-HOUSEHOLD-01"
    if w in family_phone:
        dev = "DEV-FAMILY-01"
    if w in unknown_dev:
        dev = "UNKNOWN"
    if w in blank_dev:
        dev = ""
    if w in zero_dev:
        dev = "000000000000000"
    if w in blank_nid:
        nid = ""
    if w in pending_nid:
        nid = "PENDING"
    wallet_rows.append([w, msisdn, nid, dev, tier, opened.strftime("%Y-%m-%d"), last.strftime("%Y-%m-%d"), f"{prior:.2f}"])
write_csv("inbox/wallet_master", "WALLET_MASTER_20261014.csv",
          ["wallet_id", "msisdn", "national_id", "device_id", "tier", "opened_date", "last_active_date", "prior_lifetime_volume"],
          wallet_rows)

REGION = ["North", "South", "East", "West", "Central"]
write_csv("inbox/agent_master", "AGENT_MASTER_20261014.csv",
          ["agent_id", "agent_name", "region", "opened_date", "float_limit"],
          [[a, f"Agent Shop {i:02d}", REGION[i % 5], (datetime(2021, 1, 1) + timedelta(days=rng_m.randint(0, 1500))).strftime("%Y-%m-%d"),
            f"{rng_m.choice([50000, 100000, 250000]):.2f}"] for i, a in enumerate(AGENTS, 1)])
