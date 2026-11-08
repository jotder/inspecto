#!/usr/bin/env python3
"""Regenerate the `la-health` Space Template's provider billing fraud corpus - SYNTHETIC, deterministic (one seeded Random).

Writes spaces/_templates/la-health/data/inbox/<feed>/ (12 months landed, 2025-10-01..2026-09-30) and data/staged/<feed>/ (the
next month, 2026-10, for the "new data arrives" step) for nine feeds:
  address, owner, provider, provider_owner, member (one extract each as of 2026-09-30, landed only), claim_line (by service
  month), admission (by admission month), referral (by referral month), prescription (by fill month)
and tools/gen-la-health-data.ground-truth.json (story -> entity ids and look-alike ids; NOT shipped in the data download).

Planted stories (plan: docs/superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md, spec 9; pinned by
tools/gen-la-health-data.test.mjs over the files and HealthLinksJobGoldenTest over the link Datasets):
  1 REFERRAL KICKBACKS  2 York general practices send 85 % of their referrals to one imaging centre; the centre's owner lives
                        at the same home as the owner of one of the practices (spouses)
                        look-alike: a Scarborough practice that sends 85 % of its referrals to the only hospital nearby
                        (owned by a public trust), like every other practice in that town
  2 PHANTOM BILLING     a Hull physiotherapy clinic bills 40 of its patients for sessions on dates they were in hospital
                        elsewhere (28) or after they died (12); none of it is reversed
                        look-alike: a Leeds physiotherapy clinic whose 15 session lines dated inside a hospital stay are
                        reversed within a week and re-billed with the right date (as are about 15 single errors elsewhere)
  3 PILL MILL           one Sheffield pain clinic prescribes strong opioids in large quantities to 70 patients who live over
                        50 km away and mostly pay cash, filled at 2 Sheffield pharmacies
                        look-alike: a Leeds oncology centre with as many strong-opioid patients, all local and insured,
                        filling at their own local pharmacies
  4 UPCODING            3 urgent-care clinics in 3 cities under one owner bill the highest visit level about 4x as often as
                        clinics with the same patient ages
                        look-alike: a Lincoln elderly-care clinic with as high a top-level share, because its patients are
                        all over 80 (the same as its peers with that patient mix)
Scale: 20 000 members, 1 500 providers, about 90 000 claim lines, 3 000 admissions, 15 000 referrals, 30 000 prescriptions.
Names, practice names, streets and SYN- bank accounts are invented; procedure codes are invented (not any real code set);
cities are real only at city level and coordinates are random points around a city centre. Nothing derives from a real source.
"""
from __future__ import annotations

import csv
import json
import math
import random
from datetime import date, timedelta
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
DATA = TOOLS.parent / "spaces" / "_templates" / "la-health" / "data"
TRUTH = TOOLS / "gen-la-health-data.ground-truth.json"
SEED = 20261110
rng = random.Random(SEED)
START, LANDED_END, END = date(2025, 10, 1), date(2026, 9, 30), date(2026, 10, 31)
MONTHS = [date(2025 + (9 + i) // 12, (9 + i) % 12 + 1, 1) for i in range(13)]   # 2025-10 .. 2026-10
N_MEMBERS = 20000
AS_OF_YEAR = 2026

CITIES = {"Leeds": (53.8008, -1.5491, 31), "Sheffield": (53.3811, -1.4701, 26), "York": (53.9600, -1.0873, 14),
          "Hull": (53.7676, -0.3274, 15), "Lincoln": (53.2307, -0.5406, 10), "Scarborough": (54.2797, -0.4044, 4)}
SPECIALTIES = {"General practice": 380, "Urgent care": 60, "Geriatric medicine": 14, "Internal medicine": 90, "Cardiology": 50,
               "Orthopaedics": 50, "Dermatology": 50, "Psychiatry": 50, "Oncology": 20, "Pain medicine": 20, "Physiotherapy": 120,
               "Imaging": 40, "Laboratory": 30, "Pharmacy": 300, "Podiatry": 70, "Ophthalmology": 70, "Counselling": 74}
SCARBOROUGH_OK = {"General practice", "Pharmacy", "Physiotherapy", "Podiatry"}
HOSPITALS = {"Leeds": 3, "Sheffield": 3, "York": 2, "Hull": 2, "Lincoln": 1, "Scarborough": 1}
SUFFIX = {"General practice": ["Surgery", "Medical Practice", "Health Centre"], "Urgent care": ["Walk-in Clinic"],
          "Geriatric medicine": ["Elderly Care Clinic"], "Internal medicine": ["Medical Clinic"], "Cardiology": ["Heart Clinic"],
          "Orthopaedics": ["Orthopaedic Clinic"], "Dermatology": ["Skin Clinic"], "Psychiatry": ["Psychiatry Practice"],
          "Oncology": ["Oncology Centre"], "Pain medicine": ["Pain Clinic"], "Physiotherapy": ["Physiotherapy"],
          "Imaging": ["Imaging Centre"], "Laboratory": ["Laboratory"], "Pharmacy": ["Pharmacy", "Chemist"],
          "Podiatry": ["Foot Clinic"], "Ophthalmology": ["Eye Clinic"], "Counselling": ["Counselling Service"], "Hospital": ["Hospital"]}
W1 = ["Amber", "Bracken", "Chalk", "Dove", "Ember", "Flint", "Gorse", "Hazel", "Iron", "Jasper", "Kiln", "Larch", "Mill", "Oak",
      "Pippin", "Quarry", "Rowan", "Sorrel", "Teasel", "Willow", "Alder", "Brook", "Cobble", "Dunnock", "Elder", "Fern", "Lark",
      "Moss", "Nettle", "Plover", "Rush", "Sedge", "Thrush", "Vetch", "Wagtail", "Yew"]
W2 = ["Grove", "Lane", "Park", "House", "Court", "Road", "Green", "Hill", "Mews", "Row", "Field", "Bank", "Gate", "Square"]
SUF = ["Lane", "Road", "Close", "Street", "Avenue", "Grove", "Way", "Terrace"]
FIRST = ["Ada", "Basil", "Cora", "Dev", "Edie", "Flynn", "Gwen", "Hugo", "Isla", "Jude", "Kira", "Lars", "Maeve", "Nico", "Opal",
         "Piers", "Rhea", "Sami", "Tess", "Ugo", "Vida", "Wade", "Xena", "Yusuf", "Zara", "Arlo", "Bea", "Cyrus", "Dina", "Eben",
         "Fenna", "Gil", "Hana", "Ivo", "Juno", "Kofi", "Lumi", "Milo", "Nell", "Otis", "Pria", "Quin", "Ravi", "Sela", "Tobin"]
LAST = ["Abbott", "Bramley", "Calloway", "Denholm", "Ellery", "Fairbairn", "Gilchrist", "Holloway", "Ingram", "Jessop", "Kinsella",
        "Lindqvist", "McAllister", "Nightingale", "Ormsby", "Prentice", "Quarry", "Rowntree", "Stanhope", "Treadwell", "Upton",
        "Varga", "Wetherby", "Yelland", "Zamora", "Ashby", "Blackwood", "Crowther", "Dunbar", "Everley", "Fenwick", "Garside",
        "Hartigan", "Ivesley", "Jowett", "Kettering", "Loxley", "Marchbank", "Northcott", "Oddie", "Pemberton", "Radcliffe"]
TRUSTS = {"Harrowfell Hospitals Trust": ["Leeds", "Sheffield"], "Saltmere Health Trust": ["York", "Scarborough"],
          "Tarnley Health Trust": ["Hull", "Lincoln"]}
CHAINS = ["Vellmoor Pharmacy Group", "Pellbrook Chemists Ltd"]
LABS = "Corrandale Diagnostics Ltd"
R5 = {"a": 0.03, "b": 0.06, "c": 0.11, "d": 0.24}       # share of office visits billed at the top level (5), by age band
LOWER = [0.08, 0.32, 0.42, 0.18]                          # levels 1-4, scaled to the rest
DRUGS = ["Statin", "Antihypertensive", "Antidepressant", "Inhaler", "Insulin", "Antibiotic", "Anticoagulant", "Weak opioid"]


def rand_date(a: date, b: date) -> date:
    return a + timedelta(days=rng.randint(0, (b - a).days))


def weekday(d: date) -> date:
    while d.weekday() >= 5:
        d += timedelta(days=1)
    return d


def month_start(d: date) -> date:
    return date(d.year, d.month, 1)


def band(m) -> str:
    age = AS_OF_YEAR - m["by"]
    return "a" if age < 40 else "b" if age < 65 else "c" if age < 80 else "d"


def km(a, b) -> float:
    la1, lo1, la2, lo2 = map(math.radians, (float(a["lat"]), float(a["lon"]), float(b["lat"]), float(b["lon"])))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6371 * math.asin(math.sqrt(h))


# ---- phase 1: addresses, providers, owners, members (ids assigned by permutation at the end of the phase) ---------------------
addresses = []
seen_streets = set()


def new_address(city: str, may_blank: bool = True) -> dict:
    while True:
        street = f"{rng.randint(1, 220)} {rng.choice(W1)} {rng.choice(SUF)}"
        if (street, city) not in seen_streets:
            seen_streets.add((street, city))
            break
    lat, lon, _ = CITIES[city]
    blank = may_blank and rng.random() < 0.03
    a = {"street": street, "city": city, "lat": "" if blank else f"{lat + rng.uniform(-0.05, 0.05):.5f}",
         "lon": "" if blank else f"{lon + rng.uniform(-0.08, 0.08):.5f}"}
    addresses.append(a)
    return a


accounts_used: set[str] = set()


def new_account() -> str:
    while True:
        a = f"SYN-{rng.randint(10_000_000, 99_999_999)}"
        if a not in accounts_used:
            accounts_used.add(a)
            return a


providers = []
names_used = set()


def new_provider(spec: str, city: str) -> dict:
    while True:
        name = f"{rng.choice(W1)} {rng.choice(W2)} {rng.choice(SUFFIX[spec])}"
        if name not in names_used:
            names_used.add(name)
            break
    addr = new_address(city)
    while not addr["lat"]:
        addr = new_address(city)
    p = {"spec": spec, "city": city, "addr": addr, "name": name, "acct": "" if rng.random() < 0.04 else new_account(), "owners": []}
    providers.append(p)
    return p


city_names = list(CITIES)
for spec, n in SPECIALTIES.items():
    cities = [c for c in city_names if c != "Scarborough" or spec in SCARBOROUGH_OK]
    weights = [CITIES[c][2] for c in cities]
    for c in rng.choices(cities, weights=weights, k=n):
        new_provider(spec, c)
for city, n in HOSPITALS.items():
    for _ in range(n):
        new_provider("Hospital", city)
assert len(providers) == 1500, len(providers)
by_spec_city: dict[tuple, list] = {}
for p in providers:
    by_spec_city.setdefault((p["spec"], p["city"]), []).append(p)


def pick(spec, city):
    return by_spec_city.get((spec, city), [])


# the story providers
GP1, GP2 = pick("General practice", "York")[:2]
IMG = pick("Imaging", "York")[0]
CLP = pick("Physiotherapy", "Hull")[0]
CLL = pick("Physiotherapy", "Leeds")[0]
MILL = pick("Pain medicine", "Sheffield")[0]
PH1, PH2 = pick("Pharmacy", "Sheffield")[:2]
ONC = pick("Oncology", "Leeds")[0]
U1, U2, U3 = pick("Urgent care", "Hull")[0], pick("Urgent care", "Lincoln")[0], pick("Urgent care", "York")[0]
GERL = pick("Geriatric medicine", "Lincoln")[0]
HOSPS = pick("Hospital", "Scarborough")[0]
GPR = pick("General practice", "Scarborough")[0]
STORY_PROVIDERS = [GP1, GP2, IMG, CLP, CLL, MILL, PH1, PH2, ONC, U1, U2, U3, GERL, HOSPS, GPR]
for p, n in [(GP1, "Brackenmoor Surgery"), (GP2, "Kilnworth Medical Practice"), (IMG, "Emberlea Imaging Centre"),
             (CLP, "Sedgefold Physiotherapy"), (CLL, "Willowmere Physiotherapy"), (MILL, "Flintcross Pain Clinic"),
             (ONC, "Rowanbrook Oncology Centre"), (GERL, "Aldermoor Elderly Care Clinic"), (HOSPS, "Saltmere Bay Hospital")]:
    names_used.discard(p["name"])
    assert n not in names_used, n
    names_used.add(n)
    p["name"] = n

owners = []


def new_owner(kind: str, city: str, name: str | None = None, home: dict | None = None) -> dict:
    o = {"kind": kind, "name": name or f"{rng.choice(FIRST)} {rng.choice(LAST)}", "city": city,
         "addr": home or new_address(city, may_blank=False), "owns": []}
    owners.append(o)
    return o


def own(p, o):
    p["owners"].append(o)
    o["owns"].append(p)


trusts = {t: new_owner("organisation", cities[0], t) for t, cities in TRUSTS.items()}
chains = [new_owner("organisation", "Leeds", c) for c in CHAINS]
labs = new_owner("organisation", "Sheffield", LABS)
D1 = new_owner("person", "York")
S1 = new_owner("person", "York", home=D1["addr"])                       # D1's spouse: one home
D2 = new_owner("person", "York")
OWU = new_owner("person", "Hull")
OWL = new_owner("person", "Lincoln")
for p, o in [(GP1, D1), (GP2, D2), (IMG, S1), (U1, OWU), (U2, OWU), (U3, OWU), (GERL, OWL)]:
    own(p, o)
for p in [CLP, CLL, MILL, PH1, PH2, ONC, GPR]:
    own(p, new_owner("person", p["city"]))
reserved = {id(o) for o in owners}
by_city_owners: dict[str, list] = {}
for p in providers:
    if p["owners"]:
        continue
    if p["spec"] == "Hospital":
        own(p, next(t for t, cs in zip(trusts.values(), TRUSTS.values()) if p["city"] in cs))
    elif p["spec"] == "Laboratory":
        own(p, labs)
    elif p["spec"] == "Pharmacy" and rng.random() < 0.45:
        own(p, chains[0] if rng.random() < 0.55 else chains[1])
    else:
        pool = by_city_owners.setdefault(p["city"], [])
        if pool and rng.random() < 0.08:
            own(p, rng.choice(pool))                                      # a small chain under one person
        else:
            o = new_owner("person", p["city"])
            pool.append(o)
            own(p, o)
        if p["spec"] not in ("Pharmacy", "Imaging") and rng.random() < 0.07:
            o2 = new_owner("person", p["city"])                           # a partner
            pool.append(o2)
            own(p, o2)
# 40 background couples: two owners of practices in one city share a home
coupled: set[int] = set()
while len(coupled) < 80:
    city = rng.choice([c for c in city_names if c != "Scarborough"])
    a, b = rng.sample(by_city_owners[city], 2)
    if id(a) not in coupled and id(b) not in coupled:
        coupled.update((id(a), id(b)))
        addresses.remove(b["addr"])
        b["addr"] = a["addr"]

members = []
AGES = [(0, 17, 20), (18, 39, 28), (40, 64, 32), (65, 79, 14), (80, 97, 6)]
while len(members) < N_MEMBERS:
    city = rng.choices(city_names, weights=[CITIES[c][2] for c in city_names])[0]
    addr = new_address(city)
    for _ in range(min(rng.choices([1, 2, 3, 4], weights=[40, 33, 14, 13])[0], N_MEMBERS - len(members))):
        lo, hi, _w = rng.choices(AGES, weights=[w for _, _, w in AGES])[0]
        age = rng.randint(lo, hi)
        members.append({"by": AS_OF_YEAR - age, "addr": addr, "city": city, "died": None, "gp": None, "story": None})
for m in members:
    age = AS_OF_YEAR - m["by"]
    p = 0.08 if age >= 80 else 0.02 if age >= 65 else 0.002
    if rng.random() < p:
        m["died"] = rand_date(START + timedelta(days=20), LANDED_END)

# GP registration: list sizes heavy-tailed; the two York practices are large
for city in city_names:
    gps = pick("General practice", city)
    w = [rng.lognormvariate(0, 0.6) * (5 if g in (GP1, GP2) else 1) for g in gps]
    for m in members:
        if m["city"] == city and rng.random() < 0.92:
            m["gp"] = rng.choices(gps, weights=w)[0]


def assign_ids(items, prefix, width):
    order = list(range(len(items)))
    rng.shuffle(order)
    for n, i in enumerate(order, 1):
        items[i]["id"] = f"{prefix}-{n:0{width}d}"


assign_ids(addresses, "AD", 5)
assign_ids(providers, "PR", 4)
assign_ids(owners, "OW", 4)
assign_ids(members, "M", 5)

# ---- phase 2: the background ----------------------------------------------------------------------------------------------------
lines = []          # {p, m, d, pc, lvl, billed, paid, rcv, rev(line|None), story}
referrals = []      # (from, to, member, date)
admissions = []     # {m, h, fd, td}
scripts = []        # {pr, ph, m, cls, qty, cash, d}
DEST_SPECS = ["Internal medicine", "Cardiology", "Orthopaedics", "Dermatology", "Psychiatry", "Oncology", "Pain medicine",
              "Physiotherapy", "Imaging", "Podiatry", "Ophthalmology", "Counselling", "Hospital"]
DEST_WEIGHT = {"Imaging": 3.0, "Physiotherapy": 2.0, "Hospital": 2.0}


def received(d: date) -> date:
    return d + timedelta(days=rng.choice([2, 3, 3, 4, 5, 5, 6, 7, 8, 9, 10, 12, 14, 20]))


def add_line(p, m, d, pc, lvl=None, billed=None, story=None):
    if billed is None:
        billed = {"OFV": 40 + 30 * (lvl or 3), "SPV": 140, "PHY": 55, "LAB": 35, "IMG-XR": 80, "IMG-CT": 310, "IMG-MR": 520}[pc]
        billed = round(billed * rng.uniform(0.92, 1.08), 2)
    ln = {"p": p, "m": m, "d": d, "pc": pc, "lvl": lvl, "billed": billed, "paid": round(billed * rng.uniform(0.78, 0.9), 2),
          "rcv": received(d), "rev": None, "story": story}
    lines.append(ln)
    return ln


def level(m, boost=1.0):
    r5 = min(0.6, R5[band(m)] * boost)
    if rng.random() < r5:
        return 5
    return rng.choices([1, 2, 3, 4], weights=LOWER)[0]


def dest_lines(dest, m, d):
    d0 = weekday(d + timedelta(days=rng.randint(3, 30)))
    if dest["spec"] == "Imaging":
        add_line(dest, m, d0, rng.choices(["IMG-XR", "IMG-CT", "IMG-MR"], weights=[60, 25, 15])[0])
    elif dest["spec"] == "Physiotherapy":
        for k in range(rng.randint(2, 6)):
            add_line(dest, m, weekday(d0 + timedelta(days=7 * k)), "PHY")
    else:
        for k in range(rng.randint(1, 3)):
            add_line(dest, m, weekday(d0 + timedelta(days=21 * k)), "SPV")


dests_by_city = {c: [p for s in DEST_SPECS for p in by_spec_city.get((s, c), []) if p is not MILL] for c in city_names}
gp_pref = {}
for g in (p for p in providers if p["spec"] == "General practice"):
    home = g["city"] if g["city"] != "Scarborough" else "York"
    opts = [d for d in dests_by_city[home] if not (g["city"] == "Scarborough" and d is IMG)]
    opts = rng.sample(opts, min(20, len(opts)))                          # a practice refers to the providers it knows
    w = [DEST_WEIGHT.get(d["spec"], 1.0) * rng.lognormvariate(0, 1.0) for d in opts]
    w = [min(x, 0.2 * sum(w)) for x in w]                                # no background practice favours one provider much
    gp_pref[id(g)] = (opts, w)
scarborough_share = {id(g): (0.85 if g is GPR else rng.uniform(0.7, 0.9)) for g in pick("General practice", "Scarborough")}


quota = {id(GP1): [0, 0], id(GP2): [0, 0], id(GPR): [0, 0]}     # referrals so far, of them to the favourite: holds 85 % exactly


def refer(g, m, d):
    opts, w = gp_pref[id(g)]
    q = quota.get(id(g))
    if q:
        q[0] += 1
        fav = q[1] < 0.85 * q[0]
        q[1] += fav
    if g in (GP1, GP2) and fav:
        dest = IMG
    elif g is GPR and fav:
        dest = HOSPS
    elif g is not GPR and id(g) in scarborough_share and rng.random() < scarborough_share[id(g)]:
        dest = HOSPS
    else:
        while True:
            dest = rng.choices(opts, weights=w)[0]
            if not (g in (GP1, GP2) and dest is IMG):
                break
    referrals.append((g, dest, m, d))
    dest_lines(dest, m, d)


VISITS = {"a": 1.4, "b": 1.9, "c": 3.5, "d": 4.5}
for m in members:
    if m["gp"]:
        n = sum(1 for _ in range(12) if rng.random() < (VISITS[band(m)] if AS_OF_YEAR - m["by"] >= 18 else 1.5) / 12 * 1.08)
        for _ in range(n):
            d = weekday(rand_date(START, END - timedelta(days=2)))
            add_line(m["gp"], m, d, "OFV", level(m))
            if rng.random() < 0.06:
                add_line(rng.choice(pick("Laboratory", m["city"]) or pick("Laboratory", "York")), m, d, "LAB")
            if rng.random() < (0.5 if m["gp"] in (GP1, GP2) else 0.38):
                refer(m["gp"], m, d)
uc_pref = {c: [(u, rng.lognormvariate(0, 0.5) * (8 if u in (U1, U2, U3) else 1)) for u in by_spec_city.get(("Urgent care", c), [])]
           for c in city_names}
for m in members:
    if uc_pref[m["city"]] and rng.random() < 0.18:
        us = uc_pref[m["city"]]
        u = rng.choices([x for x, _ in us], weights=[w for _, w in us])[0]
        for _ in range(rng.choice([1, 1, 2])):
            add_line(u, m, rand_date(START, END - timedelta(days=1)), "OFV", level(m, 4.5 if u in (U1, U2, U3) else 1.0))
for m in members:
    gers = by_spec_city.get(("Geriatric medicine", m["city"]), [])
    if gers and band(m) == "d" and rng.random() < 0.55:
        g = rng.choices(gers, weights=[4 if x is GERL else 1 for x in gers])[0]
        for _ in range(rng.randint(3, 6) if g is GERL else rng.randint(2, 5)):
            add_line(g, m, weekday(rand_date(START, END - timedelta(days=2))), "OFV", level(m, 1.15 if g is GERL else 1.0))

# admissions
busy: dict[int, list] = {}


def free(m, fd, td):
    return all(td < a["fd"] or fd > a["td"] for a in busy.get(id(m), []))


def admit(m, h, fd, td, story=None):
    a = {"m": m, "h": h, "fd": fd, "td": td, "story": story}
    admissions.append(a)
    busy.setdefault(id(m), []).append({"fd": fd - timedelta(days=1), "td": td + timedelta(days=1)})
    add_line(h, m, fd, "INP", billed=round(1200 + 600 * (td - fd).days * rng.uniform(0.85, 1.15), 2), story=story)
    return a


ADM_W = {"a": 0.6, "b": 1.2, "c": 3.0, "d": 6.0}
candidates = [m for m in members]
wts = [ADM_W[band(m)] for m in candidates]
while len(admissions) < 2945:
    m = rng.choices(candidates, weights=wts)[0]
    city = m["city"] if rng.random() < 0.95 else "York"
    h = rng.choice(pick("Hospital", city))
    length = min(21, 1 + int(rng.expovariate(1 / 3.5)))
    fd = rand_date(START, END - timedelta(days=length))
    td = fd + timedelta(days=length)
    if m["died"] and td > m["died"]:
        continue
    if free(m, fd, td):
        admit(m, h, fd, td)

# prescriptions: chronic patients of their own practice, at one or two local pharmacies
pharm_by_city = {c: pick("Pharmacy", c) for c in city_names}
opioid_patients: dict[int, set] = {}
for m in members:
    if not m["gp"] or rng.random() > {"a": 0.09, "b": 0.19, "c": 0.38, "d": 0.45}[band(m)]:
        continue
    phs = rng.sample(pharm_by_city[m["city"]], rng.choice([1, 1, 2]))
    for cls in rng.sample(DRUGS, rng.choice([1, 1, 2])):
        d = rand_date(START, START + timedelta(days=60))
        while d <= END:
            scripts.append({"pr": m["gp"], "ph": rng.choice(phs), "m": m, "cls": cls, "qty": rng.choice([28, 28, 56, 84]),
                            "cash": "Y" if rng.random() < 0.03 else "N", "d": d})
            d += timedelta(days=rng.randint(30, 92))
STRONG = "Strong opioid"
opioid_prescribers = [p for p in providers if p["spec"] in ("Pain medicine", "General practice") and p not in (MILL, GP1, GP2)]
members_by_city = {c: [m for m in members if m["city"] == c] for c in city_names}
for pr in opioid_prescribers:
    local = members_by_city[pr["city"]]
    n = rng.randint(5, 25) if pr["spec"] == "Pain medicine" else (rng.randint(0, 6) if rng.random() < 0.4 else 0)
    for m in rng.sample(local, min(n, len(local))):
        ph = rng.choice(pharm_by_city[m["city"]])
        d = rand_date(START, END - timedelta(days=30))
        for _ in range(rng.randint(1, 4)):
            scripts.append({"pr": pr, "ph": ph, "m": m, "cls": STRONG, "qty": rng.choice([28, 56, 60]), "cash": "N", "d": d})
            d += timedelta(days=rng.randint(26, 40))
            if d > END:
                break

# ---- phase 3: the stories -------------------------------------------------------------------------------------------------------
taken: set[int] = set()


def eligible(m, city=None, lo=18, hi=97, alive=True):
    age = AS_OF_YEAR - m["by"]
    return (id(m) not in taken and (city is None or m["city"] == city) and lo <= age <= hi and (not alive or m["died"] is None)
            and m["addr"]["lat"] != "")


def choose(n, **kw):
    pool = [m for m in members if eligible(m, **kw)]
    out = rng.sample(pool, n)
    taken.update(id(m) for m in out)
    return out


def scrub(m, cut: date):
    """Drop everything dated after cut for member m (a story death)."""
    lines[:] = [ln for ln in lines if not (ln["m"] is m and ln["d"] > cut)]
    referrals[:] = [r for r in referrals if not (r[2] is m and r[3] > cut)]
    scripts[:] = [s for s in scripts if not (s["m"] is m and s["d"] > cut)]
    admissions[:] = [a for a in admissions if not (a["m"] is m and a["td"] > cut)]
    busy[id(m)] = [b for b in busy.get(id(m), []) if b["td"] <= cut]


def sessions(p, m, first: date, n: int, story):
    d = weekday(first)
    out = []
    for _ in range(n):
        out.append(add_line(p, m, d, "PHY", story=story))
        d = weekday(d + timedelta(days=7))
    return out


# 3 PILL MILL first (its patients come from Hull, York, Lincoln and Scarborough) - and the oncology look-alike
mill_patients = []
while len(mill_patients) < 70:
    m = choose(1, lo=22, hi=60)[0]
    if m["city"] not in ("Hull", "York", "Lincoln", "Scarborough") or km(m["addr"], MILL["addr"]) <= 52:
        taken.discard(id(m))
        continue
    mill_patients.append(m)
for m in mill_patients:
    m["story"] = "pill_mill"
    scripts[:] = [s for s in scripts if not (s["m"] is m and s["cls"] == STRONG)]
    d = weekday(rand_date(START, date(2026, 3, 31)))
    for _ in range(rng.randint(5, 11)):
        if d > LANDED_END:
            break
        scripts.append({"pr": MILL, "ph": PH1 if rng.random() < 0.6 else PH2, "m": m, "cls": STRONG, "qty": rng.choice(range(90, 190, 10)),
                        "cash": "Y" if rng.random() < 0.9 else "N", "d": d})
        add_line(MILL, m, d, "OFV", rng.choice([3, 4]))
        d = weekday(d + timedelta(days=rng.randint(26, 35)))
leeds_pharm = rng.sample(pharm_by_city["Leeds"], 15)
onc_patients = []
while len(onc_patients) < 60:
    m = choose(1, city="Leeds", lo=45, hi=88)[0]
    if km(m["addr"], ONC["addr"]) >= 15:
        taken.discard(id(m))
        continue
    onc_patients.append(m)
for m in onc_patients:
    scripts[:] = [s for s in scripts if not (s["m"] is m and s["cls"] == STRONG)]
    near = sorted(leeds_pharm, key=lambda p: km(m["addr"], p["addr"]))[:2]
    d = weekday(rand_date(START, date(2026, 5, 31)))
    for _ in range(rng.randint(3, 9)):
        if d > LANDED_END:
            break
        scripts.append({"pr": ONC, "ph": rng.choice(near), "m": m, "cls": STRONG, "qty": rng.choice([28, 56, 60, 84, 90]),
                        "cash": "Y" if rng.random() < 0.02 else "N", "d": d})
        add_line(ONC, m, d, "SPV")
        d = weekday(d + timedelta(days=rng.randint(21, 35)))

# 2 PHANTOM BILLING: 40 Hull patients of the physiotherapy clinic
dead_story = choose(12, city="Hull", lo=70, hi=92)
adm_story = choose(28, city="Hull", lo=25, hi=85)
hull_hosp = pick("Hospital", "Hull")
for m in dead_story:
    m["story"] = "phantom_billing"
    m["died"] = rand_date(date(2026, 1, 10), date(2026, 5, 20))
    scrub(m, m["died"])
    sessions(CLP, m, m["died"] - timedelta(days=rng.randint(50, 80)), rng.randint(3, 6), None)
    d = weekday(m["died"] + timedelta(days=rng.randint(7, 14)))
    for _ in range(rng.randint(3, 8)):
        if d > date(2026, 9, 15):
            break
        add_line(CLP, m, d, "PHY", story="phantom_billing")
        d = weekday(d + timedelta(days=rng.choice([7, 14])))
for m in adm_story:
    m["story"] = "phantom_billing"
    while True:
        length = rng.randint(6, 14)
        fd = rand_date(date(2026, 1, 5), date(2026, 8, 1))
        td = fd + timedelta(days=length)
        if free(m, fd, td):
            break
    admit(m, rng.choice(hull_hosp), fd, td, story="phantom_billing")
    sessions(CLP, m, fd - timedelta(days=rng.randint(30, 50)), rng.randint(3, 5), None)
    days_in = [fd + timedelta(days=k) for k in range(1, length) if (fd + timedelta(days=k)).weekday() < 5]
    inside = sorted(rng.sample(days_in, k=min(len(days_in), rng.randint(2, 4))))
    for d in inside:
        add_line(CLP, m, d, "PHY", story="phantom_billing")


def corrected_error(p, m, a, story):
    """A line keyed with a date inside m's hospital stay a, reversed within a week, re-billed with the right date."""
    wrong = a["fd"] + timedelta(days=rng.randint(1, (a["td"] - a["fd"]).days - 1))
    bad = add_line(p, m, wrong, "PHY" if p["spec"] == "Physiotherapy" else "SPV", story=story)
    rev = dict(bad, billed=-bad["billed"], paid=-bad["paid"], rcv=bad["rcv"] + timedelta(days=rng.randint(2, 6)), rev=bad)
    lines.append(rev)
    right = weekday(a["td"] + timedelta(days=rng.randint(1, 3)))
    add_line(p, m, right, bad["pc"], story=story)


# the look-alike: 15 Leeds patients of the other physiotherapy clinic, each with one corrected date error
leeds_hosp = pick("Hospital", "Leeds")
clinic_lookalike = choose(15, city="Leeds", lo=25, hi=85)
for m in clinic_lookalike:
    while True:
        length = rng.randint(4, 10)
        fd = rand_date(date(2025, 11, 3), date(2026, 8, 1))
        td = fd + timedelta(days=length)
        if free(m, fd, td):
            break
    a = admit(m, rng.choice(leeds_hosp), fd, td, story="phantom_lookalike")
    sessions(CLL, m, td + timedelta(days=rng.randint(4, 10)), rng.randint(3, 6), None)
    corrected_error(CLL, m, a, "phantom_lookalike")
# about 15 single corrected errors elsewhere
others = [p for p in providers if p["spec"] in ("Physiotherapy", "Internal medicine", "Cardiology", "Orthopaedics", "Podiatry")
          and p not in STORY_PROVIDERS]
bg_errors = []
for p in rng.sample(others, 15):
    adm = [a for a in admissions if a["story"] is None and id(a["m"]) not in taken and a["m"]["city"] == p["city"]
           and (a["td"] - a["fd"]).days >= 2 and a["td"] <= date(2026, 9, 1)]
    a = rng.choice(adm)
    taken.add(id(a["m"]))
    corrected_error(p, a["m"], a, "background_error")
    bg_errors.append((p, a["m"]))

# ---- the background's hygiene: nobody else bills inside a hospital stay or after a death ----------------------------------------
stay = {}
for a in admissions:
    stay.setdefault(id(a["m"]), []).append(a)


def flagged(ln):
    m = ln["m"]
    if m["died"] and ln["d"] > m["died"]:
        return True
    return any(a["fd"] < ln["d"] < a["td"] and a["h"] is not ln["p"] for a in stay.get(id(m), []))


lines[:] = [ln for ln in lines if ln["story"] is not None or not flagged(ln)]
referrals[:] = [r for r in referrals if not (r[2]["died"] and r[3] > r[2]["died"])]
scripts[:] = [s for s in scripts if not (s["m"]["died"] and s["d"] > s["m"]["died"])]
admissions.sort(key=lambda a: (a["fd"], a["m"]["id"]))

# ---- checks: the stories are what the plan says, and nothing else looks like them -----------------------------------------------
landed = [ln for ln in lines if ln["d"] <= LANDED_END]
reversed_ = {id(ln["rev"]) for ln in lines if ln["rev"] is not None}
open_flags = {}
for ln in landed:
    if ln["rev"] is None and id(ln) not in reversed_ and flagged(ln):
        open_flags.setdefault(id(ln["p"]), set()).add(id(ln["m"]))
assert set(open_flags) == {id(CLP)}, "only the physiotherapy clinic has open lines in a stay or after a death"
assert open_flags[id(CLP)] == {id(m) for m in dead_story + adm_story}
assert all(not flagged(ln) or ln["rev"] is not None or id(ln) in reversed_ or ln["p"] is CLP for ln in lines)
assert not any(flagged(ln) for ln in lines if ln["d"] > LANDED_END), "the staged month carries no flagged line"

landed_ref = [r for r in referrals if r[3] <= LANDED_END]
ref_tot, ref_pair = {}, {}
for f, t, m, d in landed_ref:
    ref_tot[id(f)] = ref_tot.get(id(f), 0) + 1
    ref_pair[(id(f), id(t))] = ref_pair.get((id(f), id(t)), 0) + 1
concentrated = {f for (f, t), n in ref_pair.items() if ref_tot[f] >= 20 and n / ref_tot[f] >= 0.6}
scar = {id(g) for g in pick("General practice", "Scarborough") if ref_tot.get(id(g), 0) >= 20}
assert concentrated == {id(GP1), id(GP2)} | scar, [(p["name"], p["city"], ref_tot[id(p)]) for p in providers if id(p) in concentrated ^ ({id(GP1), id(GP2)} | scar)]
assert id(GPR) in scar
for g in (GP1, GP2, GPR):
    share = ref_pair[(id(g), id(IMG if g is not GPR else HOSPS))] / ref_tot[id(g)]
    assert 0.78 <= share <= 0.92, (g["name"], share, ref_tot[id(g)])
assert not any(t is IMG for f, t, m, d in referrals if f["city"] == "Scarborough")

landed_rx = [s for s in scripts if s["d"] <= LANDED_END]
op = {}
for s in landed_rx:
    if s["cls"] == STRONG:
        op.setdefault(id(s["pr"]), set()).add(id(s["m"]))
assert {p for p, ms in op.items() if len(ms) >= 40} == {id(MILL), id(ONC)}, "only the mill and the oncology centre"
assert op[id(MILL)] == {id(m) for m in mill_patients} and op[id(ONC)] == {id(m) for m in onc_patients}
assert not any(s["ph"] in (PH1, PH2) for s in landed_rx if s["m"] in onc_patients)
assert all(s["pr"] is MILL for s in scripts if s["ph"] in (PH1, PH2) and s["cls"] == STRONG and s["m"] in mill_patients)

ov = [ln for ln in landed if ln["pc"] == "OFV" and ln["lvl"]]
band_rate = {}
for b in "abcd":
    xs = [ln for ln in ov if band(ln["m"]) == b]
    band_rate[b] = sum(ln["lvl"] == 5 for ln in xs) / len(xs)
overall = sum(ln["lvl"] == 5 for ln in ov) / len(ov)
prof = {}
for ln in ov:
    s = prof.setdefault(id(ln["p"]), [ln["p"], 0, 0, 0.0])
    s[1] += 1
    s[2] += ln["lvl"] == 5
    s[3] += band_rate[band(ln["m"])]
flag_up = {}
for pid, (p, n, n5, exp) in prof.items():
    if n >= 150 and n5 / n >= 1.75 * overall:
        flag_up[pid] = (p, n5 / n, n5 / exp)
assert {id(U1), id(U2), id(U3), id(GERL)} <= set(flag_up), (overall, band_rate, [(p["name"], n, n5 / n, n5 / e) for p, n, n5, e in
                                                              (prof.get(id(x), [x, 1, 0, 1]) for x in (U1, U2, U3, GERL))])
for pid, (p, share, ratio) in flag_up.items():
    if p in (U1, U2, U3):
        assert ratio >= 3.0, (p["name"], ratio)
    else:
        assert ratio < 1.6, (p["name"], p["spec"], ratio)

# ---- write ------------------------------------------------------------------------------------------------------------------------
lines.sort(key=lambda ln: (ln["d"], ln["p"]["id"], ln["m"]["id"], ln["rev"] is not None, ln["pc"], ln["rcv"]))
for n, ln in enumerate([x for x in lines if x["rev"] is None], 1):
    ln["id"] = f"LN-{n:07d}"
for n, ln in enumerate([x for x in lines if x["rev"] is not None], 1):
    ln["id"] = f"LR-{n:05d}"
referrals.sort(key=lambda r: (r[3], r[0]["id"], r[2]["id"], r[1]["id"]))
scripts.sort(key=lambda s: (s["d"], s["pr"]["id"], s["m"]["id"], s["cls"]))


def write(rel, header, rows):
    f = DATA / rel
    f.parent.mkdir(parents=True, exist_ok=True)
    with f.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)


def iso(d):
    return d.isoformat() if d else ""


for old in sorted(DATA.glob("*/*/*.csv")):
    old.unlink()
write("inbox/address/ADDRESS_20260930.csv", ["address_id", "street", "city", "lat", "lon"],
      sorted([a["id"], a["street"], a["city"], a["lat"], a["lon"]] for a in addresses))
write("inbox/owner/OWNER_20260930.csv", ["owner_id", "name", "owner_type", "address_id"],
      sorted([o["id"], o["name"], o["kind"], o["addr"]["id"]] for o in owners))
write("inbox/provider/PROVIDER_20260930.csv", ["provider_id", "name", "specialty", "address_id", "bank_account"],
      sorted([p["id"], p["name"], p["spec"], p["addr"]["id"], p["acct"]] for p in providers))
write("inbox/provider_owner/PROVIDER_OWNER_20260930.csv", ["provider_id", "owner_id"],
      sorted([p["id"], o["id"]] for p in providers for o in p["owners"]))
write("inbox/member/MEMBER_20260930.csv", ["member_id", "birth_year", "address_id", "died_on"],
      sorted([m["id"], m["by"], m["addr"]["id"], iso(m["died"])] for m in members))
for mo in MONTHS:
    ym = mo.strftime("%Y%m")
    box = "staged" if mo > LANDED_END else "inbox"
    write(f"{box}/claim_line/CLAIM_LINE_{ym}.csv",
          ["claim_id", "provider_id", "member_id", "service_date", "procedure_code", "level", "billed", "paid", "received_on", "reverses"],
          [[ln["id"], ln["p"]["id"], ln["m"]["id"], iso(ln["d"]), ln["pc"], ln["lvl"] or "", f"{ln['billed']:.2f}", f"{ln['paid']:.2f}",
            iso(ln["rcv"]), ln["rev"]["id"] if ln["rev"] else ""] for ln in lines if month_start(ln["d"]) == mo])
    write(f"{box}/admission/ADMISSION_{ym}.csv", ["admission_id", "member_id", "hospital_id", "from_date", "to_date"],
          [[f"ADM-{i:05d}", a["m"]["id"], a["h"]["id"], iso(a["fd"]), iso(a["td"])] for i, a in enumerate(admissions, 1)
           if month_start(a["fd"]) == mo])
    write(f"{box}/referral/REFERRAL_{ym}.csv", ["referral_id", "from_provider", "to_provider", "member_id", "referred_on"],
          [[f"RF-{i:05d}", f["id"], t["id"], m["id"], iso(d)] for i, (f, t, m, d) in enumerate(referrals, 1) if month_start(d) == mo])
    write(f"{box}/prescription/PRESCRIPTION_{ym}.csv",
          ["prescription_id", "prescriber_id", "pharmacy_id", "member_id", "drug_class", "qty", "paid_cash", "filled_on"],
          [[f"RX-{i:06d}", s["pr"]["id"], s["ph"]["id"], s["m"]["id"], s["cls"], s["qty"], s["cash"], iso(s["d"])]
           for i, s in enumerate(scripts, 1) if month_start(s["d"]) == mo])

ids = lambda xs: sorted(x["id"] for x in xs)   # noqa: E731
scar_gps = [g for g in pick("General practice", "Scarborough") if id(g) in scar]
truth = {
    "seed": SEED,
    "window": {"landed": [START.isoformat(), LANDED_END.isoformat()], "staged": [(LANDED_END + timedelta(days=1)).isoformat(), END.isoformat()]},
    "counts": {"address": len(addresses), "owner": len(owners), "provider": len(providers),
               "provider_owner": sum(len(p["owners"]) for p in providers), "member": len(members),
               "claim_line_landed": len(landed), "admission_landed": sum(1 for a in admissions if a["fd"] <= LANDED_END),
               "referral_landed": len(landed_ref), "prescription_landed": len(landed_rx),
               "owner_persons": sum(1 for o in owners if o["kind"] == "person")},
    "stories": {
        "referral_kickbacks": {"hub": IMG["id"], "view": "health_referral_concentration",
                               "entities": sorted([GP1["id"], GP2["id"], IMG["id"], D1["id"], D2["id"], S1["id"], D1["addr"]["id"]]),
                               "lookalikes": [GPR["id"]]},
        "phantom_billing": {"hub": CLP["id"], "view": "health_phantom_billing",
                            "entities": sorted([CLP["id"], *ids(dead_story + adm_story)]), "lookalikes": [CLL["id"]]},
        "pill_mill": {"hub": MILL["id"], "view": "health_pill_mill",
                      "entities": sorted([MILL["id"], PH1["id"], PH2["id"], *ids(mill_patients)]), "lookalikes": [ONC["id"]]},
        "upcoding": {"hub": OWU["id"], "view": "health_upcoding",
                     "entities": sorted([OWU["id"], U1["id"], U2["id"], U3["id"]]), "lookalikes": [GERL["id"]]},
    },
    "referral_kickbacks": {"practices": [GP1["id"], GP2["id"]], "imaging_centre": IMG["id"], "practice_owner": D1["id"],
                           "spouse": S1["id"], "home": D1["addr"]["id"], "second_practice_owner": D2["id"],
                           "lookalike_practice": GPR["id"], "lookalike_hospital": HOSPS["id"],
                           "lookalike_hospital_owner": HOSPS["owners"][0]["id"], "scarborough_practices": ids(scar_gps)},
    "phantom_billing": {"clinic": CLP["id"], "in_hospital": ids(adm_story), "after_death": ids(dead_story),
                        "lookalike_clinic": CLL["id"], "lookalike_patients": ids(clinic_lookalike),
                        "background_errors": sorted(p["id"] for p, _ in bg_errors)},
    "pill_mill": {"prescriber": MILL["id"], "pharmacies": [PH1["id"], PH2["id"]], "patients": ids(mill_patients),
                  "lookalike": ONC["id"], "lookalike_patients": ids(onc_patients)},
    "upcoding": {"owner": OWU["id"], "clinics": [U1["id"], U2["id"], U3["id"]], "lookalike": GERL["id"],
                 "lookalike_owner": OWL["id"], "top_level_share_all": round(overall, 4)},
}
TRUTH.write_text(json.dumps(truth, indent=1, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
print(f"lines {len(landed)} landed of {len(lines)}, admissions {len(admissions)}, referrals {len(landed_ref)} landed of {len(referrals)}, "
      f"scripts {len(landed_rx)} landed of {len(scripts)}, owners {len(owners)}, addresses {len(addresses)}, overall top {overall:.3f}, "
      f"upcoding flags {len(flag_up)}, concentrated {len(concentrated)}")
