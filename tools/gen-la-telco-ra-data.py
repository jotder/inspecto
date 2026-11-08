#!/usr/bin/env python3
"""Regenerate the `la-telco-ra` Space Template's telecom revenue-assurance corpus - SYNTHETIC, deterministic (one seeded Random).

Writes spaces/_templates/la-telco-ra/data/inbox/<feed>/ (12 months landed, 2025-10-01..2026-09-30) and data/staged/<feed>/ (the
next month, 2026-10, for the "new data arrives" step) for seven feeds:
  dealer, enterprise_trunk (one extract each, landed only), subscriber (one landed extract of every subscriber activated by the
  end of the landed months, plus the October activations), network_usage and billing_record (one row per subscriber per month,
  summed from switch records and from rated billing records), topup (by month topped up), trunk_call (one row per enterprise
  trunk, destination and day, by month)
and tools/gen-la-telco-ra-data.ground-truth.json (story -> entity ids and look-alike ids; NOT shipped in the data download).

Planted stories (plan: docs/superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md, spec 10; pinned by
tools/gen-la-telco-ra-data.test.mjs over the files and TelcoRaLinksJobGoldenTest over the link Datasets). SIM box, Wangiri and
IRSF are la-showcase's; this Space does not repeat them.
  1 UNBILLED USAGE      300 SIMs activated by one dealer on one device on one day (2026-03-17) use the network heavily every
                        month and never reach billing (no billing record, no top-up)
                        look-alike: 40 internal test SIMs (plan "Internal test", the known list) activated by the network test
                        team's own devices, also used and never billed - and about a dozen ordinary subscribers whose first two
                        months reached billing late
  2 DEALER COMMISSION   one dealer activates 400 SIMs on its 3 devices over 6 weeks (2026-02-02..03-15); each is topped up once
    FRAUD               with 5.00 at the dealer's own shop within 2 days (the first recharge that earns the commission), makes a
                        test call and goes dormant
                        look-alike: a dealer near a university activates 350 SIMs in the first two weeks of term (2025-10-01..
                        10-12); the students keep topping up and using them all year
  3 PBX HACKING         4 enterprise trunks of 4 small firms call premium-rate numbers in 3 countries at night and at weekends
                        (attacks over six weekends, April-June 2026)
                        look-alike: a firm with a real office in Singapore calling it every working night (that office's working
                        hours), and two firms calling North American offices in the evening
Scale: 7 500 subscribers (spec: 15 000 - the monthly usage and billing feeds would triple the data), 200 dealers, 150 trunks of
120 enterprise customers. MSISDNs are in the unassigned +999 range and IMSIs use the 001-01 test network; dealer names are
invented; cities are real only at city level; country codes are destinations only. Nothing derives from a real source.
"""
from __future__ import annotations

import csv
import json
import math
import random
from datetime import date, datetime, timedelta
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
DATA = TOOLS.parent / "spaces" / "_templates" / "la-telco-ra" / "data"
TRUTH = TOOLS / "gen-la-telco-ra-data.ground-truth.json"
SEED = 20261110
rng = random.Random(SEED)
START, LANDED_END, END = date(2025, 10, 1), date(2026, 9, 30), date(2026, 10, 31)
MONTHS = [date(2025 + (9 + i) // 12, (9 + i) % 12 + 1, 1) for i in range(13)]   # 2025-10 .. 2026-10
N_SUBS, N_DEALERS, N_CORNER_POS, N_CUST, N_TRUNKS = 7500, 199, 170, 120, 150
OFF_HOURS = 300   # off-hours minutes on one trunk to one destination that the PBX screen flags (documented in the Job)

CITIES = ["Leeds", "Sheffield", "Manchester", "Liverpool", "Newcastle", "Glasgow"]
NAME_A = ["Northgate", "Ridgeway", "Kestrel", "Juniper", "Harbour", "Larkspur", "Millstone", "Copperfield", "Bramble", "Ashdown",
          "Foxglove", "Greystone", "Hawthorn", "Ironbridge", "Kingfisher", "Lantern", "Meadow", "Oakridge", "Pebble", "Quayside",
          "Redwing", "Saltmarsh", "Thistle", "Underhill", "Valley", "Westbrook", "Yardley", "Zephyr", "Cobalt", "Driftwood"]
NAME_B = ["Mobile", "Phones", "Connect", "Wireless", "Talk", "Handsets", "Mobile Hub", "Phone Shop"]
PLANS_PRE = ["Prepaid 10", "Prepaid 20", "Prepaid 30"]
PLANS_POST = ["Postpaid S", "Postpaid M", "Postpaid L"]
BG_COUNTRIES = ["FR", "DE", "ES", "IE", "NL", "US", "IN", "PL", "IT", "AE", "ZA", "AU", "SG"]
PRN = ["LT", "SK", "SI"]   # the three countries whose premium-rate ranges the hacked trunks call (premium ranges exist everywhere)

DL_TEST, DL_UNBILLED, DL_FRAUD, DL_UNI = "DL-000", "DL-047", "DL-112", "DL-163"
UNBILLED_DAY = date(2026, 3, 17)
FRAUD_WEEKS = [date(2026, 2, 2) + timedelta(weeks=i) for i in range(6)]   # Mondays
UNI_FROM, UNI_TO = date(2025, 10, 1), date(2025, 10, 12)


def rand_date(a: date, b: date) -> date:
    return a + timedelta(days=rng.randint(0, (b - a).days))


def month_start(d: date) -> date:
    return d.replace(day=1)


def month_end(m: date) -> date:
    return (m.replace(day=28) + timedelta(days=4)).replace(day=1) - timedelta(days=1)


def at(d: date, lo=9, hi=19) -> datetime:
    return datetime(d.year, d.month, d.day, rng.randint(lo, hi - 1), rng.randint(0, 59), rng.randint(0, 59))


# ---- dealers, devices, points of sale ---------------------------------------------------------------------------------------------

names = rng.sample([f"{a} {b}" for a in NAME_A for b in NAME_B], N_DEALERS)
dealers = {DL_TEST: {"name": "Network test team (internal)", "city": "Leeds", "weight": 0.0}}
for i in range(1, N_DEALERS + 1):
    dealers[f"DL-{i:03d}"] = {"name": names[i - 1], "city": rng.choice(CITIES), "weight": rng.paretovariate(1.4)}
dealers[DL_UNBILLED].update(city="Manchester", weight=3.0)
dealers[DL_FRAUD].update(city="Leeds", weight=2.5)
dealers[DL_UNI].update(city="Sheffield", weight=2.5)
n_dev = 0
for d_id, d in dealers.items():
    k = {DL_TEST: 2, DL_FRAUD: 3, DL_UNBILLED: 2, DL_UNI: 2}.get(d_id) or rng.choice([1, 1, 2, 2, 3])
    d["devices"] = [f"DEV-{n_dev + j + 1:05d}" for j in range(k)]
    n_dev += k
pos_list = [(d_id, d["city"]) for d_id, d in dealers.items() if d_id != DL_TEST] + [(None, rng.choice(CITIES)) for _ in range(N_CORNER_POS)]
rng.shuffle(pos_list)
pos_city = {}
for i, (d_id, city) in enumerate(pos_list, 1):
    pos_city[f"POS-{i:04d}"] = city
    if d_id:
        dealers[d_id]["pos"] = f"POS-{i:04d}"
pos_by_city = {c: [p for p, pc in pos_city.items() if pc == c] for c in CITIES}
CAMPUS = [dealers[DL_UNI]["pos"]] + [p for p in pos_by_city["Sheffield"] if p != dealers[DL_UNI]["pos"]][:9]
POS_FRAUD = dealers[DL_FRAUD]["pos"]

# ---- subscribers ---------------------------------------------------------------------------------------------------------------------

numbers = rng.sample(range(10 ** 7), N_SUBS)
imsis = rng.sample(range(10 ** 9), N_SUBS)
subs = []


def subscriber(dealer, act: datetime, plan, kind, device=True):
    i = len(subs)
    d = dealers[dealer]
    s = {"msisdn": f"9996{numbers[i]:07d}", "imsi": f"00101{imsis[i]:010d}", "dealer": dealer, "act": act, "plan": plan,
         "device": rng.choice(d["devices"]) if device else "", "kind": kind, "city": d["city"], "churn": None, "dormant": False,
         "usage": {}, "billing": {}, "topups": []}
    subs.append(s)
    return s


bg_dealers = [d for d in dealers if d != DL_TEST]
weights = [dealers[d]["weight"] for d in bg_dealers]
N_BG = N_SUBS - 300 - 400 - 350 - 40
fraud_lo, fraud_hi = FRAUD_WEEKS[0], FRAUD_WEEKS[-1] + timedelta(days=6)
for _ in range(N_BG):
    dealer = rng.choices(bg_dealers, weights)[0]
    if rng.random() < 0.25:
        day = rand_date(START, END)
    else:
        day = START - timedelta(days=1 + min(2550, int(rng.expovariate(1 / 700))))
    # the two special dealers' story weeks hold their story activations only
    if dealer == DL_FRAUD and fraud_lo <= day <= fraud_hi:
        day += timedelta(days=50)
    if dealer == DL_UNI and date(2025, 9, 29) <= day <= date(2025, 10, 19):
        day += timedelta(days=30)
    if dealer == DL_UNBILLED and day == UNBILLED_DAY:
        day += timedelta(days=1)
    plan = rng.choice(PLANS_PRE) if rng.random() < 0.75 else rng.choice(PLANS_POST)
    # activations recorded before 2022 often carry no device (the dealer app did not record it then)
    s = subscriber(dealer, at(day), plan, "background", device=not (day.year < 2022 and rng.random() < 0.35))

unbilled = [subscriber(DL_UNBILLED, datetime(2026, 3, 17, 10, 2, 0) + timedelta(seconds=79 * i + rng.randint(0, 40)), "Prepaid 10",
                       "unbilled") for i in range(300)]
for s in unbilled:
    s["device"] = dealers[DL_UNBILLED]["devices"][0]
test_sims = [subscriber(DL_TEST, at(rand_date(date(2024, 1, 8), date(2024, 6, 28))), "Internal test", "test") for _ in range(40)]
fraud = []
for w, n in zip(FRAUD_WEEKS, [67, 67, 67, 67, 66, 66]):
    for _ in range(n):
        fraud.append(subscriber(DL_FRAUD, at(w + timedelta(days=rng.randint(0, 4))), "Prepaid 10", "fraud"))
assert len(fraud) == 400
uni = [subscriber(DL_UNI, at(rand_date(UNI_FROM, UNI_TO), 10, 18), rng.choice(["Prepaid 10", "Prepaid 20"]), "uni") for _ in range(350)]
assert len(subs) == N_SUBS

# ---- life: churn, dormancy, monthly usage and billing --------------------------------------------------------------------------------

for s in subs:
    if s["kind"] != "background":
        continue
    post = s["plan"].startswith("Postpaid")
    if not post and s["act"].date() < START and rng.random() < 0.08:
        s["dormant"] = True
    m = month_start(max(s["act"].date(), START))
    while m <= END:
        if rng.random() < (0.006 if post else 0.013):
            s["churn"] = rand_date(m, month_end(m))
            break
        m = month_start(m + timedelta(days=32))
    s["mu_min"] = rng.lognormvariate(math.log(150), 0.6)
    s["mu_mb"] = rng.lognormvariate(math.log(3000), 0.8)


def active_fraction(s, m: date) -> float:
    a, b = max(m, s["act"].date()), min(month_end(m), s["churn"] or END)
    if b < a:
        return 0.0
    return ((b - a).days + 1) / ((month_end(m) - m).days + 1)


for s in subs:
    for m in MONTHS:
        f = active_fraction(s, m)
        if f <= 0:
            continue
        k = s["kind"]
        if k == "background":
            if s["dormant"]:
                continue
            mins = round(rng.lognormvariate(math.log(s["mu_min"] * f), 0.35))
            mb = round(rng.lognormvariate(math.log(s["mu_mb"] * f), 0.45))
        elif k == "unbilled":
            mins, mb = round(rng.uniform(800, 2500) * f), round(rng.uniform(20000, 60000) * f)
        elif k == "test":
            mins, mb = rng.randint(50, 300), rng.randint(1000, 10000)
        elif k == "fraud":
            if m != month_start(s["act"].date()):
                continue
            mins, mb = rng.randint(1, 4), rng.randint(0, 3)
        else:   # uni: students, lighter over the summer
            summer = m.month in (7, 8)
            mins = round(rng.uniform(40, 200) * f * (0.5 if summer else 1))
            mb = round(rng.uniform(4000, 15000) * f * (0.5 if summer else 1))
        if mins <= 0 and mb <= 0:
            continue
        s["usage"][m] = (max(mins, 0), max(mb, 0))
        if k not in ("unbilled", "test"):
            s["billing"][m] = (mins, mb + (rng.choice([-1, 1]) if rng.random() < 0.03 and mb > 1 else 0))

# background leakage: 50 single subscriber-months that never reached billing (a dropped batch), and a dozen subscribers whose first
# two months reached billing late (a provisioning lag) - the reconciliation's ordinary noise
special = {DL_TEST, DL_UNBILLED, DL_FRAUD, DL_UNI}
bg_used = [s for s in subs if s["kind"] == "background" and s["dealer"] not in special and len(s["usage"]) >= 3]
late = [s for s in bg_used if date(2025, 11, 1) <= s["act"].date() <= date(2026, 6, 30)]
late = rng.sample(late, 12)
for s in late:
    s["kind_note"] = "late"
    for m in sorted(s["usage"])[:2]:
        del s["billing"][m]
dropped = rng.sample([s for s in bg_used if "kind_note" not in s], 50)
for s in dropped:
    m = rng.choice([m for m in sorted(s["usage"]) if m <= LANDED_END])
    del s["billing"][m]

# ---- top-ups ------------------------------------------------------------------------------------------------------------------------

AMOUNTS = {"Prepaid 10": [5, 10, 10, 10, 20], "Prepaid 20": [10, 20, 20, 20, 30], "Prepaid 30": [20, 30, 30, 30, 50]}
for s in subs:
    k = s["kind"]
    if k == "fraud":
        s["topups"].append((5.0, at(s["act"].date() + timedelta(days=rng.randint(0, 2))), "retail", POS_FRAUD))
        continue
    if k in ("unbilled", "test") or s["plan"].startswith("Postpaid") or s["dormant"]:
        continue
    if k == "uni":
        fav = rng.sample(CAMPUS, rng.choice([1, 2]))
        t = s["act"] + timedelta(hours=rng.randint(1, 30))
        while t.date() <= END:
            ch = "retail" if rng.random() < 0.5 else "app"
            s["topups"].append((float(rng.choice([10, 10, 20])), at(t.date(), 8, 22), ch, rng.choice(fav) if ch == "retail" else ""))
            t += timedelta(days=rng.randint(20, 40))
        continue
    fav = rng.sample(pos_by_city[s["city"]], rng.choice([1, 1, 2]))
    t = datetime.combine(max(s["act"].date(), START), datetime.min.time()) + timedelta(days=rng.randint(0, 40))
    stop = s["churn"] or END
    while t.date() <= stop:
        r = rng.random()
        ch = "retail" if r < 0.55 else "app" if r < 0.85 else "card"
        s["topups"].append((float(rng.choice(AMOUNTS[s["plan"]])), at(t.date(), 8, 22), ch, rng.choice(fav) if ch == "retail" else ""))
        t += timedelta(days=rng.randint(30, 75))

# ---- enterprise trunks and their international calls -----------------------------------------------------------------------------

customers = [f"ENT-{i:04d}" for i in range(1, N_CUST + 1)]
trunk_owner = list(customers) + rng.sample(customers, 25) + rng.sample(customers, 5)
rng.shuffle(trunk_owner)
trunks = {f"TRK-{i:03d}": c for i, c in enumerate(trunk_owner, 1)}
by_customer = {}
for t, c in trunks.items():
    by_customer.setdefault(c, []).append(t)
single = [t for t, c in trunks.items() if len(by_customer[c]) == 1]
picked = rng.sample(single, 7)
HACKED, TRK_LA, US_EVENING = picked[:4], picked[4], picked[5:7]
calls = {}   # (trunk, country, type, day) -> [minutes, start_hour]


def call(t, country, ntype, day, minutes, hour):
    key = (t, country, ntype, day)
    if key in calls:
        calls[key][0] += minutes
        calls[key][1] = min(calls[key][1], hour)
    else:
        calls[key] = [minutes, hour]


DAYS = [START + timedelta(days=i) for i in range((END - START).days + 1)]
HOURS = [8, 9, 9, 10, 10, 10, 11, 11, 11, 12, 13, 14, 14, 14, 15, 15, 15, 16, 16, 17, 18]
hotels = rng.sample([t for t in trunks if t not in picked], 6)
premium_day = rng.sample([t for t in trunks if t not in picked and t not in hotels], 8)
for t in trunks:
    pool = [c for c in BG_COUNTRIES if c != "SG" or t not in hotels + US_EVENING]
    dests = [(c, "GEO" if rng.random() < 0.8 else "MOB") for c in rng.sample(pool, rng.choice([1, 2, 2, 3]))]
    if t == TRK_LA:
        dests = [d for d in dests if d[0] != "SG"]
    for c, nt in dests:
        p = rng.uniform(0.03, 0.3)
        for day in DAYS:
            weekend = day.isoweekday() >= 6
            if weekend and t not in hotels:
                continue
            if rng.random() < (p * 0.15 if weekend else p):
                call(t, c, nt, day, max(1, round(rng.lognormvariate(math.log(12), 0.8) * (0.5 if weekend else 1))),
                     rng.choice(HOURS) if rng.random() > 0.02 else rng.choice([19, 20]))
    if t in premium_day:
        c = rng.choice(["IE", "NL"])
        for day in DAYS:
            if day.isoweekday() <= 5 and rng.random() < 0.02:
                call(t, c, "PRN", day, rng.randint(5, 20), rng.randint(9, 16))
for t in US_EVENING:
    for day in DAYS:
        if day.isoweekday() <= 5 and rng.random() < 0.6:
            call(t, "US", "GEO", day, rng.randint(15, 45), rng.choice([21, 21, 22]))
for day in DAYS:   # the Singapore office: its working day is the night at home
    if day.isoweekday() <= 5 and rng.random() < 0.85:
        call(TRK_LA, "SG", "GEO", day, rng.randint(15, 80), rng.choice([1, 2, 2, 3, 3, 7, 8]))
# six attack weekends, Friday night to Monday morning; each hacked trunk is hit on one or two of them
ATTACKS = [date(2026, 4, 3), date(2026, 4, 24), date(2026, 5, 8), date(2026, 5, 22), date(2026, 6, 5), date(2026, 6, 19)]
hit = {HACKED[0]: [0, 3], HACKED[1]: [1], HACKED[2]: [2, 5], HACKED[3]: [4]}
for t, ws in hit.items():
    targets = PRN if t in (HACKED[0], HACKED[2]) else rng.sample(PRN, 2)
    for w in ws:
        fri = ATTACKS[w]
        for off, hours in ((0, [22, 23]), (1, [0, 1, 2, 3, 13, 22]), (2, [0, 1, 2, 3, 11, 23]), (3, [0, 1, 2, 3, 4])):
            for c in targets:
                call(t, c, "PRN", fri + timedelta(days=off), rng.randint(200, 900), rng.choice(hours))
if not set(PRN) <= {c for (t, c, nt, _) in calls if t in HACKED and nt == "PRN"}:
    raise SystemExit("the attacks must cover all three countries")


off_hours = {}
for (t, c, nt, day), (m, h) in calls.items():
    if day <= LANDED_END and (h < 7 or h >= 21 or day.isoweekday() >= 6):
        off_hours[(t, c, nt)] = off_hours.get((t, c, nt), 0) + m
flagged = sorted(k for k, m in off_hours.items() if m >= OFF_HOURS)
pairs = {(t, c, nt) for (t, c, nt, _) in calls}
expected = sorted([(t, c, "PRN") for t in HACKED for c in PRN if (t, c, "PRN") in pairs]
                  + [(TRK_LA, "SG", "GEO")] + [(t, "US", "GEO") for t in US_EVENING])
assert flagged == expected, (flagged, expected)
assert not any(t not in HACKED and nt == "PRN" and c in PRN for (t, c, nt, _) in calls), "only the hacked trunks call the story's ranges"

# ---- checks on the planted shape ------------------------------------------------------------------------------------------------------


def week(d: date) -> date:
    return d - timedelta(days=d.weekday())


earning = {}
for s in subs:
    if s["topups"] and s["act"].date() <= LANDED_END:
        earning.setdefault((s["dealer"], week(s["act"].date())), []).append(s)
spikes = {k: v for k, v in earning.items() if len(v) >= 50}
assert {k[0] for k in spikes} == {DL_FRAUD, DL_UNI}, sorted(spikes)
assert sorted(s["msisdn"] for v in spikes.values() for s in v) == sorted(s["msisdn"] for s in fraud + uni)
unbilled_months = lambda s: sum(1 for m in s["usage"] if m <= LANDED_END and m not in s["billing"])   # noqa: E731
over = [s for s in subs if unbilled_months(s) >= 2]
assert sorted(s["msisdn"] for s in over) == sorted(s["msisdn"] for s in unbilled + test_sims + late)
assert all(unbilled_months(s) == 7 for s in unbilled) and all(unbilled_months(s) == 12 for s in test_sims)
assert all(len(s["topups"]) == 1 and len(s["usage"]) == 1 for s in fraud)
assert all(sum(1 for t in s["topups"] if t[1].date() <= LANDED_END) >= 6 and len(s["usage"]) == 13 for s in uni)
assert all(s["dealer"] != DL_UNBILLED for s in late + dropped)

# ---- write ------------------------------------------------------------------------------------------------------------------------


def write(rel, header, rows):
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

write("inbox/dealer/DEALER_20260930.csv", ["dealer_id", "name", "city"], [[d, v["name"], v["city"]] for d, v in sorted(dealers.items())])
write("inbox/enterprise_trunk/ENTERPRISE_TRUNK_20260930.csv", ["trunk_id", "customer_id"], sorted(trunks.items()))
SUB_HEAD = ["msisdn", "imsi", "dealer_id", "activated_at", "plan", "activation_device"]
by_act = sorted(subs, key=lambda s: (s["act"], s["msisdn"]))
write("inbox/subscriber/SUBSCRIBER_20260930.csv", SUB_HEAD,
      [[s["msisdn"], s["imsi"], s["dealer"], ts(s["act"]), s["plan"], s["device"]] for s in by_act if s["act"].date() <= LANDED_END])
write("staged/subscriber/SUBSCRIBER_20261031.csv", SUB_HEAD,
      [[s["msisdn"], s["imsi"], s["dealer"], ts(s["act"]), s["plan"], s["device"]] for s in by_act if s["act"].date() > LANDED_END])
for m in MONTHS:
    ym, box = m.strftime("%Y%m"), "staged" if m > LANDED_END else "inbox"
    write(f"{box}/network_usage/NETWORK_USAGE_{ym}.csv", ["msisdn", "period", "minutes", "mb"],
          sorted([s["msisdn"], m.isoformat(), *s["usage"][m]] for s in subs if m in s["usage"]))
    write(f"{box}/billing_record/BILLING_RECORD_{ym}.csv", ["msisdn", "period", "billed_minutes", "billed_mb"],
          sorted([s["msisdn"], m.isoformat(), *s["billing"][m]] for s in subs if m in s["billing"]))
    write(f"{box}/topup/TOPUP_{ym}.csv", ["msisdn", "amount", "topped_up_at", "channel", "pos_id"],
          sorted([s["msisdn"], f"{a:.2f}", ts(t), ch, pos] for s in subs for a, t, ch, pos in s["topups"] if month_start(t.date()) == m))
    write(f"{box}/trunk_call/TRUNK_CALL_{ym}.csv", ["trunk_id", "call_date", "destination_country", "number_type", "minutes", "start_hour"],
          sorted([t, day.isoformat(), c, {"GEO": "geographic", "MOB": "mobile", "PRN": "premium"}[nt], mh[0], mh[1]]
                 for (t, c, nt, day), mh in calls.items() if month_start(day) == m))

ids = lambda ss: sorted(s["msisdn"] for s in ss)   # noqa: E731
landed = lambda key: sum(1 for s in subs for m in s[key] if m <= LANDED_END)   # noqa: E731
prn_nodes = sorted(f"CTRY-{c}-PRN" for c in PRN)
truth = {
    "seed": SEED,
    "window": {"landed": [START.isoformat(), LANDED_END.isoformat()], "staged": [(LANDED_END + timedelta(days=1)).isoformat(), END.isoformat()]},
    "off_hours_minutes_flag": OFF_HOURS,
    "counts": {"dealer": len(dealers), "subscriber_landed": sum(1 for s in subs if s["act"].date() <= LANDED_END),
               "network_usage_landed": landed("usage"), "billing_record_landed": landed("billing"),
               "topup_landed": sum(1 for s in subs for t in s["topups"] if t[1].date() <= LANDED_END),
               "enterprise_trunk": len(trunks), "trunk_call_landed": sum(1 for k in calls if k[3] <= LANDED_END)},
    "stories": {
        "unbilled_usage": {"hub": DL_UNBILLED, "view": "telco_ra_unbilled_usage",
                           "entities": sorted([DL_UNBILLED, dealers[DL_UNBILLED]["devices"][0], *ids(unbilled)]), "lookalikes": [DL_TEST]},
        "dealer_commission_fraud": {"hub": DL_FRAUD, "view": "telco_ra_commission_spikes",
                                    "entities": sorted([DL_FRAUD, POS_FRAUD, *dealers[DL_FRAUD]["devices"], *ids(fraud)]), "lookalikes": [DL_UNI]},
        "pbx_hacking": {"hub": prn_nodes[0], "view": "telco_ra_pbx_off_hours",
                        "entities": sorted([*HACKED, *(trunks[t] for t in HACKED), *prn_nodes]), "lookalikes": [TRK_LA]},
    },
    "unbilled_usage": {"dealer": DL_UNBILLED, "device": dealers[DL_UNBILLED]["devices"][0], "day": UNBILLED_DAY.isoformat(),
                       "subscribers": ids(unbilled), "test_dealer": DL_TEST, "test_devices": dealers[DL_TEST]["devices"],
                       "test_sims": ids(test_sims), "late_billing": ids(late), "dropped_months": ids(dropped)},
    "dealer_commission_fraud": {"dealer": DL_FRAUD, "devices": dealers[DL_FRAUD]["devices"], "pos": POS_FRAUD,
                                "weeks": [w.isoformat() for w in FRAUD_WEEKS], "subscribers": ids(fraud),
                                "lookalike_dealer": DL_UNI, "lookalike_subscribers": ids(uni), "campus_pos": sorted(CAMPUS)},
    "pbx_hacking": {"trunks": sorted(HACKED), "customers": sorted(trunks[t] for t in HACKED), "countries": PRN, "nodes": prn_nodes,
                    "attack_fridays": [d.isoformat() for d in ATTACKS], "lookalike_trunk": TRK_LA, "lookalike_customer": trunks[TRK_LA],
                    "evening_trunks": sorted(US_EVENING)},
}
TRUTH.write_text(json.dumps(truth, indent=1, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
c = truth["counts"]
print(f"subscribers {c['subscriber_landed']} landed of {len(subs)}, usage {c['network_usage_landed']}, billing {c['billing_record_landed']}, "
      f"topups {c['topup_landed']}, trunk calls {c['trunk_call_landed']}, devices {n_dev}, pos {len(pos_city)}")
