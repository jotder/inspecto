#!/usr/bin/env python3
"""Regenerate the `la-crypto` Space Template's crypto laundering corpus - SYNTHETIC, deterministic (one seeded Random).

Writes spaces/_templates/la-crypto/data/inbox/<feed>/ (12 months landed, 2025-10-01..2026-09-30) and data/staged/<feed>/ (the next
month, 2026-10, for the "new data arrives" step) for six feeds:
  wallet (one row per address: first_seen, label exchange / mixer / merchant / hacked / unknown), transfer (one row per on-chain
  transfer, by month), cluster_hint (address -> common-input cluster), exchange_account (account, exchange, KYC person, deposit
  address), kyc_person (name, birth year, country), cashout (an exchange account paying out to a bank account, by month)
and tools/gen-la-crypto-data.ground-truth.json (story -> entity ids and look-alike ids; NOT shipped in the data download).

Planted stories (plan: docs/superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md, spec 13; pinned by
tools/gen-la-crypto-data.test.mjs over the files and CryptoLinksJobGoldenTest over the link Datasets):
  1 PEEL CHAIN          a wallet labelled hacked is emptied in one transfer on 2026-03-14; the thief's address then peels small amounts
                        over 80 hops (one transfer in, two out: a small peel and the change to a fresh address) into 6 exchange
                        deposit addresses, and the last change address keeps the rest
                        look-alike: a company payroll wallet that pays 25 staff one by one each month, change to a fresh address at
                        every payment - a chain of the same shape that pays the same 25 personal wallets every month
  2 MIXER HOP           a ransom-collection address sends three lots into the mixer; each comes out within 2 hours, 98.2-99.4 % of
                        the input, to a fresh address that deposits it all at one exchange account
                        look-alikes: two mixer users whose nearest exit is close in time but 7 % short, or close in amount but over
                        4 hours later - near misses, never matched
  3 CONVERGING CASH-OUT one common-input cluster funds 40 exchange accounts at 2 exchanges, held in 40 unrelated names, which all cash
                        out to the same 3 bank accounts
                        look-alike: an over-the-counter desk's cluster that pays 60 customers' own exchange accounts, which cash out
                        to their own bank accounts
Addresses use an invented format (`syn1` + 26 characters) that cannot match a real chain; the assets SYC and SUSD are invented; so
are the exchanges, the company, the people and the bank accounts. Nothing derives from a real source.
Scale: about 10 900 addresses and 44 500 landed transfers (spec: 30 000 and 150 000 - with every address pair a link row the plan's
20-60 k link rows needed fewer), 3 150 exchange accounts, 3 000 KYC persons, about 4 600 landed cash-outs.
"""
from __future__ import annotations

import csv
import json
import math
import random
from datetime import date, datetime, timedelta
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
DATA = TOOLS.parent / "spaces" / "_templates" / "la-crypto" / "data"
TRUTH = TOOLS / "gen-la-crypto-data.ground-truth.json"
SEED = 20261113
rng = random.Random(SEED)
START, LANDED_END, END = date(2025, 10, 1), date(2026, 9, 30), date(2026, 10, 31)
T0, T_LANDED, T_END = datetime(2025, 10, 1), datetime(2026, 9, 30, 23, 59, 59), datetime(2026, 10, 31, 23, 59, 59)
MONTHS = [date(2025 + (9 + i) // 12, (9 + i) % 12 + 1, 1) for i in range(13)]   # 2025-10 .. 2026-10
N_USERS, N_MERCHANTS, N_MIXER_USERS = 4700, 120, 140
HOP_HOURS = 24                     # a hop: one transfer in, two out to two addresses, both within this many hours
MATCH_MINUTES, MATCH_RATIO = 120, 0.98      # a mixer exit matched to an input: within 2 hours, at least 98 % of it
CAND_HOURS, CAND_RATIO = 6, 0.90           # a mixer exit candidate: within 6 hours, 90-100 % of the input
FUNDED_ACCOUNTS = 10               # a cluster paying this many exchange accounts or more is screened

EXCHANGES = ["Larkmoor Digital Exchange", "Fernhill Coin Exchange", "Ashgrove Exchange", "Pebblebrook Exchange", "Wrenfield Exchange"]
EX_W = [0.33, 0.24, 0.18, 0.15, 0.10]
FIRST = ["Amara", "Bilal", "Chiara", "Dmitri", "Elif", "Farah", "Gustavo", "Hana", "Ivan", "Jae", "Kofi", "Lena", "Mateo", "Nadia",
         "Oskar", "Priya", "Quentin", "Rosa", "Samir", "Tomas", "Uma", "Viktor", "Wen", "Ximena", "Yusuf", "Zara", "Anton", "Beatriz",
         "Caleb", "Daria", "Emeka", "Freya", "Goran", "Ines", "Jonas", "Keiko", "Luca", "Marta", "Nikhil", "Olga"]
LAST = ["Abara", "Brandt", "Castell", "Dorsey", "Eklund", "Ferreira", "Garrow", "Halloran", "Ishikawa", "Janssen", "Kowalczyk",
        "Lindqvist", "Moreau", "Nakamura", "Okafor", "Petrov", "Quaresma", "Rahimi", "Sandoval", "Tanaka", "Ulloa", "Varga", "Whitlock",
        "Xu", "Yilmaz", "Zielinski", "Achebe", "Bergstrom", "Cardoso", "Delacroix", "Esposito", "Fontaine", "Grieve", "Haddad"]
COUNTRIES = ["United Kingdom", "Germany", "Spain", "Poland", "Turkey", "Nigeria", "Kenya", "Ghana", "India", "Vietnam", "Philippines",
             "Indonesia", "Brazil", "Mexico", "Argentina", "Canada"]
HOURS = [0.6, 0.4, 0.3, 0.3, 0.3, 0.5, 1, 2, 3.5, 4.5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 4.5, 3.5, 2, 1]
ALPHA = "023456789acdefghjklmnpqrstuvwxyz"


def poisson(lam: float) -> int:
    if lam <= 0:
        return 0
    k, p, lim = 0, 1.0, math.exp(-lam)
    while True:
        p *= rng.random()
        if p <= lim:
            return k
        k += 1


def rand_time(a: datetime, b: datetime) -> datetime:
    """A time between a and b: a random day, an hour from the daily cycle, fewer at weekends."""
    days = max(0, (b - a).days)
    for _ in range(30):
        d = (a + timedelta(days=rng.randint(0, days))).date()
        if d.weekday() >= 5 and rng.random() < 0.3:
            continue
        t = datetime(d.year, d.month, d.day, rng.choices(range(24), HOURS)[0], rng.randint(0, 59), rng.randint(0, 59))
        if a <= t <= b:
            return t
    return a + timedelta(seconds=rng.randint(0, max(0, int((b - a).total_seconds()))))


def coins(median: float, sigma: float, lo=0.001) -> float:
    return round(max(lo, rng.lognormvariate(math.log(median), sigma)), 6)


# ---- addresses, clusters, people, accounts ------------------------------------------------------------------------------------

label, created, cluster_of, owner_of = {}, {}, {}, {}
n_cluster = 0


def mk(lbl: str, at: datetime, owner=None) -> str:
    while True:
        a = "syn1" + "".join(rng.choice(ALPHA) for _ in range(26))
        if a not in label:
            break
    label[a], created[a] = lbl, at
    if owner is not None:
        owner_of[a] = owner
    return a


def new_cluster(addrs) -> str:
    global n_cluster
    n_cluster += 1
    cid = f"CL-{n_cluster:05d}"
    for a in addrs:
        cluster_of[a] = cid
    return cid


persons, accounts, banks_used = {}, {}, set()


def new_bank() -> str:
    while True:
        b = f"BK-{rng.randint(10_000_000, 99_999_999)}"
        if b not in banks_used:
            banks_used.add(b)
            return b


def new_person(banks=None) -> str:
    pid = f"KP-{len(persons) + 1:05d}"
    persons[pid] = {"name": f"{rng.choice(FIRST)} {rng.choice(LAST)}", "birth_year": "" if rng.random() < 0.04 else str(rng.randint(1952, 2005)),
                    "country": rng.choice(COUNTRIES), "banks": banks or ([new_bank()] if rng.random() < 0.9 else [new_bank(), new_bank()])}
    return pid


def new_account(person: str, ex: str, opened: datetime) -> str:
    aid = f"XA-{len(accounts) + 1:05d}"
    accounts[aid] = {"person": person, "exchange": ex, "opened": opened, "deposit": mk("exchange", opened, owner=aid)}
    return aid


hot = {ex: [mk("exchange", T0 - timedelta(days=900)) for _ in range(3)] for ex in EXCHANGES}
for ex in EXCHANGES:
    new_cluster(hot[ex])
mixer_pool = [mk("mixer", T0 - timedelta(days=500)) for _ in range(16)]

transfers = []   # [from, to, amount, asset, at]


def tx(f: str, t: str, amt: float, at: datetime, asset="SYC"):
    transfers.append([f, t, round(amt, 6), asset, at])


# ---- background population ------------------------------------------------------------------------------------------------------

users = []
for i in range(N_USERS):
    join = T0 - timedelta(days=rng.randint(0, 700)) if rng.random() < 0.85 else rand_time(T0, T_END - timedelta(days=5))
    u = {"i": i, "join": join, "weight": min(8.0, rng.lognormvariate(0, 0.85)), "contacts": [], "acct": None, "acct2": None, "role": "bg"}
    n = min(5, int(rng.paretovariate(1.8)))
    u["addrs"] = [mk("unknown", join, owner=i) for _ in range(n)]
    if n >= 2 and rng.random() < 0.8:
        new_cluster(u["addrs"])
    if rng.random() < 0.62:
        ex = rng.choices(EXCHANGES, EX_W)[0]
        p = new_person()
        u["acct"] = new_account(p, ex, join - timedelta(days=rng.randint(1, 300)))
        if rng.random() < 0.05:
            u["acct2"] = new_account(p, rng.choice([e for e in EXCHANGES if e != ex]), min(join + timedelta(days=rng.randint(1, 200)), T_END - timedelta(days=1)))
    users.append(u)
for u in users:
    for _ in range(rng.choice([0, 1, 1, 2, 2, 3, 4])):
        c = rng.choice(users)
        if c is not u and c not in u["contacts"]:
            u["contacts"].append(c)

merchants = []
for i in range(N_MERCHANTS):
    opened = T0 - timedelta(days=rng.randint(30, 1500))
    m = {"addrs": [mk("merchant", opened) for _ in range(rng.choice([1, 1, 2, 3]))], "weight": rng.paretovariate(1.4), "acct": None}
    if len(m["addrs"]) >= 2:
        new_cluster(m["addrs"])
    if rng.random() < 0.8:
        m["acct"] = new_account(new_person(), rng.choices(EXCHANGES, EX_W)[0], opened)
    merchants.append(m)
m_weights = [m["weight"] for m in merchants]

month_windows = []
for m in MONTHS:
    nxt = (m.replace(day=28) + timedelta(days=4)).replace(day=1)
    month_windows.append((datetime(m.year, m.month, 1), datetime(nxt.year, nxt.month, nxt.day) - timedelta(seconds=1)))

merchant_in = {}   # merchant index -> [(at, amount)]
for u in users:
    w = u["weight"]
    for a, b in month_windows:
        if b < u["join"]:
            continue
        a = max(a, u["join"])
        if u["acct"]:
            ac = accounts[u["acct"]]
            for _ in range(poisson(0.20 * w)):
                tx(rng.choice(hot[ac["exchange"]]), rng.choice(u["addrs"]), coins(0.9, 1.1), rand_time(a, b))
            for _ in range(poisson(0.17 * w)):
                tx(rng.choice(u["addrs"]), ac["deposit"], coins(0.7, 1.1), rand_time(a, b))
            if u["acct2"] and rng.random() < 0.15 * w:
                tx(rng.choice(hot[ac["exchange"]]), accounts[u["acct2"]]["deposit"], coins(0.8, 1.0), rand_time(a, b))
        if u["contacts"]:
            for _ in range(poisson(0.13 * w)):
                c = rng.choice(u["contacts"])
                tx(rng.choice(u["addrs"]), rng.choice(c["addrs"]), coins(0.3, 1.0), rand_time(max(a, c["join"]), b) if c["join"] < b else b)
        for _ in range(poisson(0.18 * w)):
            mi = rng.choices(range(N_MERCHANTS), m_weights)[0]
            t, amt = rand_time(a, b), coins(45, 0.9)
            tx(rng.choice(u["addrs"]), rng.choice(merchants[mi]["addrs"]), amt, t, "SUSD")
            merchant_in.setdefault(mi, []).append((t, amt))
for mi, pays in sorted(merchant_in.items()):   # a merchant sweeps the week's takings to its exchange account on Monday
    m = merchants[mi]
    if not m["acct"]:
        continue
    weeks = {}
    for t, amt in pays:
        monday = (t + timedelta(days=7 - t.weekday())).date()
        weeks[monday] = weeks.get(monday, 0.0) + amt
    for monday, amt in sorted(weeks.items()):
        t = datetime(monday.year, monday.month, monday.day, rng.randint(8, 11), rng.randint(0, 59), rng.randint(0, 59))
        if t <= T_END:
            tx(rng.choice(m["addrs"]), accounts[m["acct"]]["deposit"], amt * rng.uniform(0.85, 0.98), t, "SUSD")

reserved = set()   # users a story or a look-alike uses; never picked twice


def pick_users(n, pred):
    pool = [u for u in users if u["i"] not in reserved and pred(u)]
    out = rng.sample(pool, n)
    reserved.update(u["i"] for u in out)
    return out


# ---- mixer: background orders ----------------------------------------------------------------------------------------------------

mixer_orders = []   # {"user", "in_addr", "md", "t", "amount", "exits": [[pool, to, amount, at]]}


def mixer_order(u, in_addr: str, t: datetime, amount: float, exits):
    """One order: in_addr pays a fresh mixer deposit address, which the mixer sweeps into its pool; exits leave the pool."""
    md = mk("mixer", t)
    o = {"user": u, "in_addr": in_addr, "md": md, "t": t, "amount": amount, "exits": exits}
    mixer_orders.append(o)
    return o


mixer_users = pick_users(N_MIXER_USERS, lambda u: u["join"] < T0 and u["role"] == "bg")
for u in mixer_users:
    u["role"] = "mixer_user"
    for _ in range(rng.choice([1, 1, 2, 3])):
        t, amt = rand_time(T0 + timedelta(days=2), T_END - timedelta(days=5)), coins(3.0, 0.8, 0.2)
        k = rng.choice([1, 2, 2, 3])
        parts = [rng.uniform(0.5, 1.5) for _ in range(k)]
        net = amt * (1 - rng.uniform(0.01, 0.03))
        exits = [[rng.choice(mixer_pool), mk("unknown", t, owner=u["i"]), net * p / sum(parts),
                  t + timedelta(minutes=rng.randint(6 * 60 + 5, 72 * 60))] for p in parts]
        mixer_order(u, rng.choice(u["addrs"]), t, amt, exits)

# ---- story 1: peel chain from a hacked wallet; look-alike: a payroll chain ---------------------------------------------------------

HACK_T = datetime(2026, 3, 14, 2, 17, 41)
hacked = mk("hacked", datetime(2025, 6, 2))
for i in range(6):   # the project's treasury: bought on an exchange, spent with a few vendors
    tx(rng.choice(hot["Larkmoor Digital Exchange"]), hacked, coins(80, 0.3), rand_time(T0 + timedelta(days=i * 25), T0 + timedelta(days=i * 25 + 20)))
for mi in rng.sample(range(N_MERCHANTS), 4):
    t, amt = rand_time(T0 + timedelta(days=20), HACK_T - timedelta(days=10)), coins(900, 0.4)
    tx(hacked, rng.choice(merchants[mi]["addrs"]), amt, t, "SUSD")
STOLEN = 412.5
mule_people = []
while len(mule_people) < 6:   # six unrelated people: no shared surname
    pid = new_person([new_bank()])
    if persons[pid]["name"].split()[-1] in {persons[q]["name"].split()[-1] for q in mule_people}:
        persons[pid]["name"] = f"{rng.choice(FIRST)} {rng.choice([x for x in LAST if x not in {persons[q]['name'].split()[-1] for q in mule_people}])}"
    mule_people.append(pid)
peel_exchanges = ["Fernhill Coin Exchange", "Ashgrove Exchange", "Pebblebrook Exchange", "Wrenfield Exchange", "Fernhill Coin Exchange",
                  "Ashgrove Exchange"]
peel_accounts = [new_account(p, ex, datetime(2026, 2, 1) + timedelta(days=rng.randint(0, 30), hours=rng.randint(8, 20)))
                 for p, ex in zip(mule_people, peel_exchanges)]
peel_deposits = [accounts[a]["deposit"] for a in peel_accounts]
hops = [mk("unknown", HACK_T) for _ in range(80)]
keeper = mk("unknown", HACK_T)
t = HACK_T
tx(hacked, hops[0], STOLEN, t)
remaining = STOLEN
targets = [i % 6 for i in range(80)]
rng.shuffle(targets)
peels = []
for i, h in enumerate(hops):
    t = t + timedelta(minutes=rng.randint(12, 140))
    peel = round(rng.uniform(1.6, 4.9), 6)
    change = round(remaining - peel - 0.0004, 6)
    nxt = hops[i + 1] if i + 1 < len(hops) else keeper
    tx(h, peel_deposits[targets[i]], peel, t)
    tx(h, nxt, change, t)
    peels.append((h, peel_deposits[targets[i]], peel, t))
    remaining = change
peel_cashouts = []   # each mule account pays most of what it got out to its own bank within days
for k, acc in enumerate(peel_accounts):
    got = [p for p in peels if p[1] == peel_deposits[k]]
    for j in range(0, len(got), 4):
        lot = got[j:j + 4]
        peel_cashouts.append((acc, persons[accounts[acc]["person"]]["banks"][0], round(sum(p[2] for p in lot) * rng.uniform(1100, 1300), 2),
                              lot[-1][3] + timedelta(hours=rng.randint(3, 50), minutes=rng.randint(0, 59))))

PAYROLL_STAFF = 25
company = mk("merchant", T0 - timedelta(days=800))
company_acct = new_account(new_person(), "Larkmoor Digital Exchange", T0 - timedelta(days=820))
staff = pick_users(PAYROLL_STAFF, lambda u: u["join"] < T0 and u["role"] == "bg")
salary = {u["i"]: round(rng.uniform(1.1, 3.6), 4) for u in staff}
payroll_hops = []
for a, b in month_windows:
    pay_day = (b - timedelta(days=1)).date()
    while pay_day.weekday() >= 5:
        pay_day -= timedelta(days=1)
    total = sum(salary.values()) * 1.04
    t = datetime(pay_day.year, pay_day.month, pay_day.day, 9, rng.randint(0, 20), rng.randint(0, 59))
    tx(rng.choice(hot["Larkmoor Digital Exchange"]), company, round(total * 1.01, 6), t - timedelta(days=2, hours=rng.randint(1, 5)))
    chain = [mk("unknown", t) for _ in range(PAYROLL_STAFF)]
    payroll_hops += chain
    tx(company, chain[0], total, t)
    left = total
    for k, (h, u) in enumerate(zip(chain, staff)):
        t = t + timedelta(minutes=rng.randint(1, 3), seconds=rng.randint(0, 59))
        pay = salary[u["i"]]
        left = round(left - pay - 0.0002, 6)
        tx(h, u["addrs"][0], pay, t)
        tx(h, chain[k + 1] if k + 1 < len(chain) else company, left, t)

# ---- story 2: mixer hop matched by timing and amount; look-alikes: near misses ---------------------------------------------------

ransom = mk("unknown", datetime(2026, 4, 2, 11))
ransom_payers = pick_users(7, lambda u: u["join"] < T0 and u["role"] == "bg" and u["acct"] is not None)
for u in ransom_payers:
    tx(rng.choice(u["addrs"]), ransom, coins(5.4, 0.15), rand_time(datetime(2026, 4, 2, 12), datetime(2026, 5, 10)))
cashout_person = new_person([new_bank()])
cashout_account = new_account(cashout_person, "Ashgrove Exchange", datetime(2026, 1, 19, 15, 2))
cashout_deposit = accounts[cashout_account]["deposit"]
mixer_story = []
for when, amt in [(datetime(2026, 5, 14, 9, 12, 4), 11.8), (datetime(2026, 5, 21, 22, 40, 31), 9.65), (datetime(2026, 6, 2, 3, 5, 52), 8.9)]:
    r = mk("unknown", when)
    mins, ratio = rng.randint(38, 112), rng.uniform(0.982, 0.994)
    out_t = when + timedelta(minutes=mins, seconds=rng.randint(0, 59))
    o = mixer_order(None, ransom, when, amt, [[rng.choice(mixer_pool), r, amt * ratio, out_t]])
    o["story"] = True
    dep_t = out_t + timedelta(hours=rng.randint(2, 9), minutes=rng.randint(0, 59))
    tx(r, cashout_deposit, round(amt * ratio, 6) - 0.0004, dep_t)
    mixer_story.append({"exit_address": r, "input": amt, "minutes": mins, "out_at": out_t, "deposited_at": dep_t})
story_cashouts = [(cashout_account, persons[cashout_person]["banks"][0], round(sum(m["input"] for m in mixer_story[:2]) * 0.97 * 1180, 2),
                   datetime(2026, 5, 24, 10, 41, 7)),
                  (cashout_account, persons[cashout_person]["banks"][0], round(mixer_story[2]["input"] * 0.97 * 1210, 2), datetime(2026, 6, 3, 16, 5, 33))]

near_miss = []   # (look-alike user, input amount, minutes to the other user's exit, ratio)
for when, amt, mins, ratio in [(datetime(2026, 7, 8, 14, 20, 9), 5.2, 75, 0.925), (datetime(2026, 8, 19, 20, 3, 44), 3.4, 281, 0.991)]:
    u, b = rng.sample([m for m in mixer_users if not m.get("near")], 2)
    u["near"] = b["near"] = True
    own = [[rng.choice(mixer_pool), mk("unknown", when, owner=u["i"]), amt * 0.98, when + timedelta(hours=rng.randint(20, 40))]]
    mixer_order(u, u["addrs"][0], when, amt, own)
    w = mk("unknown", when, owner=b["i"])
    b_in = amt * ratio / 0.982
    mixer_order(b, b["addrs"][0], when - timedelta(hours=27), b_in, [[rng.choice(mixer_pool), w, amt * ratio, when + timedelta(minutes=mins, seconds=13)]])
    near_miss.append({"lookalike": u["addrs"][0], "other_exit": w, "minutes": mins, "ratio": ratio, "user": u["i"]})

# ---- story 3: one cluster cashes out through 40 accounts to 3 banks; look-alike: an OTC desk ---------------------------------------

mule_banks = [new_bank() for _ in range(3)]
scam_cluster_addrs = [mk("unknown", datetime(2026, 1, 25) + timedelta(days=rng.randint(0, 20))) for _ in range(30)]
scam_cluster = new_cluster(scam_cluster_addrs)
scam_victims = pick_users(150, lambda u: u["join"] < T0 and u["role"] == "bg")
for u in scam_victims:
    for _ in range(rng.choice([1, 1, 2])):
        tx(rng.choice(u["addrs"]), rng.choice(scam_cluster_addrs), coins(2.2, 0.7), rand_time(datetime(2026, 2, 10), datetime(2026, 6, 20)))
for _ in range(40):   # the cluster's addresses spend together (that is how the common-input hint found them)
    a, b = rng.sample(scam_cluster_addrs, 2)
    tx(a, b, coins(1.5, 0.5), rand_time(datetime(2026, 3, 1), datetime(2026, 7, 15)))
fan_people, fan_accounts = [], []
for k in range(40):
    p = new_person([mule_banks[k % 3]])
    fan_people.append(p)
    fan_accounts.append(new_account(p, "Fernhill Coin Exchange" if k < 22 else "Pebblebrook Exchange",
                                    datetime(2026, 2, 3) + timedelta(days=rng.randint(0, 40), hours=rng.randint(8, 21), minutes=rng.randint(0, 59))))
fan_cashouts = []
for acc in fan_accounts:
    dep = accounts[acc]["deposit"]
    for _ in range(rng.randint(3, 8)):
        t, amt = rand_time(datetime(2026, 4, 6), datetime(2026, 7, 31)), coins(1.6, 0.4)
        tx(rng.choice(scam_cluster_addrs), dep, amt, t)
        fan_cashouts.append((acc, persons[accounts[acc]["person"]]["banks"][0], round(amt * rng.uniform(1050, 1250), 2),
                             t + timedelta(hours=rng.randint(4, 60), minutes=rng.randint(0, 59))))

otc_addrs = [mk("unknown", T0 - timedelta(days=400)) for _ in range(8)]
otc_cluster = new_cluster(otc_addrs)
otc_acct = new_account(new_person(), "Larkmoor Digital Exchange", T0 - timedelta(days=420))
otc_customers = pick_users(60, lambda u: u["join"] < T0 and u["role"] == "bg" and u["acct"] is not None)
for _ in range(90):   # the desk buys on its own exchange account and withdraws to its wallets
    tx(rng.choice(hot["Larkmoor Digital Exchange"]), rng.choice(otc_addrs), coins(25, 0.6), rand_time(T0, T_END - timedelta(days=1)))
for u in otc_customers:
    for _ in range(rng.randint(1, 4)):
        tx(rng.choice(otc_addrs), accounts[u["acct"]]["deposit"], coins(4, 0.7), rand_time(T0 + timedelta(days=3), T_LANDED - timedelta(days=2)))

# ---- mixer transfers, near-miss control, peel-shape control ------------------------------------------------------------------------

for o in mixer_orders:
    tx(o["in_addr"], o["md"], o["amount"], o["t"])
    tx(o["md"], rng.choice(mixer_pool), o["amount"] * 0.999, o["t"] + timedelta(minutes=rng.randint(10, 50)))


def candidates():
    """Every (input, exit) pair a mixer screen would consider, as the mixer_exit link of the Job computes it."""
    ins = sorted((o["t"], o["amount"], o["in_addr"], id(o)) for o in mixer_orders)
    outs = sorted((e[3], round(e[2], 6), e[1], id(o)) for o in mixer_orders for e in o["exits"])
    found = []
    for t_in, a_in, src, _ in ins:
        for t_out, a_out, dst, _ in outs:
            if t_in < t_out <= t_in + timedelta(hours=CAND_HOURS) and CAND_RATIO * round(a_in, 6) <= a_out <= round(a_in, 6):
                mins = int((t_out - t_in).total_seconds() // 60)
                found.append((src, dst, mins, a_out / round(a_in, 6), mins <= MATCH_MINUTES and a_out / round(a_in, 6) >= MATCH_RATIO, t_out))
    return found


story_exits = {m["exit_address"] for m in mixer_story}
for _ in range(20):   # a background exit that would match an input by chance leaves later
    bad = {c[1] for c in candidates() if c[4] and c[1] not in story_exits}
    if not bad:
        break
    for o in mixer_orders:
        for e in o["exits"]:
            if e[1] in bad:
                e[3] += timedelta(hours=3)
cands = candidates()
matched = sorted((c[0], c[1]) for c in cands if c[4])
assert matched == sorted((ransom, r) for r in story_exits), matched
for nm in near_miss:
    assert [c for c in cands if c[0] == nm["lookalike"]] and not any(c[4] for c in cands if c[0] == nm["lookalike"]), nm
for o in mixer_orders:
    for e in o["exits"]:
        tx(e[0], e[1], e[2], e[3])
        if not o.get("story") and o["user"] is not None:   # the fresh exit address moves the coins home within days
            u = o["user"]
            dest = accounts[u["acct"]]["deposit"] if u["acct"] else u["addrs"][0]
            tx(e[1], dest, e[2] - 0.0003, e[3] + timedelta(days=rng.randint(1, 9), hours=rng.randint(0, 12)))


def shape():
    """Addresses with the peel shape (one transfer in, two out to two addresses within HOP_HOURS) and those chained to another."""
    ins, outs = {}, {}
    for f, t, amt, asset, at in transfers:
        if at <= T_LANDED:
            ins.setdefault(t, []).append(at)
            outs.setdefault(f, []).append((at, t))
    hop = set()
    for a, o in outs.items():
        i = ins.get(a, [])
        if len(i) == 1 and len(o) == 2 and len({d for _, d in o}) == 2 and all(i[0] <= x <= i[0] + timedelta(hours=HOP_HOURS) for x, _ in o):
            hop.add(a)
    chained = set()
    for f, t, amt, asset, at in transfers:
        if at <= T_LANDED and f in hop and t in hop:
            chained |= {f, t}
    return hop, chained


story_hops, payroll_set = set(hops), {h for h in payroll_hops if created[h] <= T_LANDED}
for _ in range(10):   # a background address that happens to chain with another moves its last coins home later
    hop, chained = shape()
    stray = sorted(chained - story_hops - payroll_set)
    if not stray:
        break
    for a in stray:
        last = max(at for f, t, amt, asset, at in transfers if f == a and at <= T_LANDED)
        o = owner_of.get(a)
        if isinstance(o, int):
            dest = accounts[users[o]["acct"]]["deposit"] if users[o]["acct"] else next(x for x in users[o]["addrs"] if x != a) if len(users[o]["addrs"]) > 1 else rng.choice(hot[EXCHANGES[0]])
        else:
            dest = rng.choice(hot[EXCHANGES[0]])
        tx(a, dest, 0.0005, min(T_LANDED, last + timedelta(minutes=rng.randint(5, 90))))
hop, chained = shape()
assert chained == story_hops | payroll_set, sorted(chained ^ (story_hops | payroll_set))[:5]

# ---- cash-outs -----------------------------------------------------------------------------------------------------------------

deposits_in = {}   # account -> [(at, amount, asset)]
dep_owner = {ac["deposit"]: aid for aid, ac in accounts.items()}
for f, t, amt, asset, at in transfers:
    if t in dep_owner:
        deposits_in.setdefault(dep_owner[t], []).append((at, amt, asset))
story_accounts = set(peel_accounts) | {cashout_account} | set(fan_accounts)
cashouts = list(peel_cashouts) + story_cashouts + fan_cashouts
for aid in sorted(deposits_in):
    if aid in story_accounts:
        continue
    by_month = {}
    for at, amt, asset in deposits_in[aid]:
        by_month.setdefault((at.year, at.month), []).append((at, amt * (1.0 if asset == "SUSD" else 1180.0)))
    for (y, mo), rows in sorted(by_month.items()):
        if rng.random() < 0.55:
            last = max(r[0] for r in rows)
            banks = persons[accounts[aid]["person"]]["banks"]
            when = last + timedelta(days=rng.randint(0, 6), hours=rng.randint(1, 30))
            cashouts.append((aid, rng.choice(banks), round(sum(r[1] for r in rows) * rng.uniform(0.3, 0.95), 2), when.replace(hour=rng.randint(8, 19))))

# ---- checks over the corpus as the screens see it --------------------------------------------------------------------------------

landed = [r for r in transfers if r[4] <= T_LANDED]
pair_asset = {}
for f, t, amt, asset, at in transfers:
    assert pair_asset.setdefault((f, t), asset) == asset, "one asset per address pair"
funded = {}   # cluster -> exchange accounts its addresses paid (clusters of exchange hot wallets are services, not screened)
for f, t, amt, asset, at in landed:
    if f in cluster_of and t in dep_owner and label[f] != "exchange":
        funded.setdefault(cluster_of[f], set()).add(dep_owner[t])
flagged = sorted(c for c, v in funded.items() if len(v) >= FUNDED_ACCOUNTS)
assert flagged == sorted([scam_cluster, otc_cluster]), sorted(((c, len(v)) for c, v in funded.items()), key=lambda x: -x[1])[:5]
assert max(len(v) for c, v in funded.items() if c not in flagged) <= FUNDED_ACCOUNTS - 5
fan_banks = {b for a, b, amt, at in cashouts if a in fan_accounts}
otc_banks = {b for a, b, amt, at in cashouts if a in {u["acct"] for u in otc_customers}}
assert fan_banks == set(mule_banks) and len(otc_banks) >= 50 and not otc_banks & fan_banks, (len(fan_banks), len(otc_banks))
assert not {accounts[u["acct"]]["person"] for u in otc_customers} & set(fan_people)

# ---- write -------------------------------------------------------------------------------------------------------------------------


def write(rel, header, rows):
    if not rows and rel.startswith("staged/"):
        return
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

transfers = sorted((r for r in transfers if r[4] <= T_END), key=lambda r: (r[4], r[0], r[1]))
first_seen = {}
for f, t, amt, asset, at in transfers:
    first_seen.setdefault(f, at)
    first_seen.setdefault(t, at)
for a in label:
    first_seen.setdefault(a, max(created[a], T0) if label[a] == "exchange" and a in dep_owner else None)
seen = {a: fs for a, fs in first_seen.items() if fs is not None}
W_HEAD = ["address", "first_seen", "label"]
by_seen = sorted(seen.items(), key=lambda x: (x[1], x[0]))
write("inbox/wallet/WALLET_20260930.csv", W_HEAD, [[a, ts(fs), label[a]] for a, fs in by_seen if fs <= T_LANDED])
write("staged/wallet/WALLET_20261031.csv", W_HEAD, [[a, ts(fs), label[a]] for a, fs in by_seen if fs > T_LANDED])
C_HEAD = ["address", "cluster_id"]
write("inbox/cluster_hint/CLUSTER_HINT_20260930.csv", C_HEAD, [[a, cluster_of[a]] for a, fs in by_seen if a in cluster_of and fs <= T_LANDED])
write("staged/cluster_hint/CLUSTER_HINT_20261031.csv", C_HEAD, [[a, cluster_of[a]] for a, fs in by_seen if a in cluster_of and fs > T_LANDED])
A_HEAD = ["account_id", "exchange", "kyc_person_id", "deposit_address", "opened_at"]
write("inbox/exchange_account/EXCHANGE_ACCOUNT_20260930.csv", A_HEAD,
      [[aid, ac["exchange"], ac["person"], ac["deposit"], ts(ac["opened"])] for aid, ac in sorted(accounts.items()) if ac["opened"] <= T_LANDED])
write("staged/exchange_account/EXCHANGE_ACCOUNT_20261031.csv", A_HEAD,
      [[aid, ac["exchange"], ac["person"], ac["deposit"], ts(ac["opened"])] for aid, ac in sorted(accounts.items()) if ac["opened"] > T_LANDED])
first_open = {}
for aid, ac in accounts.items():
    first_open[ac["person"]] = min(first_open.get(ac["person"], ac["opened"]), ac["opened"])
P_HEAD = ["person_id", "name", "birth_year", "country"]
write("inbox/kyc_person/KYC_PERSON_20260930.csv", P_HEAD,
      [[p, v["name"], v["birth_year"], v["country"]] for p, v in sorted(persons.items()) if first_open[p] <= T_LANDED])
write("staged/kyc_person/KYC_PERSON_20261031.csv", P_HEAD,
      [[p, v["name"], v["birth_year"], v["country"]] for p, v in sorted(persons.items()) if first_open[p] > T_LANDED])
cashouts = [c for c in cashouts if c[3] <= T_END]
cashouts.sort(key=lambda c: (c[3], c[0]))
for m, (a, b) in zip(MONTHS, month_windows):
    ym, box = m.strftime("%Y%m"), "staged" if m > LANDED_END else "inbox"
    within = lambda t: a <= t <= b   # noqa: E731
    write(f"{box}/transfer/TRANSFER_{ym}.csv", ["tx_id", "from_address", "to_address", "amount", "asset", "tx_at"],
          [[f"TX-{i + 1:07d}", f, t, f"{amt:.6f}", asset, ts(at)] for i, (f, t, amt, asset, at) in enumerate(transfers) if within(at)])
    write(f"{box}/cashout/CASHOUT_{ym}.csv", ["cashout_id", "account_id", "bank_account", "amount", "cashout_at"],
          [[f"CO-{i + 1:06d}", acc, bank, f"{amt:.2f}", ts(at)] for i, (acc, bank, amt, at) in enumerate(cashouts) if within(at)])

truth = {
    "seed": SEED,
    "window": {"landed": [START.isoformat(), LANDED_END.isoformat()], "staged": [(LANDED_END + timedelta(days=1)).isoformat(), END.isoformat()]},
    "thresholds": {"hop_hours": HOP_HOURS, "match_minutes": MATCH_MINUTES, "match_ratio": MATCH_RATIO, "candidate_hours": CAND_HOURS,
                   "candidate_ratio": CAND_RATIO, "funded_accounts": FUNDED_ACCOUNTS},
    "counts": {"wallet_landed": sum(1 for a, fs in seen.items() if fs <= T_LANDED), "transfer_landed": len(landed),
               "cluster_hint_landed": sum(1 for a, fs in seen.items() if a in cluster_of and fs <= T_LANDED),
               "exchange_account_landed": sum(1 for ac in accounts.values() if ac["opened"] <= T_LANDED),
               "kyc_person_landed": sum(1 for p in persons if first_open[p] <= T_LANDED),
               "cashout_landed": sum(1 for c in cashouts if c[3] <= T_LANDED)},
    "stories": {
        "peel_chain": {"hub": hops[0], "view": "hack_peel_chain",
                       "entities": sorted([hacked, *hops, keeper, *peel_deposits, *peel_accounts, *mule_people]), "lookalikes": [company]},
        "mixer_hop": {"hub": ransom, "view": "mixer_hop_timing_match",
                      "entities": sorted([ransom, *story_exits, cashout_deposit, cashout_account, cashout_person]),
                      "lookalikes": sorted(nm["lookalike"] for nm in near_miss)},
        "converging_cash_out": {"hub": scam_cluster, "view": "converging_cash_out",
                                "entities": sorted([scam_cluster, *scam_cluster_addrs, *(accounts[a]["deposit"] for a in fan_accounts), *fan_accounts,
                                                 *fan_people, *mule_banks]), "lookalikes": [otc_cluster]},
    },
    "peel_chain": {"hacked": hacked, "hacked_at": ts(HACK_T), "stolen": STOLEN, "hops": hops, "keeper": keeper, "kept": remaining,
                   "deposits": peel_deposits, "accounts": peel_accounts, "persons": mule_people,
                   "peeled": round(sum(p[2] for p in peels), 6), "last_hop_at": ts(peels[-1][3]),
                   "lookalike_wallet": company, "lookalike_hops_landed": sum(1 for h in payroll_hops if created[h] <= T_LANDED),
                   "lookalike_staff": sorted(u["addrs"][0] for u in staff)},
    "mixer_hop": {"input_address": ransom, "inputs": [m["input"] for m in mixer_story], "exits": [m["exit_address"] for m in mixer_story],
                  "minutes": [m["minutes"] for m in mixer_story], "deposit": cashout_deposit, "account": cashout_account,
                  "person": cashout_person, "exchange": "Ashgrove Exchange",
                  "lookalikes": [{"address": nm["lookalike"], "other_exit": nm["other_exit"], "minutes": nm["minutes"], "ratio": round(nm["ratio"], 4)}
                                 for nm in near_miss], "candidate_pairs_landed": len({(c[0], c[1]) for c in cands if c[5] <= T_LANDED})},
    "converging_cash_out": {"cluster": scam_cluster, "addresses": sorted(scam_cluster_addrs), "accounts": sorted(fan_accounts),
                            "deposits": sorted(accounts[a]["deposit"] for a in fan_accounts), "persons": sorted(fan_people), "banks": sorted(mule_banks),
                            "exchanges": {"Fernhill Coin Exchange": 22, "Pebblebrook Exchange": 18},
                            "lookalike_cluster": otc_cluster, "lookalike_addresses": sorted(otc_addrs),
                            "lookalike_accounts": sorted(u["acct"] for u in otc_customers), "lookalike_banks": len(otc_banks)},
}
TRUTH.write_text(json.dumps(truth, indent=1, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
c = truth["counts"]
print(f"addresses {c['wallet_landed']} landed of {len(seen)}, transfers {c['transfer_landed']} landed of {len(transfers)}, clusters "
      f"{c['cluster_hint_landed']}, accounts {c['exchange_account_landed']}, persons {c['kyc_person_landed']}, cashouts {c['cashout_landed']}; "
      f"pairs {len(pair_asset)}; mixer candidates {len(cands)}; flagged {flagged}; peel hops {len(hops)}, payroll hops {len(payroll_hops)}")
