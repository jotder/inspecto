#!/usr/bin/env python3
"""Regenerate the `la-card` Space Template's card and e-commerce fraud corpus - SYNTHETIC, deterministic (one seeded Random).

Writes spaces/_templates/la-card/data/inbox/<feed>/ (12 months landed, 2025-10-01..2026-09-30) and data/staged/<feed>/ (the next
month, 2026-10, for the "new data arrives" step) for six feeds:
  cardholder, card, merchant (one extract each, landed only), card_auth (authorisations, one file per month),
  chargeback, online_account (one landed extract, one staged month)
and tools/gen-la-card-data.ground-truth.json (story -> entity ids and look-alike ids; NOT shipped in the data download).

Planted stories (plan: docs/superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md, spec 4; pinned by
tools/gen-la-card-data.test.mjs over the files and CardLinksJobGoldenTest over the link Dataset):
  1 CARD TESTING           one device and one /24 (203.0.113.0/24) try 300 cards with 1.00 authorisations at 3 small online shops
                           in one night, 85 % declined
                           look-alike: a corporate proxy (198.51.100.0/24) used by about 185 genuine cards, office hours, few declines
  2 ACCOUNT TAKEOVER       8 devices change the ship-to address of 140 online accounts to 5 drop addresses, then buy on their cards
                           look-alike: a family of four sharing one tablet across 4 accounts at one address
  3 COLLUDING MERCHANT     M-221 refunds 12 cards it never sold to; its chargebacks run about 9x its category (electronics)
                           look-alike: travel merchant M-140 with a chargeback spike in one strike month (2026-07)
  4 COMMON POINT OF        60 cards defrauded 1-6 weeks after using fuel station M-317 in a 3-week window (2026-03-02..22)
    PURCHASE               look-alike: the busy supermarket M-052 used by about 900 cards, none of them later defrauded
Scale: 5 000 cardholders, 6 500 cards, 900 merchants, 4 000 online accounts, about 77 000 authorisations and 690 chargebacks (the plan's 150 000
and 1 800 scaled down so the committed corpus stays under 10 MB). Card ids are invented (CRD-nnnnnn), never card numbers; phones are in
Ofcom's drama ranges; e-mail domains are example.com/.net/.org; IPs are documentation (RFC 5737) and shared-address (RFC 6598)
ranges; names and streets are invented, cities are real only at city level. Nothing derives from a real source.
"""
from __future__ import annotations

import csv
import json
import random
from datetime import date, datetime, timedelta
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
DATA = TOOLS.parent / "spaces" / "_templates" / "la-card" / "data"
TRUTH = TOOLS / "gen-la-card-data.ground-truth.json"
rng = random.Random(20261107)
START, LANDED_END, END = date(2025, 10, 1), date(2026, 9, 30), date(2026, 10, 31)
N_HOLDERS, N_CARDS, N_MERCHANTS, N_ACCOUNTS = 5000, 6500, 900, 4000
M221, M140, M317, M052 = "M-221", "M-140", "M-317", "M-052"
TEST_SHOPS = ["M-608", "M-613", "M-655"]
TEST_NET, PROXY_NET, ATO_NET = "203.0.113", "198.51.100", "192.0.2"
PROXY_IP = PROXY_NET + ".7"
TEST_NIGHT = date(2026, 6, 14)
CPP_FROM, CPP_TO = date(2026, 3, 2), date(2026, 3, 22)
ATO_FROM, ATO_TO = date(2026, 1, 12), date(2026, 2, 28)
STRIKE = "2026-07"
CITIES = ["Leeds", "Sheffield", "Bristol", "Manchester", "Leicester"]
FIRST = ["Ada", "Basil", "Cora", "Dev", "Edie", "Flynn", "Gwen", "Hugo", "Isla", "Jude", "Kira", "Lars", "Maeve", "Nico", "Opal",
         "Piers", "Rhea", "Sami", "Tess", "Ugo", "Vida", "Wade", "Xena", "Yusuf", "Zara", "Arlo", "Bea", "Cyrus", "Dina", "Eben",
         "Fenna", "Gil", "Hana", "Ivo", "Juno", "Kofi", "Lumi", "Milo", "Nell", "Otis"]
LAST = ["Abbott", "Bramley", "Calloway", "Denholm", "Ellery", "Fairbairn", "Gilchrist", "Holloway", "Ingram", "Jessop", "Kinsella",
        "Lindqvist", "McAllister", "Nightingale", "Ormsby", "Prentice", "Quarry", "Rowntree", "Stanhope", "Treadwell", "Upton",
        "Varga", "Wetherby", "Yelland", "Zamora", "Ashby", "Blackwood", "Crowther", "Dunbar", "Everley", "Fenwick", "Garside",
        "Hartigan", "Ivesley", "Jowett", "Kettering", "Loxley", "Marchbank", "Northcott", "Oddie"]
VARIANT = {"McAllister": "MacAllister", "Nightingale": "Nightengale", "Lindqvist": "Lindquist", "Holloway": "Halloway",
           "Fairbairn": "Fairburn", "Rowntree": "Rountree", "Treadwell": "Tredwell", "Hartigan": "Hartegan"}
STREET = ["Amber", "Bracken", "Chalk", "Dove", "Ember", "Flint", "Gorse", "Hazel", "Iron", "Jasper", "Kiln", "Larch", "Mill", "Oak",
          "Pippin", "Quarry", "Rowan", "Sorrel", "Teasel", "Willow", "Alder", "Brook", "Cobble", "Dunnock", "Elder", "Fern"]
SUFFIX = {"Lane": "Ln", "Road": "Rd", "Close": "Cl", "Street": "St", "Avenue": "Ave", "Grove": "Gr"}
PHONE_RANGES = ["07700 900", "01632 960", "0113 496 0", "0114 496 0", "0115 496 0", "0116 496 0", "0117 496 0", "0118 496 0",
                "0121 496 0", "0131 496 0", "0141 496 0", "0151 496 0", "0161 496 0", "0191 498 0", "020 7946 0"]
DOMAINS = ["example.com", "example.net", "example.org"]
BINS = [("BIN-A1", 40), ("BIN-A2", 25), ("BIN-B1", 15), ("BIN-C1", 12), ("BIN-C2", 8)]
ACQUIRERS = ["Halden Payments", "Corvid Merchant Services", "Brightwater Acquiring", "Tollgate Pay"]
ADJ = ["Amber", "Blue", "Copper", "Daisy", "Elm", "Fox", "Golden", "Harbour", "Ivy", "Juniper", "Kestrel", "Linden", "Maple", "North",
       "Orchard", "Pebble", "Quill", "Red", "Silver", "Thistle", "Union", "Vale", "Wren", "Yarrow"]
NOUN = ["Lane", "Hill", "Bridge", "Court", "Field", "Gate", "Wharf", "Row", "Yard", "Cross", "Green", "Mill"]
# mcc, word in the name, channel, share of merchants, typical amount
MCC = [("5411", "Grocers", "pos", 0.18, 38), ("5541", "Fuel", "pos", 0.08, 55), ("5812", "Kitchen", "pos", 0.14, 42),
       ("5814", "Grill", "pos", 0.10, 11), ("5912", "Pharmacy", "pos", 0.06, 17), ("5691", "Outfitters", "pos", 0.08, 64),
       ("5311", "Stores", "pos", 0.04, 72), ("5999", "Online", "ecom", 0.12, 29), ("5732", "Electronics", "ecom", 0.06, 180),
       ("5942", "Books", "ecom", 0.04, 21), ("4722", "Holidays", "ecom", 0.04, 420), ("4121", "Rides", "ecom", 0.03, 16),
       ("5815", "Apps", "ecom", 0.03, 9)]
MCC_BY = {m[0]: m for m in MCC}
DISPUTES = ["merchandise_not_received", "not_as_described", "duplicate_charge", "subscription_cancelled"]
FRAUD = "fraud_card_not_present"
WEEKDAY = [0.85, 0.85, 0.9, 0.95, 1.15, 1.3, 1.0]
HOURS = {"pos": [0, 0, 0, 0, 0, 0, 1, 3, 5, 6, 7, 8, 10, 9, 7, 7, 8, 10, 10, 8, 5, 3, 1, 0],
         "ecom": [2, 1, 1, 0, 0, 0, 1, 2, 3, 4, 5, 5, 6, 5, 5, 5, 5, 6, 7, 9, 11, 11, 8, 4]}


def pick(seq):
    return seq[rng.randrange(len(seq))]


def ts(t):
    return t.strftime("%Y-%m-%d %H:%M:%S")


def at(d, hour):
    return datetime(d.year, d.month, d.day, hour) + timedelta(seconds=rng.randrange(3600))


def hour_of(channel):
    return rng.choices(range(24), weights=HOURS[channel])[0]


def day_between(a, b):
    """A day in [a, b], busier on Fridays and Saturdays."""
    while True:
        d = a + timedelta(days=rng.randrange((b - a).days + 1))
        if rng.random() < WEEKDAY[d.weekday()] / 1.3:
            return d


def amount(mcc):
    return round(max(1.0, MCC_BY[mcc][4] * rng.lognormvariate(0, 0.55)), 2)


# ---- cardholders --------------------------------------------------------------------------------------------------------------
phones = [r + f"{i:03d}" for r in PHONE_RANGES for i in range(1000)]
rng.shuffle(phones)


def address(city):
    return f"{rng.randint(1, 180)} {pick(STREET)} {pick(list(SUFFIX))} {city}"


def short(addr):
    """The abbreviated spelling of an address ("Close" -> "Cl"), for identity resolution."""
    for long_, abbr in SUFFIX.items():
        if f" {long_} " in addr:
            return addr.replace(f" {long_} ", f" {abbr} ")
    return addr


holders = []                                       # dicts: id, first, last, name, email, phone, address, city
FAMILY_LAST, FAMILY_ADDR = "Garside", "27 Larch Grove Leeds"
for i in range(N_HOLDERS):
    hid = f"CH-{i + 1:05d}"
    if i < 4:                                      # look-alike: a family of four at one address
        first, last, city, addr = ["Hana", "Ivo", "Juno", "Kofi"][i], FAMILY_LAST, "Leeds", FAMILY_ADDR
    else:
        first, last, city = pick(FIRST), pick(LAST), CITIES[i % len(CITIES)]
        addr = address(city)
        if rng.random() < 0.08 and holders[-1]["city"] == city and i > 5:
            addr = holders[-1]["address"]          # a household of two
    email = f"{first.lower()}.{last.lower()}{rng.randint(1, 99)}@{pick(DOMAINS)}" if rng.random() > 0.05 else ""
    phone = phones.pop() if rng.random() > 0.04 else ""
    holders.append({"id": hid, "first": first, "last": last, "name": f"{first} {last}", "email": email, "phone": phone,
                    "address": addr, "city": city})
for i in range(30):                                # the same person twice, the second record spelt differently
    src = holders[4 + rng.randrange(N_HOLDERS - 4)]
    last = VARIANT.get(src["last"], src["last"] + "e")
    holders.append({**src, "id": f"CH-{N_HOLDERS + i + 1:05d}", "last": last, "name": f"{src['first']} {last}"})
holder = {h["id"]: h for h in holders}
holders.sort(key=lambda h: h["id"])
FAMILY = [f"CH-{i:05d}" for i in range(1, 5)]

# devices and home networks per holder (ids shuffled so that no id says which story it is in)
dev_ids = [f"DEV-{i:05d}" for i in range(1, 9000)]
rng.shuffle(dev_ids)
for n, h in enumerate(holders):
    h["devices"] = [dev_ids.pop() for _ in range(1 if rng.random() < 0.7 else 2)]
    h["net"] = f"100.{64 + n // 250}.{n % 250}"
    h["host"] = rng.randint(2, 250)
for hid in FAMILY:
    holder[hid]["net"] = holder[FAMILY[0]]["net"]
TABLET = dev_ids.pop()
TEST_DEVICE = dev_ids.pop()
ATO_DEVICES = sorted(dev_ids.pop() for _ in range(8))
FRAUD_DEVICES = [dev_ids.pop() for _ in range(15)]

# ---- cards ---------------------------------------------------------------------------------------------------------------------
cards = []                                         # dicts: id, bin, holder, issued
card_ids = [f"CRD-{i:06d}" for i in range(100001, 100001 + N_CARDS)]
owners = [h["id"] for h in holders] + [pick(holders)["id"] for _ in range(N_CARDS - len(holders))]
for cid, hid in zip(card_ids, owners):
    new = rng.random() < 0.06
    issued = day_between(START, date(2026, 9, 15)) if new else date(2019, 1, 1) + timedelta(days=rng.randrange(2430))
    cards.append({"id": cid, "bin": rng.choices([b for b, _ in BINS], weights=[w for _, w in BINS])[0], "holder": hid, "issued": issued})
card = {c["id"]: c for c in cards}
cards_of = {}
for c in cards:
    cards_of.setdefault(c["holder"], []).append(c["id"])

# ---- merchants -----------------------------------------------------------------------------------------------------------------
cats = [m[0] for m in MCC for _ in range(round(m[3] * N_MERCHANTS))]
cats = (cats + ["5411"] * N_MERCHANTS)[:N_MERCHANTS]
rng.shuffle(cats)
merchants = []
FIXED = {M221: ("5732", "Voltbay Direct", "Leicester"), M140: ("4722", "Skylark Holidays", "Bristol"),
         M317: ("5541", "Kestrel Fuel Parkway", "Sheffield"), M052: ("5411", "Harvest Fair Superstore", "Sheffield"),
         "M-608": ("5999", "Petal Lane Prints", "Bristol"), "M-613": ("5999", "Quill Row Stickers", "Leeds"),
         "M-655": ("5999", "Wren Yard Candles", "Manchester")}
for i, mcc in enumerate(cats):
    mid = f"M-{i + 1:03d}"
    mcc, name, city = FIXED.get(mid, (mcc, f"{pick(ADJ)} {pick(NOUN)} {MCC_BY[mcc][1]}", pick(CITIES)))
    merchants.append({"id": mid, "name": name, "mcc": mcc, "channel": MCC_BY[mcc][2], "city": city, "acquirer": pick(ACQUIRERS),
                      "weight": min(60.0, rng.paretovariate(1.2))})
merchant = {m["id"]: m for m in merchants}
merchant[M052]["weight"] = 2000.0
merchant["M-608"]["acquirer"] = merchant["M-613"]["acquirer"] = merchant["M-655"]["acquirer"] = "Tollgate Pay"
SPECIAL = {M221, M140, M317, *TEST_SHOPS}          # their traffic is planted, not drawn
pool = {}
for m in merchants:
    if m["id"] in SPECIAL:
        continue
    key = m["city"] if m["channel"] == "pos" else "ecom"
    pool.setdefault(key, []).append(m)
cum = {k: [] for k in pool}
for k, ms in pool.items():
    total = 0.0
    for m in ms:
        total += m["weight"]
        cum[k].append(total)

# ---- story roles (chosen first, so the background can keep each look-alike away from its story) ---------------------------------
taken = set()
order = [c["id"] for c in cards]
rng.shuffle(order)


def take(n, pred=lambda c: True):
    out = []
    for cid in order:
        c = card[cid]
        if cid not in taken and c["holder"] not in FAMILY and c["issued"] < START and pred(c):
            out.append(cid)
            taken.add(cid)
            if len(out) == n:
                return sorted(out)
    raise SystemExit(f"not enough cards for {n}")


in_sheffield = lambda c: holder[c["holder"]]["city"] == "Sheffield"
cpp_window = take(70, in_sheffield)
cpp_cards = cpp_window[:60]
regulars = take(50, in_sheffield)
test_cards = take(300)
test_holders = {card[c]["holder"] for c in test_cards}
refund_cards = take(12)
m221_cb_cards = take(23)
bg_fraud = take(40)
proxy_holders = sorted({h["id"] for h in holders if h["id"] not in test_holders and h["id"] not in FAMILY
                        and all(c not in taken for c in cards_of.get(h["id"], []))})
rng.shuffle(proxy_holders)
proxy_holders = sorted(proxy_holders[:400])
proxy_set = set(proxy_holders)
banned = {c: {M052} for c in cpp_window + bg_fraud}

auths = []                                         # [auth_id, card, merchant, amount, at, device, ip, result]
chargebacks = []                                   # [auth_row, reason, filed]


def auth(cid, mid, amt, t, device="", ip="", result="approved"):
    row = [None, cid, mid, f"{amt:.2f}", t, device, ip, result]
    auths.append(row)
    return row


def ecom_where(cid, t):
    h = holder[card[cid]["holder"]]
    if h["id"] in FAMILY and rng.random() < 0.5:
        device = TABLET
    else:
        device = pick(h["devices"])
    if h["id"] in proxy_set and t.weekday() < 5 and 8 <= t.hour < 19:
        return device, PROXY_IP
    return device, f"{h['net']}.{h['host'] if rng.random() < 0.9 else rng.randint(2, 250)}"


def charge_back(row, reason, lo, hi):
    filed = row[4].date() + timedelta(days=rng.randint(lo, hi))
    if filed <= END:
        chargebacks.append([row, reason, filed])


# ---- background ---------------------------------------------------------------------------------------------------------------
for c in cards:
    cid, h = c["id"], holder[c["holder"]]
    n = rng.randint(0, 2) if rng.random() < 0.08 else int(rng.lognormvariate(2.25, 0.8))
    if cid in cpp_window:
        n = max(n, 6)
    k = min(30, 1 + int(rng.paretovariate(1.5)))
    ecom_share = rng.random() * 0.5
    chosen = []
    for _ in range(k * 4):
        if len(chosen) == k:
            break
        key = "ecom" if rng.random() < ecom_share else h["city"]
        m = rng.choices(pool[key], cum_weights=cum[key])[0]
        if m["id"] not in chosen and m["id"] not in banned.get(cid, ()):
            chosen.append(m["id"])
    if not chosen:
        continue
    fav = [1 / (i + 1) for i in range(len(chosen))]
    first = max(START, c["issued"])
    for _ in range(n):
        mid = rng.choices(chosen, weights=fav)[0]
        m = merchant[mid]
        t = at(day_between(first, END), hour_of(m["channel"]))
        if m["channel"] == "ecom":
            device, ip = ecom_where(cid, t)
            row = auth(cid, mid, amount(m["mcc"]), t, device, ip, "declined" if rng.random() < 0.03 else "approved")
            if row[7] == "approved" and rng.random() < 0.009:
                charge_back(row, pick(DISPUTES), 15, 60)
        else:
            row = auth(cid, mid, amount(m["mcc"]), t, result="declined" if rng.random() < 0.02 else "approved")
            if row[7] == "approved" and rng.random() < 0.0008:
                charge_back(row, pick(DISPUTES[1:3]), 10, 45)

# the M-317 regulars fill up outside the window; the window holds exactly the 70 planted cards
for cid in regulars:
    for _ in range(rng.randint(4, 14)):
        while True:
            d = day_between(START, END)
            if not CPP_FROM - timedelta(days=7) <= d <= CPP_TO + timedelta(days=60):
                break
        auth(cid, M317, amount("5541"), at(d, hour_of("pos")))

# ---- story 1: card testing ---------------------------------------------------------------------------------------------------
attempts = [cid for cid in test_cards] + [cid for cid in test_cards[:100]]
rng.shuffle(attempts)
declined = set(range(round(0.85 * len(attempts))))
night = datetime(TEST_NIGHT.year, TEST_NIGHT.month, TEST_NIGHT.day, 1, 10)
for i, cid in enumerate(attempts):
    t = night + timedelta(seconds=i * 31 + rng.randrange(20))
    auth(cid, pick(TEST_SHOPS), 1.00, t, TEST_DEVICE, f"{TEST_NET}.{rng.randint(10, 60)}", "declined" if i in declined else "approved")

# ---- story 3: colluding merchant M-221, look-alike M-140 ----------------------------------------------------------------------
fresh = [c["id"] for c in cards if c["id"] not in taken and c["holder"] not in FAMILY]
rng.shuffle(fresh)
m221_buyers = sorted(fresh[:240]) + m221_cb_cards
m221_sales = []
for cid in m221_buyers:
    for _ in range(1 if rng.random() < 0.75 else 2):
        last = date(2026, 7, 31) if cid in m221_cb_cards else END   # every planted chargeback lands by September
        t = at(day_between(max(START, card[cid]["issued"]), last), hour_of("ecom"))
        device, ip = ecom_where(cid, t)
        m221_sales.append(auth(cid, M221, amount("5732"), t, device, ip))
for cid in m221_cb_cards:
    row = min((r for r in m221_sales if r[1] == cid), key=lambda r: r[4])
    charge_back(row, "merchandise_not_received", 20, 50)
for i, cid in enumerate(refund_cards):
    d = date(2026, 4, 6) + timedelta(days=i * 11 + rng.randrange(6))
    auth(cid, M221, round(rng.uniform(420, 940), 2), at(d, rng.randint(9, 17)), result="refund")
m140_buyers = sorted(fresh[240:640])
for cid in m140_buyers:
    t = at(day_between(max(START, card[cid]["issued"]), END), hour_of("ecom"))
    device, ip = ecom_where(cid, t)
    row = auth(cid, M140, amount("4722"), t, device, ip)
    if date(2026, 3, 1) <= t.date() <= date(2026, 6, 30) and rng.random() < 0.3:        # trips in the strike month
        chargebacks.append([row, "service_not_provided", date(2026, 7, rng.randint(3, 28))])
    elif rng.random() < 0.006:
        charge_back(row, pick(DISPUTES), 15, 60)

# ---- story 2: account takeover, look-alike: the family tablet ----------------------------------------------------------------
online = []                                        # [account, card, email, phone, device, ship_to, changed_at]
acct_ids = [f"OA-{i:05d}" for i in range(10001, 10001 + N_ACCOUNTS)]
rng.shuffle(acct_ids)
with_account = FAMILY + sorted(h["id"] for h in rng.sample([h for h in holders if h["id"] not in FAMILY], N_ACCOUNTS - 4))
accounts = []
for aid, hid in zip(sorted(acct_ids), with_account):
    h = holder[hid]
    cid = sorted(cards_of.get(hid, []))[0] if hid in cards_of else None
    if cid is None:
        continue
    created = datetime(2020, 1, 1) + timedelta(minutes=rng.randrange(60 * 24 * 2400))
    ship = short(h["address"]) if hid not in FAMILY and rng.random() < 0.03 else h["address"]
    device = TABLET if hid in FAMILY[:2] else h["devices"][0]
    phone = h["phone"] if rng.random() > 0.05 else ""
    accounts.append({"id": aid, "holder": hid, "card": cid, "phone": phone, "ship": ship, "created": created})
    online.append([aid, cid, h["email"], phone, device, ship, created])
    if hid in FAMILY:
        for _ in range(rng.randint(2, 4)):
            online.append([aid, cid, h["email"], phone, TABLET, ship, at(day_between(START, END), rng.randint(8, 21))])
    elif len(h["devices"]) > 1 and rng.random() < 0.4:
        online.append([aid, cid, h["email"], phone, h["devices"][1], ship, at(day_between(START, END), hour_of("ecom"))])
    elif rng.random() < 0.03:
        ship = address(h["city"])
        online.append([aid, cid, h["email"], phone, h["devices"][0], ship, at(day_between(START, END), hour_of("ecom"))])
DROPS = ["Flat 3 18 Mercer Street Manchester", "Unit 7 Brook Yard Leeds", "41 Cobble Row Bristol",
         "Flat 12 9 Dunnock Avenue Leicester", "2 Elder Court Sheffield"]
victims = sorted(rng.sample([a for a in accounts if a["card"] not in taken and a["holder"] not in FAMILY
                             and card[a["card"]]["issued"] < START and a["created"].date() < ATO_FROM], 140), key=lambda a: a["id"])
ato_links = []
for i, a in enumerate(victims):
    taken.add(a["card"])
    device = ATO_DEVICES[i % 8] if i < 16 else pick(ATO_DEVICES)
    drop = DROPS[i % 5] if i < 10 else pick(DROPS)
    t = at(day_between(ATO_FROM, ATO_TO), rng.choice([0, 1, 2, 3, 4, 5, 23]))
    online.append([a["id"], a["card"], holder[a["holder"]]["email"], a["phone"], device, drop, t])
    ato_links.append((a["id"], device, drop))
    for _ in range(rng.randint(1, 2)):
        m = rng.choice([m for m in pool["ecom"] if m["mcc"] in ("5999", "5815")])
        row = auth(a["card"], m["id"], round(rng.uniform(250, 900), 2), t + timedelta(minutes=rng.randint(20, 4000)), device,
                   f"{ATO_NET}.{rng.randint(10, 90)}")
        charge_back(row, "account_takeover", 10, 40)

# ---- story 4: common point of purchase at M-317, look-alike: the supermarket M-052 --------------------------------------------
cpp_visit = {}
for cid in cpp_window:
    d = day_between(CPP_FROM, CPP_TO)
    cpp_visit[cid] = d
    auth(cid, M317, amount("5541"), at(d, hour_of("pos")))
ecom_pool = [m for m in pool["ecom"] if m["mcc"] in ("5999", "5815", "5942")]   # not electronics: its chargeback rate is M-221's yardstick


def defraud(cid, d):
    t = at(d, hour_of("ecom"))
    for j in range(rng.randint(1, 3)):
        m = rng.choice(ecom_pool)
        row = auth(cid, m["id"], round(rng.uniform(60, 650), 2), t + timedelta(hours=j * rng.randint(1, 9)), pick(FRAUD_DEVICES),
                   f"100.{rng.randint(120, 127)}.{rng.randint(0, 255)}.{rng.randint(2, 250)}")
        charge_back(row, FRAUD, 5, 25)


cpp_fraud_day = {}
for cid in cpp_cards:
    cpp_fraud_day[cid] = cpp_visit[cid] + timedelta(days=rng.randint(8, 40))
    defraud(cid, cpp_fraud_day[cid])
for cid in bg_fraud:
    defraud(cid, day_between(max(START, card[cid]["issued"]) + timedelta(days=60), date(2026, 9, 20)))

# ---- ids, order and checks ----------------------------------------------------------------------------------------------------
auths.sort(key=lambda r: (r[4], r[1], r[2]))
for i, r in enumerate(auths):
    r[0] = f"A-{i + 1:07d}"
chargebacks.sort(key=lambda c: (c[2], c[0][0]))
online.sort(key=lambda r: (r[6], r[0]))


def connected(edges):
    parent = {}

    def root(x):
        while parent.setdefault(x, x) != x:
            x = parent[x]
        return x
    for a, b in edges:
        parent[root(a)] = root(b)
    return len({root(x) for x in list(parent)}) == 1


assert connected([(a, d) for a, d, _ in ato_links] + [(a, s) for a, _, s in ato_links]), "the takeover ring is one group"
used_m052 = {r[1] for r in auths if r[2] == M052}
assert not used_m052 & set(cpp_cards), "no compromised card shops at the supermarket"
assert len(used_m052) > 600, f"the supermarket is busy ({len(used_m052)} cards)"


def rate(mids):
    sales = sum(1 for r in auths if r[2] in mids and r[7] == "approved")
    return sum(1 for c in chargebacks if c[0][2] in mids) / sales


peers = {m["id"] for m in merchants if m["mcc"] == "5732" and m["id"] != M221}
m221_ratio = rate({M221}) / rate(peers)
assert 7 <= m221_ratio <= 12, f"M-221 chargebacks are {m221_ratio:.1f}x its category"

# ---- write ----------------------------------------------------------------------------------------------------------------------


def write(rel, header, rows):
    f = DATA / rel
    f.parent.mkdir(parents=True, exist_ok=True)
    with f.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)


def split(feed, header, rows, when):
    """One landed extract (to 2026-09-30) and one staged extract (October 2026)."""
    up = feed.upper()
    write(f"inbox/{feed}/{up}_20260930.csv", header, [r for r in rows if when(r) <= LANDED_END])
    write(f"staged/{feed}/{up}_20261031.csv", header, [r for r in rows if when(r) > LANDED_END])


for old in sorted(DATA.glob("*/*/*.csv")):
    old.unlink()
write("inbox/cardholder/CARDHOLDER_20260930.csv", ["holder_id", "name", "email", "phone", "address"],
      [[h["id"], h["name"], h["email"], h["phone"], h["address"]] for h in holders])
write("inbox/card/CARD_20260930.csv", ["card_id", "bin", "holder_id", "issued_at"],
      [[c["id"], c["bin"], c["holder"], c["issued"].isoformat()] for c in sorted(cards, key=lambda c: c["id"])])
write("inbox/merchant/MERCHANT_20260930.csv", ["merchant_id", "name", "mcc", "channel", "city", "acquirer"],
      [[m["id"], m["name"], m["mcc"], m["channel"], m["city"], m["acquirer"]] for m in merchants])
AUTH_HEAD = ["auth_id", "card_id", "merchant_id", "amount", "auth_at", "device_id", "ip", "result"]
months = sorted({r[4].strftime("%Y%m") for r in auths})
for ym in months:
    rows = [r[:4] + [ts(r[4])] + r[5:] for r in auths if r[4].strftime("%Y%m") == ym]
    write(f"{'staged' if ym > LANDED_END.strftime('%Y%m') else 'inbox'}/card_auth/CARD_AUTH_{ym}.csv", AUTH_HEAD, rows)
split("chargeback", ["chargeback_id", "auth_id", "reason", "filed_at"],
      [[f"CB-{i + 1:05d}", c[0][0], c[1], c[2].isoformat()] for i, c in enumerate(chargebacks)],
      lambda r: date.fromisoformat(r[3]))
split("online_account", ["account_id", "card_id", "email", "phone", "device_id", "ship_to_address", "changed_at"],
      [r[:6] + [ts(r[6])] for r in online], lambda r: date.fromisoformat(r[6][:10]))

m140_cb = [c for c in chargebacks if c[0][2] == M140]
truth = {
    "seed": 20261107,
    "window": {"landed": [START.isoformat(), LANDED_END.isoformat()], "staged": [(LANDED_END + timedelta(days=1)).isoformat(), END.isoformat()]},
    "counts": {"cardholder": len(holders), "card": len(cards), "merchant": len(merchants), "online_account": len(accounts),
               "card_auth": len(auths), "chargeback": len(chargebacks)},
    "stories": {
        "card_testing": {"hub": TEST_DEVICE, "entities": sorted([TEST_DEVICE, TEST_NET + ".0/24", *TEST_SHOPS, *test_cards]),
                         "lookalikes": [PROXY_NET + ".0/24"]},
        "account_takeover": {"hub": ATO_DEVICES[0], "entities": sorted(ATO_DEVICES + [d.upper() for d in DROPS] + [a["id"] for a in victims]),
                             "lookalikes": sorted([TABLET, FAMILY_ADDR.upper()])},
        "colluding_merchant": {"hub": M221, "entities": sorted([M221, *refund_cards, *m221_cb_cards]), "lookalikes": [M140]},
        "common_point_of_purchase": {"hub": M317, "entities": sorted([M317, *cpp_cards]), "lookalikes": [M052]},
    },
    "card_testing": {"device": TEST_DEVICE, "net": TEST_NET + ".0/24", "shops": TEST_SHOPS, "night": TEST_NIGHT.isoformat(),
                     "cards": test_cards, "proxy_ip": PROXY_IP, "proxy_holders": proxy_holders},
    "account_takeover": {"devices": ATO_DEVICES, "drops": DROPS, "accounts": [a["id"] for a in victims], "tablet": TABLET,
                         "family": FAMILY, "family_address": FAMILY_ADDR},
    "colluding_merchant": {"refund_cards": refund_cards, "chargeback_cards": m221_cb_cards, "ratio_to_category": round(m221_ratio, 2),
                           "lookalike_strike_month": STRIKE, "lookalike_chargebacks": len(m140_cb)},
    "common_point_of_purchase": {"window": [CPP_FROM.isoformat(), CPP_TO.isoformat()], "cards": cpp_cards,
                                 "fraud_day": {c: d.isoformat() for c, d in sorted(cpp_fraud_day.items())},
                                 "in_window_not_defrauded": cpp_window[60:], "background_fraud_cards": bg_fraud},
}
TRUTH.write_text(json.dumps(truth, indent=1, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
print(f"cards {len(cards)}, auths {len(auths)}, chargebacks {len(chargebacks)}, online rows {len(online)}, "
      f"M-221 {m221_ratio:.1f}x its category, M-140 {len(m140_cb)} chargebacks, M-052 used by {len(used_m052)} cards")
