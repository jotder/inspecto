#!/usr/bin/env python3
"""Regenerate the `la-ownership` Space Template's beneficial-ownership and sanctions corpus - SYNTHETIC, deterministic.

Writes spaces/_templates/la-ownership/data/inbox/<feed>/ (12 months landed, 2025-10-01 .. 2026-09-30) and
spaces/_templates/la-ownership/data/staged/<feed>/ (October 2026, copied in during the demo) for seven feeds:
  company, person, ownership, officer, customer, payment (one file per month), sanctions_list
and tools/gen-la-ownership-data.ground-truth.json (story -> entity ids and look-alike ids; NOT part of the data download).

Planted stories (plan docs/archived-documents/plans-archive/2026-11-07-la-showcase-police-insurance-utility-plan.md, spec 5):
  1 HIDDEN OWNER       one person reaches 4 customers through a 5-layer chain across 3 jurisdictions (CY, VG, AE); every stake on
                       the chain is 24 %, under a 25 % disclosure threshold; at the bottom three of the chain's vehicles hold
                       24 + 24 + 13 = 61 % of each customer.  Look-alike: a listed company holding 61 % of 4 customer
                       subsidiaries, whose own shares are spread over 60 people at under 2 % each.
  2 SANCTIONS 2 HOPS   a customer's 50 % owner is 60 % owned by a person whose name is a spelling variant of a listed person,
                       with the same birth year and nationality (a name_match).  Look-alike: a near-namesake of another listed
                       person with a different birth year and nationality (no name_match).
  3 NOMINEE DIRECTOR   one person directs 120 companies registered at one formation-agent address (written three ways).
                       Look-alike: an accountant who directs 4 family firms at 4 different addresses.
  4 CIRCULAR OWNERSHIP A owns 40 % of B, B 35 % of C, C 30 % of A; all three are customers and pay each other round the circle
                       every month.  Look-alike: a parent holding 100 % of three customer subsidiaries that pay it a monthly fee.

The sanctions list is ENTIRELY INVENTED: its names are built from coined syllables, no entry is or resembles a name on any real
list. name_match is computed by the ownership_links Job (DuckDB jaro_winkler_similarity >= 0.90 on the name or an alias, with the
same birth year and nationality); this generator runs the same rule on its own corpus and refuses to write if anything but the
planted pair would match. Every name, id and street is invented; cities are real only at city level.
"""
from __future__ import annotations

import csv
import json
import random
import re
from itertools import accumulate
from datetime import date, datetime, timedelta
from pathlib import Path

from la_gen_io import Outputs

TOOLS = Path(__file__).resolve().parent
DATA = TOOLS.parent / "spaces" / "_templates" / "la-ownership" / "data"
TRUTH = TOOLS / "gen-la-ownership-data.ground-truth.json"
SEED = 20261107
rng = random.Random(SEED)
START, END, STAGED_END = date(2025, 10, 1), date(2026, 9, 30), date(2026, 10, 31)
N_COMPANY, N_PERSON, N_CUSTOMER, N_LIST, N_PAYMENT = 6000, 5000, 1500, 400, 40000
MIN_NAME_SCORE = 0.90

FIRST = ["Adam", "Alice", "Amir", "Anna", "Ben", "Bianca", "Carl", "Chloe", "Daniel", "Dora", "Edward", "Elena", "Felix", "Fiona",
         "George", "Grace", "Hannah", "Henry", "Isaac", "Iris", "Jack", "Julia", "Karl", "Kate", "Leo", "Lucy", "Martin", "Maya",
         "Noah", "Nina", "Oscar", "Olivia", "Paul", "Priya", "Robert", "Rosa", "Samuel", "Sara", "Thomas", "Tara", "Victor", "Vera",
         "William", "Zoe", "Arjun", "Fatima", "Hugo", "Ingrid", "Marco", "Sofia"]
LAST = ["Abbott", "Barker", "Carver", "Dawson", "Ellison", "Fletcher", "Gardner", "Holloway", "Ingram", "Jennings", "Keller",
        "Lambert", "Mercer", "Norris", "Oakley", "Parsons", "Quinlan", "Rowley", "Sheldon", "Tanner", "Upton", "Vickers",
        "Walsh", "Yates", "Ashby", "Brandt", "Castell", "Delacroix", "Eckhart", "Farrell", "Granger", "Hartley", "Iyer",
        "Janssen", "Kowal", "Lindqvist", "Moreau", "Nakamura", "Osei", "Petrakis", "Rinaldi", "Sorensen", "Thorne", "Varga",
        "Weber", "Zamora", "Bishop", "Crane", "Doyle", "Everett"]
# coined syllables for the INVENTED sanctions list - disjoint from the everyday names above
LIST_FIRST_A = ["Dor", "Ans", "Tev", "Cal", "Mir", "Oren", "Sav", "Lio", "Bren", "Jor", "Kael", "Vesh", "Ul", "Zar", "Ith", "Quen"]
LIST_FIRST_B = ["in", "elm", "ard", "io", "ena", "ric", "o", "ast", "ue", "ys"]
LIST_LAST_A = ["Mal", "Quar", "Dell", "Vant", "Holl", "Ost", "Pell", "Corv", "Brix", "Tamm", "Zell", "Yor", "Fenn", "Grav", "Lumb", "Nesk"]
LIST_LAST_B = ["veck", "rant", "isorn", "reb", "imar", "ergrin", "ivane", "ayne", "oddle", "arek", "ith", "umber", "ask", "enford"]
CO_A = ["Amber", "Beacon", "Cobalt", "Delta", "Ember", "Falcon", "Granite", "Harbour", "Indigo", "Juniper", "Keystone", "Lantern",
        "Meridian", "Northgate", "Orchard", "Pinnacle", "Quarry", "Ridge", "Summit", "Tidewater", "Upland", "Vantage", "Willow",
        "Yarrow", "Zenith", "Atlas", "Bramble", "Cedar", "Driftwood", "Elmstead"]
CO_B = ["Trading", "Holdings", "Logistics", "Capital", "Foods", "Textiles", "Engineering", "Ventures", "Shipping", "Partners",
        "Supplies", "Consulting", "Metals", "Media", "Properties", "Systems", "Investments", "Agro", "Marine", "Energy"]
CO_C = ["", "", "", " Group", " International", " Global", " Services", " Europe", " Asia", " Associates", " Solutions", " Brothers"]
SUFFIX = {"GB": "Ltd", "IE": "Ltd", "NL": "B.V.", "LU": "S.A.", "CY": "Ltd", "MT": "Ltd", "AE": "FZE", "SG": "Pte Ltd",
          "HK": "Ltd", "DE": "GmbH", "CH": "AG", "VG": "Inc", "PA": "S.A.", "US": "LLC"}
CITIES = {"GB": ["London", "Manchester", "Leeds"], "IE": ["Dublin"], "NL": ["Amsterdam", "Rotterdam"], "LU": ["Luxembourg"],
          "CY": ["Limassol", "Nicosia"], "MT": ["Valletta"], "AE": ["Dubai"], "SG": ["Singapore"], "HK": ["Hong Kong"],
          "DE": ["Frankfurt", "Hamburg"], "CH": ["Zug"], "VG": ["Road Town"], "PA": ["Panama City"], "US": ["Wilmington"]}
JUR_WEIGHT = {"GB": 34, "IE": 6, "NL": 8, "LU": 5, "CY": 6, "MT": 3, "AE": 5, "SG": 6, "HK": 5, "DE": 9, "CH": 4, "VG": 4, "PA": 2, "US": 3}
NATIONALITY = ["GB", "IE", "NL", "DE", "FR", "ES", "IT", "PL", "IN", "CY", "GR", "SE", "PT", "RO", "US", "SG", "AE", "CH"]
STREETS = ["Alder Row", "Barley Walk", "Cinder Lane", "Dovecote Street", "Eskdale Road", "Fennel Court", "Gannet Way",
           "Hazel Mews", "Ironstone Place", "Jessamine Road", "Kittiwake Lane", "Larch Avenue", "Millrace Street", "Nutmeg Yard",
           "Osier Close", "Pebblebank Road", "Quillon Street", "Rushmere Way", "Saltings Lane", "Teasel Court"]
AGENT = "Suite 4, 18 Quayside Lane, London"
AGENT_VARIANTS = [AGENT, "Ste 4, 18 Quayside Ln, London", "Suite 4 18 Quayside Lane London"]


def pick(seq):
    return seq[rng.randrange(len(seq))]


def wpick(weights: dict):
    return rng.choices(list(weights), weights=list(weights.values()))[0]


def day(a: date, b: date) -> date:
    return a + timedelta(days=rng.randrange((b - a).days + 1))


def address(jur: str) -> str:
    return f"{rng.randint(1, 240)} {pick(STREETS)}, {pick(CITIES[jur])}"


# ---- Jaro-Winkler, the same rule the ownership_links Job applies (DuckDB jaro_winkler_similarity: prefix scale 0.1, up to 4) ----
def jaro(s: str, t: str) -> float:
    if s == t:
        return 1.0
    ls, lt = len(s), len(t)
    if not ls or not lt:
        return 0.0
    win = max(max(ls, lt) // 2 - 1, 0)
    sm, tm = [False] * ls, [False] * lt
    m = 0
    for i in range(ls):
        for j in range(max(0, i - win), min(lt, i + win + 1)):
            if not tm[j] and s[i] == t[j]:
                sm[i] = tm[j] = True
                m += 1
                break
    if not m:
        return 0.0
    k = trans = 0
    for i in range(ls):
        if sm[i]:
            while not tm[k]:
                k += 1
            if s[i] != t[k]:
                trans += 1
            k += 1
    return (m / ls + m / lt + (m - trans / 2) / m) / 3


def jaro_winkler(s: str, t: str) -> float:
    j = jaro(s, t)
    if j <= 0.7:
        return j
    p = 0
    while p < min(4, len(s), len(t)) and s[p] == t[p]:
        p += 1
    return j + p * 0.1 * (1 - j)


def norm(name: str) -> str:
    return " ".join(re.sub(r"[^a-z ]", "", name.lower()).split())


# ---- entity pools: ids shuffled so a story is not visible in the numbering ------------------------------------------------
company_ids = [f"CO-{i:05d}" for i in range(1, N_COMPANY + 1)]
person_ids = [f"PER-{i:05d}" for i in range(1, N_PERSON + 1)]
rng.shuffle(company_ids)
rng.shuffle(person_ids)
co_cursor = per_cursor = 0


def take_co(n: int) -> list[str]:
    global co_cursor
    out = company_ids[co_cursor:co_cursor + n]
    co_cursor += n
    return out


def take_per(n: int) -> list[str]:
    global per_cursor
    out = person_ids[per_cursor:per_cursor + n]
    per_cursor += n
    return out


companies: dict[str, dict] = {}
persons: dict[str, dict] = {}
ownership: list[tuple[str, str, float]] = []
officers: list[tuple[str, str, str]] = []
customers: dict[str, str] = {}     # customer_id -> company_id
cust_of: dict[str, str] = {}       # company_id -> customer_id
used_names: set[str] = set()


def co_name(jur: str) -> str:
    for tries in range(1000):
        n = f"{pick(CO_A)} {pick(CO_B)}{pick(CO_C)} {SUFFIX[jur]}" if tries < 20 else f"{pick(CO_A)} {pick(CO_B)} {tries} {SUFFIX[jur]}"
        if n not in used_names:
            used_names.add(n)
            return n


def new_company(cid: str, jur: str, inc: date | None = None, addr: str | None = None, name: str | None = None):
    companies[cid] = {"name": name or co_name(jur), "jurisdiction": jur, "incorporated_at": inc or day(date(1988, 1, 1), date(2024, 12, 31)),
                      "reg_address": addr if addr is not None else address(jur)}


def new_person(pid: str, name: str | None = None, birth_year: int | None = None, nat: str | None = None):
    persons[pid] = {"name": name or f"{pick(FIRST)} {pick(LAST)}", "birth_year": birth_year or rng.randint(1945, 2001),
                    "nationality": nat or pick(NATIONALITY)}


stories: dict[str, dict] = {}
reserved: set[str] = set()         # story and look-alike ids: background records never touch them

# ---- 1 HIDDEN OWNER ----------------------------------------------------------------------------------------------------
x = take_per(1)[0]
new_person(x, birth_year=1968, nat="GB")
a1, a2, a3, a4 = take_co(4)
vehicles = take_co(3)
targets = take_co(4)
for cid, jur in zip([a1, a2, a3, a4], ["CY", "VG", "AE", "CY"]):
    new_company(cid, jur, inc=day(date(2016, 1, 1), date(2019, 12, 31)))
for cid, jur in zip(vehicles, ["VG", "AE", "VG"]):
    new_company(cid, jur, inc=day(date(2019, 1, 1), date(2020, 12, 31)))
for cid in targets:
    new_company(cid, "GB", inc=day(date(2020, 6, 1), date(2022, 6, 30)))
chain = [x, a1, a2, a3, a4]
for up, down in zip(chain, chain[1:]):
    ownership.append((up, down, 24.0))
for v in vehicles:
    ownership.append((a4, v, 24.0))
for t in targets:
    for v, pct in zip(vehicles, [24.0, 24.0, 13.0]):
        ownership.append((v, t, pct))
    officers.append((t, take_per(1)[0], "director"))
hidden_directors = [o[1] for o in officers[-4:]]
for p in hidden_directors:
    new_person(p)

lc = take_co(1)[0]
new_company(lc, "GB", inc=date(1994, 3, 14), name="Northgate Consolidated plc")
lc_subs = take_co(4)
for s in lc_subs:
    new_company(s, "GB", inc=day(date(2002, 1, 1), date(2015, 12, 31)))
    ownership.append((lc, s, 61.0))
lc_holders = take_per(60)
for p in lc_holders:
    new_person(p)
    ownership.append((p, lc, round(rng.uniform(0.4, 1.9), 2)))
lc_dir = take_per(1)[0]
new_person(lc_dir)
officers.append((lc, lc_dir, "director"))
stories["hidden_owner"] = {"hub": x, "entities": chain + vehicles + targets, "lookalikes": [lc] + lc_subs,
                           "facts": {"chain_layers": 5, "jurisdictions": ["CY", "VG", "AE"], "stake": 24.0,
                                     "customer_share": 61.0, "customers": targets}}
reserved.update(chain + vehicles + targets + hidden_directors + [lc] + lc_subs + lc_holders + [lc_dir])

# ---- the INVENTED sanctions list ---------------------------------------------------------------------------------------
list_names: list[str] = []
seen = set()
while len(list_names) < N_LIST:
    n = f"{pick(LIST_FIRST_A)}{pick(LIST_FIRST_B)} {pick(LIST_LAST_A)}{pick(LIST_LAST_B)}"
    if n not in seen:
        seen.add(n)
        list_names.append(n)
sanctions: list[dict] = []
for i, n in enumerate(list_names, 1):
    first, last = n.split(" ")
    aliases = []
    if rng.random() < 0.55:
        aliases.append(f"{first[0]}. {last}")
    if rng.random() < 0.35:
        aliases.append(f"{first} {last.replace('ck', 'k').replace('v', 'w')}")
    sanctions.append({"list_id": f"SL-{i:04d}", "name": n, "aliases": "|".join(a for a in aliases if a != n),
                      "birth_year": rng.randint(1950, 1990), "nationality": pick(NATIONALITY),
                      "listed_at": day(date(2012, 1, 1), date(2025, 9, 30))})

# ---- 2 SANCTIONS TWO HOPS AWAY -----------------------------------------------------------------------------------------
listed = sanctions[136]
listed["name"], listed["aliases"], listed["birth_year"], listed["nationality"] = "Corvin Tammask", "C. Tammask", 1971, "CY"
p_s = take_per(1)[0]
new_person(p_s, name="Corvin Tamask", birth_year=1971, nat="CY")
s0, s1 = take_co(2)
new_company(s0, "GB", inc=date(2018, 5, 22))
new_company(s1, "CY", inc=date(2016, 11, 3))
ownership += [(s1, s0, 50.0), (p_s, s1, 60.0)]
s0_other = take_per(1)[0]
new_person(s0_other)
ownership.append((s0_other, s0, 50.0))
officers.append((s0, s0_other, "director"))
near = sanctions[288]
near["name"], near["aliases"], near["birth_year"], near["nationality"] = "Anselm Pellivane", "A. Pellivane", 1959, "MT"
q = take_per(1)[0]
new_person(q, name="Anselm Pelivane", birth_year=1983, nat="NL")
q_co = take_co(1)[0]
new_company(q_co, "NL", inc=date(2011, 2, 9))
ownership.append((q, q_co, 100.0))
officers.append((q_co, q, "director"))
stories["sanctions_two_hops"] = {"hub": listed["list_id"], "entities": [listed["list_id"], p_s, s1, s0], "lookalikes": [q, q_co],
                                 "facts": {"listed_name": listed["name"], "matched_person_name": persons[p_s]["name"],
                                           "near_namesake": persons[q]["name"], "near_namesake_list_id": near["list_id"],
                                           "owner_share": 50.0, "listed_share_of_owner": 60.0}}
reserved.update([p_s, s0, s1, s0_other, q, q_co])

# ---- 3 NOMINEE DIRECTOR ------------------------------------------------------------------------------------------------
n_dir = take_per(1)[0]
new_person(n_dir, birth_year=1979, nat="GB")
nominee_cos = take_co(120)
for i, cid in enumerate(nominee_cos):
    new_company(cid, "GB", inc=day(date(2023, 1, 1), date(2024, 12, 31)), addr=AGENT_VARIANTS[0 if i % 7 else (i // 7) % 3])
    officers.append((cid, n_dir, "director"))
acct = take_per(1)[0]
new_person(acct, name="Rosa Hartley", birth_year=1966, nat="GB")
family = take_co(4)
for cid in family:
    new_company(cid, "GB", inc=day(date(1999, 1, 1), date(2014, 12, 31)), name=f"Hartley {pick(CO_B)} Ltd")
    officers.append((cid, acct, "director"))
    owner = take_per(1)[0]
    new_person(owner, name=f"{pick(FIRST)} Hartley")
    ownership.append((owner, cid, 100.0))
    reserved.add(owner)
stories["nominee_director"] = {"hub": n_dir, "entities": [n_dir] + nominee_cos, "lookalikes": [acct] + family,
                               "facts": {"companies": 120, "agent_address": AGENT, "address_variants": AGENT_VARIANTS}}
reserved.update([n_dir, acct] + nominee_cos + family)

# ---- 4 CIRCULAR OWNERSHIP ----------------------------------------------------------------------------------------------
ca, cb, cc = take_co(3)
for cid, jur in zip([ca, cb, cc], ["GB", "NL", "LU"]):
    new_company(cid, jur, inc=day(date(2014, 1, 1), date(2017, 12, 31)))
ownership += [(ca, cb, 40.0), (cb, cc, 35.0), (cc, ca, 30.0)]
for cid in (ca, cb, cc):
    d = take_per(1)[0]
    new_person(d)
    officers.append((cid, d, "director"))
    reserved.add(d)
gp = take_co(1)
gp_subs = take_co(3)
new_company(gp[0], "GB", inc=date(2001, 7, 2))
for s in gp_subs:
    new_company(s, "GB", inc=day(date(2005, 1, 1), date(2012, 12, 31)))
    ownership.append((gp[0], s, 100.0))
founders = take_per(2)
for p in founders:
    new_person(p)
    ownership.append((p, gp[0], 50.0))
reserved.update([ca, cb, cc] + gp + gp_subs + founders)

# ---- background companies, people, stakes and officers -----------------------------------------------------------------
for cid in company_ids[co_cursor:]:
    new_company(cid, wpick(JUR_WEIGHT))
for pid in person_ids[per_cursor:]:
    new_person(pid)
bg_cos = company_ids[co_cursor:]
bg_pers = person_ids[per_cursor:]
holdcos = bg_cos[:260]             # only holdcos own companies, only later companies are owned by them: background stays acyclic
holdco_rank = {h: i for i, h in enumerate(holdcos)}
zipf_h = list(accumulate(1 / (i + 1) ** 0.9 for i in range(len(holdcos))))
pers_w = list(accumulate(1 / (i + 1) ** 0.35 for i in range(len(bg_pers))))
for i, cid in enumerate(bg_cos):
    k = 1 if rng.random() < 0.5 else 2
    split = [100.0] if k == 1 else pick([[50.0, 50.0], [60.0, 40.0], [75.0, 25.0], [51.0, 49.0], [80.0, 20.0]])
    owners: list[str] = []
    while len(owners) < k:
        if cid not in holdco_rank and rng.random() < 0.32:
            o = rng.choices(holdcos, cum_weights=zipf_h)[0]
        else:
            o = rng.choices(bg_pers, cum_weights=pers_w)[0]
        if o not in owners:
            owners.append(o)
    for o, pct in zip(owners, split):
        ownership.append((o, cid, pct))
dir_w = list(accumulate(1 / (i + 1) ** 0.5 for i in range(len(bg_pers))))
for cid in bg_cos:
    d = rng.choices(bg_pers, cum_weights=dir_w)[0]
    officers.append((cid, d, "director"))
    if rng.random() < 0.36:
        s = pick(bg_pers)
        if s != d:
            officers.append((cid, s, "secretary"))
# cap background directorships at 12 so no ordinary person looks like the nominee
per_dir: dict[str, int] = {}
kept = []
for o in officers:
    if o[1] in reserved or o[2] != "director":
        kept.append(o)
        continue
    per_dir[o[1]] = per_dir.get(o[1], 0) + 1
    if per_dir[o[1]] <= 12:
        kept.append(o)
    else:
        kept.append((o[0], pick(bg_pers[2000:]), "director"))
officers = kept

# a few spelling variants of people (one person registered twice) - identity resolution has work to do
dupes = rng.sample(bg_pers, 48)
for orig, twin in zip(dupes[:24], dupes[24:]):
    first, last = persons[orig]["name"].split(" ", 1)
    vowel = next((i for i in range(1, len(last)) if last[i] in "aeiou"), 1)
    last = last[:vowel] + {"a": "e", "e": "a", "i": "y", "o": "a", "u": "o"}.get(last[vowel], last[vowel]) + last[vowel + 1:]
    persons[twin] = dict(persons[orig], name=f"{first} {last}")
# realistic gaps: blank birth year / nationality / registered address on a few background records
for pid in rng.sample(bg_pers, 260):
    persons[pid][pick(["birth_year", "nationality"])] = ""
for cid in rng.sample(bg_cos, 300):
    companies[cid]["reg_address"] = ""

# ---- customers -----------------------------------------------------------------------------------------------------------
story_customers = targets + [s0] + nominee_cos[:8] + [ca, cb, cc] + lc_subs + [q_co] + family[:2] + gp_subs
cust_cos = story_customers + rng.sample([c for c in bg_cos if c not in holdco_rank], N_CUSTOMER - len(story_customers))
rng.shuffle(cust_cos)
onboard: dict[str, date] = {}
for i, cid in enumerate(cust_cos, 1):
    cu = f"CUS-{i:05d}"
    customers[cu] = cid
    cust_of[cid] = cu
    inc = companies[cid]["incorporated_at"]
    onboard[cu] = max(day(date(2012, 1, 1), date(2025, 9, 30)), inc + timedelta(days=30))
    if onboard[cu] > END:
        onboard[cu] = END - timedelta(days=rng.randrange(1, 60))
staged_customers = {}
for j, cid in enumerate(rng.sample([c for c in bg_cos if c not in cust_of and c not in holdco_rank], 8), N_CUSTOMER + 1):
    staged_customers[f"CUS-{j:05d}"] = cid
stories["hidden_owner"]["entities"] += [cust_of[t] for t in targets]
stories["hidden_owner"]["lookalikes"] += [cust_of[s] for s in lc_subs]
stories["sanctions_two_hops"]["entities"].append(cust_of[s0])
stories["sanctions_two_hops"]["lookalikes"].append(cust_of[q_co])
stories["sanctions_two_hops"]["facts"]["customer"] = cust_of[s0]
stories["nominee_director"]["entities"] += [cust_of[c] for c in nominee_cos[:8]]
stories["nominee_director"]["lookalikes"] += [cust_of[c] for c in family[:2]]
stories["circular_ownership"] = {"hub": ca, "entities": [ca, cb, cc] + [cust_of[c] for c in (ca, cb, cc)],
                                 "lookalikes": gp + gp_subs + [cust_of[s] for s in gp_subs],
                                 "facts": {"cycle": [ca, cb, cc], "stakes": [40.0, 35.0, 30.0], "monthly_round_trip": True}}
reserved.update(cust_of[c] for c in story_customers)

# ---- payments: business days and hours, heavy-tailed payers and payees -------------------------------------------------
payees = [c for c in bg_cos if c not in reserved][:900]
payee_w = list(accumulate(1 / (i + 1) ** 0.8 for i in range(len(payees))))
payers = [cu for cu, cid in customers.items()]
payer_w = dict(zip(payers, (rng.paretovariate(1.3) for _ in payers)))


def pay_time(a: date, b: date) -> datetime:
    while True:
        d = day(a, b)
        if d.weekday() < 5 or rng.random() < 0.12:
            return datetime(d.year, d.month, d.day, rng.choice([8, 9, 9, 10, 10, 11, 12, 13, 14, 14, 15, 16, 17, 19]),
                            rng.randrange(60), rng.randrange(60))


def month_starts(a: date, b: date):
    m = date(a.year, a.month, 1)
    while m <= b:
        nxt = date(m.year + (m.month == 12), m.month % 12 + 1, 1)
        yield m, nxt - timedelta(days=1)
        m = nxt


payments: list[tuple] = []


def gen_payments(a: date, b: date, n: int, cust_pool: dict):
    pool = [cu for cu in payers if cu in cust_pool and onboard.get(cu, a) <= b]
    w = list(accumulate(payer_w[cu] for cu in pool))
    out = []
    for _ in range(n):
        cu = rng.choices(pool, cum_weights=w)[0]
        to = rng.choices(payees, cum_weights=payee_w)[0]
        if to == customers[cu]:
            continue
        amt = round(min(rng.lognormvariate(7.8, 1.25), 950000.0), 2)
        out.append((cu, to, amt, max(pay_time(a, b), datetime.combine(onboard.get(cu, a), datetime.min.time()))))
    return out


circle = [(ca, cb), (cb, cc), (cc, ca)]
for m0, m1 in month_starts(START, STAGED_END):
    n = N_PAYMENT // 12
    payments += gen_payments(m0, m1, n, customers)
    base = round(rng.uniform(240000, 260000), 2)
    for k, (frm, to) in enumerate(circle):
        payments.append((cust_of[frm], to, round(base * (1 - 0.004 * k), 2), datetime(m0.year, m0.month, 3 + k * 2, 10, 15, 0)))
    for s in gp_subs:
        payments.append((cust_of[s], gp[0], 18500.00, datetime(m0.year, m0.month, 1, 9, 0, 0) + timedelta(days=(4 - m0.weekday()) % 7 if m0.weekday() > 4 else 0)))
payments.sort(key=lambda p: (p[3], p[0], p[1]))

# ---- self-check: the name_match rule finds exactly the planted pair ----------------------------------------------------
matches = []
by_key: dict[tuple, list] = {}
for s in sanctions:
    by_key.setdefault((str(s["birth_year"]), s["nationality"]), []).append(s)
for pid, p in persons.items():
    for s in by_key.get((str(p["birth_year"]), p["nationality"]), []):
        cand = [s["name"]] + [a for a in s["aliases"].split("|") if a]
        score = max(jaro_winkler(norm(p["name"]), norm(c)) for c in cand)
        if score >= MIN_NAME_SCORE:
            matches.append((pid, s["list_id"], round(score, 3)))
assert matches == [(p_s, listed["list_id"], matches[0][2] if matches else 0)], f"name_match must find only the planted pair: {matches}"
near_score = jaro_winkler(norm(persons[q]["name"]), norm(near["name"]))
assert near_score >= MIN_NAME_SCORE, near_score
stories["sanctions_two_hops"]["facts"]["match_score"] = matches[0][2]
stories["sanctions_two_hops"]["facts"]["near_namesake_score"] = round(near_score, 3)


# ---- write -----------------------------------------------------------------------------------------------------------------
def write(rel: str, header: list[str], rows):
    p = DATA / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    with out.open(p) as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)


def iso(v):
    return v.isoformat() if isinstance(v, date) and not isinstance(v, datetime) else v.strftime("%Y-%m-%d %H:%M:%S")


out = Outputs()
write("inbox/company/COMPANY_20260930.csv", ["company_id", "name", "jurisdiction", "incorporated_at", "reg_address"],
      [(c, v["name"], v["jurisdiction"], iso(v["incorporated_at"]), v["reg_address"]) for c, v in sorted(companies.items())])
write("inbox/person/PERSON_20260930.csv", ["person_id", "name", "birth_year", "nationality"],
      [(p, v["name"], v["birth_year"], v["nationality"]) for p, v in sorted(persons.items())])
write("inbox/ownership/OWNERSHIP_20260930.csv", ["owner_id", "owned_id", "percent"],
      [(o, d, f"{pct:.2f}") for o, d, pct in sorted(ownership)])
write("inbox/officer/OFFICER_20260930.csv", ["company_id", "person_id", "role"], sorted(set(officers)))
write("inbox/customer/CUSTOMER_20260930.csv", ["customer_id", "company_id", "onboarded_at"],
      [(cu, cid, iso(onboard[cu])) for cu, cid in sorted(customers.items())])
write("staged/customer/CUSTOMER_20261031.csv", ["customer_id", "company_id", "onboarded_at"],
      [(cu, cid, iso(day(date(2026, 10, 1), date(2026, 10, 31)))) for cu, cid in sorted(staged_customers.items())])
write("inbox/sanctions_list/SANCTIONS_LIST_20260930.csv", ["list_id", "name", "aliases", "birth_year", "nationality", "listed_at"],
      [(s["list_id"], s["name"], s["aliases"], s["birth_year"], s["nationality"], iso(s["listed_at"])) for s in sanctions])
seq = 0
for m0, m1 in month_starts(START, STAGED_END):
    rows = []
    for cu, to, amt, at in payments:
        if m0 <= at.date() <= m1:
            seq += 1
            rows.append((f"PAY-{seq:06d}", cu, to, f"{amt:.2f}", iso(at)))
    where = "staged" if m0 > END else "inbox"
    write(f"{where}/payment/PAYMENT_{m0:%Y%m}.csv", ["payment_id", "from_customer", "to_company", "amount", "paid_at"], rows)

for s in stories.values():
    s["entities"] = sorted(set(s["entities"]))
    s["lookalikes"] = sorted(set(s["lookalikes"]))
out.write_text(TRUTH, json.dumps({"note": "Story -> the entity ids that must form ONE connected group in the ownership_links Dataset, and "
                                          "look-alike ids that must have no link to any of them. Written by tools/gen-la-ownership-data.py; "
                                          "not part of the data download.", "seed": SEED, "min_name_score": MIN_NAME_SCORE,
                                  "stories": stories}, indent=1, sort_keys=True) + "\n")
out.prune(DATA, "*/*/*.csv")
print(f"companies {len(companies)}, persons {len(persons)}, ownership {len(ownership)}, officers {len(officers)}, "
      f"customers {len(customers)}+{len(staged_customers)} staged, payments {seq}, list {len(sanctions)}, name_match {matches}")
