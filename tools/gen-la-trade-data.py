#!/usr/bin/env python3
"""Regenerate the `la-trade` Space Template's trade-based money-laundering corpus - SYNTHETIC, deterministic.

Writes spaces/_templates/la-trade/data/inbox/<feed>/ (12 months landed, 2025-10-01 .. 2026-09-30) and
spaces/_templates/la-trade/data/staged/<feed>/ (October 2026, copied in during the demo) for seven feeds:
  trade_company, director (register snapshots), trade, bill_of_lading, vessel_position, price_reference, trade_payment (one file per month)
and tools/gen-la-trade-data.ground-truth.json (story -> entity ids and look-alike ids; NOT part of the data download).

The world: 300 vessels on 30 liner services that call 25 invented ports in 24 real countries. Each vessel sails its service
loop day by day, so vessel_position holds one row per vessel per day (a port, or "At sea - <area>"), and every genuine bill of
lading is dated on a day its vessel really was in its port of loading.

Planted stories (plan docs/superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md, spec 6):
  1 OVER-INVOICING     14 shipments of integrated circuits (HS 854231) at 3-8x the reference price, from a Hong Kong exporter to
                       a UK importer that share a director.  Look-alike: a German specialty supplier of medical instruments
                       (HS 901890) whose prices sit at 3.3-3.9x the reference all year, to 8 unrelated buyers, no shared director.
  2 CIRCULAR TRADE     the same 6 containers go A (AE) -> B (IN) -> C (MY) -> A twice in 4 months, the value rising each leg;
                       every leg's consignee ships the boxes on.  Look-alike: 6 boxes of a leasing pool, each carried for 10
                       unrelated shippers.
  3 PHANTOM SHIPMENT   5 letters of credit, paid, on bills of lading that name a vessel which, on the B/L date, was somewhere
                       else (vessel_position).  Look-alike: 5 transhipments - the trade names the ocean vessel, which was not in
                       the port of loading on the ship date, but each leg's own B/L matches its own vessel's position.
  4 SHELL IMPORTERS    6 importers registered within 2 months at one formation-agent address, 2 nominee directors between them,
                       all paying one Chinese exporter far more than it shipped them.  Look-alike: 14 long-standing companies at
                       a large business-centre address, each with its own directors.

Every company, person, vessel, port, street and container is invented; countries are real at country level only and cities
only at city level. HS codes are real codes of the public Harmonized System; every price is invented.
"""
from __future__ import annotations

import csv
import json
import random
from datetime import date, datetime, timedelta
from itertools import accumulate
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
DATA = TOOLS.parent / "spaces" / "_templates" / "la-trade" / "data"
TRUTH = TOOLS / "gen-la-trade-data.ground-truth.json"
SEED = 20261107
rng = random.Random(SEED)
START, END, STAGED_END = date(2025, 10, 1), date(2026, 9, 30), date(2026, 10, 31)
SIM_START = date(2025, 7, 1)
N_COMPANY, N_SERVICE, VESSELS_PER_SERVICE = 2500, 30, 10
N_TRADE_TARGET, N_OTHER_PAYMENT = 13000, 19500
PRICE_ALERT, MIN_SHARED_ADDRESS, MIN_RELAYS, MIN_BOX_SHIPPERS, BOX_CAP = 3.0, 5, 2, 6, 4

# ---- the world: invented ports in real countries, liner services, vessels -------------------------------------------------
PORTS = {"Jadeport (CN)": "CN", "Lotus Quay (CN)": "CN", "Harbour Crest (HK)": "HK", "Saltmere (SG)": "SG",
         "Corran Point (AE)": "AE", "Westerhaven (NL)": "NL", "Fenwick Haven (GB)": "GB", "Coralgate (IN)": "IN",
         "Lantern Bay (VN)": "VN", "Tidewell (MY)": "MY", "Marbleport (TR)": "TR", "Elbmark (DE)": "DE",
         "Pelican Sound (US)": "US", "Porto Verdal (BR)": "BR", "Lagoon Reach (NG)": "NG", "Coral Strand (KE)": "KE",
         "Cape Ardent (ZA)": "ZA", "Puerto Salvia (ES)": "ES", "Porto Celeste (IT)": "IT", "Isthmia (PA)": "PA",
         "Red Sands (EG)": "EG", "Delta Wharf (BD)": "BD", "Pinegate (KR)": "KR", "Cherry Quay (JP)": "JP",
         "Orchid Harbour (TH)": "TH"}
HUBS = ["Saltmere (SG)", "Corran Point (AE)", "Westerhaven (NL)"]
AREAS = ["North Atlantic", "South Atlantic", "Indian Ocean", "Arabian Sea", "South China Sea", "Mediterranean", "North Sea",
         "Bay of Bengal", "Gulf of Guinea", "North Pacific", "Red Sea", "Strait of Malacca"]
COUNTRY_W = {"CN": 14, "HK": 5, "SG": 6, "AE": 7, "NL": 6, "GB": 9, "IN": 9, "VN": 5, "MY": 4, "TR": 5, "DE": 8, "US": 7, "BR": 4,
             "NG": 3, "KE": 2, "ZA": 3, "ES": 4, "IT": 4, "PA": 1, "EG": 3, "BD": 3, "KR": 5, "JP": 5, "TH": 4}
SUFFIX = {"CN": "Co., Ltd", "HK": "Ltd", "SG": "Pte Ltd", "AE": "FZE", "NL": "B.V.", "GB": "Ltd", "IN": "Pvt Ltd", "VN": "JSC",
          "MY": "Sdn Bhd", "TR": "A.S.", "DE": "GmbH", "US": "LLC", "BR": "Ltda", "NG": "Ltd", "KE": "Ltd", "ZA": "(Pty) Ltd",
          "ES": "S.L.", "IT": "S.r.l.", "PA": "S.A.", "EG": "S.A.E.", "BD": "Ltd", "KR": "Co., Ltd", "JP": "K.K.", "TH": "Co., Ltd"}
CITIES = {"CN": ["Shanghai", "Shenzhen", "Ningbo"], "HK": ["Hong Kong"], "SG": ["Singapore"], "AE": ["Dubai", "Sharjah"],
          "NL": ["Rotterdam", "Amsterdam"], "GB": ["London", "Manchester", "Birmingham"], "IN": ["Mumbai", "Chennai"],
          "VN": ["Ho Chi Minh City", "Hanoi"], "MY": ["Kuala Lumpur", "Penang"], "TR": ["Istanbul", "Izmir"], "DE": ["Hamburg", "Bremen"],
          "US": ["Houston", "Newark"], "BR": ["Santos", "Sao Paulo"], "NG": ["Lagos"], "KE": ["Mombasa", "Nairobi"],
          "ZA": ["Durban", "Johannesburg"], "ES": ["Valencia", "Barcelona"], "IT": ["Genoa", "Milan"], "PA": ["Panama City"],
          "EG": ["Alexandria", "Cairo"], "BD": ["Chittagong", "Dhaka"], "KR": ["Busan", "Seoul"], "JP": ["Yokohama", "Osaka"],
          "TH": ["Bangkok"]}
STREETS = ["Alder Row", "Barley Walk", "Cinder Lane", "Dovecote Street", "Eskdale Road", "Fennel Court", "Gannet Way", "Hazel Mews",
           "Ironstone Place", "Jessamine Road", "Kittiwake Lane", "Larch Avenue", "Millrace Street", "Nutmeg Yard", "Osier Close",
           "Pebblebank Road", "Quillon Street", "Rushmere Way", "Saltings Lane", "Teasel Court", "Umber Road", "Vetch Lane",
           "Wherry Street", "Yarrowfield Road", "Cobble Quay", "Lanyard Street", "Mariner Walk", "Sextant Road", "Tiller Lane",
           "Bollard Way"]
CO_A = ["Amberline", "Bluefin", "Coralwood", "Dunmore", "Eastwind", "Fairhaven", "Goldcrest", "Highmoor", "Ivorybay", "Jadestone",
        "Kingfisher", "Lindenfield", "Moonridge", "Northwold", "Oakhurst", "Pearlway", "Quayside", "Redfern", "Silverleaf",
        "Tradewind", "Unity Crest", "Valemont", "Whitecliff", "Yellowpine", "Zephyrine", "Ashgrove", "Brightwater", "Cloudberry",
        "Driftmark", "Evergale"]
CO_B = ["Trading", "Import Export", "Industries", "Logistics", "Commodities", "Textiles", "Electronics", "Metals", "Foods",
        "Agro", "Chemicals", "Machinery", "Supplies", "Marine", "Polymers", "Garments", "Components", "Resources", "Distribution",
        "Holdings"]
FIRST = ["Adam", "Alice", "Amir", "Anna", "Ben", "Bianca", "Carl", "Chloe", "Daniel", "Dora", "Edward", "Elena", "Felix", "Fiona",
         "George", "Grace", "Hannah", "Henry", "Isaac", "Iris", "Jack", "Julia", "Karl", "Kate", "Leo", "Lucy", "Martin", "Maya",
         "Noah", "Nina", "Oscar", "Olivia", "Paul", "Priya", "Robert", "Rosa", "Samuel", "Sara", "Thomas", "Tara", "Victor", "Vera",
         "Wei", "Mei", "Ravi", "Aisha", "Kenji", "Yuki", "Emre", "Lina"]
LAST = ["Abbott", "Barker", "Carver", "Dawson", "Ellison", "Fletcher", "Gardner", "Holloway", "Ingram", "Jennings", "Keller",
        "Lambert", "Mercer", "Norris", "Oakley", "Parsons", "Quinlan", "Rowley", "Sheldon", "Tanner", "Upton", "Vickers", "Walsh",
        "Yates", "Ashby", "Brandt", "Castell", "Delacroix", "Eckhart", "Farrell", "Granger", "Hartley", "Iyer", "Janssen", "Kowal",
        "Lindqvist", "Moreau", "Nakamura", "Osei", "Petrakis", "Rinaldi", "Sorensen", "Thorne", "Varga", "Weber", "Zamora",
        "Demir", "Okafor", "Tran", "Haddad"]
V_A = ["Amber", "Boreal", "Cobalt", "Dawn", "Ember", "Fjord", "Garnet", "Halcyon", "Indigo", "Jasper", "Kestrel", "Lumen", "Marlin",
       "Nimbus", "Opal", "Petrel", "Quartz", "Russet", "Saffron", "Tamarind", "Umbra", "Vesper", "Wren", "Zircon", "Hespera"]
V_B = ["Tern", "Crest", "Star", "Voyager", "Spirit", "Horizon", "Pride", "Trader", "Meridian", "Arrow", "Bay", "Dawn", "Glory",
       "Harmony"]
OWNER_CODES = ["TQRU", "MBXU", "HSGU", "LOTU", "VRDU", "QNEU", "GWAU", "DFKU"]
# HS codes: real 6-digit codes of the public Harmonized System; the base prices (USD per unit) are invented
HS = {"090111": 3.1, "090121": 7.4, "100630": 0.52, "100199": 0.27, "170114": 0.41, "180100": 2.6, "150710": 0.95, "151110": 0.88,
      "520100": 1.8, "610910": 2.9, "620342": 7.8, "620462": 8.4, "640399": 21.0, "711319": 640.0, "711311": 210.0, "710812": 52000.0,
      "710239": 950.0, "740311": 8.6, "760110": 2.4, "847130": 420.0, "851762": 85.0, "852872": 160.0, "854231": 14.0, "854232": 4.2,
      "870323": 17500.0, "870332": 19800.0, "870899": 36.0, "300490": 22.0, "271012": 0.71, "270900": 0.52, "271111": 0.43,
      "260111": 0.11, "260300": 1.9, "940360": 95.0, "392690": 4.1, "401110": 48.0, "847330": 12.0, "850440": 31.0, "853710": 260.0,
      "901890": 340.0, "220421": 4.9, "080390": 0.62, "080510": 0.7, "020230": 5.3, "040210": 2.9, "240120": 4.4, "290511": 0.36,
      "310221": 0.19, "310230": 0.31, "390110": 1.25, "720712": 0.55, "732690": 3.7, "847989": 2400.0, "848180": 27.0,
      "850760": 140.0, "950300": 6.2, "330499": 9.5, "420221": 64.0, "910211": 88.0, "691110": 3.3, "871200": 115.0, "293399": 46.0}
HS_CODES = list(HS)


def pick(seq):
    return seq[rng.randrange(len(seq))]


def day(a: date, b: date) -> date:
    return a + timedelta(days=rng.randrange((b - a).days + 1))


def months(a: date, b: date):
    m = date(a.year, a.month, 1)
    while m <= b:
        nxt = date(m.year + (m.month == 12), m.month % 12 + 1, 1)
        yield m, nxt - timedelta(days=1)
        m = nxt


# services: the first four are fixed so the stories have a route, the rest are random loops that may call a hub
services: list[list[str]] = [
    ["Jadeport (CN)", "Harbour Crest (HK)", "Saltmere (SG)", "Corran Point (AE)", "Westerhaven (NL)", "Fenwick Haven (GB)"],
    ["Corran Point (AE)", "Coralgate (IN)", "Tidewell (MY)", "Saltmere (SG)", "Red Sands (EG)"],
    ["Elbmark (DE)", "Westerhaven (NL)", "Fenwick Haven (GB)", "Puerto Salvia (ES)", "Pelican Sound (US)"],
    ["Marbleport (TR)", "Porto Celeste (IT)", "Red Sands (EG)", "Westerhaven (NL)", "Fenwick Haven (GB)"]]
non_hubs = [p for p in PORTS if p not in HUBS]
while len(services) < N_SERVICE:
    k = rng.randint(4, 7)
    ports = [pick(HUBS)] if rng.random() < 0.75 else []
    while len(ports) < k:
        p = pick(non_hubs)
        if p not in ports:
            ports.append(p)
    rng.shuffle(ports)
    services.append(ports)
for p in PORTS:   # every port is called by at least one random service
    if not any(p in s for s in services):
        services[4 + rng.randrange(N_SERVICE - 4)].append(p)
leg_days = [[rng.randint(2, 9) for _ in s] for s in services]
leg_area = [[pick(AREAS) for _ in s] for s in services]

vessel_names: list[str] = []
while len(vessel_names) < N_SERVICE * VESSELS_PER_SERVICE:
    n = f"MV {pick(V_A)} {pick(V_B)}".upper()
    if n not in vessel_names and n.split()[1] != n.split()[2]:
        vessel_names.append(n)
vessel_service: dict[str, int] = {}
position: dict[tuple[str, date], str] = {}
calls: dict[str, list[tuple[str, date, date]]] = {}     # vessel -> [(port, arrive, depart)]
for si, s in enumerate(services):
    for vi in range(VESSELS_PER_SERVICE):
        v = vessel_names[si * VESSELS_PER_SERVICE + vi]
        vessel_service[v] = si
        i, d, cl = rng.randrange(len(s)), SIM_START - timedelta(days=rng.randrange(60)), []
        while d <= STAGED_END + timedelta(days=40):
            stay = rng.choice([1, 1, 2, 2, 3])
            dep = d + timedelta(days=stay - 1)
            cl.append((s[i], d, dep))
            for k in range(stay):
                position[(v, d + timedelta(days=k))] = s[i]
            sea = leg_days[si][i] + rng.choice([0, 0, 0, 1])
            for k in range(1, sea):
                position[(v, dep + timedelta(days=k))] = f"At sea - {leg_area[si][i]}"
            d = dep + timedelta(days=sea)
            i = (i + 1) % len(s)
        calls[v] = cl
service_vessels = {si: [v for v in vessel_names if vessel_service[v] == si] for si in range(N_SERVICE)}


def sailing(si: int, p_from: str, p_to: str, a: date, b: date, avoid: set[str] = frozenset()):
    """A real sailing of service si: a vessel that departs p_from on a day in [a, b] and calls p_to later -> (vessel, dep, arr)."""
    opts, b = [], min(b, STAGED_END)
    for v in service_vessels[si]:
        if v in avoid:
            continue
        cl = calls[v]
        for j, (p, _, dep) in enumerate(cl):
            if p == p_from and a <= dep <= b:
                nxt = next((c for c in cl[j + 1:] if c[0] == p_to), None)
                if nxt:
                    opts.append((v, dep, nxt[1]))
    return pick(opts) if opts else None


# ---- companies, accounts, directors ----------------------------------------------------------------------------------------
company_ids = [f"CO-{i:05d}" for i in range(1, N_COMPANY + 1)]
rng.shuffle(company_ids)
co_cursor = 0
companies: dict[str, dict] = {}
used_names: set[str] = set()
used_accounts: set[str] = set()


def take_co(n: int) -> list[str]:
    global co_cursor
    out = company_ids[co_cursor:co_cursor + n]
    co_cursor += n
    return out


def address(country: str) -> str:
    return f"{rng.randint(1, 320)} {pick(STREETS)}, {pick(CITIES[country])}"


def co_name(country: str) -> str:
    for tries in range(2000):
        n = f"{pick(CO_A)} {pick(CO_B)} {SUFFIX[country]}" if tries < 30 else f"{pick(CO_A)} {pick(CO_B)} {tries % 97} {SUFFIX[country]}"
        if n not in used_names:
            used_names.add(n)
            return n
    raise RuntimeError("company name space exhausted")


def account() -> str:
    while True:
        a = f"ACC-{rng.randrange(10 ** 8):08d}"
        if a not in used_accounts:
            used_accounts.add(a)
            return a


def new_company(cid: str, country: str, inc: date | None = None, addr: str | None = None, name: str | None = None):
    if name:
        used_names.add(name)
    companies[cid] = {"name": name or co_name(country), "country": country,
                      "incorporated_at": inc or day(date(1985, 1, 1), date(2024, 6, 30)),
                      "reg_address": addr if addr is not None else address(country), "account_id": account()}


person_seq = 0
persons: dict[str, str] = {}
directors: list[tuple[str, str]] = []    # (company_id, person_id)


def new_person(name: str | None = None) -> str:
    global person_seq
    person_seq += 1
    pid = f"PER-{person_seq:05d}"
    persons[pid] = name or f"{pick(FIRST)} {pick(LAST)}"
    return pid


reserved: set[str] = set()          # story and look-alike companies: background records never pick them
stories: dict[str, dict] = {}
trades: list[dict] = []             # every trade; bls inside
other_payments: list[tuple] = []    # (from_co, to_co, amount, datetime, trade_ref)


def unit_price(hs: str, d: date, factor: float) -> float:
    return round(ref_price[(hs, f"{d:%Y-%m}")] * factor, 2)


# the monthly reference medians (invented): a base price per code with a gentle monthly drift
ref_price: dict[tuple[str, str], float] = {}
for hs in HS_CODES:
    p = HS[hs]
    for m0, _ in months(START, STAGED_END):
        p *= rng.uniform(0.97, 1.035)
        ref_price[(hs, f"{m0:%Y-%m}")] = round(p, 2)


def add_trade(exporter: str, importer: str, hs: str, legs: list[tuple[str, str, str, date, date]], factor: float,
              qty: int | None = None, instrument: str | None = None, containers: list[str] | None = None,
              trade_vessel: str | None = None, value: float | None = None) -> dict:
    """legs: [(vessel, port_from, port_to, bl_date, arrive)]; one B/L per leg and container."""
    ship = legs[0][3]
    up = unit_price(hs, ship, factor)
    if value is not None:
        qty = max(1, round(value / up))
    if qty is None:
        qty = max(1, round(rng.lognormvariate(11.7, 1.0) / up))
    t = {"instrument": instrument or ("LC" if rng.random() < 0.62 else "collection"), "applicant": importer,
         "beneficiary": exporter, "hs_code": hs, "qty": qty, "unit_price": up, "value": round(qty * up, 2),
         "port_load": legs[0][1], "port_discharge": legs[-1][2], "vessel": trade_vessel if trade_vessel is not None else legs[-1][0],
         "ship_date": ship, "bls": [], "containers": containers}
    for v, pf, pt, bd, _ in legs:
        for c in containers or [None]:
            t["bls"].append({"vessel": v, "container": c, "shipper": exporter, "consignee": importer, "port_from": pf,
                             "port_to": pt, "bl_date": bd})
    trades.append(t)
    return t


country_ports = {c: [p for p, pc in PORTS.items() if pc == c] for c in COUNTRY_W}

# ---- 1 OVER-INVOICING --------------------------------------------------------------------------------------------------------
oi_imp, oi_exp = take_co(2)
new_company(oi_imp, "GB", inc=date(2017, 4, 11))
new_company(oi_exp, "HK", inc=date(2018, 9, 3))
oi_dir = new_person("Martin Castell")
directors += [(oi_imp, oi_dir), (oi_exp, oi_dir), (oi_imp, new_person()), (oi_exp, new_person())]
oi_trades = []
for m in sorted(rng.sample(range(11), 9) + rng.sample(range(11), 5)):
    a = date(2025, 10, 1) + timedelta(days=m * 28 + rng.randrange(20))
    s = sailing(0, "Harbour Crest (HK)", "Fenwick Haven (GB)", a, a + timedelta(days=25))
    oi_trades.append(add_trade(oi_exp, oi_imp, "854231", [(s[0], "Harbour Crest (HK)", "Fenwick Haven (GB)", s[1], s[2])],
                               round(rng.uniform(3.2, 7.8), 2), qty=rng.randint(9000, 26000), instrument="LC"))
ss = take_co(1)[0]
new_company(ss, "DE", inc=date(1987, 6, 22), name="Kestrelhoff Medizintechnik GmbH")
directors += [(ss, new_person()), (ss, new_person())]
ss_buyers = take_co(8)
ss_trades = []
for b in ss_buyers:
    port = pick(["Westerhaven (NL)", "Fenwick Haven (GB)", "Puerto Salvia (ES)", "Pelican Sound (US)"])
    new_company(b, PORTS[port], inc=day(date(1990, 1, 1), date(2012, 12, 31)))
    directors.append((b, new_person()))
    for _ in range(rng.randint(3, 4)):
        a = START + timedelta(days=(len(ss_trades) % 12) * 30 + rng.randrange(8))     # every month of the year
        s = sailing(2, "Elbmark (DE)", port, a, a + timedelta(days=30))
        ss_trades.append(add_trade(ss, b, "901890", [(s[0], "Elbmark (DE)", port, s[1], s[2])], round(rng.uniform(3.3, 3.9), 2),
                                   qty=rng.randint(40, 260), instrument="LC"))
reserved.update([oi_imp, oi_exp, ss] + ss_buyers)
stories["over_invoicing"] = {"entities": [oi_imp, oi_exp, oi_dir], "lookalikes": [ss] + ss_buyers,
                             "facts": {"importer": oi_imp, "exporter": oi_exp, "shared_director": oi_dir, "hs_code": "854231",
                                       "shipments": 14, "specialty_supplier": ss, "specialty_hs_code": "901890",
                                       "specialty_buyers": len(ss_buyers)}}

# ---- 2 CIRCULAR TRADE --------------------------------------------------------------------------------------------------------
ca, cb, cc = take_co(3)
new_company(ca, "AE", inc=date(2015, 2, 17))
new_company(cb, "IN", inc=date(2013, 8, 5))
new_company(cc, "MY", inc=date(2016, 11, 28))
for c in (ca, cb, cc):
    directors.append((c, new_person()))
boxes: list[str] = []
used_boxes: set[str] = set()


def new_box(code: str | None = None) -> str:
    while True:
        b = f"{code or pick(OWNER_CODES)}{rng.randrange(10 ** 7):07d}"
        if b not in used_boxes:
            used_boxes.add(b)
            return b


circle_boxes = [new_box("KLNU") for _ in range(6)]
circle_ports = {ca: "Corran Point (AE)", cb: "Coralgate (IN)", cc: "Tidewell (MY)"}
ready, legs_done = date(2026, 3, 2), []
seq3 = [ca, cb, cc, ca, cb, cc, ca]
for k in range(6):
    frm, to = seq3[k], seq3[k + 1]
    s = sailing(1, circle_ports[frm], circle_ports[to], ready, ready + timedelta(days=30))
    t = add_trade(frm, to, "847130", [(s[0], circle_ports[frm], circle_ports[to], s[1], s[2])], round(1.04 * 1.12 ** k, 2),
                  qty=1100, instrument="LC", containers=circle_boxes)
    legs_done.append(t)
    ready = s[2] + timedelta(days=rng.randint(3, 6))
assert (legs_done[-1]["ship_date"] - legs_done[0]["ship_date"]).days <= 122, "the circle closes twice within 4 months"
reserved.update([ca, cb, cc])
stories["circular_trade"] = {"entities": [ca, cb, cc] + circle_boxes, "lookalikes": [],
                             "facts": {"cycle": [ca, cb, cc], "containers": circle_boxes, "legs": 6}}

# ---- 3 PHANTOM SHIPMENT ------------------------------------------------------------------------------------------------------
ph_exp = take_co(1)[0]
ph_imps = take_co(2)
new_company(ph_exp, "TR", inc=date(2024, 1, 9))
for c in ph_imps:
    new_company(c, "GB", inc=day(date(2006, 1, 1), date(2015, 12, 31)))
for c in [ph_exp] + ph_imps:
    directors.append((c, new_person()))
PV_SVC = next(si for si in range(4, N_SERVICE) if "Marbleport (TR)" not in services[si])
pv = service_vessels[PV_SVC][3]             # a real vessel on a service that never calls Marbleport
ph_trades = []
for k, d in enumerate([date(2026, 2, 12), date(2026, 3, 20), date(2026, 4, 28), date(2026, 6, 4), date(2026, 7, 15)]):
    assert position[(pv, d)] != "Marbleport (TR)"
    ph_trades.append(add_trade(ph_exp, ph_imps[k % 2], "740311", [(pv, "Marbleport (TR)", "Fenwick Haven (GB)", d, d + timedelta(days=16))],
                               round(rng.uniform(0.95, 1.08), 2), value=rng.uniform(380000, 720000), instrument="LC",
                               containers=[new_box("ZEDU")]))
reserved.update([ph_exp] + ph_imps)
# look-alike: 5 transhipments; the feeder carries the goods to a hub, a second real vessel carries them on
ts_trades, ts_cos = [], []
shared = [(i, j, h) for i in range(4, N_SERVICE) for j in range(4, N_SERVICE) for h in HUBS
          if i != j and h in services[i] and h in services[j] and PV_SVC not in (i, j)]
while len(ts_trades) < 5:
    i, j, h = pick(shared)
    p1, p2 = pick([p for p in services[i] if p != h]), pick([p for p in services[j] if p != h])
    if PORTS[p1] == PORTS[p2]:
        continue
    a = day(date(2025, 11, 1), date(2026, 8, 15))
    f = sailing(i, p1, h, a, a + timedelta(days=30), avoid={pv})
    if not f:
        continue
    m = sailing(j, h, p2, f[2] + timedelta(days=1), f[2] + timedelta(days=12), avoid={pv})
    if not m or position.get((m[0], f[1])) == p1:
        continue
    e, imp = take_co(2)
    new_company(e, PORTS[p1])
    new_company(imp, PORTS[p2])
    for c in (e, imp):
        directors.append((c, new_person()))
    ts_cos += [e, imp]
    ts_trades.append(add_trade(e, imp, pick(HS_CODES), [(f[0], p1, h, f[1], f[2]), (m[0], h, p2, m[1], m[2])],
                               round(rng.lognormvariate(0, 0.12), 2), instrument="LC", containers=[None]))
reserved.update(ts_cos)
stories["phantom_shipment"] = {"entities": [ph_exp] + ph_imps + [pv], "lookalikes": ts_cos,
                               "facts": {"exporter": ph_exp, "importers": ph_imps, "vessel": pv, "port_of_loading": "Marbleport (TR)"}}

# ---- 4 SHELL IMPORTERS -------------------------------------------------------------------------------------------------------
AGENT = "Suite 9, 41 Larkspur Row, London"
BIZ_CENTRE = "Floor 3, Kingsbury Business Centre, 200 Aldwick Road, London"
shell_exp = take_co(1)[0]
new_company(shell_exp, "CN", inc=date(2011, 5, 30))
directors.append((shell_exp, new_person()))
shells = take_co(6)
nominees = [new_person("Oscar Delacroix"), new_person("Vera Lindqvist")]
for k, c in enumerate(shells):
    new_company(c, "GB", inc=date(2026, 3, 2) + timedelta(days=k * 10 + rng.randrange(6)), addr=AGENT)
    directors.append((c, nominees[0] if k < 4 else nominees[1]))
shell_trades, shell_pays = [], []
for c in shells:
    for _ in range(rng.randint(1, 2)):
        a = day(date(2026, 5, 4), date(2026, 8, 31))
        s = sailing(0, "Jadeport (CN)", "Fenwick Haven (GB)", a, a + timedelta(days=25))
        shell_trades.append(add_trade(shell_exp, c, pick(["610910", "950300", "392690"]),
                                      [(s[0], "Jadeport (CN)", "Fenwick Haven (GB)", s[1], s[2])], round(rng.lognormvariate(0, 0.1), 2),
                                      value=rng.uniform(9000, 24000), instrument="collection"))
    for _ in range(rng.randint(8, 12)):
        d = day(date(2026, 5, 4), END)
        shell_pays.append((c, shell_exp, round(rng.uniform(42000, 96000), 2),
                           datetime(d.year, d.month, d.day, rng.randint(9, 16), rng.randrange(60), rng.randrange(60)), ""))
other_payments += shell_pays
biz = take_co(14)
for c in biz:
    new_company(c, "GB", inc=day(date(1996, 1, 1), date(2014, 12, 31)), addr=BIZ_CENTRE)
    for _ in range(rng.randint(1, 2)):
        directors.append((c, new_person()))
reserved.update([shell_exp] + shells + biz)
stories["shell_importers"] = {"entities": shells + [shell_exp, AGENT.upper()] + nominees, "lookalikes": biz + [BIZ_CENTRE.upper()],
                              "facts": {"exporter": shell_exp, "importers": shells, "agent_address": AGENT,
                                        "nominee_directors": nominees, "business_centre": BIZ_CENTRE}}

# ---- background companies, directors ---------------------------------------------------------------------------------------
cw = list(COUNTRY_W.items())
for cid in company_ids[co_cursor:]:
    new_company(cid, rng.choices([c for c, _ in cw], weights=[w for _, w in cw])[0])
bg = company_ids[co_cursor:]
for c in bg:      # realistic gaps on background records
    if rng.random() < 0.04:
        companies[c]["reg_address"] = ""
by_country = {c: [x for x in bg if companies[x]["country"] == c] for c in COUNTRY_W}
cw_w = {c: list(accumulate(1 / (i + 1) ** 0.7 for i in range(len(v)))) for c, v in by_country.items()}
bg_people = [new_person() for _ in range(2350)]
for orig in rng.sample(bg_people, 20):    # one person registered twice with a spelling variant
    first, last = persons[orig].split(" ", 1)
    twin = new_person(f"{first} {last[:-1] + {'a': 'e', 'e': 'a', 'i': 'y', 'o': 'a', 'n': 'nn', 'r': 'rr'}.get(last[-1], last[-1] + 'e')}")
    bg_people.append(twin)
per_w = list(accumulate(1 / (i + 1) ** 0.3 for i in range(len(bg_people))))
per_count: dict[str, int] = {}
for c in bg:
    for _ in range(1 if rng.random() < 0.58 else 2):
        while True:
            p = rng.choices(bg_people, cum_weights=per_w)[0]
            if per_count.get(p, 0) < 3 and (c, p) not in directors:
                break
        per_count[p] = per_count.get(p, 0) + 1
        directors.append((c, p))


def bg_company(country: str) -> str:
    return rng.choices(by_country[country], cum_weights=cw_w[country])[0]


def bg_trade_for(company: str, as_exporter: bool, a: date, b: date):
    """One ordinary trade between a reserved company and a background partner on a real sailing."""
    home = companies[company]["country"]
    for _ in range(200):
        si = rng.randrange(N_SERVICE)
        mine = [p for p in services[si] if PORTS[p] == home]
        other = [p for p in services[si] if PORTS[p] != home and by_country[PORTS[p]]]
        if not mine or not other:
            continue
        pm, po = pick(mine), pick(other)
        p_from, p_to = (pm, po) if as_exporter else (po, pm)
        t0 = day(a, b)
        s = sailing(si, p_from, p_to, t0, t0 + timedelta(days=25), avoid={pv})
        if not s:
            continue
        partner = bg_company(PORTS[po])
        e, i = (company, partner) if as_exporter else (partner, company)
        return add_trade(e, i, pick(HS_CODES), [(s[0], p_from, p_to, s[1], s[2])], round(min(rng.lognormvariate(0, 0.16), 2.2), 2))
    raise RuntimeError(f"no sailing for {company}")


for c in [oi_imp] * 6 + [oi_exp] * 3 + ph_imps * 3 + [shell_exp] * 10:
    bg_trade_for(c, c in (oi_exp, shell_exp), START, STAGED_END - timedelta(days=30))
for c in biz:
    for _ in range(rng.randint(3, 6)):
        bg_trade_for(c, rng.random() < 0.4, START, STAGED_END - timedelta(days=30))

# ---- background trade relationships ----------------------------------------------------------------------------------------
specialty = {c: rng.sample(HS_CODES, rng.randint(1, 3)) for c in bg}
n_story = len(trades)
transhipped = 0
while len(trades) < N_TRADE_TARGET:
    si = rng.randrange(N_SERVICE)
    pl, pd = rng.sample(services[si], 2)
    if PORTS[pl] == PORTS[pd] or not by_country[PORTS[pl]] or not by_country[PORTS[pd]]:
        continue
    e, i = bg_company(PORTS[pl]), bg_company(PORTS[pd])
    n = min(1 + int(rng.paretovariate(1.5)), 40)
    hs = specialty[e]
    for _ in range(n):
        t0 = day(START, STAGED_END - timedelta(days=10))
        if rng.random() < 0.006 and pl not in HUBS and pd not in HUBS:     # a background transhipment through a hub
            via = [(h, j) for h in HUBS if h in services[si] for j in range(N_SERVICE)
                   if j not in (si, PV_SVC) and h in services[j] and pd in services[j]]
            h, j = pick(via) if via else (None, None)
            f = sailing(si, pl, h, t0, t0 + timedelta(days=25), avoid={pv}) if h else None
            m = sailing(j, h, pd, f[2] + timedelta(days=1), f[2] + timedelta(days=14), avoid={pv}) if f else None
            if f and m:
                add_trade(e, i, pick(hs), [(f[0], pl, h, f[1], f[2]), (m[0], h, pd, m[1], m[2])],
                          round(min(rng.lognormvariate(0, 0.15), 2.2), 2), containers=[None])
                transhipped += 1
                continue
        s = sailing(si, pl, pd, t0, t0 + timedelta(days=25), avoid={pv})
        if s:
            add_trade(e, i, pick(hs), [(s[0], pl, pd, s[1], s[2])], round(max(min(rng.lognormvariate(0, 0.15), 2.2), 0.5), 2))
for t in trades[n_story:]:
    if rng.random() < 0.04:
        t["vessel"] = ""          # some trades name no vessel

# ---- containers: date order, a box is reused at most BOX_CAP times and never comes back to a former consignee --------------
box_uses: dict[str, list[tuple[str, str]]] = {}      # box -> [(shipper, consignee)]
active: list[str] = []
for t in sorted(trades, key=lambda t: t["ship_date"]):
    if t["containers"] and t["containers"][0] is not None:
        continue
    for _ in range(14):
        b = pick(active) if active and rng.random() < 0.7 else None
        if b and all(t["beneficiary"] != c for _, c in box_uses[b]) and all(t["applicant"] != s for s, _ in box_uses[b]):
            break
        b = None
    if b is None:
        b = new_box()
        box_uses[b] = []
        active.append(b)
    box_uses[b].append((t["beneficiary"], t["applicant"]))
    if len(box_uses[b]) >= BOX_CAP:
        active.remove(b)
    t["containers"] = [b]
    for bl in t["bls"]:
        bl["container"] = b
# look-alike: a leasing pool's 6 boxes, each carried for 10 unrelated shippers
lease_boxes = [new_box("PWCU") for _ in range(6)]
cands = [t for t in sorted(trades[n_story:], key=lambda t: t["ship_date"]) if len(t["bls"]) == 1 and t["ship_date"] <= END]
chosen, shippers_seen, consignees_seen = [], set(), set()
for t in rng.sample(cands, len(cands)):
    e, i = t["beneficiary"], t["applicant"]
    if e in shippers_seen or e in consignees_seen or i in shippers_seen or i in consignees_seen or e == i:
        continue
    chosen.append(t)
    shippers_seen.add(e)
    consignees_seen.add(i)
    if len(chosen) == 60:
        break
for k, t in enumerate(sorted(chosen, key=lambda t: (t["ship_date"], t["beneficiary"]))):
    t["containers"] = [lease_boxes[k % 6]]
    t["bls"][0]["container"] = lease_boxes[k % 6]
stories["circular_trade"]["lookalikes"] = lease_boxes
stories["circular_trade"]["facts"]["leasing_boxes"] = lease_boxes

# ---- ids in date order, so a story is not visible in the numbering ---------------------------------------------------------
trades.sort(key=lambda t: (t["ship_date"], t["beneficiary"], t["applicant"], t["value"]))
for k, t in enumerate(trades, 1):
    t["trade_id"] = f"TR-{k:06d}"
all_bls = sorted(((bl, t) for t in trades for bl in t["bls"]), key=lambda x: (x[0]["bl_date"], x[1]["trade_id"], x[0]["container"] or ""))
for k, (bl, t) in enumerate(all_bls, 1):
    bl["bl_id"], bl["trade_id"] = f"BL-{k:07d}", t["trade_id"]

# ---- payments: each trade paid importer -> exporter (LC on presentation, collections later); background payments ------------
payments: list[tuple] = []
for t in trades:
    lag = rng.randint(6, 21) if t["instrument"] == "LC" else rng.randint(20, 60)
    d = t["ship_date"] + timedelta(days=lag)
    if d <= STAGED_END:
        payments.append((t["applicant"], t["beneficiary"], t["value"], datetime(d.year, d.month, d.day, rng.randint(8, 17),
                                                                                rng.randrange(60), rng.randrange(60)), t["trade_id"]))
payers = bg + biz
pay_w = list(accumulate(rng.paretovariate(1.2) for _ in payers))
payee_w = list(accumulate(1 / (i + 1) ** 0.8 for i in range(len(bg))))
while len(other_payments) < N_OTHER_PAYMENT:
    f, to = rng.choices(payers, cum_weights=pay_w)[0], rng.choices(bg, cum_weights=payee_w)[0]
    if f == to:
        continue
    d = day(START, STAGED_END)
    if d.weekday() >= 5 and rng.random() < 0.85:
        continue
    other_payments.append((f, to, round(min(rng.lognormvariate(8.6, 1.3), 1_500_000.0), 2),
                           datetime(d.year, d.month, d.day, rng.choice([8, 9, 10, 10, 11, 12, 14, 14, 15, 16, 17]),
                                    rng.randrange(60), rng.randrange(60)), ""))
payments += other_payments
payments.sort(key=lambda p: (p[3], p[0], p[1]))

# ---- self-checks: the rules of the trade_links Job find exactly the planted facts ------------------------------------------
landed = [t for t in trades if t["ship_date"] <= END]
ratio = {t["trade_id"]: t["unit_price"] / ref_price[(t["hs_code"], f"{t['ship_date']:%Y-%m}")] for t in trades}
over = sorted(k for k, r in ratio.items() if r > PRICE_ALERT)
assert over == sorted(t["trade_id"] for t in oi_trades + ss_trades), "only the story and the specialty supplier price over 3x"
elsewhere = sorted(bl["bl_id"] for t in trades for bl in t["bls"] if position.get((bl["vessel"], bl["bl_date"]), bl["port_from"]) != bl["port_from"])
assert elsewhere == sorted(bl["bl_id"] for t in ph_trades for bl in t["bls"]), elsewhere
naive = [t["trade_id"] for t in ts_trades if position.get((t["vessel"], t["ship_date"])) != t["port_load"]]
assert len(naive) == 5, "a check on the trade, not the B/L, would wrongly flag every transhipment look-alike"
addr_n: dict[str, int] = {}
for c in companies.values():
    if c["reg_address"]:
        addr_n[c["reg_address"].upper()] = addr_n.get(c["reg_address"].upper(), 0) + 1
assert sorted(a for a, n in addr_n.items() if n >= MIN_SHARED_ADDRESS) == sorted([AGENT.upper(), BIZ_CENTRE.upper()]), addr_n
inc = sorted(companies[c]["incorporated_at"] for c in shells)
assert (inc[-1] - inc[0]).days <= 61
box_legs: dict[str, list] = {}
for bl in sorted((bl for t in trades for bl in t["bls"]), key=lambda b: (b["bl_date"], b["bl_id"])):
    box_legs.setdefault(bl["container"], []).append(bl)
relayed = sorted(b for b, ls in box_legs.items() if sum(1 for x, y in zip(ls, ls[1:]) if y["shipper"] == x["consignee"]) >= MIN_RELAYS)
assert relayed == sorted(circle_boxes), relayed
many = sorted(b for b, ls in box_legs.items() if len({x["shipper"] for x in ls}) >= MIN_BOX_SHIPPERS)
assert many == sorted(lease_boxes), many
assert all(t["bls"] for t in trades)

# ---- facts for the user guide's answers ------------------------------------------------------------------------------------
f = stories["over_invoicing"]["facts"]
f["trades"] = sorted(t["trade_id"] for t in oi_trades)
f["price_x_reference"] = [round(min(ratio[t["trade_id"]] for t in oi_trades), 2), round(max(ratio[t["trade_id"]] for t in oi_trades), 2)]
f["specialty_price_x_reference"] = [round(min(ratio[t["trade_id"]] for t in ss_trades), 2), round(max(ratio[t["trade_id"]] for t in ss_trades), 2)]
f["specialty_trades"] = len(ss_trades)
f = stories["circular_trade"]["facts"]
f["trades"] = [t["trade_id"] for t in legs_done]
f["leg_values"] = [t["value"] for t in legs_done]
f["first_ship_date"], f["last_ship_date"] = legs_done[0]["ship_date"].isoformat(), legs_done[-1]["ship_date"].isoformat()
f = stories["phantom_shipment"]["facts"]
f["trades"] = [t["trade_id"] for t in ph_trades]
f["bills_of_lading"] = [t["bls"][0]["bl_id"] for t in ph_trades]
f["vessel_was_at"] = {t["bls"][0]["bl_id"]: f"{t['ship_date']}: {position[(pv, t['ship_date'])]}" for t in ph_trades}
f["paid"] = round(sum(p[2] for p in payments if p[4] in f["trades"]), 2)
f["transhipment_trades"] = [t["trade_id"] for t in ts_trades]
f["background_transhipments"] = transhipped
f = stories["shell_importers"]["facts"]
f["incorporated"] = [inc[0].isoformat(), inc[-1].isoformat()]
f["paid_to_exporter"] = round(sum(p[2] for p in payments if p[0] in shells and p[1] == shell_exp and p[3].date() <= END), 2)
f["shipped_by_exporter"] = round(sum(t["value"] for t in shell_trades if t["ship_date"] <= END), 2)

# ---- people renumbered in shuffled order: the story directors were created first ------------------------------------------
new_ids = [f"PER-{i:05d}" for i in range(1, len(persons) + 1)]
rng.shuffle(new_ids)
renum = dict(zip(persons, new_ids))
persons = {renum[p]: n for p, n in persons.items()}
directors = [(c, renum[p]) for c, p in directors]


def renumbered(v):
    if isinstance(v, str):
        return renum.get(v, v)
    if isinstance(v, list):
        return [renumbered(x) for x in v]
    if isinstance(v, dict):
        return {k: renumbered(x) for k, x in v.items()}
    return v


stories = renumbered(stories)


# ---- write -----------------------------------------------------------------------------------------------------------------
def write(rel: str, header: list[str], rows):
    p = DATA / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    with p.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)


def ts(v: datetime) -> str:
    return v.strftime("%Y-%m-%d %H:%M:%S")


for old in DATA.glob("*/*/*.csv"):
    old.unlink()
write("inbox/trade_company/COMPANY_20260930.csv", ["company_id", "name", "country", "reg_address", "incorporated_at", "account_id"],
      [(c, v["name"], v["country"], v["reg_address"], v["incorporated_at"].isoformat(), v["account_id"]) for c, v in sorted(companies.items())])
write("inbox/director/DIRECTOR_20260930.csv", ["company_id", "person_id", "person_name"],
      [(c, p, persons[p]) for c, p in sorted(set(directors))])
acct = {c: v["account_id"] for c, v in companies.items()}
n_rows = {"trade": 0, "bill_of_lading": 0, "payment": 0, "vessel_position": 0}
pay_seq = 0
for m0, m1 in months(START, STAGED_END):
    where = "staged" if m0 > END else "inbox"
    tag = f"{m0:%Y%m}"
    mt = [t for t in trades if m0 <= t["ship_date"] <= m1]
    write(f"{where}/trade/TRADE_{tag}.csv", ["trade_id", "instrument", "applicant", "beneficiary", "hs_code", "qty", "unit_price", "value",
                                              "port_load", "port_discharge", "vessel", "ship_date"],
          [(t["trade_id"], t["instrument"], t["applicant"], t["beneficiary"], t["hs_code"], t["qty"], f"{t['unit_price']:.2f}",
            f"{t['value']:.2f}", t["port_load"], t["port_discharge"], t["vessel"], t["ship_date"].isoformat()) for t in mt])
    mb = [bl for bl, _ in all_bls if m0 <= bl["bl_date"] <= m1]
    write(f"{where}/bill_of_lading/BILL_OF_LADING_{tag}.csv", ["bl_id", "trade_id", "vessel", "container", "shipper", "consignee",
                                                                "port_from", "port_to", "bl_date"],
          [(b["bl_id"], b["trade_id"], b["vessel"], b["container"], b["shipper"], b["consignee"], b["port_from"], b["port_to"],
            b["bl_date"].isoformat()) for b in mb])
    rows = []
    for p in payments:
        if m0 <= p[3].date() <= m1:
            pay_seq += 1
            rows.append((f"PAY-{pay_seq:06d}", acct[p[0]], acct[p[1]], f"{p[2]:.2f}", ts(p[3]), p[4]))
    write(f"{where}/trade_payment/PAYMENT_{tag}.csv", ["payment_id", "from_account", "to_account", "amount", "paid_at", "trade_ref"], rows)
    pos = []
    d = m0
    while d <= m1:
        for v in vessel_names:
            pos.append((v, d.isoformat(), position[(v, d)]))
        d += timedelta(days=1)
    write(f"{where}/vessel_position/VESSEL_POSITION_{tag}.csv", ["vessel", "pos_date", "port_or_area"], pos)
    write(f"{where}/price_reference/PRICE_REFERENCE_{tag}.csv", ["hs_code", "month", "median_unit_price"],
          [(hs, f"{m0:%Y-%m}", f"{ref_price[(hs, f'{m0:%Y-%m}')]:.2f}") for hs in sorted(HS_CODES)])
    if where == "inbox":
        n_rows["trade"] += len(mt)
        n_rows["bill_of_lading"] += len(mb)
        n_rows["payment"] += len(rows)
        n_rows["vessel_position"] += len(pos)

for s in stories.values():
    s["entities"] = sorted(set(s["entities"]))
    s["lookalikes"] = sorted(set(s["lookalikes"]))
TRUTH.write_text(json.dumps({"note": "Story -> the entity ids that must form ONE connected group in the trade_links Dataset (and in "
                                     "the story's saved-view Dataset), and look-alike ids that must have no link to any of them. Address "
                                     "nodes are the upper-cased registered address. Written by tools/gen-la-trade-data.py; not part "
                                     "of the data download.", "seed": SEED, "price_ratio_alert": PRICE_ALERT,
                             "landed_rows": n_rows, "stories": stories}, indent=1, sort_keys=True, default=str) + "\n",
                 encoding="utf-8", newline="\n")
print(f"companies {len(companies)}, directors {len(set(directors))}, trades {len(trades)} ({n_rows['trade']} landed, "
      f"{transhipped} background transhipments), B/Ls {len(all_bls)}, payments {len(payments)}, boxes {len(used_boxes)}, "
      f"positions {n_rows['vessel_position']} landed")
