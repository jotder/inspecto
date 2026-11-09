#!/usr/bin/env python3
"""Regenerate the `la-utility` Space Template's electricity revenue-protection corpus - SYNTHETIC, deterministic (one seeded Random).

Writes spaces/_templates/la-utility/data/inbox/<feed>/ (12 months landed, 2025-10-01..2026-09-30) and data/staged/<feed>/ (the next
month, 2026-10, for the "new data arrives" step) for eight feeds:
  premise, customer_account (one extract each, landed only), meter_read and transformer_read (daily reads, one file per month),
  field_visit, service_order, prepaid_vend, payment (one landed extract, one staged month)
and tools/gen-la-utility-data.ground-truth.json (story -> entity ids and look-alike ids; NOT shipped in the data download).

Planted stories (plan: docs/archived-documents/plans-archive/2026-11-07-la-showcase-police-insurance-utility-plan.md, section 3; pinned by
tools/gen-la-utility-data.test.mjs over the files and UtilityLinksJobGoldenTest over the link Dataset):
  1 METER TAMPERING        22 premises under the metered transformers TX-007 and TX-019 drop 40-70 % within 2 weeks of a visit by
                           contractor C-14 (3 of the visits are joint with C-22); transformer supply stays flat, so loss rises
                           look-alike: a holiday home whose use drops every winter, with no visit
  2 ILLEGAL RECONNECTION   15 premises disconnected for arrears keep consuming; their reconnect orders were closed by C-22, no payment
                           look-alike: 10 premises reconnected after a genuine payment, by other contractors, reads zero while off
  3 ACCOUNT CHURN          6 premises, each with two accounts closed with arrears and reopened within 30 days under another name,
                           all 18 accounts paid from ONE bank account (12 closed with arrears + 6 open now)
                           look-alike: 5 genuine changes of tenant (arrears cleared, the new tenant has a new bank account)
  4 COMMERCIAL LOAD        6 residential premises with a flat 24/7 load at 3-5x their street median, all paid from one bank account
                           look-alike: a home with a heat pump and an electric car (high but seasonal and weekly load)
  5 PREPAID TOKEN FRAUD    vendor V-08 sells 40 prepaid premises tokens whose kWh credited is 2.5-4x the money received, mostly at night
                           look-alike: the supermarket vendor V-01 with high but consistently priced daytime sales
Scale: 1 000 premises (the plan's 8 000 scaled down so the committed corpus stays near 12 MB); 40 transformers, 12 of them metered.
Every name, id, bank account and coordinate is invented; cities are city centroids plus jitter. Nothing derives from a real source.
"""
from __future__ import annotations

import csv
import json
import math
import random
from datetime import date, datetime, timedelta
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
DATA = TOOLS.parent / "spaces" / "_templates" / "la-utility" / "data"
TRUTH = TOOLS / "gen-la-utility-data.ground-truth.json"
rng = random.Random(20261107)
START, LANDED_END, END = date(2025, 10, 1), date(2026, 9, 30), date(2026, 10, 31)
DAYS = [START + timedelta(days=i) for i in range((END - START).days + 1)]
N_TX, PER_TX = 40, 25
PRICE, STANDING = 0.28, 9.0                         # credit tariff per kWh and per month; prepaid tokens are PREPAID_PRICE per kWh
PREPAID_PRICE = 0.25
TAMPER_TX, METERED_EXTRA = ("TX-007", "TX-019"), 10
C14, C22, V08, V01 = "C-14", "C-22", "V-08", "V-01"
CITY = {"Leicester": (52.6369, -1.1398), "Coventry": (52.4068, -1.5197), "Nottingham": (52.9548, -1.1581)}
CITIES = list(CITY)
FIRST = ["Ada", "Basil", "Cora", "Dev", "Edie", "Flynn", "Gwen", "Hugo", "Isla", "Jude", "Kira", "Lars", "Maeve", "Nico", "Opal",
         "Piers", "Rhea", "Sami", "Tess", "Ugo", "Vida", "Wade", "Xena", "Yusuf", "Zara", "Arlo", "Bea", "Cyrus", "Dina", "Eben"]
LAST = ["Abbott", "Bramley", "Calloway", "Denholm", "Ellery", "Fairbairn", "Gilchrist", "Holloway", "Ingram", "Jessop", "Kinsella",
        "Lindqvist", "McAllister", "Nightingale", "Ormsby", "Prentice", "Quarry", "Rowntree", "Stanhope", "Treadwell", "Upton",
        "Varga", "Wetherby", "Yelland", "Zamora", "Ashby", "Blackwood", "Crowther", "Dunbar", "Everley"]
VARIANT = {"McAllister": "MacAllister", "Nightingale": "Nightengale", "Lindqvist": "Lindquist", "Holloway": "Halloway"}
STREET = ["Amber", "Bracken", "Chalk", "Dove", "Ember", "Flint", "Gorse", "Hazel", "Iron", "Jasper", "Kiln", "Larch", "Mill", "Oak",
          "Pippin", "Quarry", "Rowan", "Sorrel", "Teasel", "Willow"]
SUFFIX = ["Lane", "Road", "Close", "Street", "Way"]


def pick(seq):
    return seq[rng.randrange(len(seq))]


def at(day, h_lo, h_hi):
    """A timestamp on `day` between hour h_lo and h_hi (h_hi may pass midnight, e.g. 22..28)."""
    t = datetime(day.year, day.month, day.day) + timedelta(seconds=rng.randint(h_lo * 3600, h_hi * 3600 - 1))
    return t


def ts(t):
    return t.strftime("%Y-%m-%d %H:%M:%S")


# ---- transformers and premises ---------------------------------------------------------------------------------------------
TX = [f"TX-{i:03d}" for i in range(1, N_TX + 1)]
TX_CITY = {t: CITIES[min(i * 3 // N_TX, 2)] for i, t in enumerate(TX)}   # 14 Leicester, 13 Coventry, 13 Nottingham
TX_AT = {t: (CITY[TX_CITY[t]][0] + rng.uniform(-0.04, 0.04), CITY[TX_CITY[t]][1] + rng.uniform(-0.06, 0.06)) for t in TX}
others = [t for t in TX if t not in TAMPER_TX]
METERED = sorted(set(TAMPER_TX) | set(rng.sample(others, METERED_EXTRA)))   # 12 of 40 (30 %) transformers carry a meter
streets = [f"{s} {x}" for s in STREET for x in SUFFIX]
rng.shuffle(streets)
TX_STREETS = {t: (streets[2 * i], streets[2 * i + 1]) for i, t in enumerate(TX)}

pids = [f"PRM-{i:04d}" for i in range(1, N_TX * PER_TX + 1)]
rng.shuffle(pids)                                   # the id says nothing about the transformer or the role
PREM = {}
used_addr = set()
for i, pid in enumerate(pids):
    t = TX[i // PER_TX]
    street = TX_STREETS[t][rng.randrange(2)]
    while True:
        a = f"{rng.randint(1, 180)} {street}"
        if a not in used_addr:
            used_addr.add(a)
            break
    lat, lon = TX_AT[t][0] + rng.uniform(-0.004, 0.004), TX_AT[t][1] + rng.uniform(-0.006, 0.006)
    tariff = "commercial" if rng.random() < 0.10 else "residential"
    meter = "prepaid" if tariff == "residential" and rng.random() < 0.35 else "smart"
    PREM[pid] = dict(id=pid, tx=t, city=TX_CITY[t], street=street, address=a, lat=lat, lon=lon, tariff=tariff, meter=meter,
                     kind="com" if tariff == "commercial" else "res",
                     base=rng.lognormvariate(math.log(60), 0.5) if tariff == "commercial" else rng.lognormvariate(math.log(9), 0.35))

used = set()


def take(n, pred, pool=None):
    cand = [p for p in (pool or pids) if p not in used and pred(PREM[p])]
    out = rng.sample(cand, n)
    used.update(out)
    return sorted(out)


def res_smart(p):
    return p["tariff"] == "residential" and p["meter"] == "smart"


def clean(p):                                       # look-alikes sit away from every story's hubs and transformers
    return res_smart(p) and p["tx"] not in TAMPER_TX


TAMPER = sorted(take(11, lambda p: p["tariff"] == "residential" and p["tx"] == TAMPER_TX[0])
                + take(11, lambda p: p["tariff"] == "residential" and p["tx"] == TAMPER_TX[1]))
for pid in TAMPER:
    PREM[pid]["meter"] = "smart"                    # a tampered meter is a credit (smart) meter
HOLIDAY, = take(1, clean)
HEATPUMP, = take(1, clean)
RECON_BAD = take(15, lambda p: res_smart(p))
RECON_OK = take(10, clean)
CHURN = take(6, res_smart)
TENANT_OK = take(5, clean)
GROW = []
for _ in range(6):                                  # six different streets, so each has its own street median
    streets_taken = {PREM[g]["street"] for g in GROW}
    GROW += take(1, lambda p: res_smart(p) and p["street"] not in streets_taken)
GROW.sort()
FRAUD = take(40, lambda p: p["meter"] == "prepaid" and p["city"] == "Coventry")
LOOKALIKE_PREMISES = {HOLIDAY, HEATPUMP, *RECON_OK, *TENANT_OK}
STORY_PREMISES = set(TAMPER) | set(RECON_BAD) | set(CHURN) | set(GROW) | set(FRAUD)

for p in PREM.values():
    p["off"] = []                                   # [(from_day, to_day)] the supply is off (reads 0)
    p["drop"] = None                                # (day, factor) the meter registers `factor` of the true use from `day`
for g in GROW:
    p = PREM[g]
    med = sorted(q["base"] for q in PREM.values() if q["street"] == p["street"] and q["kind"] == "res" and q["id"] not in STORY_PREMISES)
    p["base"], p["kind"] = med[len(med) // 2] * rng.uniform(3.2, 4.8), "grow"
PREM[HOLIDAY]["kind"] = "holiday"
PREM[HEATPUMP]["kind"], PREM[HEATPUMP]["base"] = "heatpump", 24.0


# ---- contractors -------------------------------------------------------------------------------------------------------------
CONTRACTORS = [f"C-{i:02d}" for i in range(1, 31)]
WEIGHT = {c: 1.0 / (1 + i) ** 0.8 for i, c in enumerate(rng.sample(CONTRACTORS, len(CONTRACTORS)))}   # heavy-tailed workloads
BACKGROUND_C = [c for c in CONTRACTORS if c not in (C14, C22)]


def contractor(allow_story=True):
    pool = CONTRACTORS if allow_story else BACKGROUND_C
    return rng.choices(pool, [WEIGHT[c] for c in pool])[0]


visits, orders = [], []                             # [premise, contractor, datetime, type]; [premise, type, contractor, datetime]


def day_between(a, b):
    return a + timedelta(days=rng.randrange((b - a).days + 1))


# 1) tampering: a C-14 visit, then a 40-70 % drop 2-14 days later; three visits are joint with C-22
TAMPER_VISIT = {}
for i, pid in enumerate(TAMPER):
    v = day_between(date(2025, 12, 1), date(2026, 6, 30))
    TAMPER_VISIT[pid] = v
    t = at(v, 9, 16)
    visits.append([pid, C14, t, "meter_inspection"])
    if i in (3, 9, 16):
        visits.append([pid, C22, t + timedelta(minutes=rng.randint(0, 20)), "meter_inspection"])
    PREM[pid]["drop"] = (v + timedelta(days=rng.randint(2, 14)), 1 - rng.uniform(0.40, 0.70))

# 2) disconnections for arrears: the story keeps consuming and C-22 closes the reconnect; the look-alike pays, then is reconnected
DISCONNECT = {}
for pid in RECON_BAD + RECON_OK:
    d = day_between(date(2026, 1, 10), date(2026, 7, 31))
    DISCONNECT[pid] = d
    orders.append([pid, "disconnect", contractor(False), at(d, 8, 17)])
REPAID = {}
for pid in RECON_BAD:
    orders.append([pid, "reconnect", C22, at(DISCONNECT[pid] + timedelta(days=rng.randint(5, 25)), 9, 18)])
for pid in RECON_OK:
    paid = DISCONNECT[pid] + timedelta(days=rng.randint(3, 20))
    REPAID[pid] = paid
    back = paid + timedelta(days=rng.randint(1, 2))
    orders.append([pid, "reconnect", contractor(False), at(back, 8, 17)])
    PREM[pid]["off"].append((DISCONNECT[pid], back))

# background field work; C-14 and C-22 do ordinary work too, never at a look-alike premise
VISIT_TYPES = ["meter_reading", "meter_inspection", "fault_repair", "safety_check"]
plain = [p for p in pids if p not in STORY_PREMISES]
while len(visits) < 600:
    pid = pick(pids)
    c = contractor(pid not in LOOKALIKE_PREMISES)
    if pid == HOLIDAY:
        continue                                    # the holiday home has no visit at all
    visits.append([pid, c, at(day_between(START, END), 8, 18), rng.choices(VISIT_TYPES, [5, 3, 2, 1])[0]])
for _ in range(330):
    pid = pick([p for p in plain if p != HOLIDAY])
    orders.append([pid, "meter_swap", contractor(pid not in LOOKALIKE_PREMISES), at(day_between(START, END), 8, 17)])


# ---- accounts ----------------------------------------------------------------------------------------------------------------
bank_n = [40_000_000]


def fresh_bank():
    bank_n[0] += rng.randint(1, 97)
    return f"BA-{bank_n[0]}"


used_names = set()


def fresh_name(last=None):
    while True:
        n = f"{pick(FIRST)} {pick('ABCDEFGHJKLMNPRSTW')}. {last or pick(LAST)}"
        if n not in used_names:
            used_names.add(n)
            return n


ACC = []                                            # dict(premise, holder, opened, closed, bank, story)


def account(pid, opened, closed=None, bank=None, holder=None, story=""):
    a = dict(premise=pid, holder=holder or fresh_name(), opened=opened, closed=closed, bank=bank, story=story, arrears=0.0)
    if a["bank"] is None:
        cash = PREM[pid]["meter"] == "prepaid" and rng.random() < 0.7 or rng.random() < 0.06
        a["bank"] = "" if cash else fresh_bank()
    ACC.append(a)
    return a


def before_window():
    return date(2014, 1, 1) + timedelta(days=rng.randrange((START - date(2014, 1, 1)).days - 30))


CHURN_BANK, GROW_BANK = fresh_bank(), fresh_bank()
for pid in CHURN:                                   # two cycles per premise: closed with arrears, reopened within 30 days, same bank
    surname = pick(LAST)
    c1 = day_between(date(2025, 11, 1), date(2026, 2, 15))
    o1 = c1 + timedelta(days=rng.randint(3, 30))
    c2 = o1 + timedelta(days=rng.randint(120, 170))
    o2 = c2 + timedelta(days=rng.randint(3, 30))
    account(pid, before_window(), c1, CHURN_BANK, fresh_name(surname), "churn_closed")
    account(pid, o1, c2, CHURN_BANK, fresh_name(), "churn_closed")
    account(pid, o2, None, CHURN_BANK, fresh_name(surname), "churn_open")
TENANT_CHANGE = {}
for pid in TENANT_OK + rng.sample([p for p in plain if PREM[p]["meter"] == "smart" and p not in LOOKALIKE_PREMISES], 95):
    if pid in TENANT_CHANGE:
        continue
    c = day_between(date(2025, 11, 1), date(2026, 8, 31))
    o = c + timedelta(days=rng.randint(0, 20))
    TENANT_CHANGE[pid] = (account(pid, before_window(), c, story="tenant_old"), account(pid, o, None, story="tenant_new"))
for pid in pids:
    if pid in CHURN or pid in TENANT_CHANGE:
        continue
    bank = GROW_BANK if pid in GROW else None
    account(pid, before_window(), None, bank, story="grow" if pid in GROW else "")
# placeholders: a bank account written as UNKNOWN or zeros is no evidence (the link Job must not join them)
plain_acc = [a for a in ACC if not a["story"] and a["bank"] and a["premise"] not in LOOKALIKE_PREMISES and a["premise"] not in STORY_PREMISES]
for a in rng.sample(plain_acc, 9):
    a["bank"] = "UNKNOWN" if rng.random() < 0.5 else "00000000"
# spelling variants of a few holder names (identity resolution has work to do)
for a in ACC:
    last = a["holder"].rsplit(" ", 1)[1]
    if last in VARIANT and rng.random() < 0.3:
        a["holder"] = a["holder"].replace(last, VARIANT[last])
ACC.sort(key=lambda a: (a["opened"], a["premise"]))
for i, a in enumerate(ACC):
    a["id"] = f"ACC-{i + 1:05d}"
for pid in CHURN + TENANT_OK:
    for a in [x for x in ACC if x["premise"] == pid and x["opened"] >= START]:
        orders.append([pid, "connect", contractor(False), at(a["opened"], 8, 17)])
for pid in TENANT_CHANGE:
    if pid not in TENANT_OK:
        orders.append([pid, "connect", contractor(pid not in LOOKALIKE_PREMISES), at(TENANT_CHANGE[pid][1]["opened"], 8, 17)])


def account_on(pid, d):
    for a in ACC:
        if a["premise"] == pid and a["opened"] <= d and (a["closed"] is None or d <= a["closed"]):
            return a
    return None


# ---- consumption: true use per day, and what the meter registers --------------------------------------------------------------
def season(d, amp):
    return 1 + amp * math.cos(2 * math.pi * (d.timetuple().tm_yday - 15) / 365.25)


TRUE, READ = {}, {}
for pid in pids:
    p = PREM[pid]
    t_row, r_row = [], []
    for d in DAYS:
        wk = d.weekday() >= 5
        k = p["kind"]
        if k == "grow":
            v = p["base"] * rng.gauss(1, 0.03)
        elif k == "heatpump":
            v = p["base"] * season(d, 0.65) * (1.25 if wk else 1.0) * rng.gauss(1, 0.12)
        elif k == "com":
            v = p["base"] * season(d, 0.15) * (0.55 if wk else 1.0) * rng.gauss(1, 0.10)
        else:
            v = p["base"] * season(d, 0.28) * (1.08 if wk else 1.0) * rng.gauss(1, 0.12)
            if k == "holiday" and (d.month in (11, 12, 1, 2)):
                v = p["base"] * 0.12 * rng.gauss(1, 0.2)          # empty in winter: standby load only
        if any(a <= d < b for a, b in p["off"]):
            v = 0.0
        v = max(v, 0.0)
        r = v
        if p["drop"] and d >= p["drop"][0]:
            ramp = min(1.0, ((d - p["drop"][0]).days + 1) / 4)       # the drop builds over a few days
            r = v * (1 - ramp * (1 - p["drop"][1])) * rng.gauss(1, 0.04)
        t_row.append(v)
        r_row.append(max(r, 0.0))
    TRUE[pid], READ[pid] = t_row, r_row

# ---- prepaid vends -------------------------------------------------------------------------------------------------------------
VENDORS = [f"V-{i:02d}" for i in range(1, 13)]
VENDOR_CITY = {v: CITIES[i // 4] for i, v in enumerate(VENDORS)}
vends = []                                          # [premise, vendor, amount, kwh, datetime]
V08_LEGIT = []
for pid in pids:
    p = PREM[pid]
    if p["meter"] != "prepaid":
        continue
    local = [v for v in VENDORS if VENDOR_CITY[v] == p["city"]]
    if pid in FRAUD:
        mine = [V08]
    else:
        w = [6 if v == V01 else 1 for v in local]
        if p["city"] == "Coventry":
            local, w = [v for v in local if v != V08] + [V08], [1, 1, 1, 0.6]
        mine = [rng.choices(local, w)[0]]
        if rng.random() < 0.2:
            mine.append(pick([v for v in local if v not in mine]))
        if pid in LOOKALIKE_PREMISES:
            mine = [v for v in mine if v != V08] or [pick([v for v in local if v != V08])]
        if V08 in mine:
            V08_LEGIT.append(pid)
    fraud_from = date(2026, 1, 5)
    balance, i = 0.0, 0
    while i < len(DAYS):
        balance -= TRUE[pid][i]
        if balance < 2.0:                           # tops up when the credit runs low
            v = pick(mine)
            d = DAYS[i]
            if pid in FRAUD and d >= fraud_from:
                amount = float(pick([5, 10, 10, 15]))
                kwh = round(amount / PREPAID_PRICE * rng.uniform(2.5, 4.0), 1)
                t = at(d, 22, 28) if rng.random() < 0.75 else at(d, 9, 21)
            else:
                amount = float(pick([10, 20, 20, 30, 50] if v != V01 else [20, 20, 30]))
                kwh = round(amount / PREPAID_PRICE, 1)
                t = at(d, 8, 21)
            vends.append([pid, v, amount, kwh, t])
            balance += kwh
        i += 1

# ---- payments: credit accounts pay each month's bill; arrears where a story says so --------------------------------------------
UNPAID = {}                                         # account id -> (from_day, to_day) bills left unpaid
for a in ACC:
    if a["story"] == "churn_closed":
        UNPAID[a["id"]] = (a["closed"] - timedelta(days=rng.randint(110, 140)), a["closed"])
for pid in RECON_BAD:
    a = account_on(pid, DISCONNECT[pid])
    UNPAID[a["id"]] = (DISCONNECT[pid] - timedelta(days=95), END)         # never pays again
for pid in RECON_OK:
    a = account_on(pid, DISCONNECT[pid])
    UNPAID[a["id"]] = (DISCONNECT[pid] - timedelta(days=95), REPAID[pid])
payments = []                                       # [account, datetime, amount, bank]
months = sorted({(d.year, d.month) for d in DAYS})
for (y, m) in months:
    first = date(y, m, 1)
    nxt = date(y + (m == 12), m % 12 + 1, 1)
    for pid in pids:
        if PREM[pid]["meter"] != "smart":
            continue
        a = account_on(pid, first + timedelta(days=14))
        if not a:
            continue
        idx = [i for i, d in enumerate(DAYS) if first <= d < nxt]
        bill = round(sum(READ[pid][i] for i in idx) * PRICE + STANDING, 2)
        u = UNPAID.get(a["id"])
        if u and u[0] <= first + timedelta(days=14) <= u[1]:
            a["arrears"] += bill
            continue
        if rng.random() < 0.02:                     # a month missed by an ordinary customer
            a["arrears"] += bill
            continue
        pay_day = nxt + timedelta(days=rng.randint(4, 18))
        if pay_day > END:
            continue
        payments.append([a["id"], at(pay_day, 7, 22), bill, a["bank"]])
for pid in RECON_OK:                                # the arrears are paid off in one go, then the supply comes back
    a = account_on(pid, DISCONNECT[pid])
    payments.append([a["id"], at(REPAID[pid], 9, 17), round(a["arrears"], 2), a["bank"]])
    a["arrears"] = 0.0
for pid in TENANT_CHANGE:                           # a genuine leaver settles the final bill
    TENANT_CHANGE[pid][0]["arrears"] = 0.0

# ---- meter tamper flags, a few blank optional fields --------------------------------------------------------------------------
FLAG = {}
for i, pid in enumerate(TAMPER):
    if i % 4 == 0:
        FLAG[(pid, TAMPER_VISIT[pid])] = "1"


ON_TX = {t: [p for p in sorted(pids) if PREM[p]["tx"] == t] for t in TX}


def write(rel, header, rows):
    f = DATA / rel
    f.parent.mkdir(parents=True, exist_ok=True)
    with f.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)
    print(f"{f.relative_to(DATA.parent)}: {len(rows)} rows")


def split(feed, header, rows, when):
    """One landed extract and one staged month, rows in time order."""
    rows = sorted(rows, key=when)
    write(f"inbox/{feed}/{feed.upper()}_20260930.csv", header, [r for r in rows if when(r)[:10] <= LANDED_END.isoformat()])
    write(f"staged/{feed}/{feed.upper()}_20261031.csv", header, [r for r in rows if when(r)[:10] > LANDED_END.isoformat()])


write("inbox/premise/PREMISE_20260930.csv",
      ["premise_id", "address", "city", "lat", "lon", "tariff", "meter_type", "transformer_id"],
      [[p, PREM[p]["address"], PREM[p]["city"], f"{PREM[p]['lat']:.5f}", f"{PREM[p]['lon']:.5f}", PREM[p]["tariff"], PREM[p]["meter"],
        PREM[p]["tx"]] for p in sorted(pids)])
write("inbox/customer_account/CUSTOMER_ACCOUNT_20260930.csv",
      ["account_id", "premise_id", "holder_name", "opened_at", "closed_at", "bank_account", "arrears"],
      [[a["id"], a["premise"], a["holder"], a["opened"].isoformat(), a["closed"].isoformat() if a["closed"] else "", a["bank"],
        f"{a['arrears']:.2f}"] for a in ACC])
for (y, m) in months:
    rows = []
    for i, d in enumerate(DAYS):
        if (d.year, d.month) != (y, m):
            continue
        for pid in sorted(pids):
            flag = FLAG.get((pid, d), "1" if rng.random() < 0.0003 else ("" if rng.random() < 0.04 else "0"))
            rows.append([pid, d.isoformat(), f"{READ[pid][i]:.1f}", flag])
    where = "staged" if date(y, m, 1) > LANDED_END else "inbox"
    write(f"{where}/meter_read/METER_READ_{y}{m:02d}.csv", ["premise_id", "read_date", "kwh", "tamper_flag"], rows)
    rows = []
    for t in METERED:
        loss = 0.04 + (int(t[3:]) * 7919 % 30) / 1000.0    # 4-7 % technical loss, fixed per transformer
        for i, d in enumerate(DAYS):
            if (d.year, d.month) != (y, m):
                continue
            s = sum(TRUE[p][i] for p in ON_TX[t]) * (1 + loss) * rng.gauss(1, 0.01)
            rows.append([t, d.isoformat(), f"{s:.1f}"])
    write(f"{where}/transformer_read/TRANSFORMER_READ_{y}{m:02d}.csv", ["transformer_id", "read_date", "kwh_supplied"], rows)

visit_rows = [[f"VIS-{i + 1:05d}", v[0], v[1], ts(v[2]), "" if rng.random() < 0.04 else v[3]]
              for i, v in enumerate(sorted(visits, key=lambda v: (v[2], v[0], v[1])))]
split("field_visit", ["visit_id", "premise_id", "contractor_id", "visit_at", "visit_type"], visit_rows, lambda r: r[3])
order_rows = []
for i, o in enumerate(sorted(orders, key=lambda o: (o[3], o[0], o[1]))):
    open_now = o[1] == "meter_swap" and rng.random() < 0.04
    raised = o[3] - timedelta(hours=rng.randint(2, 120))
    order_rows.append([f"ORD-{i + 1:05d}", o[0], o[1], ts(raised), "" if open_now else o[2], "" if open_now else ts(o[3])])
split("service_order", ["order_id", "premise_id", "order_type", "raised_at", "closed_by", "closed_at"], order_rows, lambda r: r[3])
vend_rows = [[f"VND-{i + 1:06d}", v[0], v[1], f"{v[2]:.2f}", f"{v[3]:.1f}", ts(v[4])]
             for i, v in enumerate(sorted(vends, key=lambda v: (v[4], v[0])))]
split("prepaid_vend", ["vend_id", "premise_id", "vendor_id", "amount", "kwh", "vend_at"], vend_rows, lambda r: r[5])
pay_rows = [[f"PAY-{i + 1:06d}", p[0], ts(p[1]), f"{p[2]:.2f}", p[3]] for i, p in enumerate(sorted(payments, key=lambda p: (p[1], p[0])))]
split("payment", ["payment_id", "account_id", "paid_at", "amount", "bank_account"], pay_rows, lambda r: r[2])

# ---- ground truth (beside the generator; not shipped) ---------------------------------------------------------------------------
acc_of = lambda pred: sorted(a["id"] for a in ACC if pred(a))
truth = {
    "seed": 20261107,
    "note": "Story -> the entity ids that must form ONE connected group in the link Dataset, and look-alike ids that must have no link "
            "to any of them. Written by tools/gen-la-utility-data.py; not part of the data download.",
    "stories": {
        "meter_tampering": {"hub": C14, "entities": TAMPER + [C14, *TAMPER_TX], "lookalikes": [HOLIDAY]},
        "illegal_reconnection": {"hub": C22, "entities": RECON_BAD + [C22], "lookalikes": RECON_OK},
        "account_churn": {"hub": CHURN_BANK,
                          "entities": acc_of(lambda a: a["premise"] in CHURN) + CHURN + [CHURN_BANK],
                          "lookalikes": acc_of(lambda a: a["premise"] in TENANT_OK) + TENANT_OK
                          + sorted({a["bank"] for a in ACC if a["premise"] in TENANT_OK and a["bank"]})},
        "commercial_load": {"hub": GROW_BANK, "entities": GROW + acc_of(lambda a: a["premise"] in GROW) + [GROW_BANK],
                            "lookalikes": [HEATPUMP] + acc_of(lambda a: a["premise"] == HEATPUMP)
                            + sorted({a["bank"] for a in ACC if a["premise"] == HEATPUMP and a["bank"]})},
        "prepaid_token_fraud": {"hub": V08, "entities": FRAUD + [V08], "lookalikes": [V01]},
    },
    "metered_transformers": METERED,
    "tamper_visit": {p: TAMPER_VISIT[p].isoformat() for p in TAMPER},
    "disconnected": {p: DISCONNECT[p].isoformat() for p in RECON_BAD + RECON_OK},
}
TRUTH.write_text(json.dumps(truth, indent=1, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
print(f"{TRUTH.name}: {len(truth['stories'])} stories")
