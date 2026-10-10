#!/usr/bin/env python3
"""Regenerate the `la-showcase` Space Template's TELECOM corpus - SYNTHETIC, deterministic (seed 20261011).

Writes one 60-day extract per raw Dataset under spaces/_templates/la-showcase/data/inbox/<feed>/ (ingested when the
Space boots; the `telecom_links` and `telecom_msisdn_indicators` sql.template Jobs then fold them):

  voice_cdr      a_number, b_number, start_time, duration_s, call_type, redirecting_number, imsi, imei, destination_type
  sms_cdr        orig_msisdn, term_msisdn, ts, message_type
  hlr_eir        msisdn, imsi, imei, forwarding_to, sim_swap_at
  crm_kyc        msisdn, id_doc_hash, address_hash, account_holder, activation_date, subscriber_type, kyc_complete
  recharge       msisdn, voucher_id, agent_id, card_hash, ts
  provisioning   msisdn, old_subscriber, new_subscriber, event, ts
  fraud_blocklist  msisdn, listed_at, reason          (feeds the on_blocklist indicator)
  fraud_alarms     msisdn, raised_at, alarm_type      (feeds the alarms_recent indicator)

Numbering (every number is invented; country codes 999/998 are not assigned to any real network):
  99971xxxxxx  home-network background subscribers (2 400, in 100 closed calling clusters of 24)
  99970000100  HUB - a taxi-dispatch business line called by 650 distinct subscribers (the supernode)
  99979xxxxxx  the planted rings (see RINGS below and docs/superpower/la-telecom-demo-data.md)
  9989100xxxx  premium-rate international range (IRSF / Wangiri callback destinations)
  998200xxxxx  ordinary international numbers

The degree ladder from the suspect WANGIRI-A (shortest paths, every link kind folded undirected):
  d1 WANGIRI-B (shared_device) and PREMIUM-01 (forwarding)   d2 IRSF-01 (voice to PREMIUM-01)
  d3 SUBFRAUD-01..05 (shared_identity: IRSF-01's id document) d4 SIMBOX-01 (shared_payment: SUBFRAUD-03's card)
The background is built so no shortcut exists: Wangiri victims come from clusters 0-39, the SIM box terminates calls
only into clusters 60-99, and a cluster never calls outside itself (except the HUB). Pinned by TelecomLinksGoldenTest.
"""
from __future__ import annotations

import csv
import hashlib
import random
from datetime import date, datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "spaces" / "_templates" / "la-showcase" / "data" / "inbox"
rng = random.Random(20261011)
START = datetime(2026, 8, 1)
DAYS = 60
END_DAY = (START + timedelta(days=DAYS - 1)).date()          # 2026-09-29
STAMP = END_DAY.strftime("%Y%m%d")

N_BG, CLUSTER = 2400, 24
BG = [f"99971{i:06d}" for i in range(1, N_BG + 1)]
CLUSTERS = [BG[c * CLUSTER:(c + 1) * CLUSTER] for c in range(N_BG // CLUSTER)]
VICTIM_CLUSTERS, MULE_CLUSTERS, SIMBOX_CLUSTERS = range(0, 40), range(40, 60), range(60, 100)
HUB = "99970000100"

WANGIRI = ["99979100001", "99979100002"]                     # A = the suspect, B = second SIM in the same handset
IRSF = [f"9997920000{i}" for i in range(1, 5)]
SUBFRAUD = [f"9997930000{i}" for i in range(1, 6)]
SIMBOX = [f"9997940000{i}" for i in range(1, 7)]
MULES = [f"9997950000{i}" for i in range(1, 6)]
PREMIUM = [f"9989100{i:04d}" for i in range(1, 13)]
SUSPECT = WANGIRI[0]
RINGS = {"wangiri": WANGIRI, "irsf": IRSF, "subscription_fraud": SUBFRAUD, "simbox": SIMBOX, "mule": MULES,
         "hub": [HUB], "premium": PREMIUM}

intl_seq = 0


def h(*parts) -> str:
    return hashlib.sha256("|".join(map(str, parts)).encode()).hexdigest()[:16]


def imsi_of(m, k=0):
    return f"99901{int(m) % 10**9:09d}{k}"


def imei_of(m, k=0):
    return f"35{h('imei', m, k)[:13].translate(str.maketrans('abcdef', '123456'))}"


def ts(day_lo=0, day_hi=DAYS, hr_lo=0, hr_hi=24):
    d = rng.randrange(day_lo, day_hi)
    return START + timedelta(days=d, seconds=rng.randint(hr_lo * 3600, hr_hi * 3600 - 1))


def fmt(t):
    return t.strftime("%Y-%m-%d %H:%M:%S") if isinstance(t, datetime) else ("" if t is None else str(t))


def foreign():
    global intl_seq
    intl_seq += 1
    return f"998200{intl_seq:05d}"


voice, sms, hlr, crm, recharge, prov = [], [], [], [], [], []
blocklist, alarms = [], []
imeis = {}                                                   # msisdn -> list of IMEIs it uses


def call(a, b, when, dur, dest="onnet", call_type="outgoing", redirect="", imei=None):
    voice.append([a, b, fmt(when), dur, call_type, redirect, imsi_of(a), imei or imeis[a][0], dest])


# ── background ────────────────────────────────────────────────────────────────────────────────────────────
customer = {}
for m in BG + [HUB]:
    imeis[m] = [imei_of(m)]
    customer[m] = f"CUST-{h('cust', m)[:8].upper()}"
two_line = rng.sample(range(N_BG // 2), 40)                  # 40 customers hold a second line in their own cluster
for i in two_line:
    a, b = BG[2 * i], BG[2 * i + 1]                          # 2i and 2i+1 sit in the same cluster (24 is even)
    customer[b] = customer[a]
households = {}
for c in CLUSTERS:                                           # a few shared home addresses inside a cluster
    for k in range(2):
        for m in rng.sample(c, rng.randint(2, 3)):
            households[m] = h("addr-household", c[0], k)

for c in CLUSTERS:                                           # friend pairs inside a cluster; calls go both ways
    for m in c:
        for f in rng.sample([x for x in c if x != m], 2):
            for _ in range(rng.randint(2, 5)):
                a, b = (m, f) if rng.random() < 0.5 else (f, m)
                call(a, b, ts(), rng.randint(20, 600))
            for _ in range(rng.randint(0, 3)):
                a, b = (m, f) if rng.random() < 0.5 else (f, m)
                sms.append([a, b, fmt(ts()), "text"])
for m in rng.sample(BG, 240):                                # some ordinary international calls, own number each
    n = foreign()
    for _ in range(rng.randint(1, 3)):
        call(m, n, ts(), rng.randint(60, 900), dest="international")
hub_callers = rng.sample(BG, 650)                            # the supernode
for m in hub_callers:
    for _ in range(rng.randint(1, 2)):
        call(m, HUB, ts(hr_lo=6, hr_hi=23), rng.randint(15, 90))
for m in rng.sample(hub_callers, 80):
    call(HUB, m, ts(hr_lo=6, hr_hi=23), rng.randint(10, 40))

for m in BG + [HUB]:
    act = date(2019, 1, 1) + timedelta(days=rng.randint(0, 2700))
    if act > END_DAY - timedelta(days=90):
        act = END_DAY - timedelta(days=90 + rng.randint(0, 400))
    stype = "business" if m == HUB else rng.choices(["prepaid", "postpaid"], [3, 1])[0]
    cust = customer[m]
    crm.append([m, h("iddoc", cust), households.get(m, h("addr", cust)), cust, act.isoformat(), stype, "true"])
    prov.append([m, "", cust, "activation", fmt(datetime.combine(act, datetime.min.time()) + timedelta(hours=10))])
    swap = ts() if rng.random() < 0.03 else None
    if swap:
        hlr.append([m, imsi_of(m, 0), imeis[m][0], "", ""])
        hlr.append([m, imsi_of(m, 1), imeis[m][0], "", fmt(swap)])
        prov.append([m, cust, cust, "sim_swap", fmt(swap)])
    else:
        hlr.append([m, imsi_of(m, 0), imeis[m][0], "", ""])
    if stype == "prepaid":
        card = h("card", cust) if rng.random() < 0.3 else ""
        for _ in range(rng.randint(1, 4)):
            when = ts(hr_lo=7, hr_hi=21)
            if card:
                recharge.append([m, "", "", card, fmt(when)])
            else:
                recharge.append([m, f"V{h('voucher', m, when)[:10].upper()}", f"AGT-{rng.randint(1, 40):03d}", "", fmt(when)])
for c in rng.sample(CLUSTERS, 25):                           # ownership transfers inside a cluster -> sim_history
    m, to = rng.sample(c, 2)
    prov.append([m, customer[m], customer[to], "ownership_transfer", fmt(ts())])
for m in rng.sample(BG, 20):                                 # call forwarding to voicemail-like cluster mate
    c = CLUSTERS[BG.index(m) // CLUSTER]
    hlr.append([m, imsi_of(m, 2), imeis[m][0], rng.choice([x for x in c if x != m]), ""])

# ── planted rings ─────────────────────────────────────────────────────────────────────────────────────────
WANGIRI_IMEI = imei_of("wangiri-handset")
for m in WANGIRI:
    imeis[m] = [WANGIRI_IMEI]
IRSF_IMEI = {m: [imei_of(m)] for m in IRSF}
imeis.update(IRSF_IMEI)
for m in SUBFRAUD + MULES:
    imeis[m] = [imei_of(m)]
SIMBOX_POOL = [imei_of("simbox-slot", k) for k in range(8)]   # one 8-slot gateway, the SIMs rotate across slots
for i, m in enumerate(SIMBOX):
    imeis[m] = [SIMBOX_POOL[(i + k) % 8] for k in range(4)]

# Wangiri: WANGIRI-A rings 400 victims for 0-3 s (one-way); 40 call back, the callback is forwarded to PREMIUM-01.
victims = rng.sample([m for c in VICTIM_CLUSTERS for m in CLUSTERS[c]], 400)
for v in victims:
    call(SUSPECT, v, ts(48, 58, 1, 5), rng.randint(0, 3), imei=WANGIRI_IMEI)
for v in victims[:40]:
    call(v, PREMIUM[0], ts(48, 60), rng.randint(40, 300), dest="premium_intl", call_type="forwarded", redirect=SUSPECT)
for v in rng.sample(victims[40:], 60):                       # WANGIRI-B (already blocklisted) ran the same play earlier
    call(WANGIRI[1], v, ts(20, 30, 1, 5), rng.randint(0, 3), imei=WANGIRI_IMEI)
hlr.append([SUSPECT, imsi_of(SUSPECT), WANGIRI_IMEI, PREMIUM[0], ""])
hlr.append([WANGIRI[1], imsi_of(WANGIRI[1]), WANGIRI_IMEI, PREMIUM[0], ""])

# IRSF: four freshly activated SIMs pump long night calls into the premium range; IRSF-01 hits PREMIUM-01.
for i, m in enumerate(IRSF):
    targets = PREMIUM[0:6] if i < 2 else PREMIUM[4:12]
    for _ in range(rng.randint(70, 90)):
        call(m, rng.choice(targets), ts(40, 60, 0, 5), rng.randint(600, 3600), dest="premium_intl")

# Subscription fraud: five MSISDNs on ONE synthetic identity (and IRSF-01 too), activated in the last 3 weeks,
# mostly international traffic.
for m in SUBFRAUD:
    for n in [foreign() for _ in range(6)]:
        for _ in range(rng.randint(3, 6)):
            call(m, n, ts(42, 60), rng.randint(120, 1500), dest="international")
    for f in SUBFRAUD:
        if f != m and rng.random() < 0.5:
            sms.append([m, f, fmt(ts(42, 60)), "text"])

# SIM box: six SIMs in one 8-slot gateway terminate grey international traffic as local calls into clusters 60-99;
# ~no inbound, IMEIs and IMSIs rotate.
simbox_targets = [m for c in SIMBOX_CLUSTERS for m in CLUSTERS[c]]
for i, m in enumerate(SIMBOX):
    for k in range(4):
        hlr.append([m, imsi_of(m, k), imeis[m][k], "", fmt(START + timedelta(days=10 + 12 * k, hours=i)) if k else ""])
    for _ in range(rng.randint(160, 200)):
        call(m, rng.choice(simbox_targets), ts(20, 60), rng.randint(30, 420), imei=rng.choice(imeis[m]))
call(simbox_targets[5], SIMBOX[0], ts(30, 60), 12)            # the one stray callback (so "~no inbound", not zero)

# Mules: forwarding chain MULE-01 -> MULE-02 -> MULE-03, all topped up by one rogue agent with shared vouchers.
mule_callers = [m for c in MULE_CLUSTERS for m in CLUSTERS[c]]
hlr.append([MULES[0], imsi_of(MULES[0]), imeis[MULES[0]][0], MULES[1], ""])
hlr.append([MULES[1], imsi_of(MULES[1]), imeis[MULES[1]][0], MULES[2], ""])
for m in MULES[2:]:
    hlr.append([m, imsi_of(m), imeis[m][0], "", ""])
for c in rng.sample(mule_callers, 30):
    when = ts(30, 60, 9, 18)
    call(c, MULES[1], when, rng.randint(30, 200), call_type="forwarded", redirect=MULES[0])
    call(c, MULES[2], when + timedelta(seconds=5), rng.randint(30, 200), call_type="forwarded", redirect=MULES[1])
for a in MULES:
    for b in MULES:
        if a != b and rng.random() < 0.4:
            call(a, b, ts(30, 60), rng.randint(20, 200))
for k, m in enumerate(MULES):                                # a cloned voucher redeemed on 3 lines, twice over
    recharge.append([m, "VMULE00000A1" if k < 3 else "VMULE00000A2", "AGT-666", "", fmt(ts(30, 60, 9, 18))])
    if k == 2:
        recharge.append([m, "VMULE00000A2", "AGT-666", "", fmt(ts(30, 60, 9, 18))])
    for j in range(3):
        recharge.append([m, f"V{h('mv', m, j)[:10].upper()}", "AGT-666", "", fmt(ts(30, 60, 9, 18))])

# KYC / provisioning for the planted numbers.
FRAUD_ID = h("iddoc", "synthetic-identity-7")
planted_crm = {
    **{m: (h("iddoc", m), h("addr", m), f"CUST-W0{i + 1}", date(2026, 8, 18 + i), "prepaid", "false") for i, m in enumerate(WANGIRI)},
    **{m: (FRAUD_ID if i == 0 else h("iddoc", m), h("addr", "irsf-flat"), f"CUST-I0{i + 1}", date(2026, 9, 8), "prepaid", "false")
       for i, m in enumerate(IRSF)},
    **{m: (FRAUD_ID, h("addr", "sub", i % 2), f"CUST-F0{i + 1}", date(2026, 9, 12 + i), "postpaid", "false") for i, m in enumerate(SUBFRAUD)},
    **{m: (h("iddoc", m), h("addr", m), f"CUST-S0{i + 1}", date(2026, 8, 15), "prepaid", "true") for i, m in enumerate(SIMBOX)},
    **{m: (h("iddoc", m), h("addr", m), f"CUST-M0{i + 1}", date(2026, 7, 1 + i), "prepaid", "true") for i, m in enumerate(MULES)},
}
for m, (doc, addr, cust, act, stype, kyc) in planted_crm.items():
    crm.append([m, doc, addr, cust, act.isoformat(), stype, kyc])
    prov.append([m, "", cust, "activation", fmt(datetime.combine(act, datetime.min.time()) + timedelta(hours=11))])
for m in WANGIRI + IRSF + SUBFRAUD:
    if not any(r[0] == m for r in hlr):
        hlr.append([m, imsi_of(m), imeis[m][0], "", ""])
FRAUD_CARD = h("card", "fraud-card-7")
recharge.append([SUBFRAUD[2], "", "", FRAUD_CARD, fmt(ts(45, 60, 9, 18))])   # the d3 -> d4 hop
for m in SIMBOX:
    for _ in range(4):
        recharge.append([m, "", "", FRAUD_CARD if m == SIMBOX[0] else h("card", "simbox", m), fmt(ts(20, 60, 9, 18))])
for k, m in enumerate(SIMBOX):
    for j in range(1, 4):
        prov.append([m, f"CUST-S0{k + 1}", f"CUST-S0{k + 1}", "sim_swap", fmt(START + timedelta(days=10 + 12 * j, hours=k))])
prov.append([SIMBOX[5], "CUST-S06", "CUST-M01", "ownership_transfer", fmt(datetime(2026, 9, 2, 14))])  # MULE-01 <-> SIMBOX-06

# Fraud history.
blocklist += [[WANGIRI[1], "2026-09-01 09:00:00", "wangiri"], [SIMBOX[2], "2026-09-15 09:00:00", "simbox"],
              [BG[1999], "2026-03-04 09:00:00", "bad_debt"]]
alarms += [[SUSPECT, "2026-09-27 08:00:00", "wangiri"], [IRSF[0], "2026-09-25 03:10:00", "irsf"],
           [IRSF[1], "2026-09-26 02:40:00", "irsf"], [SIMBOX[0], "2026-09-20 11:00:00", "simbox"]]
for m in rng.sample(BG, 6):
    alarms.append([m, fmt(ts(0, 30)), "velocity"])
for m in rng.sample(BG, 2):
    alarms.append([m, fmt(ts(50, 60)), "velocity"])


def write(feed, header, rows, key=0):
    out = ROOT / feed
    out.mkdir(parents=True, exist_ok=True)
    rows = sorted(rows, key=lambda r: (str(r[key]), *map(str, r)))
    f = out / f"{feed.upper()}_{STAMP}.csv"
    with f.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)
    print(f"{f.relative_to(ROOT.parent)}: {len(rows)} rows")


write("voice_cdr", ["a_number", "b_number", "start_time", "duration_s", "call_type", "redirecting_number", "imsi", "imei",
                    "destination_type"], voice, key=2)
write("sms_cdr", ["orig_msisdn", "term_msisdn", "ts", "message_type"], sms, key=2)
write("hlr_eir", ["msisdn", "imsi", "imei", "forwarding_to", "sim_swap_at"], hlr)
write("crm_kyc", ["msisdn", "id_doc_hash", "address_hash", "account_holder", "activation_date", "subscriber_type",
                  "kyc_complete"], crm)
write("recharge", ["msisdn", "voucher_id", "agent_id", "card_hash", "ts"], recharge, key=4)
write("provisioning", ["msisdn", "old_subscriber", "new_subscriber", "event", "ts"], prov, key=4)
write("fraud_blocklist", ["msisdn", "listed_at", "reason"], blocklist)
write("fraud_alarms", ["msisdn", "raised_at", "alarm_type"], alarms, key=1)
