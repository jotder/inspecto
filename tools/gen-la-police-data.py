#!/usr/bin/env python3
"""Regenerate the `la-police` Space Template's criminal-investigation corpus - SYNTHETIC, deterministic (one seeded Random).

Writes spaces/_templates/la-police/data/inbox/<feed>/<FEED>_20261031.csv (12 months, 2025-11-01 .. 2026-10-31) and, for the
event feeds, data/staged/<feed>/<FEED>_20261130.csv (the next month, for the "new data arrives" step), eleven feeds:
  incident_report, person, incident_person, vehicle, plate_read, call_record, handset, custody, dealer_sale, property_stolen, address
plus tools/la-police-ground-truth.json (story -> entity ids; NOT shipped in the data download).

Planted stories (plan: docs/superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md, la-police; pinned by
tools/gen-la-police-data.test.mjs on the files and LaPoliceLinksJobTest on the link Job's output):
  1 BURGLARY SERIES   18 burglaries in Feb-Apr 2026 across Leeds North and Leeds East; 40 % of the stolen items resurface at
                      DLR-04 and DLR-09 through 3 sellers, the serial matches on 60 % of those; a vehicle kept by one seller's
                      partner (same address) is read by cameras within 2 km and 30 minutes of 11 of the 18 scenes; one more
                      serial match arrives in the staged month
  2 DRUG SUPPLY       one controller number per period, 5 periods of 3-5 weeks (Mar-Jul 2026), 4 runner numbers, 60 customer
                      numbers; at least 70 % of a controller's customers carry over to the next controller number
  3 DRUG BASES        3 addresses whose lone, vulnerable residents (no record) receive frequent calls from the runner numbers
  4 WITNESS           one person is a witness in 2 unrelated incidents and was co-arrested with the suspect of one of them
Benign look-alikes that must NOT join a story: a taxi seen near 30+ scenes all year (none of the series), a delivery
driver's number with many short calls (no carry-over, no network number), a busy shared house (11 residents, many calls,
no runner calls) and a shopkeeper who is a genuine witness in 6 incidents (no custody).
Every name, id, number, plate, serial and coordinate is invented; cities are real only at city level, phone numbers are in
the UK's reserved drama ranges (07700 900xxx, 0113/0114/0115/0116/0161 496 0xxx). Nothing is taken from real records.
"""
from __future__ import annotations

import csv
import json
import math
import random
from datetime import datetime, timedelta
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
DATA = TOOLS.parent / "spaces" / "_templates" / "la-police" / "data"
TRUTH = TOOLS / "la-police-ground-truth.json"
rng = random.Random(20261107)
START, CUT, END = datetime(2025, 11, 1), datetime(2026, 11, 1), datetime(2026, 12, 1)   # rows at or after CUT are staged
INBOX_DAY, STAGED_DAY = "20261031", "20261130"
N_PERSONS, N_ADDRESSES, N_VEHICLES, N_HANDSETS = 6000, 3000, 2500, 500
N_INCIDENTS, N_READS, N_CALLS, N_SALES = 4330, 43300, 32500, 1620          # 13 months; about 12/13 land in the inbox
NEAR_M, NEAR_MIN = 2000, 30                                                   # seen_near: within 2 km and 30 minutes

FIRST = ["Alma", "Bruno", "Carys", "Dmitri", "Elsie", "Farid", "Greta", "Hamza", "Ines", "Jonas", "Katya", "Leon", "Mira",
         "Nils", "Orla", "Pavel", "Quinn", "Rosa", "Silas", "Tilda", "Umar", "Vera", "Wren", "Xavi", "Yara", "Zeke", "Ada",
         "Bram", "Cleo", "Dara", "Emil", "Fern", "Gil", "Hana", "Ivo", "Juno", "Kit", "Lior", "Maya", "Noor"]
LAST = ["Ashdown", "Belcourt", "Corrigan", "Dunmore", "Eastwick", "Farrow", "Galloway", "Hartnell", "Ivanek", "Jarrow",
        "Kessler", "Lockhart", "Marlowe", "Norcott", "Okafor", "Pemberton", "Quayle", "Redmayne", "Sandoval", "Thackeray",
        "Underhill", "Vance", "Whitlock", "Yardley", "Zielinski", "Brightwell", "Cardew", "Dalby", "Elswick", "Fennimore",
        "Gorrell", "Hollins", "Inchbold", "Jessop", "Kirkham", "Lumley", "Mossop", "Nuttall", "Oddie", "Pickersgill"]
STREETS = ["Alder", "Birch", "Cedar", "Dunlin", "Elm", "Fenwick", "Garth", "Heron", "Ivy", "Juniper", "Kestrel", "Linden",
           "Marsh", "Nettle", "Osier", "Plover", "Quarry", "Rowan", "Sorrel", "Teasel", "Upton", "Vetch", "Willow", "Yarrow",
           "Aspen", "Bracken", "Coltsfoot", "Dogrose", "Eyebright", "Foxglove", "Gorse", "Hazel", "Iris", "Larch", "Medlar",
           "Nightjar", "Oriole", "Pipit", "Redwing", "Sedge"]
SUFFIX = ["Road", "Street", "Lane", "Close", "Way", "Grove", "Terrace", "Row", "Crescent", "Mews"]
MAKES = ["Arlen", "Borro", "Castell", "Deltran", "Everly", "Fenix", "Gallant", "Harrow"]       # invented makes
COLOURS = ["black", "white", "silver", "grey", "blue", "red", "green", "bronze"]
CITY = {"Leeds": (53.8008, -1.5491), "Sheffield": (53.3811, -1.4701), "Bradford": (53.7960, -1.7594),
        "Wakefield": (53.6833, -1.4977)}
SIDES = {"North": (0.024, 0.0), "East": (0.0, 0.040), "South": (-0.024, 0.0), "West": (0.0, -0.040)}
DISTRICTS = {f"{c} {s}": (CITY[c][0] + d[0], CITY[c][1] + d[1]) for c in CITY for s, d in SIDES.items()}
SERIES_DISTRICTS = ["Leeds North", "Leeds East"]
SERIES_DEALERS = ["DLR-04", "DLR-09"]
DEALERS = [f"DLR-{i:02d}" for i in range(1, 13)]
OFFENCES = {"theft": 28, "burglary": 14, "assault": 14, "criminal_damage": 12, "vehicle_crime": 10,
            "anti_social_behaviour": 8, "drugs": 6, "fraud": 5, "public_order": 3}
DURATION_MIN = {"theft": (5, 40), "burglary": (60, 420), "assault": (5, 30), "criminal_damage": (10, 240),
                "vehicle_crime": (30, 480), "anti_social_behaviour": (10, 60), "drugs": (5, 20), "fraud": (1, 1),
                "public_order": (10, 60)}
SUSPECT_P = {"theft": .25, "burglary": .12, "assault": .6, "criminal_damage": .2, "vehicle_crime": .1,
             "anti_social_behaviour": .4, "drugs": .9, "fraud": .3, "public_order": .8}
NO_VICTIM = {"drugs", "public_order"}
ITEMS = {"laptop": .95, "mobile phone": .95, "games console": .9, "camera": .9, "power drill": .7, "tablet": .95,
         "television": .9, "headphones": .5, "watch": .4, "bicycle": .5, "jewellery": 0.0, "guitar": .6, "sat-nav": .9}
HOUR_EVENTS = [2, 1.5, 1, .8, .6, .6, .8, 1.2, 1.6, 1.8, 2, 2.2, 2.4, 2.4, 2.4, 2.6, 2.8, 3, 3.2, 3.2, 3, 2.8, 2.6, 2.2]
HOUR_TRAFFIC = [.3, .2, .1, .1, .2, .6, 1.6, 3, 3.2, 2.2, 1.8, 1.9, 2, 1.9, 1.9, 2.2, 3, 3.2, 2.4, 1.6, 1.2, .9, .6, .4]
HOUR_CALLS = [.2, .1, .05, .05, .05, .1, .4, 1, 1.6, 2, 2.2, 2.2, 2.4, 2.2, 2, 2, 2.2, 2.6, 2.8, 2.6, 2.2, 1.6, 1, .5]
WEEKDAY = [1, 1, 1, 1.05, 1.25, 1.3, 1.1]                                      # Monday .. Sunday


def pick(seq):
    return seq[rng.randrange(len(seq))]


def fmt(dt):
    return dt.strftime("%Y-%m-%d %H:%M:%S")


def metres(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    a = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(a))


def offset(lat, lon, m, bearing):
    """The point m metres from (lat, lon) towards bearing (radians) - good enough at these distances."""
    return lat + m * math.cos(bearing) / 111_320, lon + m * math.sin(bearing) / (111_320 * math.cos(math.radians(lat)))


def moment(lo, hi, hours):
    """A random second in [lo, hi) with a weekly and a daily cycle."""
    days = (hi - lo).days
    while True:
        d = lo + timedelta(days=rng.randrange(days))
        if rng.random() * 1.3 <= WEEKDAY[d.weekday()]:
            break
    h = rng.choices(range(24), weights=hours)[0]
    return d.replace(hour=h, minute=rng.randrange(60), second=rng.randrange(60))


def in_district(name, spread=1.0):
    lat, lon = DISTRICTS[name]
    return lat + rng.uniform(-.018, .018) * spread, lon + rng.uniform(-.028, .028) * spread


# ---- addresses ---------------------------------------------------------------------------------------------------------------
addresses, seen_streets = [], set()
for i in range(N_ADDRESSES):
    district = pick(list(DISTRICTS))
    while True:
        street = f"{rng.randint(1, 180)} {pick(STREETS)} {pick(SUFFIX)}"
        if (street, district) not in seen_streets:
            seen_streets.add((street, district))
            break
    lat, lon = in_district(district)
    addresses.append({"id": f"ADR-{i + 1:04d}", "street": street, "district": district, "city": district.split()[0],
                      "lat": lat, "lon": lon})
rng.shuffle(addresses)
addr_by_id = {a["id"]: a for a in addresses}
leeds_north = [a for a in addresses if a["district"] == "Leeds North"]
SELLER_HOME, (SHARED_HOUSE,) = leeds_north[0]["id"], [a["id"] for a in addresses if a["district"] == "Sheffield East"][:1]
BASES = [a["id"] for a in addresses if a["district"] in ("Leeds West", "Leeds South", "Bradford South")][:3]
reserved_addr = {SELLER_HOME, SHARED_HOUSE, *BASES}

# ---- persons -----------------------------------------------------------------------------------------------------------------
ids = [f"P-{i:05d}" for i in range(1, N_PERSONS + 1)]
rng.shuffle(ids)                                   # roles are NOT in id order, so a story is not visible in the numbering
cursor = 0


def take(n):
    global cursor
    out = ids[cursor:cursor + n]
    cursor += n
    return out


SELLERS, (PARTNER,), (TAXI_DRIVER,), (WITNESS,), (SUSPECT,), (SHOPKEEPER,), (DRIVER,) = (take(3), take(1), take(1), take(1),
                                                                                       take(1), take(1), take(1))
BASE_RESIDENTS, HOUSEMATES, RUNNER_HOLDERS = take(3), take(11), take(2)
STORY_PERSONS = set(ids[:cursor])
BG = ids[cursor:]
used_names = set()


def fresh_name():
    while True:
        n = f"{pick(FIRST)} {chr(65 + rng.randrange(26))}. {pick(LAST)}"
        if n not in used_names:
            used_names.add(n)
            return n


persons = {}
for pid in ids:
    persons[pid] = {"id": pid, "name": fresh_name(), "birth_year": rng.randint(1948, 2007), "address": "", "flags": set()}

# households: most addresses hold 1-3 people, a few many more (heavy tail); the story homes are filled explicitly
persons[SELLERS[0]]["address"] = persons[PARTNER]["address"] = SELLER_HOME
persons[SELLERS[0]]["birth_year"], persons[PARTNER]["birth_year"] = 1991, 1993
for r, a in zip(BASE_RESIDENTS, BASES):
    persons[r]["address"], persons[r]["birth_year"] = a, rng.randint(1949, 1962)
    persons[r]["flags"].add("vulnerable")
for h in HOUSEMATES:
    persons[h]["address"], persons[h]["birth_year"] = SHARED_HOUSE, rng.randint(1996, 2005)
leeds_addr = [a["id"] for a in addresses if a["city"] == "Leeds" and a["id"] not in reserved_addr]
sheff_addr = [a["id"] for a in addresses if a["city"] == "Sheffield" and a["id"] not in reserved_addr]
brad_addr = [a["id"] for a in addresses if a["city"] == "Bradford" and a["id"] not in reserved_addr]
persons[SELLERS[1]]["address"], persons[SELLERS[2]]["address"] = leeds_addr[0], leeds_addr[1]
persons[TAXI_DRIVER]["address"], persons[WITNESS]["address"], persons[SUSPECT]["address"] = leeds_addr[2], leeds_addr[3], leeds_addr[4]
persons[SHOPKEEPER]["address"], persons[DRIVER]["address"] = brad_addr[0], leeds_addr[5]
for r in RUNNER_HOLDERS:
    persons[r]["address"] = pick(leeds_addr[6:40])
taken_addr = {p["address"] for p in persons.values() if p["address"]}
free_addr = [a["id"] for a in addresses if a["id"] not in taken_addr]
SIZES = [1] * 38 + [2] * 30 + [3] * 16 + [4] * 10 + [5] * 4 + [6, 8]
queue = list(BG)
for a in free_addr:
    for _ in range(pick(SIZES)):
        if queue:
            persons[queue.pop()]["address"] = a
for pid in queue:                                    # anyone left lands at a random ordinary address
    persons[pid]["address"] = pick(free_addr)
for pid in BG:
    if rng.random() < .04:
        persons[pid]["address"] = ""                 # no fixed address recorded
    if rng.random() < .04:
        persons[pid]["birth_year"] = ""
    if rng.random() < .012:
        persons[pid]["flags"].add("vulnerable")
# spelling variants: a second record of the same person (same birth year and address, one letter different)
for pid in BG[-25:]:
    src = persons[pick(BG[:3000])]
    name = src["name"]
    j = rng.randrange(len(name))
    while not name[j].isalpha() or j == 0:
        j = rng.randrange(len(name))
    persons[pid].update(name=name[:j] + ("e" if name[j] != "e" else "a") + name[j + 1:], birth_year=src["birth_year"],
                        address=src["address"])
city_of = {pid: (addr_by_id[p["address"]]["city"] if p["address"] else "") for pid, p in persons.items()}
by_city = {c: [pid for pid in BG if city_of[pid] == c] for c in CITY}

# offenders: a pool with Zipf-like weights (a few prolific), plus the planted suspect and runner holders
OFFENDERS = [pid for pid in BG if persons[pid]["address"] and "vulnerable" not in persons[pid]["flags"]][:600]
OFF_W = [1 / (i + 1) ** .8 for i in range(len(OFFENDERS))]
off_by_city = {c: [(o, w) for o, w in zip(OFFENDERS, OFF_W) if city_of[o] == c] for c in CITY}
offender_set = set(OFFENDERS) | {SUSPECT, *RUNNER_HOLDERS}


def offender(city):
    pool = off_by_city.get(city) if rng.random() < .8 else None
    pool = pool or list(zip(OFFENDERS, OFF_W))
    return rng.choices([o for o, _ in pool], weights=[w for _, w in pool])[0]


witnessed = set()
witness_pool = {c: [pid for pid in by_city[c] if pid not in offender_set] for c in CITY}


def witness(city):
    if witnessed and rng.random() < .03:                                         # a rare genuine repeat witness
        same = sorted(w for w in witnessed if city_of[w] == city)
        if same:
            return pick(same)
    pool = witness_pool[city]
    while True:
        w = pool[rng.randrange(len(pool))]
        if w not in witnessed:
            witnessed.add(w)
            return w


def resident(city):
    return pick(by_city[city])


# ---- cameras: on main roads, 12 per city ------------------------------------------------------------------------------------
cameras = []
for c in CITY:
    for _ in range(12):
        lat, lon = CITY[c]
        cameras.append({"city": c, "lat": round(lat + rng.uniform(-.045, .045), 5), "lon": round(lon + rng.uniform(-.07, .07), 5)})
rng.shuffle(cameras)
for i, cam in enumerate(cameras):
    cam["id"] = f"CAM-{i + 1:02d}"
cams_by_city = {c: [cam for cam in cameras if cam["city"] == c] for c in CITY}

# ---- incidents ---------------------------------------------------------------------------------------------------------------
incidents = []        # dicts: offence, start, end, district, lat, lon, reporter, people [(pid, role)]


def new_incident(offence, start, district, lat=None, lon=None, minutes=None):
    if lat is None:
        lat, lon = in_district(district)
    lat, lon = round(lat, 5), round(lon, 5)                  # as written, so every distance here equals the SQL's
    lo, hi = DURATION_MIN[offence]
    inc = {"offence": offence, "start": start, "end": start + timedelta(minutes=minutes or rng.randint(lo, hi)),
           "district": district, "city": district.split()[0], "lat": lat, "lon": lon, "reporter": "", "people": []}
    incidents.append(inc)
    return inc


def add_people(inc, victim=True, witnesses=None, suspects=None):
    city = inc["city"]
    if victim and inc["offence"] not in NO_VICTIM:
        v = resident(city) if rng.random() < .85 else pick(BG)
        inc["reporter"] = v
        inc["people"].append((v, "victim"))
    n_w = witnesses if witnesses is not None else rng.choices([0, 1, 2, 3], weights=[40, 40, 15, 5])[0]
    for _ in range(n_w):
        inc["people"].append((witness(city), "witness"))
    if suspects is None:
        suspects = (2 if rng.random() < .15 else 1) if rng.random() < SUSPECT_P[inc["offence"]] else 0
    for _ in range(suspects):
        s = offender(city)
        if all(s != p for p, _ in inc["people"]):
            inc["people"].append((s, "suspect"))


# 1 BURGLARY SERIES: scenes placed 600-1700 m from a camera on a main road in the two districts
SERIES_START, SERIES_END = datetime(2026, 2, 2), datetime(2026, 5, 1)
series = []
series_cams = [cam for cam in cams_by_city["Leeds"]
               if min(metres(cam["lat"], cam["lon"], *DISTRICTS[d]) for d in SERIES_DISTRICTS) < 3500]
assert len(series_cams) >= 3, "the series districts need cameras"
for k in range(18):
    district = SERIES_DISTRICTS[k % 2]
    while True:
        cam = pick(series_cams)
        lat, lon = offset(cam["lat"], cam["lon"], rng.uniform(600, 1700), rng.uniform(0, 2 * math.pi))
        if metres(lat, lon, *DISTRICTS[district]) < 3000:
            break
    day = SERIES_START + timedelta(days=round(k * 82 / 17) + rng.randint(0, 2))
    while day.weekday() > 4:
        day += timedelta(days=1)
    start = day.replace(hour=rng.randint(9, 13), minute=rng.randrange(60), second=rng.randrange(60))
    inc = new_incident("burglary", start, district, lat, lon, minutes=rng.randint(70, 240))
    inc["camera"] = cam
    add_people(inc, witnesses=rng.choice([0, 0, 1]), suspects=0)
    series.append(inc)
VEHICLE_SCENES = sorted(rng.sample(range(18), 11))

# 4 WITNESS linked to the suspect: an assault (suspect + witness) and an unrelated theft months later in another city
inc_a = new_incident("assault", datetime(2026, 5, 14, 22, 41, 7), "Leeds West")
add_people(inc_a, witnesses=1, suspects=0)
inc_a["people"] += [(WITNESS, "witness"), (SUSPECT, "suspect")]
inc_b = new_incident("theft", datetime(2026, 8, 21, 14, 12, 40), "Sheffield South")
add_people(inc_b, witnesses=0, suspects=0)
inc_b["people"].append((WITNESS, "witness"))
witnessed.add(WITNESS)

# look-alike: a shopkeeper, a genuine witness to 6 street incidents outside the shop over the year
shop = in_district("Bradford East", .3)
shop_incidents = []
for k, off in enumerate(["theft", "criminal_damage", "assault", "theft", "public_order", "theft"]):
    start = moment(START + timedelta(days=k * 58), START + timedelta(days=k * 58 + 50), HOUR_EVENTS)
    inc = new_incident(off, start, "Bradford East", shop[0] + rng.uniform(-.0006, .0006), shop[1] + rng.uniform(-.0009, .0009))
    add_people(inc, witnesses=0)
    inc["people"].append((SHOPKEEPER, "witness"))
    shop_incidents.append(inc)
witnessed.add(SHOPKEEPER)

# background
offence_names, offence_w = list(OFFENCES), list(OFFENCES.values())
while len(incidents) < N_INCIDENTS:
    off = rng.choices(offence_names, weights=offence_w)[0]
    inc = new_incident(off, moment(START, END, HOUR_EVENTS), pick(list(DISTRICTS)))
    add_people(inc)
incidents.sort(key=lambda i: (i["start"], i["lat"]))
for n, inc in enumerate(incidents):
    inc["id"] = f"INC-{n + 1:05d}"

# ---- custody: suspects arrested (co-suspects together), warrants; the witness and the suspect arrested together -----------
custody, groups = [], [0]


def arrest(people, at):
    groups[0] += 1
    g = f"CAG-{groups[0]:04d}"
    for p in people:
        custody.append({"person": p, "at": at, "group": g})


for inc in incidents:
    sus = [p for p, r in inc["people"] if r == "suspect" and p != SUSPECT]
    if sus and rng.random() < .55:
        at = inc["start"] + timedelta(minutes=rng.randint(40, 60 * 24 * 10))
        arrest(sus if rng.random() < .8 else sus[:1], at)
while len(custody) < 1180:
    at = moment(START, END, HOUR_EVENTS)
    city = pick(list(CITY))
    people = [offender(city) for _ in range(rng.choices([1, 2, 3], weights=[78, 17, 5])[0])]
    arrest(sorted(set(people)), at)
arrest(sorted([WITNESS, SUSPECT]), datetime(2026, 2, 11, 1, 23, 51))
for r in RUNNER_HOLDERS:
    arrest([r], moment(START, datetime(2026, 2, 1), HOUR_EVENTS))
WITNESS_GROUP = f"CAG-{groups[0] - 2:04d}"
custody.sort(key=lambda c: (c["at"], c["group"], c["person"]))
for n, c in enumerate(custody):
    c["id"] = f"CUS-{n + 1:05d}"
for c in custody:
    persons[c["person"]]["flags"].add("prior_record")

# the witness story is unique: nobody else witnessed an incident whose suspect they were co-arrested with
co = {}
for c in custody:
    co.setdefault(c["group"], set()).add(c["person"])
partners = {}
for members in co.values():
    for p in members:
        partners.setdefault(p, set()).update(members - {p})
lookalikes = [(inc["id"], p) for inc in incidents for p, r in inc["people"] if r == "witness"
              and partners.get(p, set()) & {s for s, rr in inc["people"] if rr == "suspect"}]
assert lookalikes == [(inc_a["id"], WITNESS)], lookalikes
assert SHOPKEEPER not in partners and not any(c["person"] == SHOPKEEPER for c in custody)

# ---- vehicles ----------------------------------------------------------------------------------------------------------------
plates, vehicles = set(), []


def fresh_plate():
    while True:
        p = f"ZX {rng.randrange(10000):04d} {chr(65 + rng.randrange(26))}"
        if p not in plates:
            plates.add(p)
            return p


adults = [pid for pid in BG if persons[pid]["address"] and (persons[pid]["birth_year"] or 1980) <= 2006]
SERIES_PLATE, TAXI_PLATE = fresh_plate(), fresh_plate()
vehicles.append({"plate": SERIES_PLATE, "make": "Castell", "colour": "silver", "keeper": PARTNER})
vehicles.append({"plate": TAXI_PLATE, "make": "Everly", "colour": "black", "keeper": TAXI_DRIVER})
while len(vehicles) < N_VEHICLES:
    vehicles.append({"plate": fresh_plate(), "make": pick(MAKES), "colour": pick(COLOURS),
                     "keeper": "" if rng.random() < .04 else pick(adults)})
rng.shuffle(vehicles)

# ---- plate reads ---------------------------------------------------------------------------------------------------------------
by_day = {}
for inc in incidents:
    d = (inc["start"] - timedelta(minutes=NEAR_MIN)).date()
    while d <= (inc["end"] + timedelta(minutes=NEAR_MIN)).date():
        by_day.setdefault(d, []).append(inc)
        d += timedelta(days=1)


def near(read):
    """The incidents a read is 'seen near': within NEAR_M metres and NEAR_MIN minutes of the incident's window."""
    out = []
    for inc in by_day.get(read["at"].date(), []):
        if (inc["start"] - timedelta(minutes=NEAR_MIN) <= read["at"] <= inc["end"] + timedelta(minutes=NEAR_MIN)
                and metres(read["lat"], read["lon"], inc["lat"], inc["lon"]) <= NEAR_M):
            out.append(inc)
    return out


def read(plate, cam, at):
    return {"plate": plate, "camera": cam["id"], "at": at, "lat": cam["lat"], "lon": cam["lon"]}


series_ids = {inc["id"] for inc in series}
reads = []
# the series vehicle: one read per chosen scene, inside the scene's window at the scene's camera (600-1700 m away) ...
for k in VEHICLE_SCENES:
    inc = series[k]
    span = int((inc["end"] - inc["start"]).total_seconds())
    reads.append(read(SERIES_PLATE, inc["camera"], inc["start"] + timedelta(seconds=rng.randrange(-1200, span + 1200))))
# ... and ordinary driving around Leeds that never passes the other seven scenes
chosen = {series[k]["id"] for k in VEHICLE_SCENES}
while sum(r["plate"] == SERIES_PLATE for r in reads) < 11 + 34:
    r = read(SERIES_PLATE, pick(cams_by_city["Leeds"]), moment(START, END, HOUR_TRAFFIC))
    if not {i["id"] for i in near(r)} & (series_ids - chosen):
        reads.append(r)
# the taxi: all year, all over Leeds, never near a series scene
while sum(r["plate"] == TAXI_PLATE for r in reads) < 1500:
    r = read(TAXI_PLATE, pick(cams_by_city["Leeds"]), moment(START, END, HOUR_TRAFFIC))
    if not {i["id"] for i in near(r)} & series_ids:
        reads.append(r)
home_city = {v["plate"]: (city_of[v["keeper"]] if v["keeper"] else pick(list(CITY))) for v in vehicles}
bg_plates = [v["plate"] for v in vehicles if v["plate"] not in (SERIES_PLATE, TAXI_PLATE)]
bg_w = [rng.paretovariate(1.2) for _ in bg_plates]
cum = []
for w in bg_w:
    cum.append((cum[-1] if cum else 0) + w)
while len(reads) < N_READS:
    plate = rng.choices(bg_plates, cum_weights=cum)[0]
    city = home_city[plate] if rng.random() < .85 else pick(list(CITY))
    reads.append(read(plate, pick(cams_by_city[city]), moment(START, END, HOUR_TRAFFIC)))
reads.sort(key=lambda r: (r["at"], r["camera"], r["plate"]))
series_seen = sorted({i["id"] for r in reads if r["plate"] == SERIES_PLATE for i in near(r)} & series_ids)
taxi_seen = sorted({i["id"] for r in reads if r["plate"] == TAXI_PLATE for i in near(r)})
taxi_seen_inbox = sorted({i["id"] for r in reads if r["plate"] == TAXI_PLATE and r["at"] < CUT for i in near(r) if i["start"] < CUT})
assert series_seen == sorted(chosen), series_seen
assert len(taxi_seen_inbox) >= 30 and not set(taxi_seen) & series_ids, len(taxi_seen)

# ---- stolen property and second-hand dealer sales ---------------------------------------------------------------------------
serials = set()


def fresh_serial():
    while True:
        s = "SN" + "".join(rng.choice("ABCDEFGHJKLMNPRSTUVWXYZ23456789") for _ in range(8))
        if s not in serials:
            serials.add(s)
            return s


def item():
    desc = pick(list(ITEMS))
    return {"desc": desc, "serial": fresh_serial() if rng.random() < ITEMS[desc] else ""}


stolen = []          # {incident, desc, serial}
for inc in incidents:
    n = {"burglary": rng.randint(1, 4), "theft": int(rng.random() < .6), "vehicle_crime": int(rng.random() < .7) * rng.randint(1, 2)}.get(inc["offence"], 0)
    if inc["id"] in series_ids:
        n = rng.randint(2, 4)
    for k in range(n):
        it = item()
        if inc["id"] in series_ids and not it["serial"] and (k == 0 or rng.random() < .7):
            it = {"desc": pick(["laptop", "tablet", "camera", "games console"]), "serial": fresh_serial()}
        stolen.append({"incident": inc, **it})

sales = []           # {dealer, seller, desc, serial, at}
DEALER_W = [rng.paretovariate(1.1) for _ in DEALERS]
regulars = [pid for pid in BG if pid not in offender_set and persons[pid]["address"]][-60:]
REG_W = [1 / (i + 1) ** .7 for i in range(len(regulars))]
story_sellers = set(SELLERS)

# the series: 40 % of its items resurface at DLR-04 / DLR-09 through the 3 sellers, the serial matches on 60 % of those
s_items = [s for s in stolen if s["incident"]["id"] in series_ids]
n_res = round(.4 * len(s_items))
n_match = round(.6 * n_res)
with_serial = [s for s in s_items if s["serial"]]
matched = []
for k in range(18):                                   # every scene the vehicle was NOT seen near gets a serial match
    if k not in VEHICLE_SCENES:
        matched.append(next(s for s in with_serial if s["incident"] is series[k]))
rest = [s for s in with_serial if s not in matched]
rng.shuffle(rest)
matched += rest[:n_match - len(matched)]
unmatched = [s for s in s_items if s not in matched]
rng.shuffle(unmatched)
unmatched = unmatched[:n_res - len(matched)]
SELLER_DEALER = {SELLERS[0]: "DLR-04", SELLERS[1]: "DLR-09", SELLERS[2]: None}
resurfaced = []
for n, s in enumerate(matched + unmatched):
    seller = SELLERS[n % 3] if n < 3 else rng.choices(SELLERS, weights=[45, 35, 20])[0]
    dealer = SELLER_DEALER[seller] or pick(SERIES_DEALERS)
    serial = s["serial"] if s in matched else ("" if not s["serial"] or rng.random() < .5 else s["serial"][:-1] + ("Q" if s["serial"][-1] != "Q" else "R"))
    at = s["incident"]["end"] + timedelta(days=rng.randint(2, 20), hours=rng.randint(0, 6))
    sales.append({"dealer": dealer, "seller": seller, "desc": s["desc"], "serial": serial, "at": at.replace(hour=rng.randint(10, 17))})
    resurfaced.append({"incident": s["incident"]["id"], "serial": s["serial"], "matched": s in matched, "seller": seller,
                       "dealer": dealer})
for seller in SELLERS:                                # the sellers also sell their own, unremarkable goods
    for _ in range(rng.randint(1, 3)):
        it = item()
        sales.append({"dealer": pick(SERIES_DEALERS), "seller": seller, "desc": it["desc"], "serial": it["serial"],
                      "at": moment(START, CUT, HOUR_TRAFFIC).replace(hour=rng.randint(10, 17))})
# staged month: one more series item turns up, sold by the second seller
late = next(s for s in with_serial if s not in matched and s not in unmatched)
STAGED_MATCH = {"incident": late["incident"]["id"], "serial": late["serial"], "seller": SELLERS[1], "dealer": "DLR-09"}
sales.append({"dealer": "DLR-09", "seller": SELLERS[1], "desc": late["desc"], "serial": late["serial"],
              "at": datetime(2026, 11, 6, 10, 24, 18)})
# background: a handful of ordinary stolen items resurface through one-off sellers
bg_stolen = [s for s in stolen if s["serial"] and s["incident"]["id"] not in series_ids and s["incident"]["start"] < datetime(2026, 9, 1)]
one_off = [pid for pid in BG if pid not in offender_set and pid not in regulars and persons[pid]["address"]]
for s in rng.sample(bg_stolen, 6):
    sales.append({"dealer": rng.choices(DEALERS, weights=DEALER_W)[0], "seller": one_off.pop(rng.randrange(len(one_off))),
                  "desc": s["desc"], "serial": s["serial"], "at": s["incident"]["end"] + timedelta(days=rng.randint(3, 30))})
while len(sales) < N_SALES:
    seller = rng.choices(regulars, weights=REG_W)[0] if rng.random() < .35 else one_off.pop(rng.randrange(len(one_off)))
    it = item()
    if rng.random() < .3:
        it["serial"] = ""                             # dealers do not always record a serial
    sales.append({"dealer": rng.choices(DEALERS, weights=DEALER_W)[0], "seller": seller, "desc": it["desc"],
                  "serial": it["serial"], "at": moment(START, END, HOUR_TRAFFIC).replace(hour=rng.randint(9, 17))})
sales.sort(key=lambda s: (s["at"], s["dealer"], s["seller"]))
for n, s in enumerate(sales):
    s["id"] = f"DS-{n + 1:05d}"

# ---- numbers, handsets and calls -------------------------------------------------------------------------------------------
MOBILE = [f"+447700900{n:03d}" for n in range(1000)]
LAND = [f"+44{code}4960{n:03d}" for code in ("113", "114", "115", "116", "161") for n in range(1000)]
rng.shuffle(MOBILE)
rng.shuffle(LAND)
HANDSETS, OTHER_MOBILES = MOBILE[:N_HANDSETS], MOBILE[N_HANDSETS:]
CONTROLLERS, RUNNERS, BASE_NUMBERS = HANDSETS[:5], HANDSETS[5:9], HANDSETS[9:12]
HOUSE_NUMBERS, (DRIVER_NUMBER,) = HANDSETS[12:19], HANDSETS[19:20]
BG_HANDSETS = HANDSETS[20:]
CUSTOMERS = OTHER_MOBILES[:60]
NETWORK = set(CONTROLLERS) | set(RUNNERS) | set(CUSTOMERS) | set(BASE_NUMBERS)
contact_pool = LAND[:4000] + OTHER_MOBILES[60:]
calls = []


def call(a, b, at, lo=15, hi=600):
    calls.append({"caller": a, "callee": b, "at": at, "dur": max(lo, min(hi, int(rng.lognormvariate(4, .9))))})


# 2 DRUG SUPPLY: five controller numbers in turn, the customers carry over
periods, t = [], datetime(2026, 3, 2)
for _ in CONTROLLERS:
    end = t + timedelta(days=rng.randint(21, 35))
    periods.append((t, end))
    t = end + timedelta(days=rng.randint(0, 2))
pool = list(CUSTOMERS)
active = [pool.pop() for _ in range(36)]
customer_sets = []
for ctrl, (lo, hi) in zip(CONTROLLERS, periods):
    if customer_sets:
        keep = rng.sample(active, math.ceil(rng.uniform(.72, .85) * len(active)))
        active = keep + [pool.pop() for _ in range(6)]
    customer_sets.append(sorted(active))
    for cust in active:
        for _ in range(rng.randint(2, 6)):
            at = moment(lo, hi, HOUR_CALLS)
            call(cust, ctrl, at, 8, 90) if rng.random() < .85 else call(ctrl, cust, at, 8, 90)
    for run in RUNNERS:
        for _ in range(rng.randint(10, 20)):
            at = moment(lo, hi, HOUR_CALLS)
            call(ctrl, run, at, 10, 240) if rng.random() < .7 else call(run, ctrl, at, 10, 240)
assert not pool
NET_START, NET_END = periods[0][0], periods[-1][1]
# 3 DRUG BASES: the runners ring the vulnerable residents' numbers, often
for b, num in enumerate(BASE_NUMBERS):
    for run in RUNNERS[b % 4:b % 4 + 2] or RUNNERS[:2]:
        for _ in range(rng.randint(12, 20)):
            at = moment(NET_START, NET_END, HOUR_CALLS)
            call(run, num, at, 10, 200) if rng.random() < .8 else call(num, run, at, 10, 200)
    for contact in rng.sample(contact_pool[:200], 2):            # a relative or two, rarely
        for _ in range(rng.randint(3, 8)):
            call(num, contact, moment(START, END, HOUR_CALLS))
# look-alike: the delivery driver - many short calls, each customer once or twice, all year, no carry-over
driver_contacts = rng.sample(contact_pool, 230)
for contact in driver_contacts:
    day = moment(START, END, HOUR_CALLS)
    while not 8 <= day.hour < 20:
        day = moment(START, END, HOUR_CALLS)
    for _ in range(rng.choices([1, 2, 3], weights=[70, 22, 8])[0]):
        call(DRIVER_NUMBER, contact, day + timedelta(minutes=rng.randint(0, 50)), 8, 45)
# look-alike: the shared house - housemates ring each other and their own contacts, never the network
for i, a in enumerate(HOUSE_NUMBERS):
    for b in HOUSE_NUMBERS[i + 1:]:
        for _ in range(rng.randint(4, 14)):
            call(a, b, moment(START, END, HOUR_CALLS)) if rng.random() < .5 else call(b, a, moment(START, END, HOUR_CALLS))
    for contact in rng.sample(contact_pool, rng.randint(3, 6)):
        for _ in range(rng.randint(3, 12)):
            call(a, contact, moment(START, END, HOUR_CALLS))
# background: each seized or lawfully obtained handset with its own contacts (heavy-tailed), repeated calls per pair
pairs = []
for h in BG_HANDSETS:
    k = min(40, max(1, int(rng.lognormvariate(.5, .8))))
    for contact in rng.sample(contact_pool, k):
        pairs.append((h, contact, rng.expovariate(1)))
    if rng.random() < .1:
        pairs.append((h, pick([x for x in BG_HANDSETS if x != h]), rng.expovariate(1)))
budget = N_CALLS - len(calls)
total_w = sum(w for _, _, w in pairs)
for h, contact, w in pairs:
    for _ in range(max(1, round(budget * w / total_w))):
        at = moment(START, END, HOUR_CALLS)
        call(h, contact, at) if rng.random() < .6 else call(contact, h, at)
calls.sort(key=lambda c: (c["at"], c["caller"], c["callee"]))
# no look-alike number ever touches the network
for c in calls:
    ends = {c["caller"], c["callee"]}
    if ends & ({DRIVER_NUMBER} | set(HOUSE_NUMBERS)):
        assert not ends & NETWORK, c

handsets = []
attributed = dict(zip(RUNNERS[:2], RUNNER_HOLDERS)) | dict(zip(BASE_NUMBERS, BASE_RESIDENTS)) | \
    dict(zip(HOUSE_NUMBERS, HOUSEMATES[:7])) | {DRIVER_NUMBER: DRIVER}
holders = [pid for pid in BG if persons[pid]["address"]]
for num in HANDSETS:
    who = attributed.get(num, "")
    if num in BG_HANDSETS and rng.random() < .45:
        who = pick(holders)
    imei = "" if rng.random() < .04 else "99" + "".join(str(rng.randrange(10)) for _ in range(13))
    handsets.append({"number": num, "imei": imei, "person": who})
handsets.sort(key=lambda h: h["number"])

# ---- write -------------------------------------------------------------------------------------------------------------------


def write(feed, header, rows, when=None):
    """Rows whose time (when(row)) is at or after CUT go to data/staged, the rest to data/inbox."""
    parts = {"inbox": [], "staged": []}
    for r in rows:
        parts["staged" if when and when(r) >= CUT else "inbox"].append(r)
    for part, day in (("inbox", INBOX_DAY), ("staged", STAGED_DAY)):
        if not parts[part]:
            continue
        out = DATA / part / feed
        out.mkdir(parents=True, exist_ok=True)
        f = out / f"{feed.upper()}_{day}.csv"
        with f.open("w", newline="", encoding="utf-8") as fh:
            w = csv.writer(fh, lineterminator="\n")
            w.writerow(header)
            w.writerows(row[1:] if when else row for row in parts[part])
        print(f"{f.relative_to(DATA)}: {len(parts[part])} rows")


def at(row):
    return row[0]


write("address", ["address_id", "street", "district", "city", "lat", "lon"],
      [[a["id"], a["street"], a["district"], a["city"], f"{a['lat']:.5f}", f"{a['lon']:.5f}"]
       for a in sorted(addresses, key=lambda a: a["id"])])
write("person", ["person_id", "name", "birth_year", "address_id", "role_flags"],
      [[p["id"], p["name"], p["birth_year"], p["address"], ";".join(sorted(p["flags"]))]
       for p in sorted(persons.values(), key=lambda p: p["id"])])
write("incident_report", ["incident_id", "offence_type", "start_at", "end_at", "district", "city", "lat", "lon",
                          "reporting_person_id"],
      [[i["start"], i["id"], i["offence"], fmt(i["start"]), fmt(i["end"]), i["district"], i["city"], f"{i['lat']:.5f}",
        f"{i['lon']:.5f}", i["reporter"]] for i in incidents], at)
write("incident_person", ["incident_id", "person_id", "role"],
      [[i["start"], i["id"], p, r] for i in incidents for p, r in i["people"]], at)
write("vehicle", ["plate", "make", "colour", "keeper_person_id"],
      [[v["plate"], v["make"], v["colour"], v["keeper"]] for v in sorted(vehicles, key=lambda v: v["plate"])])
write("plate_read", ["plate", "camera_id", "read_at", "lat", "lon"],
      [[r["at"], r["plate"], r["camera"], fmt(r["at"]), f"{r['lat']:.5f}", f"{r['lon']:.5f}"] for r in reads], at)
write("call_record", ["caller", "callee", "start_at", "duration_s"],
      [[c["at"], c["caller"], c["callee"], fmt(c["at"]), c["dur"]] for c in calls], at)
write("handset", ["number", "imei", "attributed_person_id"], [[h["number"], h["imei"], h["person"]] for h in handsets])
write("custody", ["custody_id", "person_id", "arrested_at", "co_arrest_group"],
      [[c["at"], c["id"], c["person"], fmt(c["at"]), c["group"]] for c in custody], at)
write("dealer_sale", ["sale_id", "dealer_id", "seller_person_id", "item_desc", "serial", "sold_at"],
      [[s["at"], s["id"], s["dealer"], s["seller"], s["desc"], s["serial"], fmt(s["at"])] for s in sales], at)
write("property_stolen", ["incident_id", "item_desc", "serial"],
      [[s["incident"]["start"], s["incident"]["id"], s["desc"], s["serial"]] for s in stolen], at)

sale_of = {(s["serial"], s["seller"]): s for s in sales if s["serial"]}
truth = {
    "burglary_series": {
        "incidents": [i["id"] for i in series], "vehicle_scenes": series_seen, "plate": SERIES_PLATE,
        "keeper": PARTNER, "sellers": SELLERS, "home": SELLER_HOME, "dealers": SERIES_DEALERS,
        "matches": sorted([{"incident": r["incident"], "serial": r["serial"], "seller": r["seller"], "dealer": r["dealer"],
                            "sale": sale_of[(r["serial"], r["seller"])]["id"]} for r in resurfaced if r["matched"]],
                          key=lambda m: m["sale"]),
        "items": len(s_items), "resurfaced": len(resurfaced),
        "staged_match": STAGED_MATCH | {"sale": sale_of[(STAGED_MATCH["serial"], STAGED_MATCH["seller"])]["id"]}},
    "lookalike_taxi": {"plate": TAXI_PLATE, "keeper": TAXI_DRIVER, "scenes_seen_near": len(taxi_seen_inbox)},
    "drug_supply": {"controllers": CONTROLLERS, "periods": [[fmt(lo), fmt(hi)] for lo, hi in periods], "runners": RUNNERS,
                    "customers": sorted(CUSTOMERS), "customers_per_period": customer_sets},
    "lookalike_driver": {"number": DRIVER_NUMBER, "person": DRIVER, "contacts": len({c["callee"] for c in calls if c["caller"] == DRIVER_NUMBER and c["at"] < CUT})},
    "drug_bases": {"addresses": BASES, "residents": BASE_RESIDENTS, "numbers": BASE_NUMBERS},
    "lookalike_shared_house": {"address": SHARED_HOUSE, "residents": sorted(HOUSEMATES), "numbers": sorted(HOUSE_NUMBERS)},
    "witness": {"witness": WITNESS, "suspect": SUSPECT, "incidents": sorted([inc_a["id"], inc_b["id"]]),
                "suspect_incident": inc_a["id"], "custody_group": WITNESS_GROUP},
    "lookalike_shopkeeper": {"person": SHOPKEEPER, "incidents": sorted(i["id"] for i in shop_incidents)},
}
TRUTH.write_text(json.dumps(truth, indent=1) + "\n", encoding="utf-8", newline="\n")
print(f"{TRUTH.name}: written")
