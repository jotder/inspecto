#!/usr/bin/env python3
"""Regenerate the `la-claims` Space Template's insurance-claims corpus - SYNTHETIC, deterministic (one seeded Random).

Writes spaces/_templates/la-claims/data/inbox/<feed>/<FEED>_20260930.csv for six feeds:
  claim, claim_party, policy, provider_bill, accident_location, provider_directory

Planted stories (ground truth pinned by LaClaimsGoldenTest; plan: docs/superpower/la-multi-vertical-showcase-plan.md, 2.C):
  1 staged-accident RING   12 claimants over 9 claims share 4 phones and 3 addresses; one garage GAR-03, one clinic CLI-04, one lawyer LAW-02
  2 REPEAT PASSENGER       one ring member is a passenger on 6 of those claims with 6 different drivers in under 7 months
  3 GEO CLUSTER            11 accidents within 400 m of one junction at 02:00-04:00, none with a police report (the 9 ring claims + 2 others)
  4 INFLATED CLINIC        CLI-04 bills about 3.4x its peers per claim and is referred to ONLY by the ring's lawyer LAW-02
  5 EARLY-CLAIM POLICIES   7 policies claimed on within 20 days of inception, all bought through broker BRK-05
  6 NEAR-DUPLICATE         one claimant is the ring member CL-ring[11] under a one-letter-different name, same date of birth and phone
  7 BENIGN look-alikes     a family of four sharing one phone and address (two claims in 18 months) and the busy GP clinic CLI-01
Also: blank / placeholder phones and addresses (must NOT make links) and 40ish two-vehicle accidents (same_accident links).
Every name, id, phone, address and coordinate is invented; cities are city centroids plus jitter. Nothing derives from a real source.
"""
from __future__ import annotations

import csv
import random
from datetime import date, datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "spaces" / "_templates" / "la-claims" / "data" / "inbox"
rng = random.Random(20261016)
DAY = "20260930"
START, END = date(2025, 4, 1), date(2026, 9, 30)
N_PERSONS, N_CLAIMS = 380, 520

GARAGES = [f"GAR-{i:02d}" for i in range(1, 10)]
CLINICS = [f"CLI-{i:02d}" for i in range(1, 10)]
LAWYERS = [f"LAW-{i:02d}" for i in range(1, 8)]
BROKERS = [f"BRK-{i:02d}" for i in range(1, 7)]
RING_GAR, RING_CLI, RING_LAW, GP, RING_BROKER = "GAR-03", "CLI-04", "LAW-02", "CLI-01", "BRK-05"
FIRST = ["Alma", "Bruno", "Carys", "Dmitri", "Elsie", "Farid", "Greta", "Hamza", "Ines", "Jonas", "Katya", "Leon", "Mira", "Nils",
         "Orla", "Pavel", "Quinn", "Rosa", "Silas", "Tilda", "Umar", "Vera", "Wren", "Xavi", "Yara", "Zeke"]
LAST = ["Ashdown", "Belcourt", "Corrigan", "Dunmore", "Eastwick", "Farrow", "Galloway", "Hartnell", "Ivanek", "Jarrow", "Kessler",
        "Lockhart", "Marlowe", "Norcott", "Okafor", "Pemberton", "Quayle", "Redmayne", "Sandoval", "Thackeray", "Underhill", "Vance",
        "Whitlock", "Yardley", "Zielinski", "Brightwell", "Cardew", "Dalby"]
STREETS = ["Alder", "Birch", "Cedar", "Dunlin", "Elm", "Fenwick", "Garth", "Heron", "Ivy", "Juniper", "Kestrel", "Linden", "Marsh", "Nettle"]
CITY = {"Leeds": (53.8008, -1.5491), "Bristol": (51.4545, -2.5879), "Derby": (52.9225, -1.4746), "Norwich": (52.6309, 1.2974),
        "Exeter": (50.7184, -3.5339), "Hull": (53.7457, -0.3367)}
JUNCTION_CITY = "Derby"
JUNCTION = (CITY[JUNCTION_CITY][0] + 0.031, CITY[JUNCTION_CITY][1] - 0.047)   # the invented junction


def pick(seq):
    return seq[rng.randrange(len(seq))]


# ---- persons ---------------------------------------------------------------------------------------------------------
ids = [f"CL-{i:04d}" for i in range(1, N_PERSONS + 1)]
rng.shuffle(ids)                                   # roles are NOT in id order, so the ring is not visible in the numbering
cursor = 0


def take(n):
    global cursor
    out = ids[cursor:cursor + n]
    cursor += n
    return out


RING, (DUP,), (DUPX,), FAMILY, GEO_EXTRA, EARLY = take(12), take(1), take(1), take(4), take(2), take(7)
BG = ids[cursor:]
used_names, used_addr, phone_n = set(), set(), [0]


def fresh_name():
    while True:
        n = f"{pick(FIRST)} {pick(LAST)}"
        if n not in used_names:
            used_names.add(n)
            return n


def fresh_phone():
    phone_n[0] += 1
    return f"+4477009{phone_n[0]:05d}"


def fresh_addr(city=None):
    while True:
        a = f"{rng.randint(1, 240)} {pick(STREETS)} Road, {city or pick(list(CITY))}"
        if a not in used_addr:
            used_addr.add(a)
            return a


def fresh_dob():
    return date(1958, 1, 1) + timedelta(days=rng.randrange(0, 365 * 44))


P = {}                                             # id -> dict(name, dob, phone, addr)
for pid in ids:
    P[pid] = dict(name=fresh_name(), dob=fresh_dob(), phone=fresh_phone(), addr=fresh_addr())
# ring: 4 phones (4,4,2,2 owners) and 3 addresses
phones = [fresh_phone() for _ in range(4)]
addrs = [fresh_addr("Derby") for _ in range(3)]
for i, pid in enumerate(RING):
    P[pid]["phone"] = phones[0 if i < 4 else 1 if i < 8 else 2 if i < 10 else 3]
for group, a in (([0, 1, 4, 8], 0), ([2, 5, 9, 10], 1), ([3, 6, 7, 11], 2)):
    for i in group:
        P[RING[i]]["addr"] = addrs[a]
# family of four: one phone, one address, one surname
fam_last, fam_phone, fam_addr = "Ormerod", fresh_phone(), fresh_addr("Leeds")
for pid in FAMILY:
    P[pid]["name"] = f"{pick(FIRST)} {fam_last}"
    P[pid]["phone"], P[pid]["addr"] = fam_phone, fam_addr
used_names.update(P[p]["name"] for p in FAMILY)
# near-duplicate: ring[11] under a one-letter-different name, same date of birth and phone, a different address
first, last = P[RING[11]]["name"].split(" ", 1)
P[DUP]["name"] = f"{first} {last[:2]}{last[1]}{last[2:]}"      # doubled letter
# the phone is the same number written NATIONALLY (0 7700 ...) where the ring member has it in E.164 (+44 7700 ...): the fold compares
# digits so it makes NO link; identity resolution, which normalises to E.164, is what joins them
P[DUP]["dob"], P[DUP]["phone"] = P[RING[11]]["dob"], "0" + P[RING[11]]["phone"][3:]
# placeholders in the BACKGROUND: blank / default phones and addresses (no link may come of them)
for pid in BG[:8]:
    P[pid]["phone"] = ""
for pid in BG[8:14]:
    P[pid]["phone"] = "0000000000"
for pid in BG[14:19]:
    P[pid]["addr"] = "UNKNOWN"
for pid in BG[19:22]:
    P[pid]["addr"] = ""

# ---- claims ------------------------------------------------------------------------------------------------------------
claims, parties, accidents, bills = [], [], [], []
claim_n = [0]
acc_n = [0]
driver_of = {}                                     # claim_id -> driver id


def rand_day():
    return START + timedelta(days=rng.randrange((END - START).days + 1))


def new_accident(day, hour_lo, hour_hi, city, reported, near=None):
    acc_n[0] += 1
    aid = f"ACC-{acc_n[0]:04d}"
    if near:
        lat, lon = near[0] + rng.uniform(-0.0025, 0.0025), near[1] + rng.uniform(-0.0035, 0.0035)
    else:
        lat, lon = CITY[city][0] + rng.uniform(-0.02, 0.02), CITY[city][1] + rng.uniform(-0.02, 0.02)
    at = datetime(day.year, day.month, day.day) + timedelta(seconds=rng.randint(hour_lo * 3600, hour_hi * 3600 - 1))
    ref = f"PR-{city[:3].upper()}-{acc_n[0]:04d}" if reported else ""
    accidents.append([aid, city, f"{lat:.5f}", f"{lon:.5f}", at.strftime("%Y-%m-%d %H:%M:%S"), ref])
    return aid, at


def new_claim(aid, at, members, garage, clinic, lawyer, referred, bill_scale=None):
    """members: [(person, role)] - the first driver is the policyholder. Returns the claim id."""
    claim_n[0] += 1
    cid = f"CLM-{claim_n[0]:04d}"
    total = 0.0
    for prov, kind in ((garage, "repair"), (clinic, "treatment"), (lawyer, "legal")):
        if not prov:
            continue
        base = {"repair": (7.0, 0.35), "treatment": (6.2, 0.30), "legal": (7.2, 0.25)}[kind]
        amt = round(rng.lognormvariate(*base) * (bill_scale if bill_scale and prov == RING_CLI else 1.0), 2)
        total += amt
        bills.append([f"BIL-{len(bills) + 1:05d}", cid, prov, (at + timedelta(days=rng.randint(3, 25))).strftime("%Y-%m-%d"),
                      f"{amt:.2f}", kind])
    driver = next((p for p, r in members if r == "driver"), None)
    driver_of[cid] = driver
    reported = at.date() + timedelta(days=rng.randint(0, 6))
    claims.append([cid, None, aid, at.strftime("%Y-%m-%d"), reported.strftime("%Y-%m-%d"), f"{round(total * 1.08, 2):.2f}",
                   garage or "", clinic or "", lawyer or "", referred or "", pick(["open", "settled", "settled", "under_review"])])
    for p, role in members:
        parties.append([cid, p, role, P[p]["name"], P[p]["dob"].isoformat(), P[p]["phone"], P[p]["addr"]])
    return cid


# 1+2) the ring: nine claims; ring[0] is the passenger on the first six, with six different drivers
R = RING
ring_claims = [
    ("2025-10-14", [(R[1], "driver"), (R[0], "passenger"), (R[6], "claimant")]),
    ("2025-11-20", [(R[2], "driver"), (R[0], "passenger"), (R[7], "claimant")]),
    ("2025-12-29", [(R[3], "driver"), (R[0], "passenger"), (R[8], "claimant")]),
    ("2026-02-09", [(R[4], "driver"), (R[0], "passenger"), (R[9], "claimant")]),
    ("2026-03-18", [(R[5], "driver"), (R[0], "passenger"), (R[10], "claimant")]),
    ("2026-05-02", [(R[11], "driver"), (R[0], "passenger"), (R[6], "claimant")]),
    ("2026-06-11", [(R[6], "driver"), (R[7], "passenger"), (R[8], "claimant")]),
    ("2026-07-22", [(R[9], "driver"), (R[10], "passenger"), (R[11], "claimant")]),
    ("2026-08-26", [(R[8], "driver"), (R[1], "claimant"), (R[2], "passenger")]),
]
RING_CLAIM_IDS = []
for d, members in ring_claims:
    day = date.fromisoformat(d)
    aid, at = new_accident(day, 2, 4, JUNCTION_CITY, reported=False, near=JUNCTION)
    RING_CLAIM_IDS.append(new_claim(aid, at, members, RING_GAR, RING_CLI, RING_LAW, RING_LAW, bill_scale=3.4))

# 3) two more accidents at the junction (ordinary claimants, ordinary providers)
EXTRA_IDS = []
for pid in GEO_EXTRA:
    aid, at = new_accident(rand_day(), 2, 4, JUNCTION_CITY, reported=False, near=JUNCTION)
    EXTRA_IDS.append(new_claim(aid, at, [(pid, "driver"), (pick(BG), "claimant")], pick(GARAGES[:2] + GARAGES[3:]), None, None, None))

# 5) seven early claims: one claim each, policy bought through BRK-05 a few days before the loss
EARLY_CLAIM = {}
for pid in EARLY:
    aid, at = new_accident(rand_day(), 7, 22, pick(list(CITY)), reported=True)
    EARLY_CLAIM[pid] = new_claim(aid, at, [(pid, "driver"), (pick(BG), "claimant")], pick(GARAGES[:2] + GARAGES[3:]),
                                 pick(CLINICS[:3] + CLINICS[4:]) if rng.random() < .5 else None, None, None)

# 6) the near-duplicate: a small claim with no providers, one co-party that exists nowhere else
aid, at = new_accident(rand_day(), 9, 18, "Norwich", reported=True)
DUP_CLAIM = new_claim(aid, at, [(DUP, "driver"), (DUPX, "claimant")], None, None, None, None)

# 7a) the benign family: one claim a year, ordinary GP clinic and garage
for d, members in ((date(2025, 8, 12), [(FAMILY[0], "driver"), (FAMILY[1], "passenger")]),
                   (date(2026, 7, 3), [(FAMILY[2], "driver"), (FAMILY[3], "passenger")])):
    aid, at = new_accident(d, 8, 20, "Leeds", reported=True)
    new_claim(aid, at, members, "GAR-05", GP, None, None)

# ordinary background claims; about 16 % of accidents involve two vehicles (two claims, same accident_id)
ORD_GAR = [g for g in GARAGES if g != RING_GAR]
ORD_CLI = [c for c in CLINICS if c not in (RING_CLI, GP)]
ORD_LAW = [x for x in LAWYERS if x != RING_LAW]
bg_pool = [p for p in BG]                          # the early / extra / ring / family / dup persons appear on no other claim
night_left = 6                                     # ordinary night accidents elsewhere, none unreported inside the junction area


pax_n = {}


def ordinary_members():
    n = rng.choices([1, 2, 3], [2, 6, 2])[0]
    group = rng.sample(bg_pool, n)
    members = [(group[0], "driver")]
    for g in group[1:]:
        role = rng.choice(["passenger", "claimant"])
        if role == "passenger" and pax_n.get(g, 0) >= 3:           # the ring's repeat passenger (6 claims) must be the only one
            role = "claimant"
        pax_n[g] = pax_n.get(g, 0) + (role == "passenger")
        members.append((g, role))
    return members


def ordinary_providers():
    garage = pick(ORD_GAR) if rng.random() < .92 else None
    clinic = (GP if rng.random() < .55 else pick(ORD_CLI)) if rng.random() < .6 else None
    lawyer = pick(ORD_LAW) if rng.random() < .25 else None
    referred = lawyer if (lawyer and clinic and rng.random() < .6) else None
    return garage, clinic, lawyer, referred


OUTSIDE_GAR, OUTSIDE_LAW = {200, 300, 400}, {250, 350}   # the ring's garage and lawyer have a few ordinary customers (deterministic)
while claim_n[0] < N_CLAIMS:
    city = pick(list(CITY))
    night = night_left > 0 and city != JUNCTION_CITY and rng.random() < 0.05
    if night:
        night_left -= 1
    aid, at = new_accident(rand_day(), 1, 5, city, reported=False) if night else new_accident(rand_day(), 6, 23, city, reported=rng.random() < .88)
    garage, clinic, lawyer, referred = ordinary_providers()
    if claim_n[0] in OUTSIDE_GAR:
        garage = RING_GAR
    if claim_n[0] in OUTSIDE_LAW:
        lawyer, referred = RING_LAW, None
    new_claim(aid, at, ordinary_members(), garage, clinic, lawyer, referred)
    if rng.random() < .16 and claim_n[0] < N_CLAIMS:               # the other vehicle of the same accident
        new_claim(aid, at, ordinary_members(), pick(ORD_GAR), None, None, None)

# ---- policies: one per person; inception relative to the person's first claim as driver -----------------------------------
claim_row = {c[0]: c for c in claims}
first_loss = {}
for cid, drv in driver_of.items():
    if drv:
        d = date.fromisoformat(claim_row[cid][3])
        first_loss[drv] = min(first_loss.get(drv, d), d)
policy_of, policies = {}, []
for i, pid in enumerate(ids):
    pol = f"POL-{i + 1:04d}"
    policy_of[pid] = pol
    if pid in EARLY:
        inception, broker = first_loss[pid] - timedelta(days=rng.randint(4, 20)), RING_BROKER
    elif pid in first_loss:
        inception, broker = first_loss[pid] - timedelta(days=rng.randint(90, 700)), pick(BROKERS)
    else:
        inception, broker = date(2024, 1, 1) + timedelta(days=rng.randrange(0, 900)), pick(BROKERS)
    policies.append([pol, pid, broker, inception.isoformat(), f"{round(rng.uniform(380, 1500), 2):.2f}"])
for c in claims:
    c[1] = policy_of[driver_of[c[0]]] if driver_of[c[0]] else ""

# ---- provider directory (city centroid + jitter) ----------------------------------------------------------------------------
NAMES = {"GAR": "Autobody", "CLI": "Clinic", "LAW": "Solicitors"}
directory = []
for i, prov in enumerate(GARAGES + CLINICS + LAWYERS):
    city = JUNCTION_CITY if prov in (RING_GAR, RING_CLI, RING_LAW) else pick(list(CITY))
    lat, lon = CITY[city][0] + rng.uniform(-0.03, 0.03), CITY[city][1] + rng.uniform(-0.03, 0.03)
    kind = {"GAR": "garage", "CLI": "clinic", "LAW": "lawyer"}[prov[:3]]
    directory.append([prov, f"{pick(LAST)} {NAMES[prov[:3]]}", kind, city, f"{lat:.5f}", f"{lon:.5f}"])


def write(feed, header, rows):
    out = ROOT / feed
    out.mkdir(parents=True, exist_ok=True)
    f = out / f"{feed.upper()}_{DAY}.csv"
    with f.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)
    print(f"{f.relative_to(ROOT.parent)}: {len(rows)} rows")


write("claim", ["claim_id", "policy_id", "accident_id", "loss_date", "reported_date", "claim_amount", "garage_id", "clinic_id",
                "lawyer_id", "referred_by", "status"], claims)
write("claim_party", ["claim_id", "party_id", "role", "full_name", "dob", "phone", "address"], parties)
write("policy", ["policy_id", "holder_id", "broker_id", "inception_date", "premium"], policies)
write("provider_bill", ["bill_id", "claim_id", "provider_id", "bill_date", "amount", "service"], bills)
write("accident_location", ["accident_id", "city", "lat", "lon", "accident_at", "police_report_ref"], accidents)
write("provider_directory", ["provider_id", "provider_name", "provider_type", "city", "lat", "lon"], directory)
