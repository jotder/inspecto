#!/usr/bin/env python3
"""Regenerate the `la-audit` Space Template's procure-to-pay + journal corpus - SYNTHETIC, deterministic.

Writes under spaces/_templates/la-audit/data/:
  inbox/vendor_master/VENDOR_MASTER_20260930.csv       150 vendors (bank account, address key, phone)
  inbox/employee_master/EMPLOYEE_MASTER_20260930.csv   60 employees (E-01 is the CFO; E-<k>'s manager is E-<k div 2>, a tree)
  inbox/ap_invoice/AP_INVOICE_202510_202609.csv        ~1 800 invoices, Oct 2025 - Sep 2026
  inbox/ap_approval/AP_APPROVAL_202510_202609.csv      one approval per invoice
  inbox/gl_journal/GL_JOURNAL_FY2026.csv               40 journals (intercompany lines)
  staged/ap_invoice/AP_INVOICE_202610.csv, staged/ap_approval/AP_APPROVAL_202610.csv   the NEXT month (ordinary only)

PLANTED STORIES (docs/superpower/la-multi-vertical-showcase-plan.md section 2.B; pinned by LaAuditGoldenTest):
  1 V-117 shares bank account + address with employee E-31 and has 14 invoices, all approved by E-31's manager E-15.
  2 V-131..V-135 share bank account BA-7701 (shell cluster); V-136 is the deliberately unlinked sixth: its own account,
    but it shares an address with V-131 and V-132, so link prediction proposes it.
  3 E-23 approves 30 invoices to V-099, each 1-3 % under the 10 000 approval limit, over 6 weeks.
  4 E-07 (with E-08, E-09) signs 70 % of the invoices of 20 vendors V-021..V-040 and is their ONLY route to the rest of the
    approval graph (one edge, E-07 - V-041, a vendor two ordinary approvers also sign for): an articulation point and a bridge. The org chart is a tree (isForest true).
  5 LE-05 -> LE-06 -> LE-07 -> LE-08 -> LE-05 pass 2 400 000 around at three quarter ends (the only journal cycle).
  6 13 duplicate-payment pairs: same vendor, same amount, same week, two invoice numbers.
  7 Benign: V-001 (utilities, 100 invoices, all 18 ordinary approvers) and the CFO E-01 (40 approvals).
Every value is invented. A vendor with a placeholder account (PENDING) or blank address must make no shared-attribute link.
"""
from __future__ import annotations

import csv
import random
from datetime import date, datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "spaces" / "_templates" / "la-audit" / "data"
rng = random.Random(20261016)
LIMIT = 10000.0
START = date(2025, 10, 1)
FIRST = ["Ada", "Bram", "Cleo", "Dara", "Emil", "Fenn", "Gia", "Hale", "Iris", "Jory", "Kian", "Lena", "Milo", "Nora",
         "Odin", "Pia", "Quin", "Rhea", "Sven", "Tara", "Uma", "Vik", "Wren", "Xan", "Yara", "Zed"]
LAST = ["Ashby", "Brandt", "Corwin", "Delmar", "Eastly", "Fairn", "Garrow", "Holt", "Ivers", "Jessop", "Kerr", "Lindqvist",
        "Marlow", "Norrby", "Oakes", "Pryce", "Quill", "Rowan", "Stroud", "Thorne", "Ulvik", "Vance", "Wick", "Yarrow"]
PRE = ["Alder", "Brook", "Cedar", "Dune", "Ember", "Fjord", "Glen", "Heath", "Isle", "Juniper", "Knoll", "Lark", "Moor",
       "Nettle", "Oak", "Pine", "Quarry", "Reed", "Sedge", "Tarn", "Upland", "Vale", "Willow", "Yew"]
SUF = ["Supplies", "Logistics", "Consulting", "Works", "Partners", "Systems", "Facilities", "Catering", "Print", "Freight"]
CATEGORY = ["supplies", "logistics", "consulting", "facilities", "catering", "it_services", "print", "freight"]

VENDORS = [f"V-{i:03d}" for i in range(1, 151)]
EMPLOYEES = [f"E-{i:02d}" for i in range(1, 61)]
CFO, FICTITIOUS, E31, E31_MANAGER = "E-01", "V-117", "E-31", "E-15"
UTILITIES, BRIDGE_VENDOR = "V-001", "V-041"
P_VENDORS = [f"V-{i:03d}" for i in range(21, 41)]
P_APPROVERS = ("E-07", "E-08", "E-09")
SPLIT_VENDOR, SPLIT_APPROVER = "V-099", "E-23"
SHELLS = [f"V-{i}" for i in range(131, 136)]
SIXTH = "V-136"
SHELL_APPROVER = "E-45"
BULK_APPROVERS = [f"E-{i:02d}" for i in list(range(10, 15)) + list(range(16, 23)) + list(range(24, 30))]   # 18
PLACEHOLDER_ACCOUNT_VENDORS = ["V-090", "V-091", "V-092"]                    # bank account 'PENDING' (a placeholder, not evidence)
BLANK_ADDRESS_VENDORS = ["V-100", "V-101", "V-102", "V-103"]                 # blank address key
BENIGN_ADDRESS_PAIRS = [("V-060", "V-061"), ("V-070", "V-071"), ("V-080", "V-081")]   # a business park: two tenants each, no triangle
SPECIAL = set(P_VENDORS) | set(SHELLS) | {SIXTH, FICTITIOUS, SPLIT_VENDOR, UTILITIES, BRIDGE_VENDOR} \
    | set(PLACEHOLDER_ACCOUNT_VENDORS) | set(BLANK_ADDRESS_VENDORS) | {v for p in BENIGN_ADDRESS_PAIRS for v in p}
BULK = [v for v in VENDORS if v not in set(P_VENDORS) | set(SHELLS) | {SIXTH, FICTITIOUS, SPLIT_VENDOR}]   # utilities + bridge included
ENTITY = ["LE-01", "LE-02", "LE-03", "LE-04"]
RING = ["LE-05", "LE-06", "LE-07", "LE-08"]


def manager_of(e: str) -> str:
    k = int(e[2:])
    return f"E-{k // 2:02d}" if k > 1 else ""


def write(sub: str, name: str, header: list, rows: list) -> None:
    out = ROOT / sub
    out.mkdir(parents=True, exist_ok=True)
    f = out / name
    with f.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)
    print(f"{f.relative_to(ROOT)}: {len(rows)} rows")


# ---- masters -------------------------------------------------------------------------------------------------------
vendor_account = {v: f"BA-{1000 + int(v[2:])}" for v in VENDORS}
vendor_address = {v: f"AD-{1000 + int(v[2:])}" for v in VENDORS}
emp_account = {e: f"BA-{4000 + int(e[2:])}" for e in EMPLOYEES}
emp_address = {e: f"AD-{4000 + int(e[2:])}" for e in EMPLOYEES}
vendor_account[FICTITIOUS] = emp_account[E31] = "BA-9031"
vendor_address[FICTITIOUS] = emp_address[E31] = "AD-9031"
for s in SHELLS:
    vendor_account[s] = "BA-7701"
vendor_account[SIXTH] = "BA-7702"
for s in ("V-131", "V-132", SIXTH):
    vendor_address[s] = "AD-7001"
for v in PLACEHOLDER_ACCOUNT_VENDORS:
    vendor_account[v] = "PENDING"
for v in BLANK_ADDRESS_VENDORS:
    vendor_address[v] = ""
for a, b in BENIGN_ADDRESS_PAIRS:
    vendor_address[b] = vendor_address[a]

vname = {v: f"{rng.choice(PRE)} {rng.choice(SUF)} Ltd" for v in VENDORS}
vname[UTILITIES] = "Meridian Utilities Ltd"
write("inbox/vendor_master", "VENDOR_MASTER_20260930.csv",
      ["VENDOR_ID", "VENDOR_NAME", "BANK_ACCOUNT", "ADDRESS_KEY", "PHONE", "CATEGORY", "ONBOARDED_DATE"],
      [[v, vname[v], vendor_account[v], vendor_address[v], f"+999-555-{rng.randint(1000, 9999)}",
        "utilities" if v == UTILITIES else rng.choice(CATEGORY),
        (date(2018, 1, 1) + timedelta(days=rng.randrange(0, 2800))).isoformat()] for v in VENDORS])

DEPT = ["finance", "operations", "procurement", "it", "hr", "legal"]
write("inbox/employee_master", "EMPLOYEE_MASTER_20260930.csv",
      ["EMPLOYEE_ID", "FULL_NAME", "MANAGER_ID", "DEPARTMENT", "BANK_ACCOUNT", "ADDRESS_KEY", "APPROVAL_LIMIT", "HIRE_DATE"],
      [[e, f"{rng.choice(FIRST)} {rng.choice(LAST)}", manager_of(e), rng.choice(DEPT), emp_account[e], emp_address[e],
        f"{250000 if e == CFO else 50000 if int(e[2:]) < 8 else LIMIT:.0f}",
        (date(2012, 1, 1) + timedelta(days=rng.randrange(0, 4000))).isoformat()] for e in EMPLOYEES])

# ---- invoices ------------------------------------------------------------------------------------------------------
inv_rows: list = []
appr_rows: list = []
invoice_seq = 0
vendor_inv_no: dict = {}


def weekday_on_or_after(d: date) -> date:
    while d.weekday() > 4:
        d += timedelta(days=1)
    return d


def amount() -> float:
    while True:
        a = round(rng.lognormvariate(7.0, 0.8), 2)
        if 50 <= a < 9000:
            return a


def emit(rows_i: list, rows_a: list, vendor: str, day: date, amt: float, approver: str, inv_no: str | None = None) -> None:
    global invoice_seq
    invoice_seq += 1
    iid = f"INV-{invoice_seq:06d}"
    vendor_inv_no[vendor] = vendor_inv_no.get(vendor, 0) + 1
    no = inv_no or f"{vname_short(vendor)}-{vendor_inv_no[vendor]:04d}"
    pay = day + timedelta(days=30 + rng.randint(-3, 3))
    rows_i.append([iid, vendor, no, rng.choice(["LE-01", "LE-02", "LE-03", "LE-04"]), day.isoformat(), pay.isoformat(),
                   f"{amt:.2f}", "EUR", vendor_account[vendor]])
    appr_dt = datetime.combine(day + timedelta(days=rng.randint(1, 3)), datetime.min.time()) + timedelta(
        seconds=rng.randint(9 * 3600, 17 * 3600))
    rows_a.append([f"APR-{invoice_seq:06d}", iid, approver, appr_dt.strftime("%Y-%m-%d %H:%M:%S"), appr_dt.strftime("%Y-%m-%d"), "1"])


def vname_short(v: str) -> str:
    return f"{vname[v].split()[0][:3].upper()}{v[2:]}"


def any_day(lo: date = START, span: int = 364) -> date:
    return weekday_on_or_after(lo + timedelta(days=rng.randrange(span)))


# approver pairs of the ordinary vendors (degree 2 each, so the ordinary approval graph has no 3-core and no bridge)
cfo_vendors = set(rng.sample([v for v in BULK if v not in (UTILITIES, BRIDGE_VENDOR) and v not in PLACEHOLDER_ACCOUNT_VENDORS
                              and v not in BLANK_ADDRESS_VENDORS], 40))
pair: dict = {}
for v in BULK:
    if v == UTILITIES:
        continue
    prim = rng.choice(BULK_APPROVERS)
    if v in cfo_vendors:
        pair[v] = (prim, CFO)
    else:
        pair[v] = (prim, rng.choice([a for a in BULK_APPROVERS if a != prim]))

# duplicate-payment vendors: 13 ordinary vendors, never a CFO vendor
dup_vendors = rng.sample([v for v in BULK if v not in cfo_vendors and v not in (UTILITIES, BRIDGE_VENDOR)], 13)

for v in BULK:
    if v == UTILITIES:
        for k in range(100):
            emit(inv_rows, appr_rows, v, any_day(), round(rng.uniform(1800, 6200), 2), BULK_APPROVERS[k % 18])
        continue
    prim, sec = pair[v]
    n = rng.randint(4, 17)
    for k in range(n):
        if v in cfo_vendors:
            who = sec if k == 1 else prim
        elif v == BRIDGE_VENDOR:
            who = "E-07" if k == 0 else prim if rng.random() < 0.7 else sec   # E-07's ONE invoice (one link, so a true bridge) into the ordinary graph
        else:
            who = prim if k == 0 else sec if k == 1 else prim if rng.random() < 0.7 else sec
        emit(inv_rows, appr_rows, v, any_day(), amount(), who)

p_seq = 0
for v in P_VENDORS:                                              # 70 % E-07, 20 % E-08, 10 % E-09 (cycle of 10 over all 300)
    for k in range(15):
        p_seq += 1
        who = P_APPROVERS[0] if p_seq % 10 < 7 else P_APPROVERS[1] if p_seq % 10 < 9 else P_APPROVERS[2]
        emit(inv_rows, appr_rows, v, any_day(), amount(), who)

for k in range(14):                                              # 1: the fictitious vendor
    emit(inv_rows, appr_rows, FICTITIOUS, any_day(date(2026, 1, 5), 240), round(rng.uniform(2000, 8000), 2), E31_MANAGER)

for v in SHELLS + [SIXTH]:                                       # 2: the shell cluster and its unlinked sixth
    for k in range(9):
        emit(inv_rows, appr_rows, v, any_day(date(2026, 2, 2), 200), round(rng.uniform(1500, 4000), 2), SHELL_APPROVER)

d0 = date(2026, 3, 2)                                            # 3: threshold splitting, 30 invoices over 6 weeks
for k in range(30):
    emit(inv_rows, appr_rows, SPLIT_VENDOR, weekday_on_or_after(d0 + timedelta(days=int(k * 41 / 29))),
         round(LIMIT * rng.uniform(0.97, 0.99), 2), SPLIT_APPROVER)

for v in dup_vendors:                                            # 6: a second invoice, same vendor + amount + ISO week
    mine = [i for i in inv_rows if i[1] == v]
    first = mine[0]
    d1 = date.fromisoformat(first[4])
    while d1.weekday() > 2:                                      # Mon..Wed so +1/+2 days stays in the week
        d1 -= timedelta(days=1)
    first[4] = d1.isoformat()
    approver = next(a[2] for a in appr_rows if a[1] == first[0])
    emit(inv_rows, appr_rows, v, d1 + timedelta(days=rng.randint(1, 2)), float(first[6]), approver,
         inv_no=f"{vname_short(v)}-D{vendor_inv_no[v]:03d}")

order = sorted(range(len(inv_rows)), key=lambda i: (inv_rows[i][4], inv_rows[i][0]))
inv_rows = [inv_rows[i] for i in order]
appr_by_inv = {a[1]: a for a in appr_rows}
appr_rows = [appr_by_inv[i[0]] for i in inv_rows]
IH = ["INVOICE_ID", "VENDOR_ID", "INVOICE_NO", "LEGAL_ENTITY", "INVOICE_DATE", "PAYMENT_DATE", "AMOUNT", "CURRENCY", "PAYMENT_ACCOUNT"]
AH = ["APPROVAL_ID", "INVOICE_ID", "APPROVER_ID", "APPROVED_AT", "APPROVED_DATE", "LEVEL"]
write("inbox/ap_invoice", "AP_INVOICE_202510_202609.csv", IH, inv_rows)
write("inbox/ap_approval", "AP_APPROVAL_202510_202609.csv", AH, appr_rows)

# staged NEXT month: ordinary vendors only, only the vendor's existing approver pair (adds no link kind, bridge or cycle)
st_i: list = []
st_a: list = []
for k in range(40):
    v = rng.choice([x for x in BULK if x not in (UTILITIES, BRIDGE_VENDOR) and x not in cfo_vendors
                    and x not in PLACEHOLDER_ACCOUNT_VENDORS and x not in BLANK_ADDRESS_VENDORS])
    emit(st_i, st_a, v, any_day(date(2026, 10, 1), 28), amount(), rng.choice(pair[v]))
write("staged/ap_invoice", "AP_INVOICE_202610.csv", IH, st_i)
write("staged/ap_approval", "AP_APPROVAL_202610.csv", AH, st_a)

# ---- journals ------------------------------------------------------------------------------------------------------
gl: list = []
jn = 0
for qe in (date(2025, 12, 31), date(2026, 3, 31), date(2026, 6, 30)):            # 5: the round trip at three quarter ends
    for k in range(4):
        jn += 1
        at = datetime.combine(qe, datetime.min.time()) + timedelta(hours=9 + 2 * k)
        gl.append([f"JNL-RT-{jn:02d}", "1", qe.isoformat(), at.strftime("%Y-%m-%d %H:%M:%S"), RING[k], RING[(k + 1) % 4],
                   "1900-IC-RECEIVABLE", "2400000.00", "intercompany settlement"])
for k in range(28):                                                              # ordinary: LE-01..04 downhill, then into the ring only
    d = any_day(date(2025, 10, 1), 360)
    a = rng.randrange(4)
    b = rng.choice([x for x in ENTITY[a + 1:] + RING])
    at = datetime.combine(d, datetime.min.time()) + timedelta(seconds=rng.randint(9 * 3600, 17 * 3600))
    for line, acct in enumerate(rng.sample(["6100-RECHARGE", "6200-IT-SHARED", "6300-MGMT-FEE", "6400-RENT"], rng.randint(1, 3)), 1):
        gl.append([f"JNL-{k + 1:04d}", str(line), d.isoformat(), at.strftime("%Y-%m-%d %H:%M:%S"), ENTITY[a], b, acct,
                   f"{round(rng.lognormvariate(10.5, 0.7), 2):.2f}", "shared service recharge"])
gl.sort(key=lambda r: (r[2], r[0], r[1]))
write("inbox/gl_journal", "GL_JOURNAL_FY2026.csv",
      ["JOURNAL_ID", "LINE_NO", "POSTED_DATE", "POSTED_AT", "FROM_ENTITY", "TO_ENTITY", "GL_ACCOUNT", "AMOUNT", "DESCRIPTION"], gl)
