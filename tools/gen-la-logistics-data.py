#!/usr/bin/env python3
"""Regenerate the `la-logistics` Space Template's cargo-theft corpus - SYNTHETIC, deterministic (one seeded Random).

Writes spaces/_templates/la-logistics/data/inbox/<feed>/ (12 months landed, 2025-10-01..2026-09-30) and data/staged/<feed>/ (the
next month, 2026-10, for the "new data arrives" step) for eight feeds:
  carrier, driver, place (one extract each, landed only: carriers and drivers on the register, warehouses and the places
  trucks stop), shipment, trip, stop (stop-level GPS: place, start, minutes), warehouse_scan (one file per month), loss_report
  (one landed file, one staged)
and tools/gen-la-logistics-data.ground-truth.json (story -> entity ids and look-alike ids; NOT shipped in the data download).

Planted stories (plan: docs/superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md, spec 11; pinned by
tools/gen-la-logistics-data.test.mjs over the files and LogisticsLinksJobGoldenTest over the link Datasets).
  1 INSIDE JOB          12 short-delivered loads share one driver pair (a two-driver team); one night-shift scanner at the
                        Nottingham warehouse scanned 10 of them out
                        look-alike: the busiest driver on the busiest route (Southampton - Birmingham) with 2 losses in about
                        320 trips in the landed year
  2 STOP HOTSPOT        9 high-value trips stop 25-70 minutes at one unofficial lay-by and are stolen there; every high-value
                        truck that stood there that long was robbed
                        look-alike: the official services on the busiest corridor, where about 240 trucks a year (26 high-value) take a
                        long break and 3 high-value loads were stolen
  3 FICTITIOUS CARRIER  a carrier registered 3 weeks earlier, whose phone is a dismissed driver's (that driver lost a load in
                        November 2025), takes 5 high-value loads that never arrive and sends no GPS
                        look-alike: a new, insured owner-operator (its phone is its own driver's) that delivers every load
Scale: 14 000 trips and shipments (spec: 25 000 - the link budget), 900 drivers, 120 carriers. Places, carriers, drivers,
shippers and consignees are invented; cities are real only at city level; phone numbers are in the UK's reserved drama ranges.
Nothing derives from a real source.
"""
from __future__ import annotations

import csv
import json
import math
import random
from datetime import date, datetime, timedelta
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
DATA = TOOLS.parent / "spaces" / "_templates" / "la-logistics" / "data"
TRUTH = TOOLS / "gen-la-logistics-data.ground-truth.json"
SEED = 20261111
rng = random.Random(SEED)
START, LANDED_END, END = date(2025, 10, 1), date(2026, 9, 30), date(2026, 10, 31)
MONTHS = [date(2025 + (9 + i) // 12, (9 + i) % 12 + 1, 1) for i in range(13)]   # 2025-10 .. 2026-10
N_TRIPS, N_CARRIERS, N_DRIVERS = 14000, 120, 895   # plus the fictitious carrier's 5 drivers = 900
HV = 100_000      # a high-value load (documented in the Jobs)
LONG = 20         # a long stop, in minutes: the only stops the link Dataset keeps
N_BG_LOSS = 34    # random background losses over the 13 months, about 1 in 400 trips

# ---- geography: twelve warehouses (real cities, invented sites), places along the roads ---------------------------------------------

CITIES = {"Southampton": (50.91, -1.40, 2), "London": (51.51, -0.13, 5), "Bristol": (51.45, -2.59, 2), "Cardiff": (51.48, -3.18, 1),
          "Birmingham": (52.49, -1.89, 5), "Nottingham": (52.95, -1.15, 2), "Manchester": (53.48, -2.24, 4),
          "Liverpool": (53.41, -2.98, 2), "Leeds": (53.80, -1.55, 3), "Doncaster": (53.52, -1.13, 2), "Newcastle": (54.98, -1.61, 2),
          "Glasgow": (55.86, -4.25, 2)}
WH_NAMES = ["Harbourgate", "Thamesmead Park", "Avonside", "Taff Vale", "Midland Cross", "Trentfield", "Irwell Park", "Merseyway",
            "Airedale", "Donside", "Tyneholme", "Clydebank Road"]
WH = {f"WH-{i + 1:02d}": c for i, c in enumerate(CITIES)}
WH_OF = {c: w for w, c in WH.items()}
WH_S, WH_D = WH_OF["Nottingham"], WH_OF["Southampton"]
BUSIEST = ("Southampton", "Birmingham")
LANDLINE = {"Leeds": "0113 496 0", "Nottingham": "0115 496 0", "Birmingham": "0121 496 0", "Manchester": "0161 496 0",
            "Liverpool": "0151 496 0", "Newcastle": "0191 498 0", "Glasgow": "0141 496 0", "Bristol": "0117 496 0",
            "Cardiff": "029 2018 0", "London": "020 7946 0"}


def xy(lat, lon):
    return lon * 111.32 * math.cos(math.radians(54)), lat * 110.57


def km(a, b):
    (ax, ay), (bx, by) = xy(*CITIES[a][:2]), xy(*CITIES[b][:2])
    return math.hypot(ax - bx, ay - by) * 1.25   # road, not straight line


def hours(o, d):
    return km(o, d) / 68 + 0.3


pairs = [(a, b) for a in CITIES for b in CITIES if a < b]
pair_w = {}
for a, b in pairs:
    w = CITIES[a][2] * CITIES[b][2] / km(a, b) ** 0.9
    pair_w[(a, b)] = w * (9 if (a, b) == tuple(sorted(BUSIEST)) else 1)
routes = [(a, b) for a, b in pairs] + [(b, a) for a, b in pairs]
route_w = [pair_w[tuple(sorted(r))] for r in routes]

WORDS = ["Ashcombe", "Barrowden", "Brackenhurst", "Calder Moor", "Coldharbour", "Dunsford", "Elmstead", "Fernleigh", "Foxhollow",
         "Gorsebrook", "Greenhithe Lane", "Hartwell", "Hazelden", "Holmewood", "Ivybridge Lane", "Kestrel Hill", "Kingsmere",
         "Larchfield", "Longmoor", "Marlow Heath", "Meadowcroft", "Millbrook", "Nethercote", "Oakhanger", "Orchard Gate", "Pennyford",
         "Quarry Bank", "Redlands", "Ridgeway", "Rookwood", "Saltcote", "Sandhurst Gap", "Shepherds Cross", "Silverdale", "Stonecross",
         "Swallowfield", "Thornbury Wood", "Upperton", "Walden Cross", "Westcombe", "Whinfell", "Willowbrook", "Woodgate", "Yarrow Bank",
         "Alderholt", "Blackthorn", "Briar Hill", "Chalkpit", "Copse End", "Deerleap", "Easterby", "Fallowfield", "Gallows Hill",
         "Hawksmoor", "Hollin Bank", "Inglemire", "Juniper Hill", "Kilnhurst", "Lapwing Moor", "Lowfield", "Moorgate", "Northwold",
         "Old Toll", "Pinfold", "Ravensdale", "Rushmere", "Skylark Hill", "Starling Bank", "Tanners Cross", "Twyford Bank",
         "Underwood", "Valley Farm", "Warren End", "Wheatsheaf", "Whitecross", "Windmill Hill", "Wolds Edge", "Wychwood", "Yewtree",
         "Ambergate", "Beacon End", "Birchover", "Bramblebank", "Carrow Hill", "Cinderford Gap", "Clayhanger", "Crossways", "Dovecote",
         "Edgecote", "Elderfield", "Fiveways", "Flaxmoor", "Glebe Farm", "Goldthorn", "Harrowden", "Heathcote", "Highmoor", "Hopwell",
         "Ironstone", "Ketley Bank", "Knightsbridge Farm", "Lamberhurst", "Linthwaite", "Marshgate", "Mossley Hill", "Newbold Gap",
         "Oldfield", "Owlcotes", "Parkgate", "Pebblecombe", "Quernmore", "Ridgehill", "Rowley Gap", "Sandyford", "Scarth Gap",
         "Sheepwash", "Stanwick", "Stoneleigh", "Sunnyside", "Thistledown", "Tollgate", "Upwood", "Waterloo Farm", "Weston Moor",
         "Whitmoor", "Wildmoor", "Woodhouse Gap", "Wrenbury", "Yatesbury", "Zeal Cross", "Ashby Gap", "Bellhouse", "Cobblers Cross",
         "Draycott", "Eastfield", "Fordwells", "Gilmorton", "Hallaton", "Ingleby", "Sallowmere", "Keyham", "Langham Gap", "Medbourne", "Normanton Gap"]
words = rng.sample(WORDS, len(WORDS))
places = {}   # id -> dict(name, type, area, lat, lon)
ptypes = ["services"] * 22 + ["truck_park"] * 12 + ["fuel_station"] * 36 + ["layby"] * 56
rng.shuffle(ptypes)
for i, t in enumerate(ptypes):
    a, b = rng.choices(pairs, [pair_w[p] for p in pairs])[0]
    f = rng.uniform(0.15, 0.85)
    lat = CITIES[a][0] + f * (CITIES[b][0] - CITIES[a][0]) + rng.gauss(0, 0.03)
    lon = CITIES[a][1] + f * (CITIES[b][1] - CITIES[a][1]) + rng.gauss(0, 0.04)
    places[f"PL-{i + 1:03d}"] = {"type": t, "lat": round(lat, 4), "lon": round(lon, 4)}


def along(pid, a, b):
    """The fraction along a -> b where the place sits, or None when it is not beside that road."""
    (ax, ay), (bx, by) = xy(*CITIES[a][:2]), xy(*CITIES[b][:2])
    px, py = xy(places[pid]["lat"], places[pid]["lon"])
    dx, dy = bx - ax, by - ay
    t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
    if not 0.08 <= t <= 0.92:
        return None
    return t if math.hypot(ax + t * dx - px, ay + t * dy - py) < 12 else None


OFFICIAL = ("services", "truck_park")
for a, b in pairs:   # every road of 2.5 hours or more has somewhere official to take the break
    if hours(a, b) >= 2.5 and not any(places[p]["type"] in OFFICIAL and along(p, a, b) for p in places):
        places[f"PL-{len(places) + 1:03d}"] = {"type": "services", "lat": round((CITIES[a][0] + CITIES[b][0]) / 2, 4),
                                               "lon": round((CITIES[a][1] + CITIES[b][1]) / 2, 4)}
SUFFIX = {"services": "Services", "truck_park": "Truck Stop", "fuel_station": "Fuel", "layby": "Lay-by"}
for i, (pid, p) in enumerate(places.items()):
    p["name"] = f"Lay-by at {words[i]}" if p["type"] == "layby" else f"{words[i]} {SUFFIX[p['type']]}"
    p["area"] = min(CITIES, key=lambda c: math.dist(xy(*CITIES[c][:2]), xy(p["lat"], p["lon"])))
road = {}   # (a, b) ordered -> [(t, place)] along it
for a, b in routes:
    road[(a, b)] = sorted((t, p) for p in places if (t := along(p, a, b)) is not None)

# the lay-by of the stop-hotspot story: the unofficial lay-by beside the most 2.5-hour-plus roads, off the busiest corridor
layby_roads = {p: [r for r in routes if hours(*r) >= 2.5 and any(q == p for _, q in road[r]) and set(r) != set(BUSIEST)]
               for p in places if places[p]["type"] == "layby"}
PL_L = max(sorted(layby_roads), key=lambda p: (len(layby_roads[p]), p))
HOT_ROADS = layby_roads[PL_L]

# ---- carriers and drivers --------------------------------------------------------------------------------------------------------

FIRST = ["James", "Mohammed", "Oliver", "Harry", "Daniel", "Ryan", "Liam", "Adam", "Tomasz", "Piotr", "Andrei", "Gary", "Paul", "Mark",
         "Stephen", "Kevin", "Darren", "Lee", "Craig", "Shaun", "Imran", "Bilal", "Sunil", "Rajesh", "Marek", "Kamil", "Ionut",
         "Sarah", "Emma", "Claire", "Joanne", "Nicola", "Aisha", "Priya", "Agnieszka", "Kelly", "Gemma", "Rachel", "Dean", "Wayne",
         "Neil", "Carl", "Jason", "Scott", "Gareth", "Rhys", "Callum", "Euan", "Fraser", "Hamish"]
LAST = ["Abbott", "Ainsworth", "Barker", "Bramley", "Cartwright", "Chapman", "Dalton", "Dobson", "Ellison", "Fairclough", "Garside",
        "Gledhill", "Hargreaves", "Holroyd", "Ibbotson", "Jagger", "Kershaw", "Lockwood", "Marsden", "Mellor", "Nuttall", "Oldroyd",
        "Pickles", "Priestley", "Ramsden", "Rushworth", "Sutcliffe", "Tattersall", "Uttley", "Varley", "Whiteley", "Wormald",
        "Nowak", "Kowalski", "Wisniewski", "Popescu", "Ionescu", "Khan", "Hussain", "Patel", "Akhtar", "Begum", "Macleod", "Fraser",
        "Mackay", "Jones", "Evans", "Price", "Morgan", "Hughes"]
CO_A = ["Northway", "Pennine", "Crossdale", "Ridgeline", "Kestrel", "Fenland", "Wolds", "Moorside", "Riverside", "Highland",
        "Coastline", "Midshires", "Greenlane", "Ironside", "Packhorse", "Swiftline", "Longhaul", "Redbridge", "Westgate", "Eastgate",
        "Blackbrook", "Silverline", "Oakfield", "Stonebridge", "Valeway", "Harrier", "Brookside", "Fieldgate", "Hilltop", "Lakeside"]
CO_B = ["Haulage", "Logistics", "Transport", "Freight", "Carriers", "Distribution"]
co_names = rng.sample([f"{a} {b} Ltd" for a in CO_A for b in CO_B], N_CARRIERS)
mobiles = rng.sample(range(1000), 1000)
landlines = {c: rng.sample(range(1000), 40) for c in CITIES}


def mobile_fmt(n, style):
    return {0: f"07700 900{n:03d}", 1: f"07700900{n:03d}", 2: f"+44 7700 900{n:03d}"}[style]


carriers = {}
cids = [f"CAR-{i:03d}" for i in range(1, N_CARRIERS + 1)]
order = rng.sample(cids, N_CARRIERS)
CAR_F, CAR_N, CAR_AB, CAR_D, CAR_T = order[:5]
new_others = order[5:13]
owner_ops = [CAR_N] + order[13:26]
for i, c in enumerate(cids):
    city = rng.choice(list(CITIES))
    carriers[c] = {"name": co_names[i], "city": city, "insured": "Y" if rng.random() < 0.93 else "N",
                   "reg": datetime.combine(START - timedelta(days=rng.randint(200, 7000)), datetime.min.time()) + timedelta(hours=rng.randint(8, 17)),
                   "weight": rng.paretovariate(1.3), "drivers": []}
    pre = LANDLINE.get(city, "01632 960")
    carriers[c]["phone"] = pre + f"{landlines[city].pop():03d}"
for c in new_others:
    carriers[c]["reg"] = datetime.combine(START + timedelta(days=rng.randint(10, 320)), datetime.min.time()) + timedelta(hours=rng.randint(9, 16))
carriers[CAR_F].update(reg=datetime(2026, 5, 11, 10, 14, 0), insured="N", weight=0.0, name="Larkmoor Freight Services Ltd")
carriers[CAR_N].update(reg=datetime(2026, 3, 2, 11, 40, 0), insured="Y", weight=0.0)
for c in (CAR_AB, CAR_D, CAR_T):
    carriers[c]["insured"] = "Y"

drivers = {}
n_drv = 0


def new_driver(carrier, style=None):
    global n_drv
    n_drv += 1
    d = f"DRV-{n_drv:04d}"
    drivers[d] = {"name": f"{rng.choice(FIRST)} {rng.choice(LAST)}", "carrier": carrier, "mobile": mobiles[n_drv - 1],
                  "style": rng.choices([0, 1, 2], [80, 10, 10])[0] if style is None else style, "blank": False, "left": None,
                  "weight": rng.paretovariate(1.6), "vehicle": f"VH-{n_drv:04d}"}
    carriers[carrier]["drivers"].append(d)
    return d


for c in owner_ops:
    new_driver(c)
rest = [c for c in cids if c not in owner_ops and c != CAR_F]
for c in rest:
    new_driver(c)
while n_drv < N_DRIVERS - 4:   # A, B, the busy driver and the dismissed driver come next
    new_driver(rng.choices(rest, [carriers[c]["weight"] + 0.3 for c in rest])[0])
for c in owner_ops:   # an owner-operator's phone is its driver's mobile
    d = carriers[c]["drivers"][0]
    carriers[c]["phone"] = mobile_fmt(drivers[d]["mobile"], rng.choice([0, 1, 2]))
DRV_N = carriers[CAR_N]["drivers"][0]
A, B = new_driver(CAR_AB), new_driver(CAR_AB)
DRV_D = new_driver(CAR_D)
DRV_T = new_driver(CAR_T, style=0)
drivers[DRV_T]["left"] = date(2025, 11, 30)
for d in (A, B, DRV_D):
    drivers[d]["weight"] = 0.0
fake = [new_driver(CAR_F) for _ in range(5)]
for d in fake:
    drivers[d]["weight"] = 0.0
carriers[CAR_F]["phone"] = mobile_fmt(drivers[DRV_T]["mobile"], 2)   # the dismissed driver's mobile, written another way
specials = {A, B, DRV_D, DRV_T, DRV_N, *fake}
plain = [d for d in drivers if d not in specials and drivers[d]["carrier"] not in owner_ops]
for d in rng.sample(plain, 44):   # leavers during the year
    drivers[d]["left"] = START + timedelta(days=rng.randint(20, 380))
for d in rng.sample(plain, 26):   # missing mobile numbers on the register
    drivers[d]["blank"] = True
households = []
for c in rng.sample([c for c in rest if c not in (CAR_AB, CAR_D, CAR_T) and len(carriers[c]["drivers"]) >= 3], 3):   # two drivers, one phone
    a, b = [d for d in carriers[c]["drivers"] if not drivers[d]["blank"] and d not in specials][:2]
    drivers[b]["mobile"], drivers[b]["style"] = drivers[a]["mobile"], (drivers[a]["style"] + 1) % 3
    households.append(sorted([a, b]))
assert n_drv == 900

# ---- shipments and trips -----------------------------------------------------------------------------------------------------------

GOODS = {"electronics": (60000, 18), "pharmaceuticals": (70000, 6), "tobacco": (110000, 4), "spirits": (55000, 7),
         "clothing": (32000, 12), "food": (12000, 22), "auto parts": (26000, 9), "building materials": (9000, 9),
         "household goods": (18000, 9), "paper": (7000, 4)}
HV_GOODS = ["electronics", "pharmaceuticals", "tobacco"]
SHIPPERS = [f"{a} {b}" for a, b in zip(rng.sample(CO_A, 30), rng.sample(["Foods", "Electronics", "Pharma", "Brands", "Supplies", "Group",
                                                                          "Trading", "Imports", "Wholesale", "Retail"] * 3, 30))]
CONSIGNEES = [f"{w} {s}" for w, s in zip(rng.sample(WORDS, 90), rng.choices(["Stores", "Retail Park", "Superstore", "Depot", "Outlet",
                                                                               "Trade Centre"], k=90))]
DAYS = [START + timedelta(days=i) for i in range((END - START).days + 1)]
DAY_W = [1.0 if d.isoweekday() <= 5 else 0.45 if d.isoweekday() == 6 else 0.3 for d in DAYS]
HOUR_W = [3, 2, 2, 3, 5, 7, 8, 7, 5, 4, 3, 3, 3, 3, 3, 4, 4, 5, 6, 7, 8, 8, 6, 4]


def pick_carrier(t: datetime):
    pool = [c for c in cids if carriers[c]["reg"] < t - timedelta(days=3) and c not in (CAR_AB, CAR_D, CAR_T, CAR_F, CAR_N)]
    w = [carriers[c]["weight"] * min(1.0, (t - carriers[c]["reg"]).days / 120) + 0.05 for c in pool]
    return rng.choices(pool, w)[0]


def active(d, t: datetime):
    return drivers[d]["weight"] > 0 and (drivers[d]["left"] is None or t.date() <= drivers[d]["left"])


trips = []
for i in range(N_TRIPS):
    day = rng.choices(DAYS, DAY_W)[0]
    dep = datetime(day.year, day.month, day.day, rng.choices(range(24), HOUR_W)[0], rng.randint(0, 59))
    o, d = rng.choices(routes, route_w)[0]
    goods = rng.choices(list(GOODS), [g[1] for g in GOODS.values()])[0]
    value = round(rng.lognormvariate(math.log(GOODS[goods][0]), 0.55), -2)
    c = pick_carrier(dep)
    pool = [x for x in carriers[c]["drivers"] if active(x, dep)] or [x for x in carriers[c]["drivers"] if drivers[x]["weight"] > 0]
    drv = rng.choices(pool, [drivers[x]["weight"] for x in pool])[0]
    co = None
    others = [x for x in pool if x != drv]
    if hours(o, d) > 4 and others and rng.random() < 0.3:
        co = rng.choice(others)
    trips.append({"n": i + 1, "o": o, "d": d, "dep": dep, "goods": goods, "value": value, "carrier": c, "driver": drv, "co": co,
                  "shipper": rng.choice(SHIPPERS), "consignee": rng.choice(CONSIGNEES) if rng.random() > 0.03 else "",
                  "lost": None, "arrived": True, "gps": True, "stops": []})


def landed(t):
    return t["dep"].date() <= LANDED_END


def set_driver(t, carrier, drv, co=None):
    t.update(carrier=carrier, driver=drv, co=co)


def hv(t):
    return t["value"] >= HV


def make_hv(t, lo=110_000, hi=260_000):
    t["goods"] = rng.choice(HV_GOODS)
    t["value"] = float(round(rng.uniform(lo, hi), -2))


taken = set()


def take(cands, k):
    out = rng.sample([t for t in cands if t["n"] not in taken], k)
    taken.update(t["n"] for t in out)
    return out


# look-alike 1: the busiest driver on the busiest route
busy = [t for t in trips if {t["o"], t["d"]} == set(BUSIEST)]
d_trips = take(busy, 330) + take([t for t in trips if t["o"] == "Southampton" and t["d"] != "Birmingham"], 14)
for t in d_trips:
    set_driver(t, CAR_D, DRV_D)
# story 1: the two-driver team; most of its trips leave the Nottingham warehouse
ab_trips = take([t for t in trips if t["o"] == "Nottingham" and hours(t["o"], t["d"]) >= 1.5], 72) \
    + take([t for t in trips if t["o"] not in ("Nottingham", "Southampton") and hours(t["o"], t["d"]) >= 1.5], 40)
for t in ab_trips:
    set_driver(t, CAR_AB, A, B)
# the dismissed driver: trips until November 2025
t_trips = take([t for t in trips if t["dep"].date() < date(2025, 11, 28) and t["o"] not in ("Nottingham", "Southampton")], 22)
for t in t_trips:
    set_driver(t, CAR_T, DRV_T)
# look-alike 3: the new owner-operator, from 18 days after it registered
n_trips = take([t for t in trips if t["dep"] >= datetime(2026, 3, 20) and t["dep"].date() <= LANDED_END and t["o"] not in ("Nottingham",)], 58)
for t in n_trips:
    set_driver(t, CAR_N, DRV_N)
n_early = sorted([t for t in n_trips if t["dep"] < datetime(2026, 4, 28)], key=lambda t: t["dep"])
for t in n_early[:8]:
    make_hv(t)
# story 3: the fictitious carrier's five loads, 18-32 days after it registered
f_trips = sorted(take([t for t in trips if datetime(2026, 5, 30) <= t["dep"] <= datetime(2026, 6, 12, 9)
                       and t["o"] not in ("Nottingham", "Southampton")], 5), key=lambda t: t["dep"])
for t, drv in zip(f_trips, fake):
    set_driver(t, CAR_F, drv)
    make_hv(t, 140_000, 280_000)
    t.update(lost="non_delivery", arrived=False, gps=False)
for t in trips:   # vehicles: a driver's usual truck, sometimes another of the fleet, sometimes not recorded
    r = rng.random()
    t["vehicle"] = "" if r < 0.04 else drivers[t["driver"]]["vehicle"] if r < 0.88 else drivers[rng.choice(carriers[t["carrier"]]["drivers"])]["vehicle"]

# ---- stops (stop-level GPS) --------------------------------------------------------------------------------------------------------


def plan_stops(t):
    o, d = t["o"], t["d"]
    h = hours(o, d)
    cand = road[(o, d)]
    stops = []
    if not cand:
        return stops
    fuel = [x for x in cand if places[x[1]]["type"] in ("fuel_station", "services")]
    if fuel and rng.random() < 0.35:
        stops.append((rng.choice(fuel), rng.randint(6, 18)))
    if rng.random() < 0.25:
        stops.append((rng.choice(cand), rng.randint(3, 15)))
    need = h > 4.5 or (h >= 2.5 and rng.random() < 0.5) or rng.random() < 0.06
    if need:
        mid = [x for x in (x for x in cand if 0.3 <= x[0] <= 0.85) if not (hv(t) and x[1] == PL_L)]             or [x for x in cand if not (hv(t) and x[1] == PL_L)]
        w = {"services": 55, "truck_park": 25, "layby": 15, "fuel_station": 5}
        if mid:   # a high-value load never takes its break at the story's lay-by unless it is robbed there
            x = rng.choices(mid, [w[places[p]["type"]] for _, p in mid])[0]
            stops.append((x, rng.randint(45, 70) if h > 4.5 else rng.randint(20, 40)))
    return sorted(stops)


for t in trips:
    t["stops"] = plan_stops(t) if t["gps"] else []


def long_stops(t):
    return [p for (_, p), m in t["stops"] if m >= LONG]


def end_at(t, place, minutes):
    """The trip stops at `place` for `minutes` and the load is stolen there: nothing after it."""
    tt = next(x for x in road[(t["o"], t["d"])] if x[1] == place)
    t["stops"] = [s for s in t["stops"] if s[0][0] < tt[0] and s[1] < LONG] + [(tt, minutes)]
    t.update(lost="theft", arrived=False)


lost_people = {}   # driver -> lost trips


def mark(t):
    for p in (t["driver"], t["co"]):
        if p:
            lost_people.setdefault(p, []).append(t["n"])


# story 2: nine high-value trips on the roads past the lay-by, robbed while standing there
hot_c = [t for t in trips if (t["o"], t["d"]) in HOT_ROADS and t["gps"] and t["n"] not in taken and t["co"] is None
         and t["carrier"] not in (CAR_AB, CAR_D, CAR_T, CAR_N, CAR_F) and date(2025, 10, 15) <= t["dep"].date() <= date(2026, 9, 20)
         and t["dep"].hour >= 14]
rng.shuffle(hot_c)
hot = []
for t in hot_c:   # nine different drivers
    if len(hot) < 9 and t["driver"] not in {x["driver"] for x in hot}:
        hot.append(t)
taken.update(t["n"] for t in hot)
for t in hot:
    make_hv(t, 120_000, 240_000)
    end_at(t, PL_L, rng.randint(25, 70))
    mark(t)

# ---- warehouse scans ---------------------------------------------------------------------------------------------------------------

SHIFTS = ("early", "late", "night")
staff = {}   # (warehouse, shift) -> [employee]
emp_w = {}
n_emp = 0
for w in WH:
    for s in SHIFTS:
        for _ in range(7):
            n_emp += 1
            e = f"EMP-{n_emp:04d}"
            staff.setdefault((w, s), []).append(e)
            emp_w[e] = rng.paretovariate(2.0)
E = staff[(WH_S, "night")][3]


def shift(x: datetime):
    return "early" if 6 <= x.hour < 14 else "late" if 14 <= x.hour < 22 else "night"


def who(w, x: datetime):
    ss = staff[(w, shift(x))]
    return rng.choices(ss, [emp_w[e] for e in ss])[0]


def arrival(t):
    mins = sum(m for _, m in t["stops"])
    return t["dep"] + timedelta(hours=hours(t["o"], t["d"]) * rng.uniform(1.0, 1.2), minutes=mins)


# story 1: twelve short deliveries for the team; ten left Nottingham at night, scanned out by the same night-shift scanner
ab_landed = [t for t in ab_trips if landed(t) and t["dep"].date() <= date(2026, 9, 20) and t["dep"].date() >= date(2025, 10, 10)]
ab_night = sorted(rng.sample([t for t in ab_landed if t["o"] == "Nottingham"], 10), key=lambda t: t["dep"])
ab_other = sorted(rng.sample([t for t in ab_landed if t["o"] != "Nottingham"], 2), key=lambda t: t["dep"])
for t in ab_night:
    d0 = t["dep"].date()
    t["dep"] = datetime(d0.year, d0.month, d0.day, rng.choice([0, 1, 1, 2, 2, 3]), rng.randint(30, 59))
    t["night_scan"] = True
for t in ab_night + ab_other:
    t["goods"] = rng.choice(["electronics", "spirits", "pharmaceuticals", "tobacco"])
    t["value"] = float(round(rng.uniform(45_000, 98_000), -2))
    t["lost"] = "shortage"
    mark(t)
for t in d_trips:
    t["co"] = None

for t in trips:
    t["arr"] = arrival(t) if t["arrived"] else None
    w_o, w_d = WH_OF[t["o"]], WH_OF[t["d"]]
    sc = []
    tin = t["dep"] - timedelta(minutes=rng.randint(8 * 60, 72 * 60))
    sc.append((w_o, "inbound", tin, who(w_o, tin)))
    if rng.random() < 0.6:
        tp = tin + timedelta(minutes=rng.randint(10, 90))
        sc.append((w_o, "putaway", tp, who(w_o, tp)))
    tout = t["dep"] - timedelta(minutes=rng.randint(20, 60) if t.get("night_scan") else rng.randint(20, 100))
    emp = E if t.get("night_scan") else who(w_o, tout)
    if emp == E and not t.get("night_scan") and t["lost"]:
        emp = staff[(w_o, "night")][0]
    sc.append((w_o, "outbound", tout, emp))
    t["out_emp"] = emp
    t["scans"] = sc
    t["booked"] = tin - timedelta(hours=rng.randint(24, 240))

# look-alike 2: the official services where the most high-value trucks take their break; three were robbed there
hv_long = {}
for t in trips:
    if hv(t) and landed(t):
        for p in long_stops(t):
            hv_long[p] = hv_long.get(p, 0) + 1
PL_R = max(sorted(p for p in hv_long if places[p]["type"] == "services" and p != PL_L), key=lambda p: (hv_long[p], p))


def eligible(t):
    return (t["n"] not in taken and not t["lost"] and t["out_emp"] != E and PL_L not in long_stops(t)
            and not any(p in lost_people or p in specials for p in (t["driver"], t["co"]) if p))


lost_at = {}   # place -> lost trips with a long stop there (landed)


def count_places(t):
    if landed(t):
        for p in long_stops(t):
            lost_at.setdefault(p, set()).add(t["n"])


for t in hot:
    count_places(t)
r_trips = take([t for t in trips if eligible(t) and hv(t) and landed(t) and PL_R in long_stops(t)], 3)
for t in r_trips:
    end_at(t, PL_R, next(m for (_, p), m in t["stops"] if p == PL_R) if PL_R in long_stops(t) else 50)
    t["arr"] = None
    mark(t)
    count_places(t)
# the dismissed driver's theft, November 2025
t_loss = next(t for t in sorted(t_trips, key=lambda t: t["dep"]) if t["dep"].date() >= date(2025, 11, 3) and t["out_emp"] != E and t["co"] is None
              and not ({PL_L, PL_R} & set(long_stops(t))))
t_loss.update(lost="theft", arrived=False, arr=None)
mark(t_loss)
count_places(t_loss)
# look-alike 1: two thefts in about 340 trips, on the busiest route
d_loss = sorted(rng.sample([t for t in d_trips if landed(t) and {t["o"], t["d"]} == set(BUSIEST) and t["out_emp"] != E
                            and not ({PL_L, PL_R} & set(long_stops(t)))], 2), key=lambda t: t["dep"])
for t in d_loss:
    t.update(lost="theft", arrived=False, arr=None)
    mark(t)
    count_places(t)
# background: about one loss in 400 trips, two thirds of them high-value loads
bg_loss = []
pool = [t for t in trips if t["carrier"] not in (CAR_AB, CAR_D, CAR_T, CAR_F, CAR_N)]
rng.shuffle(pool)
pool.sort(key=lambda t: 0 if hv(t) and rng.random() < 0.7 else 1)
for t in pool:
    if len(bg_loss) == N_BG_LOSS:
        break
    if not eligible(t) or any(len(lost_at.get(p, ())) >= 2 and p != PL_R for p in long_stops(t) if landed(t)):
        continue
    if PL_R in long_stops(t):
        continue
    kind = "theft" if rng.random() < 0.55 else "shortage"
    t["lost"] = kind
    if kind == "theft":
        t.update(arrived=False, arr=None)
    taken.add(t["n"])
    bg_loss.append(t)
    mark(t)
    count_places(t)
for t in trips:   # the arrival scan, at the destination warehouse, when the load got there
    if t["arrived"] and t["arr"] is not None:
        ta = t["arr"] + timedelta(minutes=rng.randint(15, 120))
        t["scans"].append((WH_OF[t["d"]], "arrived", ta, who(WH_OF[t["d"]], ta)))
    for i, s in enumerate(t["scans"]):
        if s[1] != "outbound" and rng.random() < 0.012:   # a handheld that logged no user
            t["scans"][i] = (*s[:3], "")

# ---- loss reports -------------------------------------------------------------------------------------------------------------------

losses = []
for t in trips:
    if not t["lost"]:
        continue
    if t["lost"] == "shortage":
        rep = t["arr"] + timedelta(hours=rng.randint(2, 48))
    elif t["lost"] == "theft":
        rep = t["dep"] + timedelta(hours=hours(t["o"], t["d"]) + rng.uniform(1, 10))
    else:
        rep = t["dep"] + timedelta(days=rng.randint(2, 4), hours=rng.randint(0, 8))
    losses.append([rep.replace(microsecond=0, second=rng.randint(0, 59)), t])
losses.sort(key=lambda x: (x[0], x[1]["n"]))
for i, (rep, t) in enumerate(losses, 1):
    t["loss_id"], t["reported"] = f"LR-{i:04d}", rep

# ---- checks: what the three screens will select from the landed months (mirrors logistics_screen_links) -------------------------

TR = lambda t: f"TR-{t['n']:05d}"   # noqa: E731
L = [t for t in trips if landed(t)]
lostL = [t for t in L if t["lost"] and t["reported"].date() <= LANDED_END]
assert all(t["reported"].date() <= LANDED_END for t in ab_night + ab_other + hot + f_trips + r_trips + d_loss + [t_loss])
on_loss = {}
for t in lostL:
    for p in (t["driver"], t["co"], t["out_emp"]):
        if p:
            on_loss.setdefault(p, set()).add(TR(t))
repeat = {p for p, s in on_loss.items() if len(s) >= 2}
assert {p for p in repeat if p.startswith("DRV-")} == {A, B, DRV_D}, sorted(p for p in repeat if p.startswith("DRV-"))
assert on_loss[A] == on_loss[B] == {TR(t) for t in ab_night + ab_other} and len(on_loss[A]) == 12
assert on_loss[E] == {TR(t) for t in ab_night}, "the night scanner is on the ten Nottingham losses and no other"
assert len(on_loss[DRV_D]) == 2
adj = {}
for p in repeat:
    for tr in on_loss[p]:
        adj.setdefault(p, set()).add(tr)
        adj.setdefault(tr, set()).add(p)


def component(start):
    seen, todo = {start}, [start]
    while todo:
        for n in adj.get(todo.pop(), ()):
            if n not in seen:
                seen.add(n)
                todo.append(n)
    return seen


inside_members = {A, B, E, *(TR(t) for t in ab_night + ab_other)}
assert not component(DRV_D) & inside_members, "the busy driver stays apart from the team inside the screen"
long_lost = {}
for t in lostL:
    for p in long_stops(t):
        long_lost.setdefault(p, set()).add(TR(t))
hot_places = sorted(p for p, s in long_lost.items() if len(s) >= 3)
assert hot_places == sorted([PL_L, PL_R]), hot_places
layby_hv = sorted(TR(t) for t in L if hv(t) and PL_L in long_stops(t))
assert layby_hv == sorted(TR(t) for t in hot) == sorted(long_lost[PL_L]), "every high-value truck that stood at the lay-by was robbed"
rest_hv = [t for t in L if hv(t) and PL_R in long_stops(t)]
assert len(rest_hv) >= 20 and len(long_lost[PL_R]) == 3, (len(rest_hv), long_lost[PL_R])
assert not any(PL_R in long_stops(t) and PL_L in long_stops(t) for t in L)


def age(t):
    return (t["dep"] - carriers[t["carrier"]]["reg"]).days


new_hv = [t for t in L if hv(t) and age(t) < 60]
assert {t["carrier"] for t in new_hv} >= {CAR_F, CAR_N}
assert sorted(TR(t) for t in new_hv if t["carrier"] == CAR_F) == sorted(TR(t) for t in f_trips)
assert all(18 <= age(t) <= 32 for t in f_trips) and all(not t["arrived"] for t in f_trips)
assert all(t["arrived"] and not t["lost"] for t in n_trips) and len([t for t in new_hv if t["carrier"] == CAR_N]) >= 6


def norm(p):
    q = "".join(ch for ch in p if ch.isdigit())
    return "0" + q[2:] if q.startswith("44") else q


phone_of = {c: norm(v["phone"]) for c, v in carriers.items()}
dphone = {d: ("" if v["blank"] else norm(mobile_fmt(v["mobile"], v["style"]))) for d, v in drivers.items()}
assert [d for d, p in dphone.items() if p == phone_of[CAR_F]] == [DRV_T]
assert [d for d, p in dphone.items() if p == phone_of[CAR_N]] == [DRV_N]
assert len(lost_people[DRV_T]) == 1 and t_loss["reported"].date() < date(2025, 12, 1)
assert all(len(lost_people[t["driver"]]) == 1 for t in hot + r_trips + bg_loss)
assert not any(t["lost"] for t in d_trips if t not in d_loss) and not any(t["lost"] for t in ab_trips if t not in ab_night + ab_other)

# ---- write ------------------------------------------------------------------------------------------------------------------------


def write(rel, header, rows):
    f = DATA / rel
    f.parent.mkdir(parents=True, exist_ok=True)
    with f.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)


def ts(x):
    return x.strftime("%Y-%m-%d %H:%M:%S") if x else ""


for old in sorted(DATA.glob("*/*/*.csv")):
    old.unlink()

write("inbox/carrier/CARRIER_20260930.csv", ["carrier_id", "name", "phone", "registered_at", "insured"],
      [[c, v["name"], v["phone"], ts(v["reg"]), v["insured"]] for c, v in sorted(carriers.items())])
write("inbox/driver/DRIVER_20260930.csv", ["driver_id", "name", "phone", "carrier_id", "left_on"],
      [[d, v["name"], "" if v["blank"] else mobile_fmt(v["mobile"], v["style"]), v["carrier"], v["left"].isoformat() if v["left"] else ""]
       for d, v in sorted(drivers.items())])
write("inbox/place/PLACE_20260930.csv", ["place_id", "name", "place_type", "area", "lat", "lon"],
      [[w, f"{WH_NAMES[i]} Distribution Centre", "warehouse", c, CITIES[c][0], CITIES[c][1]] for i, (w, c) in enumerate(WH.items())]
      + [[p, v["name"], v["type"], v["area"], v["lat"], v["lon"]] for p, v in sorted(places.items())])
for m in MONTHS:
    ym, box = m.strftime("%Y%m"), "staged" if m > LANDED_END else "inbox"
    mt = sorted((t for t in trips if t["dep"].date().replace(day=1) == m), key=lambda t: t["n"])
    write(f"{box}/shipment/SHIPMENT_{ym}.csv", ["shipment_id", "shipper", "consignee", "goods", "value", "booked_at"],
          [[f"SH-{t['n']:05d}", t["shipper"], t["consignee"], t["goods"], f"{t['value']:.2f}", ts(t["booked"])] for t in mt])
    write(f"{box}/trip/TRIP_{ym}.csv", ["trip_id", "shipment_id", "driver_id", "co_driver_id", "vehicle", "carrier_id", "route", "depart_at", "arrive_at"],
          [[TR(t), f"SH-{t['n']:05d}", t["driver"], t["co"] or "", t["vehicle"], t["carrier"], f"{t['o']}-{t['d']}", ts(t["dep"]), ts(t["arr"])] for t in mt])
    rows = []
    for t in mt:
        h = hours(t["o"], t["d"])
        prev = t["dep"]
        for (frac, p), mins in t["stops"]:   # in road order: each stop starts after the one before it ends
            at_ = max(prev + timedelta(minutes=5), t["dep"] + timedelta(minutes=round(frac * h * 60) + rng.randint(-10, 10)))
            rows.append([TR(t), p, ts(at_), mins])
            prev = at_ + timedelta(minutes=mins)
    write(f"{box}/stop/STOP_{ym}.csv", ["trip_id", "place_id", "start_at", "minutes"], sorted(rows))
    write(f"{box}/warehouse_scan/WAREHOUSE_SCAN_{ym}.csv", ["shipment_id", "warehouse_id", "scan_type", "scanned_at", "employee_id"],
          sorted([f"SH-{t['n']:05d}", w, st, ts(x), e] for t in mt for w, st, x, e in t["scans"]))
for box, lo, hi in (("inbox", START, LANDED_END), ("staged", LANDED_END + timedelta(days=1), END + timedelta(days=10))):
    write(f"{box}/loss_report/LOSS_REPORT_{hi.strftime('%Y%m%d') if box == 'inbox' else '20261031'}.csv",
          ["loss_id", "shipment_id", "reported_at", "loss_type"],
          [[t["loss_id"], f"SH-{t['n']:05d}", ts(rep), t["lost"]] for rep, t in losses if lo <= rep.date() <= hi])

ids = lambda ts_: sorted(TR(t) for t in ts_)   # noqa: E731
lids = lambda ts_: sorted(t["loss_id"] for t in ts_)   # noqa: E731
n_rows = lambda key: sum(len(t[key]) for t in L)   # noqa: E731
truth = {
    "seed": SEED,
    "window": {"landed": [START.isoformat(), LANDED_END.isoformat()], "staged": [(LANDED_END + timedelta(days=1)).isoformat(), END.isoformat()]},
    "high_value": HV, "long_stop_minutes": LONG,
    "counts": {"carrier": len(carriers), "driver": len(drivers), "place": len(places) + len(WH), "warehouse": len(WH),
               "trip_landed": len(L), "shipment_landed": len(L), "stop_landed": n_rows("stops"), "warehouse_scan_landed": n_rows("scans"),
               "loss_report_landed": len(lostL), "co_driven_landed": sum(1 for t in L if t["co"]),
               "long_stop_pairs_landed": sum(len(set(long_stops(t))) for t in L)},
    "stories": {
        "inside_job": {"hub": A, "view": "driver_team_inside_job",
                       "entities": sorted([A, B, E, *ids(ab_night + ab_other), *lids(ab_night + ab_other)]), "lookalikes": [DRV_D]},
        "stop_hotspot": {"hub": PL_L, "view": "theft_stop_hotspot", "entities": sorted([PL_L, *ids(hot), *lids(hot)]), "lookalikes": [PL_R]},
        "fictitious_carrier": {"hub": CAR_F, "view": "fictitious_carrier",
                               "entities": sorted([CAR_F, DRV_T, *ids(f_trips), *lids(f_trips)]), "lookalikes": [CAR_N]},
    },
    "inside_job": {"driver": A, "co_driver": B, "carrier": CAR_AB, "scanner": E, "scanner_warehouse": WH_S, "trips": ids(ab_night + ab_other),
                   "night_trips": ids(ab_night), "losses": lids(ab_night + ab_other), "team_trips_landed": sum(1 for t in ab_trips if landed(t)),
                   "lookalike_driver": DRV_D, "lookalike_carrier": CAR_D, "lookalike_trips": ids(d_loss),
                   "lookalike_trips_landed": sum(1 for t in d_trips if landed(t)), "busiest_route": list(BUSIEST)},
    "stop_hotspot": {"layby": PL_L, "layby_name": places[PL_L]["name"], "trips": ids(hot), "losses": lids(hot), "roads": sorted(f"{a}-{b}" for a, b in HOT_ROADS),
                     "lookalike_services": PL_R, "lookalike_name": places[PL_R]["name"], "lookalike_lost_trips": ids(r_trips),
                     "lookalike_high_value_long_stops": len(rest_hv)},
    "fictitious_carrier": {"carrier": CAR_F, "registered_at": ts(carriers[CAR_F]["reg"]), "trips": ids(f_trips), "losses": lids(f_trips),
                           "drivers": fake, "thief": DRV_T, "thief_carrier": CAR_T, "thief_trip": TR(t_loss), "thief_loss": t_loss["loss_id"],
                           "lookalike_carrier": CAR_N, "lookalike_driver": DRV_N, "lookalike_registered_at": ts(carriers[CAR_N]["reg"]),
                           "lookalike_high_value_trips": ids(t for t in new_hv if t["carrier"] == CAR_N)},
    "background": {"lost_trips": ids(bg_loss), "services_thefts": ids(r_trips), "households": households, "owner_operators": sorted(owner_ops)},
}
TRUTH.write_text(json.dumps(truth, indent=1, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
c = truth["counts"]
print(f"trips {c['trip_landed']} landed of {len(trips)}, stops {c['stop_landed']}, scans {c['warehouse_scan_landed']}, losses {c['loss_report_landed']}, "
      f"co-driven {c['co_driven_landed']}, long-stop pairs {c['long_stop_pairs_landed']}, places {c['place']}, PL_L {PL_L}, PL_R {PL_R} ({len(rest_hv)} hv)")
