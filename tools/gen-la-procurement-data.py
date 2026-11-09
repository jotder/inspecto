#!/usr/bin/env python3
"""Regenerate the `la-procurement` Space Template's bid-rigging and procurement fraud corpus - SYNTHETIC, deterministic (one seeded Random).

Writes spaces/_templates/la-procurement/data/inbox/<feed>/ (12 months landed, tenders published 2025-10-01..2026-09-30) and
data/staged/<feed>/ (the next month, 2026-10, for the "new data arrives" step) for seven feeds:
  company, director, procurement_person, official (one extract each, landed only), tender and bid (one file per month of publication),
  contract_change (one landed extract, one staged month)
and tools/gen-la-procurement-data.ground-truth.json (story -> entity ids and look-alike ids; NOT shipped in the data download).

Planted stories (plan: docs/superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md, spec 7; pinned by
tools/gen-la-procurement-data.test.mjs over the files and ProcurementLinksJobGoldenTest over the link Datasets):
  1 BID ROTATION        4 road firms win the 24 Highways North road-maintenance tenders in turn; only they bid; the losing bids are
                        5-12 % above the winner (never identical); 2 of the firms share a director
                        look-alike: 3 large firms that win most Highways South road tenders but bid independently (lowest bid wins,
                        spreads from under 1 % to over 20 %, they skip tenders)
  2 COVER BIDS          one IT firm wins 10 tenders; its 3 "competitors" bid 5-12 % higher, 60 % of those cover bids from the
                        winner's own IP address within minutes of its bid, 75 % with the winner's estimator as document author
                        look-alike: the buyer's public help-desk kiosk (one IP) that 14 small firms bid from, hours apart, own authors
  3 CONFLICT OF         a Parks official approves 9 contract changes for one grounds firm whose director - a relative - lives at
    INTEREST            the official's home address
                        look-alike: a Facilities contracts manager who approves about 34 changes across many unrelated suppliers
  4 PHOENIX FIRM        a cleaning firm debarred on 2026-01-15; its two directors resign and run a new firm incorporated 2026-02-02
                        that wins a cleaning tender awarded 2026-04-08, inside 3 months
                        look-alike: the director of a firm debarred in 2022 who joins an unrelated, long-established firm in 2025
Scale: 900 companies, 2 000 people (300 officials), about 1 200 landed tenders and 6 000 bids, 800 contract changes. Phones are in
Ofcom's drama ranges; IPs are shared-address (RFC 6598) and documentation (RFC 5737) ranges; bank accounts are invented SYN- numbers;
names and streets are invented, cities are real only at city level. Nothing derives from a real source.
"""
from __future__ import annotations

import csv
import json
import random
from datetime import date, datetime, timedelta
from pathlib import Path

from la_gen_io import Outputs

TOOLS = Path(__file__).resolve().parent
DATA = TOOLS.parent / "spaces" / "_templates" / "la-procurement" / "data"
TRUTH = TOOLS / "gen-la-procurement-data.ground-truth.json"
rng = random.Random(20261108)
START, LANDED_END, END = date(2025, 10, 1), date(2026, 9, 30), date(2026, 10, 31)
N_COMPANIES, N_PERSONS, N_OFFICIALS = 900, 2000, 300
KIOSK_IP = "198.51.100.20"
PLACEHOLDER_AUTHOR = "Administrator"
DEBAR_DAY, NEW_FIRM_DAY, PHOENIX_AWARD = date(2026, 1, 15), date(2026, 2, 2), date(2026, 4, 8)

# category -> buyer units, landed tenders, median estimate, words for company names
MARKETS = {
    "Road maintenance": (["Highways North", "Highways South"], 160, 180_000, ["Paving", "Surfacing", "Roadworks", "Highways"]),
    "IT services": (["Digital Services"], 140, 90_000, ["Digital", "Systems", "Data", "Networks"]),
    "Parks and grounds": (["Parks"], 140, 60_000, ["Grounds", "Landscapes", "Gardens", "Arboriculture"]),
    "Waste collection": (["Environment"], 120, 250_000, ["Waste", "Recycling", "Environmental"]),
    "Building repairs": (["Property", "Housing"], 200, 70_000, ["Builders", "Construction", "Building Services", "Joinery"]),
    "Cleaning services": (["Facilities"], 140, 50_000, ["Cleaning", "Hygiene", "Janitorial"]),
    "Care transport": (["Adult Social Care"], 120, 80_000, ["Transport", "Travel", "Mobility", "Minibuses"]),
    "Office supplies": (["Procurement Hub", "Facilities"], 180, 20_000, ["Office Supplies", "Stationers", "Supplies"]),
}
UNITS = sorted({u for m in MARKETS.values() for u in m[0]})
CITIES = ["Leeds", "Sheffield", "Bradford", "Wakefield", "York"]
FIRST = ["Ada", "Basil", "Cora", "Dev", "Edie", "Flynn", "Gwen", "Hugo", "Isla", "Jude", "Kira", "Lars", "Maeve", "Nico", "Opal",
         "Piers", "Rhea", "Sami", "Tess", "Ugo", "Vida", "Wade", "Xena", "Yusuf", "Zara", "Arlo", "Bea", "Cyrus", "Dina", "Eben",
         "Fenna", "Gil", "Hana", "Ivo", "Juno", "Kofi", "Lumi", "Milo", "Nell", "Otis", "Pria", "Quin", "Ravi", "Sela", "Tobin"]
LAST = ["Abbott", "Bramley", "Calloway", "Denholm", "Ellery", "Fairbairn", "Gilchrist", "Holloway", "Ingram", "Jessop", "Kinsella",
        "Lindqvist", "McAllister", "Nightingale", "Ormsby", "Prentice", "Quarry", "Rowntree", "Stanhope", "Treadwell", "Upton",
        "Varga", "Wetherby", "Yelland", "Zamora", "Ashby", "Blackwood", "Crowther", "Dunbar", "Everley", "Fenwick", "Garside",
        "Hartigan", "Ivesley", "Jowett", "Kettering", "Loxley", "Marchbank", "Northcott", "Oddie", "Pemberton", "Radcliffe"]
STREET = ["Amber", "Bracken", "Chalk", "Dove", "Ember", "Flint", "Gorse", "Hazel", "Iron", "Jasper", "Kiln", "Larch", "Mill", "Oak",
          "Pippin", "Quarry", "Rowan", "Sorrel", "Teasel", "Willow", "Alder", "Brook", "Cobble", "Dunnock", "Elder", "Fern"]
SUFFIX = {"Lane": "Ln", "Road": "Rd", "Close": "Cl", "Street": "St", "Avenue": "Ave", "Grove": "Gr"}
ADJ = ["Amber", "Blue", "Copper", "Daisy", "Elm", "Fox", "Golden", "Harbour", "Ivy", "Juniper", "Kestrel", "Linden", "Maple", "North",
       "Orchard", "Pebble", "Quill", "Red", "Silver", "Thistle", "Union", "Vale", "Wren", "Yarrow", "Aire", "Calder", "Dales", "Pennine"]
LEGAL = ["Ltd", "Limited", "Ltd", "LLP", "Group"]
PHONE_RANGES = ["07700 900", "01632 960", "0113 496 0", "0114 496 0", "0115 496 0", "0116 496 0", "0117 496 0", "0118 496 0"]
HOURS = [0, 0, 0, 0, 0, 0, 0, 1, 4, 7, 8, 8, 6, 7, 8, 8, 7, 5, 3, 2, 1, 1, 0, 0]

# story companies (fixed ids, so the docs and tests can name them)
R = ["CO-0117", "CO-0242", "CO-0388", "CO-0561"]              # bid rotation
L = ["CO-0021", "CO-0055", "CO-0090"]                         # look-alike: big road firms bidding independently
W, C = "CO-0074", ["CO-0153", "CO-0309", "CO-0702"]           # cover bids
F = "CO-0426"                                                 # conflict of interest: the favoured grounds firm
D_OLD, D_NEW = "CO-0815", "CO-0899"                           # phoenix
X_OLD, U = "CO-0640", "CO-0233"                               # look-alike: debarred in 2022, a director joins U in 2025
STORY_COS = set(R + L + [W, *C, F, D_OLD, D_NEW, X_OLD, U])
MAIN = {**{c: "Road maintenance" for c in R + L}, W: "IT services", **{c: "IT services" for c in C}, F: "Parks and grounds",
        D_OLD: "Cleaning services", D_NEW: "Cleaning services", X_OLD: "Building repairs", U: "Care transport"}


def pick(seq):
    return seq[rng.randrange(len(seq))]


def ts(t):
    return t.strftime("%Y-%m-%d %H:%M:%S")


def weekday_between(a, b):
    while True:
        d = a + timedelta(days=rng.randrange((b - a).days + 1))
        if d.weekday() < 5:
            return d


def office_time(d):
    return datetime(d.year, d.month, d.day, rng.choices(range(24), weights=HOURS)[0]) + timedelta(seconds=rng.randrange(3600))


def street(city):
    return f"{rng.randint(1, 180)} {pick(STREET)} {pick(list(SUFFIX))} {city}"


def short(addr):
    for long_, abbr in SUFFIX.items():
        if f" {long_} " in addr:
            return addr.replace(f" {long_} ", f" {abbr} ")
    return addr


phones = [r + f"{i:03d}" for r in PHONE_RANGES for i in range(1000)]
rng.shuffle(phones)

# ---- companies ------------------------------------------------------------------------------------------------------------------
cats = list(MARKETS)
cat_w = [MARKETS[c][1] for c in cats]
companies, names = {}, set()
for i in range(N_COMPANIES):
    cid = f"CO-{i + 1:04d}"
    main = MAIN.get(cid) or rng.choices(cats, weights=cat_w)[0]
    while True:
        name = f"{pick(ADJ)} {pick(MARKETS[main][3])} {pick(LEGAL)}"
        if name not in names:
            names.add(name)
            break
    second = rng.choices(cats, weights=cat_w)[0] if rng.random() < 0.25 and cid not in STORY_COS else None
    city = CITIES[i % len(CITIES)]
    inc = date(1995, 1, 1) + timedelta(days=rng.randrange((date(2024, 6, 30) - date(1995, 1, 1)).days))
    companies[cid] = {"id": cid, "name": name, "main": main, "second": second if second != main else None, "city": city,
                      "address": street(city), "phone": phones.pop() if rng.random() > 0.05 else "",
                      "bank": f"SYN-{rng.randrange(10 ** 8):08d}" if rng.random() > 0.04 else "", "inc": inc, "debarred": None,
                      "weight": min(rng.paretovariate(1.3), 12.0), "ips": [f"100.{64 + i // 250}.{i % 250}.{rng.randint(2, 250)}"]}
    if rng.random() < 0.15:
        companies[cid]["ips"].append(f"100.{80 + i // 250}.{i % 250}.{rng.randint(2, 250)}")
for c in L:
    companies[c]["weight"] = 20.0                                # large, efficient firms
companies[U]["inc"] = date(2009, 5, 12)
companies[X_OLD]["inc"], companies[X_OLD]["debarred"] = date(2011, 3, 1), date(2022, 5, 18)
companies[D_OLD]["inc"], companies[D_OLD]["debarred"] = date(2012, 9, 3), DEBAR_DAY
companies[D_NEW].update(inc=NEW_FIRM_DAY, address=short(companies[D_OLD]["address"]), phone=companies[D_OLD]["phone"] or phones.pop(),
                        city=companies[D_OLD]["city"])
companies[D_NEW]["name"] = companies[D_OLD]["name"].split(" ")[0] + " Facility Hygiene Ltd"
background_debarred = []
for cid in sorted(companies):                                    # 8 firms debarred years before the window, none of them bids now
    if len(background_debarred) == 8:
        break
    if cid not in STORY_COS and rng.random() < 0.03:
        companies[cid]["debarred"] = date(2018, 1, 1) + timedelta(days=rng.randrange(6 * 365))
        companies[cid]["inc"] = min(companies[cid]["inc"], companies[cid]["debarred"] - timedelta(days=900))
        background_debarred.append(cid)

# ---- people, addresses, directors, officials --------------------------------------------------------------------------------------
people, n_addr = {}, 0


def new_address():
    global n_addr
    n_addr += 1
    return f"AD-{n_addr:05d}"


def new_person(first=None, last=None, address=None):
    pid = f"P-{len(people) + 1:05d}"
    first, last = first or pick(FIRST), last or pick(LAST)
    people[pid] = {"id": pid, "name": f"{first} {last}", "first": first, "last": last, "address": address or new_address()}
    return pid


directors = []                                                   # [company, person, from, to]
protected_people = set()


def direct(cid, pid, frm, to=None):
    directors.append([cid, pid, frm, to])


# story people first
P_SHARED = new_person()
O = new_person(last="Rowntree")
D_REL = new_person(first="Tobin", last="Rountree", address=people[O]["address"])  # the official's relative, spelt differently
P1, P2, P3 = new_person(), new_person(), new_person()
PX = new_person()
O2 = new_person()
protected_people |= {P_SHARED, O, D_REL, P1, P2, P3, PX, O2}

officials = {O: "Parks", O2: "Facilities"}
for u in UNITS:                                                  # 300 officials, each at their own home
    while sum(1 for x in officials.values() if x == u) < N_OFFICIALS // len(UNITS):
        officials[new_person()] = u

seats, reusable = {}, set()
for cid, c in sorted(companies.items()):
    if cid in (D_OLD, D_NEW, X_OLD, U, F):
        continue
    k = rng.choices([1, 2, 3], weights=[55, 35, 10])[0]
    for j in range(k):
        if cid not in STORY_COS and reusable and rng.random() < 0.06:
            pid = pick(sorted(reusable))                         # a few people direct two or three firms
        else:
            pid = new_person()
        if cid in R or cid in L or cid in C or cid == W:
            protected_people.add(pid)
        frm = c["inc"] + timedelta(days=rng.randrange(0, 2000))
        frm = min(frm, date(2025, 6, 30))
        to = c["debarred"] if c["debarred"] else None
        direct(cid, pid, frm, to)
        seats[pid] = seats.get(pid, 0) + 1
        if cid not in STORY_COS and pid not in protected_people and seats[pid] < 3:
            reusable.add(pid)
        else:
            reusable.discard(pid)
    if rng.random() < 0.08 and cid not in STORY_COS:                # a director who left before the window
        pid = new_person()
        frm = c["inc"]
        direct(cid, pid, frm, min(frm + timedelta(days=rng.randrange(200, 3000)), date(2025, 6, 30)))
direct(R[1], P_SHARED, date(2016, 4, 1))
direct(R[3], P_SHARED, date(2019, 9, 16))
direct(F, D_REL, date(2017, 2, 6))
direct(F, new_person(), date(2017, 2, 6))
direct(D_OLD, P1, date(2012, 9, 3), date(2026, 1, 20))
direct(D_OLD, P2, date(2014, 1, 10), date(2026, 1, 20))
direct(D_NEW, P1, NEW_FIRM_DAY)
direct(D_NEW, P2, NEW_FIRM_DAY)
direct(D_NEW, P3, NEW_FIRM_DAY)
direct(X_OLD, PX, date(2011, 3, 1), date(2022, 5, 18))
direct(X_OLD, new_person(), date(2011, 3, 1), date(2022, 5, 18))
direct(U, new_person(), date(2009, 5, 12))
direct(U, PX, date(2025, 11, 3))                                 # three and a half years after X_OLD was debarred

director_people = {d[1] for d in directors}
# households: spouses who direct nothing, and a few couples who both direct (never an official with a director, bar the story)
while len(people) < N_PERSONS:
    host = pick(sorted(director_people | set(officials)))
    if host in protected_people:
        continue
    new_person(last=people[host]["last"] if rng.random() < 0.7 else None, address=people[host]["address"])
for _ in range(12):                                              # couples who both direct firms: move one to the other's home
    a, b = rng.sample(sorted(director_people - protected_people - set(officials)), 2)
    people[b]["address"] = people[a]["address"]

# ---- tenders and bids -----------------------------------------------------------------------------------------------------------
tenders, bids = [], []
by_market = {m: [c for c in sorted(companies) if (companies[c]["main"] == m or companies[c]["second"] == m) and c not in STORY_COS]
             for m in cats}
authors_used = set()


def author_of(first, last):
    base = f"{first[0].lower()}.{last.lower()}"
    a, n = base, 1
    while a in authors_used:
        n += 1
        a = f"{base}{n}"
    authors_used.add(a)
    return a


for cid in sorted(companies):
    staff = [author_of(pick(FIRST), pick(LAST)) for _ in range(rng.choice([1, 1, 2]))]
    companies[cid]["authors"] = staff


def month_days(y, m):
    first = date(y, m, 1)
    nxt = date(y + (m == 12), m % 12 + 1, 1)
    return first, nxt - timedelta(days=1)


def new_tender(cat, unit, published, estimate):
    t = {"id": None, "unit": unit, "cat": cat, "estimate": round(estimate, -2), "published": published,
         "deadline": published + timedelta(days=rng.randint(14, 21)), "awarded": None, "winner": None, "value": None}
    t["awarded"] = t["deadline"] + timedelta(days=rng.randint(10, 35))
    tenders.append(t)
    return t


def add_bid(t, cid, amount, when, ip=None, author=None):
    c = companies[cid]
    if ip is None:
        ip = pick(c["ips"]) if rng.random() > 0.03 else ""
    if author is None:
        r = rng.random()
        author = "" if r < 0.04 else PLACEHOLDER_AUTHOR if r < 0.07 else pick(c["authors"])
    b = {"tender": t, "bidder": cid, "amount": round(amount, 2), "at": when, "ip": ip, "author": author}
    bids.append(b)
    return b


def bid_time(t):
    return office_time(weekday_between(t["deadline"] - timedelta(days=4), t["deadline"]))


def settle(t):
    tb = [b for b in bids if b["tender"] is t]
    win = min(tb, key=lambda b: b["amount"])
    t["winner"], t["value"] = win["bidder"], win["amount"]


months = [(2025, m) for m in (10, 11, 12)] + [(2026, m) for m in range(1, 11)]
rotation, covers_t, f_tenders, south = [], [], [], []
for cat in cats:
    units, n, est, _ = MARKETS[cat]
    total = round(n * 13 / 12)
    for i in range(total):
        y, m = months[i * 13 // total]
        a, b = month_days(y, m)
        unit = units[0] if len(units) == 1 else ("Highways South" if cat == "Road maintenance" else pick(units))
        if cat == "Road maintenance" and i % 4 == 0:
            unit = "Highways North"
        t = new_tender(cat, unit, weekday_between(a, a + timedelta(days=11)), est * rng.lognormvariate(0, 0.45))
        if cat == "Road maintenance" and unit == "Highways South":
            south.append(t)
for y, m in months[:12]:                                         # the 24 rotation tenders, two a month in Highways North
    a, _ = month_days(y, m)
    for k in range(2):
        rotation.append(new_tender("Road maintenance", "Highways North", weekday_between(a + timedelta(days=k * 9), a + timedelta(days=k * 9 + 4)),
                                   210_000 * rng.lognormvariate(0, 0.25)))
it_landed = [t for t in tenders if t["cat"] == "IT services" and t["published"] <= LANDED_END]
covers_t = sorted(rng.sample([t for t in it_landed if t["published"] <= date(2026, 8, 31)], 10), key=lambda t: t["published"])
parks = [t for t in tenders if t["cat"] == "Parks and grounds" and date(2025, 10, 1) <= t["published"] <= date(2026, 3, 31)]
f_tenders = sorted(rng.sample(parks, 6), key=lambda t: t["published"])
cleaning = sorted([t for t in tenders if t["cat"] == "Cleaning services"], key=lambda t: t["published"])
d_old_t = [t for t in cleaning if t["published"] < date(2025, 12, 15)][1:7:2]
d_new_t = [t for t in cleaning if date(2026, 2, 16) <= t["published"] <= date(2026, 6, 30)][:4]
d_new_win = min((t for t in d_new_t), key=lambda t: abs((t["awarded"] - PHOENIX_AWARD).days))
d_new_win["awarded"] = PHOENIX_AWARD
special = {id(t) for t in rotation + covers_t + f_tenders + d_old_t + d_new_t}
south_ids = {id(t) for t in south}
assert d_new_win["deadline"] < PHOENIX_AWARD


def eligible(t):
    return [c for c in by_market[t["cat"]] if companies[c]["inc"] < t["published"]
            and (companies[c]["debarred"] is None or companies[c]["debarred"] > t["deadline"])]

# honest tenders: lowest bid wins
for t in tenders:
    if id(t) in special:
        continue
    pool = eligible(t)
    k = min(len(pool), rng.choices(range(2, 10), weights=[10, 18, 20, 18, 13, 9, 7, 5])[0])
    chosen = set()
    if id(t) in south_ids:
        chosen |= {c for c in L if rng.random() < 0.75}
    while len(chosen) < k:
        chosen.add(rng.choices(pool, weights=[companies[c]["weight"] for c in pool])[0])
    for cid in sorted(chosen):
        f = rng.lognormvariate(-0.04, 0.10) * (0.93 if cid in L else 1.0)
        add_bid(t, cid, t["estimate"] * f, bid_time(t))
    settle(t)
for t in [x for x in tenders if x["unit"] == "Highways North" and id(x) not in special][:8]:
    rf = R[len([b for b in bids if b["bidder"] in R]) % 4]          # each rotation firm also loses a few open tenders honestly
    w = min(b["amount"] for b in bids if b["tender"] is t)
    add_bid(t, rf, w * rng.uniform(1.04, 1.15), bid_time(t))

# 1 bid rotation: R1..R4 win in turn; only they bid; losers 5-12 % above the winner
for i, t in enumerate(sorted(rotation, key=lambda t: t["published"])):
    win = R[i % 4]
    base = t["estimate"] * rng.uniform(1.03, 1.10)
    add_bid(t, win, base, bid_time(t))
    for cid in R:
        if cid != win:
            add_bid(t, cid, base * rng.uniform(1.05, 1.12), bid_time(t))
    settle(t)

# 2 cover bids: W wins; covers 5-12 % higher; 60 % from W's IP within minutes, 75 % with W's estimator as author
W_IP, W_AUTHOR = companies[W]["ips"][0], companies[W]["authors"][0]
cover_rows = []
for t in covers_t:
    when = bid_time(t)
    w_bid = t["estimate"] * rng.uniform(1.00, 1.08)
    add_bid(t, W, w_bid, when, ip=W_IP, author=W_AUTHOR)
    for cid in sorted(rng.sample(C, rng.choice([2, 3, 3]))):
        cover_rows.append(add_bid(t, cid, w_bid * rng.uniform(1.05, 1.12), when, ip="", author=""))
    settle(t)
n_ip = round(0.6 * len(cover_rows))
ip_rows = set()
for cid in C:                                                    # every cover firm has at least one bid from W's address
    ip_rows.add(id(next(b for b in cover_rows if b["bidder"] == cid)))
for b in rng.sample(cover_rows, len(cover_rows)):
    if len(ip_rows) < n_ip:
        ip_rows.add(id(b))
au_rows = {id(b) for b in rng.sample(cover_rows, round(0.75 * len(cover_rows)))}
for b in cover_rows:
    if id(b) in ip_rows:
        b["ip"], b["at"] = W_IP, b["at"] + timedelta(minutes=rng.randint(2, 25))
    else:
        b["ip"], b["at"] = companies[b["bidder"]]["ips"][0], office_time(b["at"].date())
    b["author"] = W_AUTHOR if id(b) in au_rows else companies[b["bidder"]]["authors"][0]
for cid in [W, *C]:                                              # the ring also bids honestly elsewhere, from its own offices
    for t in rng.sample([x for x in it_landed if id(x) not in special], 4):
        add_bid(t, cid, t["value"] * rng.uniform(1.03, 1.2), bid_time(t), ip=companies[cid]["ips"][0], author=companies[cid]["authors"][0])

# 3 the favoured grounds firm wins six Parks tenders (lowest bid; the money is in the changes)
for t in f_tenders:
    pool = eligible(t)
    for cid in rng.sample(pool, 3):
        add_bid(t, cid, t["estimate"] * rng.uniform(0.98, 1.12), bid_time(t))
    add_bid(t, F, t["estimate"] * rng.uniform(0.90, 0.96), bid_time(t))
    settle(t)

# 4 phoenix: the old firm wins before it is debarred; the new one bids from February and wins in April
for t, firm in [(t, D_OLD) for t in d_old_t] + [(t, D_NEW) for t in d_new_t]:
    pool = eligible(t)
    others = rng.sample(pool, 3)
    for cid in others:
        add_bid(t, cid, t["estimate"] * rng.uniform(0.97, 1.10), bid_time(t))
    low = min(b["amount"] for b in bids if b["tender"] is t)
    win = firm == D_OLD or t is d_new_win
    add_bid(t, firm, low * (rng.uniform(0.95, 0.99) if win else rng.uniform(1.02, 1.08)), bid_time(t))
    settle(t)

# the kiosk: 14 small background firms sometimes bid from the buyer's public help-desk kiosk, hours apart, with their own authors
small = sorted({b["bidder"] for b in bids if b["bidder"] not in STORY_COS and companies[b["bidder"]]["weight"] < 1.5})
kiosk_firms = sorted(rng.sample(small, 14))
for cid in kiosk_firms:
    own = [b for b in bids if b["bidder"] == cid]
    for b in rng.sample(own, min(len(own), rng.randint(1, 3))):
        b["ip"], b["author"] = KIOSK_IP, pick(companies[cid]["authors"])

tenders.sort(key=lambda t: (t["published"], t["cat"], t["estimate"]))
for i, t in enumerate(tenders):
    t["id"] = f"T-{i + 1:05d}"
bids.sort(key=lambda b: (b["at"], b["bidder"]))
for i, b in enumerate(bids):
    b["id"] = f"B-{i + 1:06d}"

# ---- contract changes -----------------------------------------------------------------------------------------------------------
changes = []                                                     # [tender, value, official, day]
unit_officials = {u: sorted(p for p, x in officials.items() if x == u and p not in (O, O2)) for u in UNITS}


def change(t, official, lo, hi, after=(20, 240)):
    d = t["awarded"] + timedelta(days=rng.randint(*after))
    if d <= END:
        changes.append([t, round(t["value"] * rng.uniform(lo, hi), 2), official, d])


bg_pairs = {}
for t in tenders:
    if t in f_tenders or id(t) in {id(x) for x in rotation + covers_t} or t["winner"] in (D_OLD, D_NEW):
        continue
    if rng.random() < 0.62:
        for _ in range(rng.choice([1, 1, 1, 2, 2, 3])):
            free = [o for o in unit_officials[t["unit"]] if bg_pairs.get((o, t["winner"]), 0) < 3]
            o = pick(free)
            bg_pairs[(o, t["winner"])] = bg_pairs.get((o, t["winner"]), 0) + 1
            change(t, o, 0.02, 0.15)
facilities = [t for t in tenders if t["unit"] == "Facilities" and t["winner"] not in STORY_COS and t["awarded"] <= END - timedelta(days=120)]
o2_firms = {}
for t in rng.sample(facilities, len(facilities)):
    if sum(o2_firms.values()) == 34:
        break
    if o2_firms.get(t["winner"], 0) == 2:
        continue
    o2_firms[t["winner"]] = o2_firms.get(t["winner"], 0) + 1  # the look-alike: one busy contracts manager
    change(t, O2, 0.02, 0.12, after=(10, 120))
for k in range(9):                                               # the conflict: nine changes for the relative's firm
    change(f_tenders[k % 6], O, 0.08, 0.25, after=(20 + k * 12, 40 + k * 12))
for t in rng.sample([x for x in tenders if x["unit"] == "Parks" and x["winner"] != F], 3):
    change(t, O, 0.02, 0.10)
changes.sort(key=lambda c: (c[3], c[0]["id"]))

# ---- invariants -------------------------------------------------------------------------------------------------------------------
landed_t = [t for t in tenders if t["published"] <= LANDED_END]
for i, t in enumerate(sorted(rotation, key=lambda t: t["published"])):
    tb = sorted((b for b in bids if b["tender"] is t), key=lambda b: b["amount"])
    assert [b["bidder"] for b in tb][0] == R[i % 4] and {b["bidder"] for b in tb} == set(R), "rotation in turn, only the four bid"
    assert all(1.05 <= b["amount"] / tb[0]["amount"] <= 1.12 for b in tb[1:]), "losing bids 5-12 % above"
    assert len({b["amount"] for b in tb}) == 4, "never identical"
pair = {}
for t, v, o, d in changes:
    pair[(o, t["winner"])] = pair.get((o, t["winner"]), 0) + 1
assert pair[(O, F)] == 9, pair[(O, F)]
assert max(n for (o, c), n in pair.items() if (o, c) != (O, F)) <= 3, "no other official approves many changes for one firm"
assert sum(1 for t, v, o, d in changes if o == O2) == 34 and max(n for (o, c), n in pair.items() if o == O2) <= 2
assert all(not (t["winner"] == F and o == O2) for t, v, o, d in changes)
home = {}
for p in people.values():
    home.setdefault(p["address"], set()).add(p["id"])
for addr, who in home.items():
    if who & set(officials) and who & director_people:
        assert who & set(officials) == {O} and who & director_people == {D_REL}, f"only the story official lives with a director ({addr})"
for b in bids:
    deb = companies[b["bidder"]]["debarred"]
    assert deb is None or b["at"].date() < deb, f"{b['bidder']} bids after it was debarred"
    assert companies[b["bidder"]]["inc"] < b["tender"]["published"] or b["bidder"] == D_NEW
assert d_new_win["winner"] == D_NEW and (PHOENIX_AWARD - DEBAR_DAY).days <= 90
assert all(t["awarded"] <= END for t in rotation + covers_t + f_tenders + d_old_t + [d_new_win]), "every story tender is decided by the end of the staged month"
assert all(t["winner"] == D_OLD for t in d_old_t) and all(t["winner"] == W for t in covers_t) and all(t["winner"] == F for t in f_tenders)
assert not {b["bidder"] for b in bids if b["tender"] in rotation} & set(L), "the big road firms never bid on the rotation tenders"
assert not {b["bidder"] for b in bids if b["ip"] == KIOSK_IP} & STORY_COS
kiosk_bids = [b for b in bids if b["ip"] == KIOSK_IP]
assert all(abs((x["at"] - y["at"]).total_seconds()) >= 3600 for x in kiosk_bids for y in kiosk_bids
           if x is not y and x["tender"] is y["tender"]), "kiosk bids on one tender are hours apart"
south_wins = sum(1 for t in south if t["winner"] in L) / len(south)
assert south_wins >= 0.5, f"the big three win most Highways South tenders ({south_wins:.0%})"
ip_users, au_users = {}, {}
for b in bids:
    if b["ip"]:
        ip_users.setdefault(b["ip"], set()).add(b["bidder"])
    if b["author"] and b["author"] != PLACEHOLDER_AUTHOR:
        au_users.setdefault(b["author"], set()).add(b["bidder"])
shared_ips = {ip: u for ip, u in ip_users.items() if len(u) > 1}
shared_authors = {a: u for a, u in au_users.items() if len(u) > 1}
assert set(shared_ips) == {W_IP, KIOSK_IP}, shared_ips
assert set(shared_authors) == {W_AUTHOR}, shared_authors

# ---- write ------------------------------------------------------------------------------------------------------------------------


def write(rel, header, rows):
    f = DATA / rel
    f.parent.mkdir(parents=True, exist_ok=True)
    with out.open(f) as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)


def iso(d):
    return d.isoformat() if d else ""


out = Outputs()
write("inbox/company/COMPANY_20260930.csv", ["company_id", "name", "reg_address", "phone", "bank_account", "incorporated_at", "debarred_at"],
      [[c["id"], c["name"], c["address"], c["phone"], c["bank"], iso(c["inc"]), iso(c["debarred"])] for c in companies.values()])
write("inbox/director/DIRECTOR_20260930.csv", ["company_id", "person_id", "from_date", "to_date"],
      sorted([[c, p, iso(f), iso(t)] for c, p, f, t in directors]))
write("inbox/procurement_person/PERSON_20260930.csv", ["person_id", "name", "address_id"],
      [[p["id"], p["name"], p["address"]] for p in sorted(people.values(), key=lambda p: p["id"])])
write("inbox/official/OFFICIAL_20260930.csv", ["person_id", "buyer_unit"], sorted([[p, u] for p, u in officials.items()]))
for y, m in months:
    ym = f"{y}{m:02d}"
    box = "staged" if ym > LANDED_END.strftime("%Y%m") else "inbox"
    mt = [t for t in tenders if t["published"].strftime("%Y%m") == ym]
    write(f"{box}/tender/TENDER_{ym}.csv", ["tender_id", "buyer_unit", "category", "estimate", "published_at", "awarded_at", "awarded_to", "award_value"],
          [[t["id"], t["unit"], t["cat"], f"{t['estimate']:.2f}", iso(t["published"])]
           + ([iso(t["awarded"]), t["winner"], f"{t['value']:.2f}"] if t["awarded"] <= END else ["", "", ""]) for t in mt])
    write(f"{box}/bid/BID_{ym}.csv", ["bid_id", "tender_id", "bidder_id", "amount", "submitted_at", "ip", "doc_author"],
          [[b["id"], b["tender"]["id"], b["bidder"], f"{b['amount']:.2f}", ts(b["at"]), b["ip"], b["author"]]
           for b in bids if b["tender"]["published"].strftime("%Y%m") == ym])
CC_HEAD = ["change_id", "tender_id", "change_value", "approved_by", "approved_at"]
cc_rows = [[f"CC-{i + 1:05d}", t["id"], f"{v:.2f}", o, iso(d)] for i, (t, v, o, d) in enumerate(changes)]
write("inbox/contract_change/CONTRACT_CHANGE_20260930.csv", CC_HEAD, [r for r in cc_rows if r[4] <= LANDED_END.isoformat()])
write("staged/contract_change/CONTRACT_CHANGE_20261031.csv", CC_HEAD, [r for r in cc_rows if r[4] > LANDED_END.isoformat()])

rot_sorted = sorted(rotation, key=lambda t: t["published"])
truth = {
    "seed": 20261108,
    "window": {"landed": [START.isoformat(), LANDED_END.isoformat()], "staged": [(LANDED_END + timedelta(days=1)).isoformat(), END.isoformat()]},
    "counts": {"company": len(companies), "person": len(people), "official": len(officials), "director": len(directors),
               "tender_landed": len(landed_t), "bid_landed": sum(1 for b in bids if b["tender"]["published"] <= LANDED_END),
               "contract_change": len(changes)},
    "stories": {
        "bid_rotation": {"hub": R[0], "view": "bid_rotation", "entities": sorted(R + [t["id"] for t in rotation] + [P_SHARED]),
                         "lookalikes": sorted(L)},
        "cover_bids": {"hub": W_IP, "view": "cover_bids_shared_ip", "entities": sorted([W, *C, W_IP, W_AUTHOR]), "lookalikes": [KIOSK_IP]},
        "conflict_of_interest": {"hub": F, "view": "official_approving_relative_firm", "entities": sorted([O, F, D_REL, people[O]["address"]]),
                                 "lookalikes": [O2]},
        "phoenix_firm": {"hub": D_NEW, "view": "debarred_firm_reborn", "entities": sorted([D_OLD, D_NEW, P1, P2, d_new_win["id"]]),
                         "lookalikes": sorted([U, PX])},
    },
    "bid_rotation": {"firms": R, "shared_director": P_SHARED, "shared_by": [R[1], R[3]], "unit": "Highways North",
                     "tenders_in_order": [t["id"] for t in rot_sorted], "winners_in_order": [t["winner"] for t in rot_sorted],
                     "lookalike_firms": L, "lookalike_unit": "Highways South", "lookalike_win_share": round(south_wins, 3)},
    "cover_bids": {"winner": W, "covers": C, "winner_ip": W_IP, "winner_author": W_AUTHOR, "tenders": [t["id"] for t in covers_t],
                   "cover_bids": len(cover_rows), "from_winner_ip": len(ip_rows), "with_winner_author": len(au_rows),
                   "kiosk_ip": KIOSK_IP, "kiosk_firms": kiosk_firms},
    "conflict_of_interest": {"official": O, "official_unit": "Parks", "firm": F, "director": D_REL, "home": people[O]["address"],
                             "tenders": [t["id"] for t in f_tenders], "changes": 9,
                             "lookalike_official": O2, "lookalike_unit": "Facilities", "lookalike_changes": 34},
    "phoenix_firm": {"old": D_OLD, "debarred_at": DEBAR_DAY.isoformat(), "new": D_NEW, "incorporated_at": NEW_FIRM_DAY.isoformat(),
                     "directors": [P1, P2], "new_director": P3, "won": d_new_win["id"], "awarded_at": PHOENIX_AWARD.isoformat(),
                     "lookalike_old": X_OLD, "lookalike_director": PX, "lookalike_new_home": U,
                     "background_debarred": sorted(background_debarred)},
}
out.write_text(TRUTH, json.dumps(truth, indent=1, sort_keys=True) + "\n")
out.prune(DATA, "*/*/*.csv")
print(f"companies {len(companies)}, people {len(people)}, directors {len(directors)}, tenders {len(landed_t)} landed of {len(tenders)}, "
      f"bids {truth['counts']['bid_landed']} landed of {len(bids)}, changes {len(changes)}, big three win {south_wins:.0%} of Highways South, "
      f"covers {len(cover_rows)} ({len(ip_rows)} from W's IP, {len(au_rows)} W's author)")
