#!/usr/bin/env python3
"""Regenerate the `la-benefits` Space Template's benefits and tax fraud corpus - SYNTHETIC, deterministic (one seeded Random).

Writes spaces/_templates/la-benefits/data/inbox/<feed>/ (12 months landed, 2025-10-01..2026-09-30) and data/staged/<feed>/ (the
next month, 2026-10, for the "new data arrives" step) for seven feeds:
  address, person, household (one extract each, landed only), claim (one landed extract of the claims open in the window, plus
  the claims that start in the staged month), death_register (by month notified), payroll (by pay period), tax_return (by
  month filed)
and tools/gen-la-benefits-data.ground-truth.json (story -> entity ids and look-alike ids; NOT shipped in the data download).

Planted stories (plan: docs/superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md, spec 8; pinned by
tools/gen-la-benefits-data.test.mjs over the files and BenefitsLinksJobGoldenTest over the link Datasets):
  1 ADDRESS FARM        14 claimants under 5 surnames at one address, each declaring a single-person household, paid into 3
                        accounts that nobody else uses
                        look-alike: 3 care homes, 20-35 genuine residents each, one declaration per home, each resident paid
                        into their own account
  2 PAYMENTS TO THE     9 pensioners who died between November 2025 and April 2026 (deaths notified within 2 weeks) whose
    DECEASED            pensions are never stopped; they are paid into 3 accounts that also receive the 3 claims of one
                        living person
                        look-alike: a death notified 40 days late; the claim stops 2 days after the notice and the
                        overpayment is recovered in full
  3 UNDECLARED WORK     20 people on Jobseeker benefit appear on the payroll of 2 employers (4 of them on both) for 6 months
                        or more, earning 1 100-1 900 a month while claiming
                        look-alike: a jobseeker with permitted part-time earnings under the 200-a-month limit (and about 30
                        more like them, plus about 30 people whose claim closed in the month they started work)
  4 TAX-PREPARER RING   preparer P-12 files 80 returns in January-February 2026 with refunds of 1 800-3 400 (the median
                        refund is about 330), all paid into 6 accounts the preparer controls
                        look-alike: P-03, a large preparer whose 250 clients' refunds go to each client's own account
Scale: 12 000 people at about 5 250 of 6 500 addresses, about 7 700 claims, 300 deaths, about 40 000 payroll rows, 8 000 tax
returns. Phones are in Ofcom's drama ranges; bank accounts are invented SYN- numbers; names and streets are invented; cities are
real only at city level and coordinates are random points around a city centre. Nothing derives from a real source.
"""
from __future__ import annotations

import csv
import json
import math
import random
from datetime import date, timedelta
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
DATA = TOOLS.parent / "spaces" / "_templates" / "la-benefits" / "data"
TRUTH = TOOLS / "gen-la-benefits-data.ground-truth.json"
SEED = 20261109
rng = random.Random(SEED)
START, LANDED_END, END = date(2025, 10, 1), date(2026, 9, 30), date(2026, 10, 31)
MONTHS = [date(2025 + (9 + i) // 12, (9 + i) % 12 + 1, 1) for i in range(13)]   # 2025-10 .. 2026-10
N_PERSONS, N_ADDR, N_EMP, N_RETURNS, N_DEATHS = 12000, 6500, 600, 8000, 300
LIMIT = 200   # permitted earnings a month while on Jobseeker benefit (this synthetic world's figure)

CITIES = {"Bristol": (51.4545, -2.5879), "Cardiff": (51.4816, -3.1791), "Nottingham": (52.9548, -1.1581),
          "Leicester": (52.6369, -1.1398), "Coventry": (52.4068, -1.5197)}
FIRST = ["Ada", "Basil", "Cora", "Dev", "Edie", "Flynn", "Gwen", "Hugo", "Isla", "Jude", "Kira", "Lars", "Maeve", "Nico", "Opal",
         "Piers", "Rhea", "Sami", "Tess", "Ugo", "Vida", "Wade", "Xena", "Yusuf", "Zara", "Arlo", "Bea", "Cyrus", "Dina", "Eben",
         "Fenna", "Gil", "Hana", "Ivo", "Juno", "Kofi", "Lumi", "Milo", "Nell", "Otis", "Pria", "Quin", "Ravi", "Sela", "Tobin",
         "Amara", "Bram", "Cleo", "Darius", "Elin", "Faris", "Greer", "Hollis", "Imke", "Jory", "Keziah", "Leif", "Mira", "Noor"]
LAST = ["Abbott", "Bramley", "Calloway", "Denholm", "Ellery", "Fairbairn", "Gilchrist", "Holloway", "Ingram", "Jessop", "Kinsella",
        "Lindqvist", "McAllister", "Nightingale", "Ormsby", "Prentice", "Quarry", "Rowntree", "Stanhope", "Treadwell", "Upton",
        "Varga", "Wetherby", "Yelland", "Zamora", "Ashby", "Blackwood", "Crowther", "Dunbar", "Everley", "Fenwick", "Garside",
        "Hartigan", "Ivesley", "Jowett", "Kettering", "Loxley", "Marchbank", "Northcott", "Oddie", "Pemberton", "Radcliffe",
        "Sallow", "Thorley", "Vickery", "Wragg", "Ackroyd", "Birtwistle", "Cotterill", "Dimmock", "Elphick", "Fothergill"]
VARIANT = {"Kinsella": "Kinsela", "McAllister": "MacAllister", "Holloway": "Halloway", "Fairbairn": "Fairburn",
           "Nightingale": "Nightengale", "Cotterill": "Cotterell", "Fothergill": "Fothergil"}
STREET = ["Amber", "Bracken", "Chalk", "Dove", "Ember", "Flint", "Gorse", "Hazel", "Iron", "Jasper", "Kiln", "Larch", "Mill", "Oak",
          "Pippin", "Quarry", "Rowan", "Sorrel", "Teasel", "Willow", "Alder", "Brook", "Cobble", "Dunnock", "Elder", "Fern",
          "Lark", "Moss", "Nettle", "Plover", "Rush", "Sedge", "Thrush", "Vetch", "Wagtail", "Yew"]
SUFFIX = ["Lane", "Road", "Close", "Street", "Avenue", "Grove", "Way", "Terrace"]
PHONES = ["07700 900", "01632 960", "0113 496 0", "0114 496 0", "0115 496 0", "0116 496 0", "0117 496 0", "0118 496 0",
          "0121 496 0", "0131 496 0", "0141 496 0", "0151 496 0", "0161 496 0", "020 7946 0", "0191 498 0", "029 2018 0"]
AMOUNT = {"Pension": (700, 1100), "Housing": (300, 900), "Jobseeker": (320, 420), "Disability": (250, 750), "Carer": (300, 330),
          "Child": (100, 260)}

FARM = "AD-03141"
CARE = ["AD-00412", "AD-02718", "AD-05077"]
E1, E2 = "EMP-0217", "EMP-0388"           # undeclared work: the two employers
RING_PREP, BIG_PREP = "P-12", "P-03"      # tax-preparer ring, and the large honest preparer


def rand_date(a: date, b: date) -> date:
    return a + timedelta(days=rng.randint(0, (b - a).days))


def month_start(d: date) -> date:
    return date(d.year, d.month, 1)


phone_pool = [p + f"{i:03d}" for p in PHONES for i in range(1000)]
rng.shuffle(phone_pool)
accounts_used: set[str] = set()


def new_account() -> str:
    while True:
        a = f"SYN-{rng.randint(10_000_000, 99_999_999)}"
        if a not in accounts_used:
            accounts_used.add(a)
            return a


# ---- addresses --------------------------------------------------------------------------------------------------------------------
addresses = {}
seen_streets = set()
for i in range(1, N_ADDR + 1):
    city = rng.choices(list(CITIES), weights=[30, 18, 20, 17, 15])[0]
    while True:
        street = f"{rng.randint(1, 180)} {rng.choice(STREET)} {rng.choice(SUFFIX)}"
        if (street, city) not in seen_streets:
            seen_streets.add((street, city))
            break
    lat, lon = CITIES[city]
    blank = rng.random() < 0.03
    addresses[f"AD-{i:05d}"] = {"street": street, "city": city,
                                "lat": "" if blank else f"{lat + rng.uniform(-0.05, 0.05):.5f}",
                                "lon": "" if blank else f"{lon + rng.uniform(-0.07, 0.07):.5f}"}
for n, a in zip(["Linden Court", "Harbour View", "Orchard House"], CARE):
    addresses[a]["street"] = f"{n} Care Home, {addresses[a]['street']}"

# ---- people and households ------------------------------------------------------------------------------------------------------
people = []        # dicts; ids assigned at the end by a random permutation so story people are not numbered together
households = []    # (address, declared_by person dict or None, declared_members)


def person(name, birth, addr, acct=None, phone=True):
    p = {"name": name, "birth": birth, "addr": addr, "acct": acct, "phone": phone_pool.pop() if phone else "",
         "kind": "background", "claims": [], "hh": None}
    people.append(p)
    return p


def surname_of(s):
    return VARIANT[s] if s in VARIANT and rng.random() < 0.06 else s


# the address farm: 14 claimants, 5 surnames, each a single-person household; 3 accounts nobody else uses
farm_accounts = [new_account() for _ in range(3)]
farm = []
for surname, n in zip(["Varga", "Dunbar", "Oddie", "Kettering", "Zamora"], [4, 3, 3, 2, 2]):
    for _ in range(n):
        p = person(f"{rng.choice(FIRST)} {surname}", rng.randint(1978, 2001), FARM, phone=rng.random() < 0.6)
        p["kind"] = "farm"
        farm.append(p)
        households.append((FARM, p, 1))

# the care homes: genuine residents, one declaration per home, each paid into their own account
care = {}
for a, n in zip(CARE, [31, 24, 27]):
    care[a] = []
    for _ in range(n):
        p = person(f"{rng.choice(FIRST)} {surname_of(rng.choice(LAST))}", rng.randint(1928, 1948), a, new_account(), phone=False)
        p["kind"] = "care"
        care[a].append(p)
    households.append((a, None, n))

# the background population
free_addr = [a for a in addresses if a != FARM and a not in CARE]
rng.shuffle(free_addr)
for addr in free_addr:
    if len(people) >= N_PERSONS:
        break
    if rng.random() < 0.15:
        continue    # vacant
    size = min(rng.choices([1, 2, 3, 4, 5, 6], weights=[34, 31, 15, 12, 6, 2])[0], N_PERSONS - len(people))
    adults = 1 if size == 1 else (2 if rng.random() < 0.8 else 1)
    kids = size - adults
    surname = rng.choice(LAST)
    if kids:
        head_birth = rng.randint(1966, 1998)
    else:
        head_birth = rng.randint(1936, 2004) if adults == 1 else rng.randint(1938, 2000)
    members = []
    joint = adults == 2 and rng.random() < 0.45
    shared = new_account() if joint else None
    for k in range(adults):
        sn = surname if k == 0 or rng.random() < 0.75 else rng.choice(LAST)
        b = head_birth if k == 0 else max(1930, min(2005, head_birth + rng.randint(-5, 5)))
        members.append(person(f"{rng.choice(FIRST)} {surname_of(sn)}", b, addr, shared or new_account(), rng.random() < 0.7))
    for _ in range(kids):
        if head_birth <= 1984 and rng.random() < 0.35:   # a grown-up son or daughter still at home
            members.append(person(f"{rng.choice(FIRST)} {surname_of(surname)}", rng.randint(1998, 2007), addr, new_account(), rng.random() < 0.8))
        else:
            members.append(person(f"{rng.choice(FIRST)} {surname_of(surname)}", rng.randint(max(head_birth + 18, 2009), 2024), addr, None, False))
    declared = size - 1 if size > 1 and rng.random() < 0.05 else size
    households.append((addr, members[0], declared))
    for m in members:
        m["hh"] = members
assert len(people) == N_PERSONS, len(people)


def age(p):
    return 2026 - p["birth"]


# ---- claims -----------------------------------------------------------------------------------------------------------------------
claims = []    # dicts: person, type, start, end, amount, acct, recovered


def claim(p, kind, start, end=None, acct=None):
    if start > END:
        return None    # e.g. a pension that starts after the staged month
    lo, hi = AMOUNT[kind]
    c = {"person": p, "type": kind, "start": start, "end": end, "amount": round(rng.uniform(lo, hi), 2),
         "acct": acct or p["acct"], "recovered": None}
    claims.append(c)
    p["claims"].append(c)
    return c


def claim_start(recent=False):
    if recent or rng.random() < 0.25:
        return rand_date(date(2025, 4, 1), END)
    return rand_date(date(2016, 1, 1), date(2025, 9, 30))


for i, p in enumerate(farm):
    kind = "Housing" if i % 3 != 2 else rng.choice(["Jobseeker", "Disability"])
    claim(p, kind, rand_date(date(2025, 3, 1), date(2026, 6, 30)), acct=farm_accounts[i % 3])
for a in CARE:
    for p in care[a]:
        claim(p, "Pension", date(p["birth"] + 66, rng.randint(1, 12), 1))
        if rng.random() < 0.4:
            claim(p, "Disability", rand_date(date(2016, 1, 1), date(2025, 6, 30)))

background = [p for p in people if p["kind"] == "background"]
adults = [p for p in background if age(p) >= 18]
seen_hh = set()
for p in adults:
    if age(p) >= 66:
        if rng.random() < 0.93:
            claim(p, "Pension", date(p["birth"] + 66, rng.randint(1, 12), 1))
        if rng.random() < 0.12:
            claim(p, "Housing", claim_start())
        if rng.random() < 0.10:
            claim(p, "Disability", claim_start())
        continue
    if rng.random() < 0.12:
        s = claim_start(recent=True)
        claim(p, "Jobseeker", s, None if rng.random() < 0.3 else s + timedelta(days=int(rng.expovariate(1 / 150)) + 20))
    if rng.random() < 0.27:
        claim(p, "Housing", claim_start())
    if rng.random() < 0.10:
        claim(p, "Disability", claim_start())
    if rng.random() < 0.05:
        claim(p, "Carer", claim_start())
    hh = id(p["hh"])
    if hh not in seen_hh and any(age(m) < 18 for m in p["hh"]):
        seen_hh.add(hh)
        claim(p, "Child", claim_start())
for c in claims:   # most long claims run on; a few end in the window
    if c["type"] not in ("Jobseeker", "Pension") and c["end"] is None and rng.random() < 0.08:
        c["end"] = rand_date(max(c["start"], START), END)


def jobseeker(p):
    return next((c for c in p["claims"] if c["type"] == "Jobseeker"), None)


# ---- payroll ----------------------------------------------------------------------------------------------------------------------
emp_ids = [f"EMP-{i:04d}" for i in range(1, N_EMP + 1)]
emp_weight = {e: rng.paretovariate(1.2) for e in emp_ids}
for e in (E1, E2):
    emp_weight[e] = 0.0
payroll = []    # (employer, person, period, wages)


def employ(p, e, months, wage, jitter=0.05):
    for m in months:
        payroll.append((e, p, m, round(wage * rng.uniform(1 - jitter, 1 + jitter), 2)))


def random_employer():
    return rng.choices(emp_ids, weights=[emp_weight[e] for e in emp_ids])[0]


# story 3 first: 20 people with no claims and no job, then a Jobseeker claim and two employers' payroll
working_age = [p for p in adults if 18 <= age(p) <= 64 and not p["claims"]]
rng.shuffle(working_age)
work_story = working_age[:20]
for i, p in enumerate(work_story):
    p["kind"] = "work"
    s = rand_date(date(2025, 5, 1), date(2025, 12, 31))
    claim(p, "Jobseeker", s)
    first = max(MONTHS.index(month_start(s)) + 1 if month_start(s) >= START else 0, rng.randint(0, 5))
    months = MONTHS[first:first + rng.randint(9, 13)]
    if i < 12:
        employ(p, E1, months, rng.uniform(1100, 1900))
    if i >= 8:
        employ(p, E2, months if i >= 12 else months[1:], rng.uniform(1100, 1900))
controller = working_age[20]
controller["kind"] = "controller"

# people who never claimed Jobseeker and are not story people: the workforce, with E1 and E2's own staff
workers_pool = [p for p in working_age[21:]] + [p for p in adults if 18 <= age(p) <= 64 and p["claims"] and not jobseeker(p)]
rng.shuffle(workers_pool)
workers = workers_pool[:3350]
for i, p in enumerate(workers):
    e = E1 if i < 34 else E2 if i < 64 else random_employer()
    r = rng.random()
    a, b = (0, 13) if r < 0.7 else (rng.randint(1, 11), 13) if r < 0.85 else (0, rng.randint(2, 12))
    base = min(9000.0, max(600.0, rng.lognormvariate(math.log(2100), 0.45)))
    employ(p, e, MONTHS[a:b], base)
    p["worker"] = True

# background jobseekers: some start work after the claim closes, some in the month it closes, a few earn under the limit
starters, permitted = [], []
for p in adults:
    c = jobseeker(p)
    if not c or p["kind"] != "background" or p.get("worker"):
        continue
    if c["end"] and START <= c["end"] < date(2026, 10, 1):
        r = rng.random()
        if r < 0.45:
            first = MONTHS.index(month_start(c["end"])) + 1
        elif r < 0.53:
            first = MONTHS.index(month_start(c["end"]))    # starts work in the month the claim closes
            starters.append(p)
        else:
            continue
        employ(p, random_employer(), MONTHS[first:], rng.uniform(1300, 2600))
        p["worker"] = True
    elif c["end"] is None and len(permitted) < 31 and c["start"] < date(2025, 12, 1):
        months = MONTHS[MONTHS.index(month_start(max(c["start"], START))) + 1:][:rng.randint(4, 12)]
        if len(months) >= 4:
            employ(p, random_employer(), months, rng.uniform(60, 180), jitter=0.08)
            permitted.append(p)
            p["worker"] = True
for e, p, m, w in payroll:
    assert e not in (E1, E2) or p["kind"] == "work" or not jobseeker(p), "only story jobseekers are on E1 / E2's payroll"
lookalike_work = max(permitted, key=lambda p: (sum(1 for x in payroll if x[1] is p), p["birth"]))
lookalike_work["kind"] = "lookalike_work"

# ---- deaths -----------------------------------------------------------------------------------------------------------------------
deaths = []    # (person, died_on, notified_on)
elderly_single = [p for p in background if age(p) >= 70 and len(p["hh"]) == 1 and not p.get("worker")
                  and [c["type"] for c in p["claims"]] == ["Pension"]]
rng.shuffle(elderly_single)
dead_story = elderly_single[:9]
dead_accounts = [new_account() for _ in range(3)]
for i, p in enumerate(dead_story):
    p["kind"] = "dead_story"
    died = rand_date(date(2025, 11, 1), date(2026, 4, 30))
    deaths.append((p, died, died + timedelta(days=rng.randint(1, 14))))
    p["claims"][0]["acct"] = dead_accounts[i % 3]
for kind, acct in zip(["Housing", "Disability", "Carer"], dead_accounts):
    claim(controller, kind, rand_date(date(2025, 6, 1), date(2025, 12, 31)), acct=acct)
late = elderly_single[9]
late["kind"] = "lookalike_death"
died = date(2026, 2, 9)
deaths.append((late, died, died + timedelta(days=40)))

candidates = [p for p in background if not p.get("worker") and p["kind"] == "background"
              and (age(p) >= 70 or (age(p) >= 40 and rng.random() < 0.08))]
rng.shuffle(candidates)
for p in candidates:
    if len(deaths) >= N_DEATHS:
        break
    notified = rand_date(START, END)
    active = [c for c in p["claims"] if c["end"] is None or c["end"] > notified - timedelta(days=60)]
    if notified > LANDED_END and active:
        continue    # an October death of a claimant would need a claim update the staged month does not carry
    r = rng.random()
    lag = rng.randint(0, 7) if r < 0.6 else rng.randint(8, 29) if r < 0.9 else rng.randint(30, 60)
    died = notified - timedelta(days=lag)
    if any(c["start"] > died for c in p["claims"]):
        continue
    deaths.append((p, died, notified))
    p["kind"] = "dead"
for p, died, notified in deaths:
    if p["kind"] == "dead_story":
        continue
    for c in p["claims"]:
        if c["end"] is None or c["end"] > died:
            c["end"] = min(LANDED_END, notified + timedelta(days=2 if p is late else rng.randint(0, 5)))
            overpaid = round(c["amount"] * (c["end"] - died).days / 30.4, 2)
            c["recovered"] = overpaid if p is late or rng.random() < 0.85 else round(overpaid * rng.uniform(0, 0.6), 2)
dead_ids = {id(p) for p, _, _ in deaths}

# ---- tax returns ------------------------------------------------------------------------------------------------------------------
preparers = [f"P-{i:02d}" for i in range(1, 100)]
eligible = [p for p in adults if p["kind"] in ("background", "work", "lookalike_work") and id(p) not in dead_ids]
rng.shuffle(eligible)
ring = [p for p in eligible if age(p) <= 64 and p["kind"] == "background"][:80]
ring_ids = {id(p) for p in ring}
rest = [p for p in eligible if id(p) not in ring_ids]
big_clients = rest[:250]
others = rest[250:]
ring_accounts = [new_account() for _ in range(6)]
returns = []   # (person, preparer or "", refund, account or "", filed)
FILE_MONTH_WEIGHTS = [4, 5, 9, 40, 6, 5, 7, 5, 5, 4, 4, 3, 3]


def filed_on():
    m = rng.choices(MONTHS, weights=FILE_MONTH_WEIGHTS)[0]
    nxt = MONTHS[MONTHS.index(m) + 1] if m != MONTHS[-1] else END + timedelta(days=1)
    return rand_date(m, nxt - timedelta(days=1))


def honest(p, prep):
    if rng.random() < 0.25:
        returns.append((p, prep, 0.0, "", filed_on()))
    else:
        refund = 2500 + rng.uniform(0, 2500) if rng.random() < 0.01 else min(4000.0, max(5.0, rng.lognormvariate(math.log(330), 0.8)))
        returns.append((p, prep, round(refund, 2), p["acct"], filed_on()))


for i, p in enumerate(ring):
    returns.append((p, RING_PREP, round(rng.uniform(1800, 3400), 2), ring_accounts[i % 6], rand_date(date(2026, 1, 6), date(2026, 2, 27))))
for p in big_clients:
    honest(p, BIG_PREP)
counts = {pr: min(55, max(4, int(rng.paretovariate(1.3) * 12))) for pr in preparers if pr not in (RING_PREP, BIG_PREP)}
pos = 0
for pr, n in counts.items():
    for p in others[pos:pos + n]:
        honest(p, pr)
    pos += n
for p in others[pos:pos + N_RETURNS - len(returns)]:
    honest(p, "")
assert len(returns) == N_RETURNS, len(returns)
returns.sort(key=lambda r: (r[4], rng.random()))

# ---- ids ----------------------------------------------------------------------------------------------------------------------------
perm = list(range(1, N_PERSONS + 1))
rng.shuffle(perm)
for p, n in zip(people, perm):
    p["id"] = f"CIT-{n:05d}"
claims_sorted = sorted(claims, key=lambda c: (c["start"], c["person"]["id"], c["type"]))
for i, c in enumerate(claims_sorted, 1):
    c["id"] = f"CL-{i:06d}"
hh_rows = []
for k, (addr, by, declared) in enumerate(sorted(households, key=lambda h: (h[0], h[1]["id"] if h[1] else "")), 1):
    hh_rows.append([f"HH-{k:05d}", addr, by["id"] if by else "", declared])

# ---- invariants (the stories are what they claim and nothing else looks like them) ---------------------------------------------
in_window = [c for c in claims if (c["end"] is None or c["end"] >= START)]
claimants_at = {}
for c in in_window:
    claimants_at.setdefault(c["person"]["addr"], set()).add(c["person"]["id"])
big_addr = {a for a, s in claimants_at.items() if len(s) >= 8}
assert big_addr == {FARM, *CARE}, big_addr
died_of = {p["id"]: d for p, d, _ in deaths}
ongoing_after_death = {c["person"]["id"] for c in in_window if c["person"]["id"] in died_of
                       and (c["end"] is None or c["end"] > LANDED_END) and c["start"] <= died_of[c["person"]["id"]]}
assert ongoing_after_death == {p["id"] for p in dead_story}, ongoing_after_death
acct_claims = {}
for c in in_window:
    acct_claims.setdefault(c["acct"], set()).add(c["person"]["id"])
for a in dead_accounts:
    assert acct_claims[a] == {p["id"] for p in dead_story if p["claims"][0]["acct"] == a} | {controller["id"]}
for a in farm_accounts:
    assert acct_claims[a] <= {p["id"] for p in farm}
assert all(len(acct_claims[p["acct"]]) == 1 for a in CARE for p in care[a]), "care home residents are paid into their own accounts"


def over_limit_months(p):
    c = jobseeker(p)
    if not c:
        return 0
    return sum(1 for e, q, m, w in payroll if q is p and m >= month_start(c["start"]) and (c["end"] is None or m <= c["end"]) and w > LIMIT)


for p in work_story:
    assert sum(1 for e, q, m, w in payroll if q is p and m <= LANDED_END and w > LIMIT) >= 6
for p in adults:
    if p["kind"] != "work":
        assert over_limit_months(p) <= 1, p["id"]
refund_users = {}
for p, prep, refund, acct, filed in returns:
    if acct and refund > 0:
        refund_users.setdefault(acct, set()).add(p["id"])
assert {a for a, s in refund_users.items() if len(s) > 2} == set(ring_accounts)
assert max(counts.values()) <= 55 and len({id(r[0]) for r in returns}) == N_RETURNS

# ---- write ------------------------------------------------------------------------------------------------------------------------


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
      [[a, v["street"], v["city"], v["lat"], v["lon"]] for a, v in addresses.items()])
write("inbox/person/PERSON_20260930.csv", ["person_id", "name", "birth_year", "address_id", "phone"],
      [[p["id"], p["name"], p["birth"], p["addr"], p["phone"]] for p in sorted(people, key=lambda p: p["id"])])
write("inbox/household/HOUSEHOLD_20260930.csv", ["household_id", "address_id", "declared_by", "declared_members"], hh_rows)
CLAIM_HEAD = ["claim_id", "person_id", "benefit_type", "start_date", "end_date", "monthly_amount", "bank_account", "overpaid_recovered"]


def claim_row(c, as_of):
    ended = c["end"] if c["end"] and c["end"] <= as_of else None
    rec = f"{c['recovered']:.2f}" if ended and c["recovered"] is not None else ""
    return [c["id"], c["person"]["id"], c["type"], iso(c["start"]), iso(ended), f"{c['amount']:.2f}", c["acct"], rec]


write("inbox/claim/CLAIM_20260930.csv", CLAIM_HEAD,
      [claim_row(c, LANDED_END) for c in claims_sorted if c["start"] <= LANDED_END and (c["end"] is None or c["end"] >= START)])
write("staged/claim/CLAIM_20261031.csv", CLAIM_HEAD, [claim_row(c, END) for c in claims_sorted if c["start"] > LANDED_END])
for m in MONTHS:
    ym = m.strftime("%Y%m")
    box = "staged" if m > LANDED_END else "inbox"
    write(f"{box}/death_register/DEATH_REGISTER_{ym}.csv", ["person_id", "died_on", "notified_on"],
          sorted([p["id"], iso(d), iso(n)] for p, d, n in deaths if month_start(n) == m))
    write(f"{box}/payroll/PAYROLL_{ym}.csv", ["employer_id", "person_id", "period", "wages"],
          sorted([e, p["id"], iso(per), f"{w:.2f}"] for e, p, per, w in payroll if per == m))
    write(f"{box}/tax_return/TAX_RETURN_{ym}.csv", ["return_id", "taxpayer_id", "preparer_id", "refund", "bank_account", "filed_at"],
          [[f"TR-{i:06d}", p["id"], prep, f"{refund:.2f}", acct, iso(filed)]
           for i, (p, prep, refund, acct, filed) in enumerate(returns, 1) if month_start(filed) == m])

ids = lambda ps: sorted(p["id"] for p in ps)   # noqa: E731
claim_ids = lambda ps: sorted(c["id"] for p in ps for c in p["claims"])   # noqa: E731
work_claims = sorted(jobseeker(p)["id"] for p in work_story)
landed_payroll = sum(1 for x in payroll if x[2] <= LANDED_END)
truth = {
    "seed": SEED,
    "window": {"landed": [START.isoformat(), LANDED_END.isoformat()], "staged": [(LANDED_END + timedelta(days=1)).isoformat(), END.isoformat()]},
    "permitted_earnings_limit": LIMIT,
    "counts": {"address": len(addresses), "person": len(people), "household": len(hh_rows),
               "claim_landed": sum(1 for c in claims if c["start"] <= LANDED_END and (c["end"] is None or c["end"] >= START)),
               "death_landed": sum(1 for _, _, n in deaths if n <= LANDED_END), "payroll_landed": landed_payroll,
               "tax_return_landed": sum(1 for r in returns if r[4] <= LANDED_END)},
    "stories": {
        "address_farm": {"hub": FARM, "view": "address_farm",
                         "entities": sorted([FARM, *ids(farm), *claim_ids(farm), *farm_accounts]), "lookalikes": sorted(CARE)},
        "payments_to_the_deceased": {"hub": controller["id"], "view": "paid_after_death",
                                     "entities": sorted([controller["id"], *ids(dead_story), *claim_ids(dead_story + [controller]), *dead_accounts]),
                                     "lookalikes": [late["id"]]},
        "undeclared_work": {"hub": E1, "view": "undeclared_work",
                            "entities": sorted([E1, E2, *ids(work_story), *work_claims]), "lookalikes": [lookalike_work["id"]]},
        "tax_preparer_ring": {"hub": RING_PREP, "view": "tax_preparer_refund_ring",
                              "entities": sorted([RING_PREP, *ids(ring), *ring_accounts]), "lookalikes": [BIG_PREP]},
    },
    "address_farm": {"address": FARM, "claimants": ids(farm), "surnames": ["Dunbar", "Kettering", "Oddie", "Varga", "Zamora"],
                     "accounts": farm_accounts, "care_homes": {a: len(care[a]) for a in CARE}},
    "payments_to_the_deceased": {"deceased": ids(dead_story), "accounts": dead_accounts, "controller": controller["id"],
                                 "controller_claims": claim_ids([controller]),
                                 "lookalike": late["id"], "lookalike_died_on": iso(died_of[late["id"]]), "lookalike_notice_days": 40,
                                 "lookalike_claim": late["claims"][0]["id"]},
    "undeclared_work": {"employers": [E1, E2], "claimants": ids(work_story),
                        "on_both": ids(work_story[8:12]), "lookalike": lookalike_work["id"],
                        "permitted_earners": ids(permitted), "started_work_in_closing_month": ids(starters)},
    "tax_preparer_ring": {"preparer": RING_PREP, "taxpayers": ids(ring), "accounts": ring_accounts,
                          "lookalike_preparer": BIG_PREP, "lookalike_clients": len(big_clients)},
}
TRUTH.write_text(json.dumps(truth, indent=1, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
print(f"people {len(people)}, addresses {len(addresses)}, households {len(hh_rows)}, claims {truth['counts']['claim_landed']} landed of "
      f"{len(claims)}, deaths {len(deaths)}, payroll {landed_payroll} landed of {len(payroll)}, returns {len(returns)}, "
      f"permitted {len(permitted)}, starters {len(starters)}, preparers {sum(counts.values())} prepared by 97 + 330")
