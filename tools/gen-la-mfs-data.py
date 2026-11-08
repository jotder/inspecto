#!/usr/bin/env python3
"""Regenerate the `la-mfs` Space Template's mobile financial services fraud corpus - SYNTHETIC, deterministic (one seeded Random).

Writes spaces/_templates/la-mfs/data/inbox/<feed>/ (12 months landed, 2025-10-01..2026-09-30) and data/staged/<feed>/ (the next
month, 2026-10, for the "new data arrives" step) for seven feeds:
  agent, merchant (one extract each, landed only), wallet (one landed extract of every wallet opened by the end of the landed
  months, plus the October openings), wallet_txn (cash_in, cash_out, p2p, merchant_pay and the merchants' settlement sweeps, by
  month), sim_event (SIM swaps and port-outs, by month), device_login (one row per wallet, handset and day, by month),
  agent_commission (one row per agent and month)
and tools/gen-la-mfs-data.ground-truth.json (story -> entity ids and look-alike ids; NOT shipped in the data download).

Planted stories (plan: docs/superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md, spec 14; pinned by
tools/gen-la-mfs-data.test.mjs over the files and MfsLinksJobGoldenTest over the link Datasets). la-mobile-money covers wallet
anti-money-laundering (structuring, mule chains, a device farm); this Space covers fraud AGAINST the MFS business and its customers
and shares no data, names or ids with it.
  1 SIM-SWAP TAKEOVER   one call-centre login (S-41) swaps 30 customers' SIMs in 10 days (2026-06-08..06-17); within the hour each
                        wallet is logged into from one of 6 handsets it never used and drained within 2 hours to 5 new mule
                        wallets, which cash out at 2 agents; each customer gets the number back at a store 1-3 days later
                        look-alike: a busy store login (S-72) running a SIM upgrade drive - 160 genuine swaps, most followed by a
                        new handset of the customer's own, and every customer keeps the balance
  2 SPLIT DEPOSITS      agent A-052 registers 120 wallets itself (2025-12..2026-01) and splits each of their deposits into 3-13
                        small cash-ins minutes apart, earning the per-transaction commission on each; the wallets do little else
                        look-alike: a market agent (A-131) whose 250 traders make many small cash-ins - one a visit - and also pay
                        merchants and each other
  3 MERCHANT CASH-OUT   merchant MR-077, opened 2026-09-07, receives payments from 60 new wallets minutes after each was funded at
                        one of 3 agents (A-207, A-208, A-219), and settles to one bank account every day
                        look-alike: a popular grocery (MR-214) with steady payments from 200 wallets all year, 50 of whom often pay
                        it right after a cash-in next door, settling weekly
Scale: 8 000 wallets (spec: 20 000 - with every pair a link the plan's 20-60 k link rows needed fewer), 300 agents under 12 super
agents, 400 merchants, about 101 000 landed transactions. MSISDNs are in the unassigned +999 range; names and areas are invented;
no real operator, agent, merchant or bank. Nothing derives from a real source.
"""
from __future__ import annotations

import csv
import json
import math
import random
from bisect import bisect_left, bisect_right
from datetime import date, datetime, timedelta
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
DATA = TOOLS.parent / "spaces" / "_templates" / "la-mfs" / "data"
TRUTH = TOOLS / "gen-la-mfs-data.ground-truth.json"
SEED = 20261111
rng = random.Random(SEED)
START, LANDED_END, END = date(2025, 10, 1), date(2026, 9, 30), date(2026, 10, 31)
T0, T_LANDED, T_END = datetime(2025, 10, 1), datetime(2026, 9, 30, 23, 59, 59), datetime(2026, 10, 31, 23, 59, 59)
MONTHS = [date(2025 + (9 + i) // 12, (9 + i) % 12 + 1, 1) for i in range(13)]   # 2025-10 .. 2026-10
N_WALLETS, N_AGENTS, N_SUPER, N_MERCHANTS = 8000, 300, 12, 400
BURST = 15            # SIM swaps by one login within 10 days that the swap screen flags (documented in the Job)
SPLIT_N, SPLIT_AVG = 1000, 25.0   # cash-ins at one agent, and the average under which the split screen flags it
QUICK_PAYERS = 30     # wallets paying a merchant within 60 minutes of a cash-in that the merchant screen flags

AREAS = ["Northgate", "Riverside", "Old Market", "Hillcrest", "Eastfield", "Lakeside", "Mill Lane", "Station Road", "Westbrook",
         "Brickfield", "Ferry Point", "Cedar Heights"]
AGENT_A = ["Kestrel", "Juniper", "Harbour", "Larkspur", "Millstone", "Copperfield", "Bramble", "Ashdown", "Foxglove", "Greystone",
           "Hawthorn", "Kingfisher", "Lantern", "Meadow", "Oakridge", "Pebble", "Redwing", "Thistle", "Valley", "Zephyr", "Cobalt",
           "Driftwood", "Saffron", "Willow", "Amberly"]
AGENT_B = ["Cash Point", "Money Shop", "Mobile Agent", "Pay Point", "Kiosk", "Wallet Corner", "Express", "Traders", "Cash Hub", "Agency", "Pay Corner", "Money Point", "Mobile Cash", "Till"]
MCATS = {"grocery": 0.24, "restaurant": 0.14, "pharmacy": 0.10, "fuel": 0.08, "clothing": 0.08, "electronics": 0.06, "transport": 0.12,
         "school fees": 0.06, "hardware": 0.06, "salon": 0.06}
M_SUFFIX = {"grocery": "Grocers", "restaurant": "Kitchen", "pharmacy": "Pharmacy", "fuel": "Fuel Stop", "clothing": "Outfitters",
            "electronics": "Electronics", "transport": "Bus Co-op", "school fees": "Academy", "hardware": "Hardware", "salon": "Salon"}
HOURS = [0.2, 0.1, 0.1, 0.1, 0.2, 0.5, 1.5, 3, 5, 6, 6.5, 6.5, 7, 6.5, 6, 6, 6.5, 7, 7, 6, 4.5, 3, 1.5, 0.6]

A_SPLIT, A_MARKET = "A-052", "A-131"
A_COLLUDE = ["A-207", "A-208", "A-219"]
A_MULE_OUT = ["A-088", "A-093"]
MR_COLLUDE, MR_GROCERY = "MR-077", "MR-214"
S_FRAUD, S_STORE = "S-41", "S-72"
CC_LOGINS = [f"S-{i:02d}" for i in range(1, 61)]
STORE_LOGINS = [f"S-{i:02d}" for i in range(61, 91)]
AREA_OF = {A_SPLIT: "Brickfield", A_MARKET: "Old Market", **{a: "Station Road" for a in A_COLLUDE}, **{a: "Eastfield" for a in A_MULE_OUT}}
SWAP_FROM = datetime(2026, 6, 8)
COLLUDE_OPEN = date(2026, 9, 7)


def poisson(lam: float) -> int:
    if lam <= 0:
        return 0
    k, p, lim = 0, 1.0, math.exp(-lam)
    while True:
        p *= rng.random()
        if p <= lim:
            return k
        k += 1


def at_day(d: date, hour=None) -> datetime:
    h = rng.choices(range(24), HOURS)[0] if hour is None else hour
    return datetime(d.year, d.month, d.day, h, rng.randint(0, 59), rng.randint(0, 59))


def rand_dt(a: datetime, b: datetime) -> datetime:
    d = (a + timedelta(seconds=rng.randint(0, max(0, int((b - a).total_seconds()))))).date()
    t = at_day(d)
    return min(max(t, a), b)


def money(median: float, sigma: float, lo=1.0) -> float:
    return round(max(lo, rng.lognormvariate(math.log(median), sigma)), 2)


def fee(amount: float) -> float:
    return 0.40 if amount <= 50 else 0.80 if amount <= 200 else 1.50


# ---- agents, merchants, logins ------------------------------------------------------------------------------------------------

agent_names = rng.sample([f"{a} {b}" for a in AGENT_A for b in AGENT_B], N_AGENTS)
agents = {}
for i in range(1, N_AGENTS + 1):
    a = f"A-{i:03d}"
    agents[a] = {"name": agent_names[i - 1], "area": AREA_OF.get(a, rng.choice(AREAS)), "super": f"SA-{rng.randint(1, N_SUPER):02d}",
                 "float": f"FA-{100000 + i * 7:06d}", "weight": rng.paretovariate(1.5)}
agents[A_MARKET]["name"] = "Old Market Traders Cash Point"
agents_in_area = {ar: [a for a, v in agents.items() if v["area"] == ar] for ar in AREAS}

m_names = set()
merchants = {}
cats, cw = list(MCATS), list(MCATS.values())
for i in range(1, N_MERCHANTS + 1):
    m = f"MR-{i:03d}"
    cat = rng.choices(cats, cw)[0]
    while True:
        name = f"{rng.choice(AGENT_A)} {rng.choice(['', 'Corner ', 'Family ', 'Central '])}{M_SUFFIX[cat]}".replace("  ", " ")
        if name not in m_names:
            break
        cat = rng.choices(cats, cw)[0]
    m_names.add(name)
    merchants[m] = {"name": name, "cat": cat, "area": rng.choice(AREAS), "opened": date(2019, 1, 1) + timedelta(days=rng.randint(0, 2350)),
                    "account": f"BA-{rng.randint(1000000, 9999999)}", "weight": rng.paretovariate(1.6), "weekly": rng.random() < 0.3}
merchants[MR_GROCERY].update(name="Hillcrest Family Grocers", cat="grocery", area="Hillcrest", opened=date(2023, 4, 3), weekly=True, weight=0.0)
merchants[MR_COLLUDE].update(name="Brightline Electronics Wholesale", cat="electronics", area="Station Road", opened=COLLUDE_OPEN, weight=0.0)
assert len({v["account"] for v in merchants.values()}) == N_MERCHANTS
merchants_in_area = {ar: [m for m, v in merchants.items() if v["area"] == ar and v["weight"] > 0] for ar in AREAS}

# ---- wallets ----------------------------------------------------------------------------------------------------------------------

numbers = rng.sample(range(10 ** 7), N_WALLETS + 200)
n_dev = 0


def new_device() -> str:
    global n_dev
    n_dev += 1
    return f"HS-{n_dev:05d}"


wallets: list[dict] = []


def wallet(area, opened: datetime, reg, kyc, role="bg") -> dict:
    w = {"id": f"WL-{len(wallets) + 1:05d}", "msisdn": f"9997{numbers[len(wallets)]:07d}", "area": area, "opened": opened, "reg": reg,
         "kyc": kyc, "role": role, "devices": [(new_device(), opened)], "contacts": [], "merchants": [], "agents": [],
         "rate": min(12.0, rng.paretovariate(2.2) * 0.52), "end": T_END, "swapped": False}
    wallets.append(w)
    return w


for _ in range(N_WALLETS - 120 - 60 - 5):
    area = rng.choice(AREAS)
    opened = (datetime(2021, 1, 1) + timedelta(days=rng.randint(0, 1733), hours=rng.randint(8, 19))) if rng.random() < 0.82 \
        else rand_dt(T0, T_END - timedelta(days=3))
    loc = [a for a in agents_in_area[area] if a not in (A_MARKET, A_SPLIT, *A_COLLUDE)]
    reg = rng.choice(loc) if rng.random() < 0.65 else ""
    wallet(area, opened, reg, rng.choices([1, 2, 3], [50, 35, 15])[0])

bg = [w for w in wallets if w["role"] == "bg"]
for w in bg:   # home agents (not the four story agents), merchants of the area, contacts
    loc = [a for a in agents_in_area[w["area"]] if a not in (A_MARKET, A_SPLIT, *A_COLLUDE)]
    k = rng.choice([1, 1, 2])
    w["agents"] = list(dict.fromkeys(rng.choices(loc, [agents[a]["weight"] for a in loc], k=k)))
    ms = merchants_in_area[w["area"]]
    w["merchants"] = list(dict.fromkeys(rng.choices(ms, [merchants[m]["weight"] for m in ms], k=rng.choice([1, 1, 2, 3]))))

# traders of the market agent: Old Market wallets whose cash-ins are all small and all at A-131
traders = rng.sample([w for w in bg if w["area"] == "Old Market" and w["opened"] < T0], 250)
for w in traders:
    w["role"], w["agents"] = "trader", [A_MARKET]
    if rng.random() < 0.4:
        w["reg"] = A_MARKET
# grocery regulars in Hillcrest; 50 of them often pay right after a cash-in next door
hill = [w for w in bg if w["area"] == "Hillcrest" and w["opened"] < T0]
grocery_payers = rng.sample(hill, 200)
grocery_quick = grocery_payers[:50]
for w in grocery_payers:
    w["grocery"] = True
victims = rng.sample([w for w in bg if w["role"] == "bg" and not w.get("grocery") and w["area"] != "Riverside" and w["opened"] < T0
                      and w["kyc"] >= 2], 30)
for w in victims:
    w["role"] = "victim"
    w["rate"] = max(w["rate"], 1.2)

# split-deposit wallets (registered by A-052), mules, collusion payers
split = [wallet("Brickfield", rand_dt(datetime(2025, 12, 1), datetime(2026, 1, 31, 18)), A_SPLIT, 1, "split") for _ in range(120)]
mules = [wallet("Eastfield", rand_dt(datetime(2026, 5, 10), datetime(2026, 5, 30)), "", 1, "mule") for _ in range(5)]
colluders = []
for i in range(60):
    ag = A_COLLUDE[i % 3]
    colluders.append(wallet("Station Road", rand_dt(datetime(2026, 8, 20), datetime(2026, 9, 8, 18)), ag, 1, "collude"))
    colluders[-1]["agents"] = [ag]

# contacts: background wallets opened before the window, never a story wallet
pool = [w for w in wallets if w["role"] in ("bg", "trader") and w["opened"] < T0]
pool_by_area = {ar: [w for w in pool if w["area"] == ar] for ar in AREAS}
for w in [*bg, *victims]:
    k = rng.choice([0, 1, 1, 1, 2, 2, 3])
    for _ in range(k):
        src = pool_by_area["Old Market"] if w["role"] == "trader" else pool_by_area[w["area"]] if rng.random() < 0.85 else pool
        c = rng.choice(src)
        if c is not w and c not in w["contacts"]:
            w["contacts"].append(c)

# ---- SIM events -------------------------------------------------------------------------------------------------------------------

sim_events = []   # (msisdn, event, at, channel, login)
logins_bg = [s for s in CC_LOGINS + STORE_LOGINS if s not in (S_FRAUD, S_STORE)]


def channel(login):
    return "call_centre" if login in CC_LOGINS else "store"


def office_hours(t: datetime, login) -> datetime:
    return t.replace(hour=rng.randint(9, 18) if channel(login) == "store" else rng.randint(7, 21))


def active_from(w):
    return max(w["opened"], T0)


free = [w for w in bg if w["role"] == "bg" and not w.get("grocery") and w["opened"] < T0 + timedelta(days=200)]
rng.shuffle(free)
store_customers = [w for w in free if w["area"] == "Riverside"][:160]
for w in store_customers:
    w["role"] = "store_swap"
rest = [w for w in free if w["role"] == "bg"]
s41_genuine, phone_changers, swap_only, ported = rest[:10], rest[10:810], rest[810:1150], rest[1150:1310]


def change_phone(w, when: datetime):
    w["devices"].append((new_device(), when))


# the store's SIM upgrade drive: 110 swaps over 2026-03-02..03-27 (working days), 50 more through the year
drive = [d for d in (date(2026, 3, 2) + timedelta(days=i) for i in range(26)) if d.weekday() < 5]
for i, w in enumerate(store_customers):
    if i < 110:
        t = at_day(drive[i % len(drive)], rng.randint(9, 17))
    else:
        t = office_hours(rand_dt(max(active_from(w) + timedelta(days=14), datetime(2025, 10, 15)), T_LANDED - timedelta(days=3)), S_STORE)
    sim_events.append((w["msisdn"], "sim_swap", t, "store", S_STORE))
    w["swapped"] = True
    if rng.random() < 0.75:
        change_phone(w, t + timedelta(minutes=rng.randint(120, 1300)))
for w in s41_genuine:   # the login's ordinary work, away from the 10 days it was abused
    t = SWAP_FROM
    while SWAP_FROM - timedelta(days=12) <= t <= SWAP_FROM + timedelta(days=22):
        t = rand_dt(max(active_from(w) + timedelta(days=14), datetime(2025, 10, 15)), T_LANDED - timedelta(days=3))
    t = office_hours(t, S_FRAUD)
    sim_events.append((w["msisdn"], "sim_swap", t, "call_centre", S_FRAUD))
    w["swapped"] = True
for w in phone_changers:   # a new handset; 60 % after a lost or damaged SIM was swapped first
    t = rand_dt(max(active_from(w) + timedelta(days=14), datetime(2025, 10, 15)), T_END - timedelta(days=2))
    if rng.random() < 0.6:
        lg = rng.choice(logins_bg)
        t = office_hours(t, lg)
        sim_events.append((w["msisdn"], "sim_swap", t, channel(lg), lg))
        w["swapped"] = True
        change_phone(w, t + timedelta(minutes=rng.randint(60, 2000)))
    else:
        change_phone(w, t)
for w in swap_only:
    t = rand_dt(max(active_from(w) + timedelta(days=14), datetime(2025, 10, 15)), T_END - timedelta(days=1))
    lg = rng.choice(logins_bg)
    t = office_hours(t, lg)
    sim_events.append((w["msisdn"], "sim_swap", t, channel(lg), lg))
    w["swapped"] = True
for w in ported:
    t = rand_dt(max(active_from(w) + timedelta(days=30), datetime(2025, 10, 15)), T_END - timedelta(days=1))
    lg = rng.choice(logins_bg)
    t = office_hours(t, lg)
    sim_events.append((w["msisdn"], "port_out", t, channel(lg), lg))
    w["end"] = t
pool_ids = {id(w) for w in ported}
for w in wallets:   # a ported number's wallet closes: nobody pays it afterwards
    w["contacts"] = [c for c in w["contacts"] if id(c) not in pool_ids]

# ---- transactions -----------------------------------------------------------------------------------------------------------------

txns = []     # [from, to, type, amount, at, device]
logins = []   # (wallet, device, at)


def device_at(w, t: datetime) -> str:
    dev = w["devices"][0][0]
    for d, since in w["devices"]:
        if since <= t:
            dev = d
    return dev


def txn(frm, to, typ, amount, t, dev=""):
    txns.append([frm, to, typ, amount, t, dev])


def wallet_txn(w, frm, to, typ, amount, t):
    dev = "" if typ == "cash_in" else device_at(w, t)
    txn(frm, to, typ, amount, t, dev)
    if dev:
        logins.append((w["id"], dev, t - timedelta(minutes=rng.randint(1, 9))))


def month_days(m: date):
    nxt = (m.replace(day=28) + timedelta(days=4)).replace(day=1)
    return [m + timedelta(days=i) for i in range((nxt - m).days)]


for w in [*bg, *victims]:
    a0, a1 = active_from(w), w["end"]
    logins.append((w["id"], device_at(w, a0), a0 + timedelta(hours=rng.randint(1, 30))))
    for d, since in w["devices"][1:]:
        if since < a1:
            logins.append((w["id"], d, since))
    trader = w["role"] == "trader"
    for m in MONTHS:
        days = [d for d in month_days(m) if a0.date() <= d <= a1.date()]
        if not days:
            continue
        n = poisson(w["rate"] * len(days) / 30)
        if trader:
            n += poisson(0.6 * len(days) / 30)
        for _ in range(n):
            t = at_day(rng.choice(days))
            if not a0 <= t <= a1:
                continue
            r = rng.random()
            if trader and r < 0.55 or not trader and r < 0.30:
                ag = rng.choice(w["agents"])
                wallet_txn(w, ag, w["id"], "cash_in", money(14 if trader else 40, 0.45 if trader else 0.8), t)
            elif r < 0.48 and not trader or trader and r < 0.65:
                wallet_txn(w, w["id"], rng.choice(w["agents"]), "cash_out", money(50, 0.8), t)
            elif r < 0.73 and w["contacts"]:
                c = rng.choice(w["contacts"])
                if active_from(c) <= t <= c["end"]:
                    wallet_txn(w, w["id"], c["id"], "p2p", money(25, 0.9), t)
            else:
                if w.get("grocery") and rng.random() < 0.5:
                    continue
                wallet_txn(w, w["id"], rng.choice(w["merchants"]), "merchant_pay", money(12, 0.9), t)
        if w.get("grocery"):   # the grocery regulars: about two visits a month
            for _ in range(poisson(2.0 * len(days) / 30)):
                t = at_day(rng.choice(days))
                if a0 <= t <= a1:
                    wallet_txn(w, w["id"], MR_GROCERY, "merchant_pay", money(28, 0.5), t)
    if w in grocery_quick:   # cash in next door, then shop: 3-5 times a year
        for _ in range(rng.randint(3, 5)):
            t = rand_dt(a0, T_LANDED - timedelta(hours=2))
            wallet_txn(w, rng.choice(w["agents"]), w["id"], "cash_in", money(60, 0.4), t)
            wallet_txn(w, w["id"], MR_GROCERY, "merchant_pay", money(35, 0.4), t + timedelta(minutes=rng.randint(5, 50)))

# story 1: the SIM-swap takeover through S-41
attacker = [new_device() for _ in range(6)]
swap_days = [SWAP_FROM.date() + timedelta(days=i) for i in range(10)]
mule_in = {m["id"]: [] for m in mules}
for m in mules:
    logins.append((m["id"], m["devices"][0][0], m["opened"] + timedelta(minutes=rng.randint(3, 40))))
    txn(rng.choice(A_MULE_OUT), m["id"], "cash_in", money(20, 0.3), m["opened"] + timedelta(hours=1))
takeover = []
for i, w in enumerate(victims):
    t = at_day(swap_days[i % 10], rng.randint(8, 19))
    sim_events.append((w["msisdn"], "sim_swap", t, "call_centre", S_FRAUD))
    dev = attacker[i % 6]
    tl = t + timedelta(minutes=rng.randint(5, 40))
    logins.append((w["id"], dev, tl))
    total = money(900, 0.45, 250)
    k = rng.choice([1, 2, 2, 3])
    targets = rng.sample(mules, k)
    tt = tl
    for j, mu in enumerate(targets):
        tt = tt + timedelta(minutes=rng.randint(3, 20))
        amt = round(total / k, 2)
        txn(w["id"], mu["id"], "p2p", amt, tt, dev)
        mule_in[mu["id"]].append((tt, amt))
    lg = rng.choice([s for s in STORE_LOGINS if s != S_STORE])
    back = office_hours(t + timedelta(days=rng.randint(1, 3)), lg)
    sim_events.append((w["msisdn"], "sim_swap", back, "store", lg))
    logins.append((w["id"], device_at(w, back), back + timedelta(minutes=rng.randint(20, 90))))
    takeover.append({"wallet": w["id"], "swap": t, "drain": round(total, 2), "back": back})
# while the fraudster holds the number the customer cannot use the wallet: nothing of theirs between the swap and the recovery
held = {v["wallet"]: (v["swap"], v["back"]) for v in takeover}
drains = {id(r) for r in txns if r[0] in held and r[5] in attacker}
txns = [r for r in txns if id(r) in drains or not any(k in held and held[k][0] <= r[4] <= held[k][1] for k in (r[0], r[1]))]
logins = [l for l in logins if l[1] in attacker or not (l[0] in held and held[l[0]][0] <= l[2] <= held[l[0]][1])]
for m in mules:   # each mule cashes its takings out within hours at the two agents, in chunks of up to 500
    for tt, amt in sorted(mule_in[m["id"]]):
        left, t2 = amt, tt
        while left > 0.005:
            piece = round(min(left, rng.uniform(250, 500)), 2)
            t2 = t2 + timedelta(minutes=rng.randint(30, 180))
            wallet_txn(m, m["id"], rng.choice(A_MULE_OUT), "cash_out", piece, t2)
            left = round(left - piece, 2)

# story 2: A-052 splits each deposit of the 120 wallets it registered into 3-13 small cash-ins minutes apart
for w in split:
    logins.append((w["id"], w["devices"][0][0], w["opened"] + timedelta(minutes=rng.randint(5, 60))))
    t = w["opened"] + timedelta(days=rng.randint(2, 10))
    held = 0.0
    visits = 0
    while t < T_LANDED - timedelta(days=1):
        t = at_day(t.date(), rng.randint(8, 18))
        total = rng.uniform(60, 180)
        while total > 5:
            piece = round(min(total, rng.uniform(10, 25)), 2)
            txn(A_SPLIT, w["id"], "cash_in", piece, t)
            held += piece
            total -= piece
            t += timedelta(minutes=rng.randint(1, 4), seconds=rng.randint(0, 59))
        visits += 1
        if visits % rng.choice([2, 3]) == 0:
            wallet_txn(w, w["id"], A_SPLIT, "cash_out", round(held * rng.uniform(0.85, 1.0), 2), t + timedelta(days=rng.randint(3, 12)))
            held = 0.0
        t += timedelta(days=rng.randint(28, 49))
    if rng.random() < 0.25:
        c = rng.choice(pool_by_area["Brickfield"])
        wallet_txn(w, w["id"], c["id"], "p2p", money(20, 0.5), rand_dt(w["opened"] + timedelta(days=20), T_LANDED - timedelta(days=5)))

# story 3: the colluding merchant - 60 new wallets funded at three agents and paying MR-077 minutes later
for w in colluders:
    logins.append((w["id"], w["devices"][0][0], w["opened"] + timedelta(minutes=rng.randint(5, 60))))
    days = sorted(rng.sample([COLLUDE_OPEN + timedelta(days=2 + i) for i in range(22)], rng.randint(2, 4)))
    for d in days:
        t = at_day(d, rng.randint(9, 19))
        amt = money(320, 0.3, 150)
        txn(w["agents"][0], w["id"], "cash_in", min(amt, 480.0), t)
        wallet_txn(w, w["id"], MR_COLLUDE, "merchant_pay", round(min(amt, 480.0) * rng.uniform(0.95, 1.0), 2), t + timedelta(minutes=rng.randint(3, 25)))

# merchants' settlement sweeps to their bank accounts: MR-077 every day, the rest weekly (Mondays) or monthly (the 1st)
paid_in = {}
for f, to, typ, amt, t, _ in txns:
    if typ == "merchant_pay":
        paid_in.setdefault(to, []).append((t, amt))
for m, v in merchants.items():
    rows = sorted(paid_in.get(m, []))
    if not rows:
        continue
    def bucket(t: datetime) -> date:
        if m == MR_COLLUDE:
            return t.date()
        if v["weekly"]:
            return t.date() + timedelta(days=7 - t.weekday())
        return ((t.date().replace(day=28) + timedelta(days=4)).replace(day=1))
    due = {}
    for t, amt in rows:
        due[bucket(t)] = due.get(bucket(t), 0) + amt
    for d, amt in sorted(due.items()):
        st = datetime(d.year, d.month, d.day, 23, rng.randint(30, 58)) if m == MR_COLLUDE else datetime(d.year, d.month, d.day, 2, rng.randint(0, 59))
        if st <= T_END:
            txn(m, v["account"], "settlement", round(amt, 2), st)

# ---- tidy: the landed window, ids, logins per wallet, handset and day -------------------------------------------------------------

txns = [t for t in txns if T0 <= t[4] <= T_END]
txns.sort(key=lambda r: (r[4], r[0], r[1]))
for i, r in enumerate(txns, 1):
    r.insert(0, f"TX{i:08d}")
first_login = {}
for wid, dev, t in logins:
    if T0 <= t <= T_END:
        k = (wid, dev, t.date())
        if k not in first_login or t < first_login[k]:
            first_login[k] = t
login_rows = sorted((t, wid, dev) for (wid, dev, _), t in first_login.items())
sim_events.sort(key=lambda e: (e[2], e[0]))

# ---- the three screens, as the Jobs compute them, over the landed months ------------------------------------------------------------

landed = [r for r in txns if r[5] <= T_LANDED]
by_id = {w["id"]: w for w in wallets}
by_msisdn = {w["msisdn"]: w for w in wallets}
swaps = [e for e in sim_events if e[1] == "sim_swap" and e[2] <= T_LANDED and by_msisdn[e[0]]["opened"] <= T_LANDED]
by_login = {}
for e in swaps:
    by_login.setdefault(e[4], []).append(e[2])
burst = {}
for lg, ts in by_login.items():
    ts.sort()
    burst[lg] = max(bisect_right(ts, t + timedelta(days=10)) - bisect_left(ts, t) for t in ts)
assert sorted(lg for lg, n in burst.items() if n >= BURST) == [S_FRAUD, S_STORE], sorted(burst.items(), key=lambda x: -x[1])[:5]
assert max(n for lg, n in burst.items() if lg not in (S_FRAUD, S_STORE)) <= BURST - 8, "background logins stay well under the burst"

ci_by_agent = {}
ci_pairs = {}
for _, f, to, typ, amt, t, _ in landed:
    if typ == "cash_in":
        ci_by_agent.setdefault(f, []).append(amt)
        ci_pairs.setdefault(to, set()).add(f)
flag_split = sorted(a for a, v in ci_by_agent.items() if len(v) >= SPLIT_N and sum(v) / len(v) < SPLIT_AVG)
assert flag_split == sorted([A_SPLIT, A_MARKET]), flag_split
assert not any({A_SPLIT, A_MARKET} <= v for v in ci_pairs.values()), "no wallet cashes in at both screened agents"

ci_times = {}
for _, f, to, typ, amt, t, _ in landed:
    if typ == "cash_in":
        ci_times.setdefault(to, []).append((t, f))
for v in ci_times.values():
    v.sort()
quick = {}        # (wallet, merchant) -> payments within 60 minutes after a cash-in
quick_agent = {}  # (wallet, agent) -> such payments whose latest cash-in was there
for _, f, to, typ, amt, t, _ in landed:
    if typ != "merchant_pay":
        continue
    cands = [c for c in ci_times.get(f, []) if t - timedelta(minutes=60) < c[0] <= t]
    if cands:
        quick[(f, to)] = quick.get((f, to), 0) + 1
        ag = max(cands)[1]
        quick_agent[(f, ag)] = quick_agent.get((f, ag), 0) + 1
quick_payers = {}
for (f, m) in quick:
    quick_payers.setdefault(m, set()).add(f)
flag_quick = sorted(m for m, v in quick_payers.items() if len(v) >= QUICK_PAYERS)
assert flag_quick == sorted([MR_COLLUDE, MR_GROCERY]), sorted(((m, len(v)) for m, v in quick_payers.items()), key=lambda x: -x[1])[:5]
assert max(len(v) for m, v in quick_payers.items() if m not in flag_quick) <= QUICK_PAYERS - 20
grocery_quick_agents = {ag for (f, ag) in quick_agent if f in quick_payers[MR_GROCERY]}
assert not grocery_quick_agents & set(A_COLLUDE), "the grocery's quick payers never fund at the colluding agents"
assert not quick_payers[MR_COLLUDE] & {w["id"] for w in grocery_payers}
assert {ag for (f, ag) in quick_agent if f in quick_payers[MR_COLLUDE]} == set(A_COLLUDE)
assert len(quick_payers[MR_COLLUDE]) == 60

# ---- write ------------------------------------------------------------------------------------------------------------------------


def write(rel, header, rows):
    f = DATA / rel
    f.parent.mkdir(parents=True, exist_ok=True)
    with f.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)


def ts(t: datetime) -> str:
    return t.strftime("%Y-%m-%d %H:%M:%S")


for old in sorted(DATA.glob("*/*/*.csv")):
    old.unlink()

write("inbox/agent/AGENT_20260930.csv", ["agent_id", "name", "area", "super_agent_id", "float_account"],
      [[a, v["name"], v["area"], v["super"], v["float"]] for a, v in sorted(agents.items())])
write("inbox/merchant/MERCHANT_20260930.csv", ["merchant_id", "name", "category", "opened_at", "settlement_account"],
      [[m, v["name"], v["cat"], v["opened"].isoformat(), v["account"]] for m, v in sorted(merchants.items())])
W_HEAD = ["wallet_id", "msisdn", "kyc_level", "opened_at", "registered_by_agent"]
by_open = sorted(wallets, key=lambda w: (w["opened"], w["id"]))
write("inbox/wallet/WALLET_20260930.csv", W_HEAD,
      [[w["id"], w["msisdn"], w["kyc"], ts(w["opened"]), w["reg"]] for w in by_open if w["opened"] <= T_LANDED])
write("staged/wallet/WALLET_20261031.csv", W_HEAD,
      [[w["id"], w["msisdn"], w["kyc"], ts(w["opened"]), w["reg"]] for w in by_open if w["opened"] > T_LANDED])
commission = {}
for _, f, to, typ, amt, t, _ in txns:
    if typ in ("cash_in", "cash_out"):
        a = f if typ == "cash_in" else to
        k = (a, t.date().replace(day=1))
        n, c = commission.get(k, (0, 0.0))
        commission[k] = (n + 1, c + fee(amt))
for m in MONTHS:
    ym, box = m.strftime("%Y%m"), "staged" if m > LANDED_END else "inbox"
    nxt = (m.replace(day=28) + timedelta(days=4)).replace(day=1)
    within = lambda t: m <= t.date() < nxt   # noqa: E731
    write(f"{box}/wallet_txn/WALLET_TXN_{ym}.csv", ["txn_id", "from_id", "to_id", "type", "amount", "txn_at", "device_id"],
          [[i, f, to, typ, f"{amt:.2f}", ts(t), dev] for i, f, to, typ, amt, t, dev in txns if within(t)])
    write(f"{box}/sim_event/SIM_EVENT_{ym}.csv", ["msisdn", "event", "event_at", "channel", "staff_login"],
          [[e[0], e[1], ts(e[2]), e[3], e[4]] for e in sim_events if within(e[2])])
    write(f"{box}/device_login/DEVICE_LOGIN_{ym}.csv", ["wallet_id", "device_id", "login_at"],
          [[wid, dev, ts(t)] for t, wid, dev in login_rows if within(t)])
    write(f"{box}/agent_commission/AGENT_COMMISSION_{ym}.csv", ["agent_id", "month", "transactions", "commission"],
          [[a, m.isoformat(), *(lambda nc: (nc[0], f"{nc[1]:.2f}"))(commission.get((a, m), (0, 0.0)))] for a in sorted(agents)])

ids = lambda ws: sorted(w["id"] for w in ws)   # noqa: E731
cnt = lambda rows, end=T_LANDED: sum(1 for r in rows if r <= end)   # noqa: E731
truth = {
    "seed": SEED,
    "window": {"landed": [START.isoformat(), LANDED_END.isoformat()], "staged": [(LANDED_END + timedelta(days=1)).isoformat(), END.isoformat()]},
    "thresholds": {"swaps_in_10_days": BURST, "split_cash_ins": SPLIT_N, "split_average_below": SPLIT_AVG, "quick_payers": QUICK_PAYERS},
    "counts": {"agent": len(agents), "merchant": len(merchants), "wallet_landed": sum(1 for w in wallets if w["opened"] <= T_LANDED),
               "wallet_txn_landed": len(landed), "sim_event_landed": cnt([e[2] for e in sim_events]),
               "device_login_landed": cnt([r[0] for r in login_rows]), "agent_commission_landed": len(agents) * 12},
    "stories": {
        "sim_swap_takeover": {"hub": S_FRAUD, "view": "mfs_sim_swap_bursts",
                              "entities": sorted([S_FRAUD, *ids(victims), *attacker, *ids(mules), *A_MULE_OUT]), "lookalikes": [S_STORE]},
        "split_deposits": {"hub": A_SPLIT, "view": "mfs_split_deposits", "entities": sorted([A_SPLIT, *ids(split)]), "lookalikes": [A_MARKET]},
        "merchant_cash_out": {"hub": MR_COLLUDE, "view": "mfs_merchant_quick_pay",
                              "entities": sorted([MR_COLLUDE, merchants[MR_COLLUDE]["account"], *A_COLLUDE, *ids(colluders)]),
                              "lookalikes": [MR_GROCERY]},
    },
    "sim_swap_takeover": {"login": S_FRAUD, "days": [swap_days[0].isoformat(), swap_days[-1].isoformat()], "victims": ids(victims),
                          "attacker_devices": attacker, "mules": ids(mules), "cash_out_agents": A_MULE_OUT,
                          "drained": {v["wallet"]: v["drain"] for v in takeover}, "genuine_swaps_by_login": ids(s41_genuine),
                          "lookalike_login": S_STORE, "lookalike_customers": ids(store_customers)},
    "split_deposits": {"agent": A_SPLIT, "wallets": ids(split), "lookalike_agent": A_MARKET, "lookalike_traders": ids(traders),
                       "cash_ins": len(ci_by_agent[A_SPLIT]), "lookalike_cash_ins": len(ci_by_agent[A_MARKET])},
    "merchant_cash_out": {"merchant": MR_COLLUDE, "opened": COLLUDE_OPEN.isoformat(), "account": merchants[MR_COLLUDE]["account"],
                          "agents": A_COLLUDE, "payers": ids(colluders), "lookalike_merchant": MR_GROCERY,
                          "lookalike_account": merchants[MR_GROCERY]["account"], "lookalike_payers": ids(grocery_payers),
                          "lookalike_quick_payers": sorted(quick_payers[MR_GROCERY])},
}
TRUTH.write_text(json.dumps(truth, indent=1, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
c = truth["counts"]
print(f"wallets {c['wallet_landed']} landed of {len(wallets)}, txns {c['wallet_txn_landed']} landed of {len(txns)}, sim events "
      f"{c['sim_event_landed']}, logins {c['device_login_landed']}, handsets {n_dev}; bursts {burst[S_FRAUD]}/{burst[S_STORE]}; split "
      f"{len(ci_by_agent[A_SPLIT])}/{len(ci_by_agent[A_MARKET])}; quick {len(quick_payers[MR_COLLUDE])}/{len(quick_payers[MR_GROCERY])}")
