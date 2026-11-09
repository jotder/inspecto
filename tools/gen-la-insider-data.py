#!/usr/bin/env python3
"""Regenerate the `la-insider` Space Template's insider-threat corpus - SYNTHETIC, deterministic (one seeded Random).

Writes spaces/_templates/la-insider/data/inbox/<feed>/ (12 months landed, 2025-10-01..2026-09-30) and data/staged/<feed>/ (the
next month, 2026-10, for the "new data arrives" step) for six feeds:
  employee (one HR extract, landed only), access_grant (one landed extract of every grant up to 2026-09-30, one staged file),
  access_daily (DAILY COUNTS per employee, system and day - never record-level), email_external (aggregated per employee,
  external domain and day), expense (one file per month), vendor_change (one landed file, one staged)
and tools/gen-la-insider-data.ground-truth.json (story -> entity ids and look-alike ids; NOT shipped in the data download).

Planted stories (plan: docs/archived-documents/plans-archive/2026-11-07-la-showcase-police-insurance-utility-plan.md, spec 12; pinned by
tools/gen-la-insider-data.test.mjs over the files and InsiderLinksJobGoldenTest over the link Datasets).
  1 LEAVER EXFILTRATION  a customer account specialist gives notice, then reads about 20x their normal customer records
                         in the customer system for three weeks and mails 1.2 GB of attachments to a personal mail domain
                         look-alike: a data migration engineer whose ticketed bulk read of the customer archive is just as
                         large (about 20x their normal), with no notice and no mail to speak of
  2 ACCESS CREEP         manager EMP-0007 (the spec's M-7, an operations manager) grants payroll and treasury access to 4 of
                         their reports, none in a finance role, with no ticket; one of them later changes a vendor's bank
                         details
                         look-alike: an employee who moved into a payroll role (role_since on the HR record) and was granted
                         payroll access by the payroll manager with a ticket, the day after the move
  3 MUTUAL APPROVALS     two account managers in different sales teams approve each other's expenses 40 times, every one
                         just under the 500 single-approver limit, mostly submitted at weekends
                         look-alike: a sales team lead approving the team's frequent expenses in the normal way (weekdays,
                         amounts spread, never approved back)
Every person, team, system, vendor and mail domain is invented: people are ids only (no names), systems are generic
upper-case names, mail domains end in the reserved .example. Nothing derives from a real source.
"""
from __future__ import annotations

import csv
import json
import math
import random
from datetime import date, datetime, timedelta
from pathlib import Path

from la_gen_io import Outputs

TOOLS = Path(__file__).resolve().parent
DATA = TOOLS.parent / "spaces" / "_templates" / "la-insider" / "data"
TRUTH = TOOLS / "gen-la-insider-data.ground-truth.json"
SEED = 20261112
rng = random.Random(SEED)
START, LANDED_END, END = date(2025, 10, 1), date(2026, 9, 30), date(2026, 10, 31)
MONTHS = [date(2025 + (9 + i) // 12, (9 + i) % 12 + 1, 1) for i in range(13)]   # 2025-10 .. 2026-10
LIMIT = 500.0                                   # the single-approver expense limit (documented in the Jobs)
FINANCE = ("AP", "PAYROLL", "TREASURY")         # finance systems
LOGGED = ("AP", "CRM", "CUSTARCH", "HRIS", "PAYROLL", "TREASURY")   # record-holding systems: daily read counts kept
PERSONAL = ("inboxly.example", "mailnest.example", "postmail.example")   # personal webmail domains (documented in the Job)

DEPTS = {"Customer Service": 260, "Sales": 300, "Operations": 820, "Finance": 140, "IT": 280, "Engineering": 700, "HR": 90,
         "Marketing": 210, "Legal": 60, "Procurement": 139}   # + the chief executive = 3 000
ROLES = {"Customer Service": ["Customer Account Specialist", "Customer Service Advisor", "Complaints Handler"],
         "Sales": ["Account Manager", "Field Sales Representative", "Sales Support Officer"],
         "Operations": ["Operations Analyst", "Logistics Coordinator", "Facilities Coordinator", "Planner"],
         "IT": ["Service Desk Analyst", "Systems Administrator", "Network Engineer", "Data Migration Engineer"],
         "Engineering": ["Software Engineer", "QA Engineer", "Data Engineer"],
         "HR": ["HR Advisor", "Recruiter", "HR Systems Officer"],
         "Marketing": ["Marketing Analyst", "Content Producer", "Campaign Manager"],
         "Legal": ["Legal Counsel", "Paralegal"],
         "Procurement": ["Buyer", "Category Manager"]}
FIN_TEAMS = [("Payroll Manager", "Payroll Officer", "PAYROLL"), ("Accounts Payable Manager", "Accounts Payable Clerk", "AP"),
             ("Treasury Manager", "Treasury Analyst", "TREASURY"), ("Financial Controller", "Management Accountant", None)]


def d(s):
    return date.fromisoformat(s)


def dt(day, h=9, m=0, s=0):
    return datetime(day.year, day.month, day.day, h, m, s)


def days(a, b):
    out, x = [], a
    while x <= b:
        out.append(x)
        x += timedelta(days=1)
    return out


def office_time(day):
    return dt(day, rng.randint(8, 17), rng.randint(0, 59), rng.randint(0, 59))


def lognorm(median, sigma):
    return median * math.exp(rng.gauss(0, sigma))


# ---- the organisation ---------------------------------------------------------------------------------------------------------------

people = []   # dicts; ids assigned at the end


def person(dept, role, mgr, hired, kind):
    p = {"dept": dept, "role": role, "mgr": mgr, "hired": hired, "role_since": hired, "notice": None, "left": None, "kind": kind}
    people.append(p)
    return p


def hire_date(lo, hi):
    span = (hi - lo).days
    return lo + timedelta(days=int(span * (1 - rng.random() ** 1.6)))   # skewed toward recent hires


ceo = person("Executive", "Chief Executive", None, d("2011-03-01"), "head")
heads, managers = {}, []
for dept, n in DEPTS.items():
    head = person(dept, f"Head of {dept}", ceo, hire_date(d("2011-06-01"), d("2020-01-01")), "head")
    heads[dept] = head
    n_staff = n - 1
    n_mgr = math.ceil(n_staff / 11)
    n_staff -= n_mgr
    mgrs = []
    for i in range(n_mgr):
        if dept == "Finance":
            mrole, srole, _ = FIN_TEAMS[i % len(FIN_TEAMS)]
        elif dept == "Sales":
            mrole, srole = "Sales Team Lead", None
        else:
            mrole, srole = f"{dept} Manager", None
        m = person(dept, mrole, head, hire_date(d("2012-01-01"), d("2024-06-01")), "manager")
        m["team_role"] = srole
        mgrs.append(m)
        managers.append(m)
    weights = [rng.uniform(0.6, 1.4) for _ in mgrs]
    for i in range(n_staff):
        m = mgrs[i] if i < len(mgrs) else rng.choices(mgrs, weights)[0]
        if m.get("team_role"):
            role = m["team_role"]
        elif dept == "Sales":
            role = rng.choices(ROLES["Sales"], [3, 4, 2])[0]
        elif dept == "IT":
            role = rng.choices(ROLES["IT"], [5, 4, 3, 1])[0]
        else:
            role = rng.choice(ROLES[dept])
        person(dept, role, m, hire_date(d("2013-01-01"), d("2026-09-01")), "staff")

assert len(people) == 3000, len(people)

# ids by hire date (older staff, lower numbers); the chosen operations manager is EMP-0007, the spec's "M-7"
ops_mgrs = sorted([m for m in managers if m["dept"] == "Operations"], key=lambda m: m["hired"])
creep_mgr = next(m for m in ops_mgrs if sum(1 for p in people if p["mgr"] is m) >= 9)
order = sorted(people, key=lambda p: (p["hired"], people.index(p)))
order.remove(creep_mgr)
order.insert(6, creep_mgr)
for i, p in enumerate(order):
    p["id"] = f"EMP-{i + 1:04d}"
assert creep_mgr["id"] == "EMP-0007"
by_id = {p["id"]: p for p in people}
reports = {}
for p in people:
    if p["mgr"]:
        reports.setdefault(p["mgr"]["id"], []).append(p)

# ---- story people ---------------------------------------------------------------------------------------------------------------------

old = lambda p: p["hired"] < d("2023-01-01")
leaver = next(p for p in order if p["role"] == "Customer Account Specialist" and old(p))
engineer = next(p for p in order if p["role"] == "Data Migration Engineer" and old(p))
creep = [p for p in sorted(reports["EMP-0007"], key=lambda p: p["id"]) if old(p)][:4]
roles4 = ["Operations Analyst", "Logistics Coordinator", "Facilities Coordinator", "Operations Analyst"]
for p, r in zip(creep, roles4):
    p["role"] = r
vendor_editor = creep[0]
pay_mgr = next(m for m in managers if m["role"] == "Payroll Manager")
mover = next(p for p in sorted(reports[pay_mgr["id"]], key=lambda p: p["id"]) if old(p))
mover["role_since"] = d("2026-03-02")
team_leads = [m for m in managers if m["dept"] == "Sales"]
for m in team_leads:   # every sales team lead's team; the look-alike's is all field sales (the busiest expense claimants)
    m["field"] = sum(1 for p in reports[m["id"]] if p["role"] == "Field Sales Representative")
tl = max(team_leads, key=lambda m: (m["field"], m["id"]))
for p in reports[tl["id"]]:
    p["role"] = "Field Sales Representative"
acct = [p for p in order if p["role"] == "Account Manager" and old(p) and p["mgr"] is not tl]
mut_a = acct[0]
mut_b = next(p for p in acct if p["mgr"] is not mut_a["mgr"])
STORY = {leaver["id"], engineer["id"], mover["id"], mut_a["id"], mut_b["id"], "EMP-0007"} | {p["id"] for p in creep}

# ---- leavers in the year (about 7 %), role moves (about 8 %) -----------------------------------------------------------------------

for p in order:
    if p["kind"] != "staff" or p["id"] in STORY:
        continue
    if rng.random() < 0.07 and p["hired"] < d("2025-06-01"):
        notice = START + timedelta(days=rng.randint(10, 330))
        p["notice"], p["left"] = notice, min(notice + timedelta(days=rng.choice([28, 28, 30, 35])), LANDED_END)
    elif rng.random() < 0.08 and p["hired"] < d("2024-01-01"):
        p["role_since"] = p["hired"] + timedelta(days=rng.randint(400, (d("2026-08-01") - p["hired"]).days))
leaver["notice"], leaver["left"] = d("2026-06-01"), d("2026-06-30")


def active(p, day):
    return p["hired"] <= day and (p["left"] is None or day < p["left"])


# ---- access grants ----------------------------------------------------------------------------------------------------------------------

grants = []   # (employee, system, granted_by, granted_at, ticket)
ticket_no = [104000]


def ticket(prefix="REQ"):
    ticket_no[0] += rng.randint(1, 9)
    return f"{prefix}-{ticket_no[0]}"


def systems_of(p):
    dept, role = p["dept"], p["role"]
    s = ["MAIL", "WIKI"] + (["TICKETS"] if rng.random() < 0.7 else [])
    if dept == "Customer Service":
        s += ["CRM"]
    elif dept == "Sales":
        s += ["CRM"] + (["DWH"] if rng.random() < 0.4 else [])
    elif dept == "Operations":
        s += ["ERP"] + (["DWH"] if rng.random() < 0.3 else [])
    elif dept == "Finance":
        s += ["ERP"]
        team = next((t for t in FIN_TEAMS if role in t[:2]), None)
        if team and team[2]:
            s += [team[2]]
        elif role in ("Financial Controller", "Management Accountant"):
            s += ["DWH"]
        if role == "Head of Finance":
            s += list(FINANCE)
    elif dept == "IT":
        s += ["CODE"] + (["CUSTARCH"] if role == "Data Migration Engineer" else [])
    elif dept == "Engineering":
        s += ["CODE"] + (["DWH"] if rng.random() < 0.3 else [])
    elif dept == "HR":
        s += ["HRIS"]
    elif dept == "Marketing":
        s += ["DWH"] + (["CRM"] if rng.random() < 0.3 else [])
    elif dept == "Legal":
        s += ["CONTRACTS"]
    elif dept == "Procurement":
        s += ["ERP", "CONTRACTS"]
    return s


for p in order:
    granter = p["mgr"]["id"] if p["mgr"] else ""
    for s in systems_of(p):
        at = office_time(p["hired"] + timedelta(days=rng.randint(0, 3)))
        tk = "" if (s not in FINANCE and rng.random() < 0.02) else ticket()
        grants.append((p["id"], s, granter, at, tk))
# mid-year extra grants in the background: ticketed, by the employee's manager, never a finance system outside finance
for p in rng.sample([p for p in order if p["kind"] == "staff" and p["id"] not in STORY], 420):
    s = rng.choice(["DWH", "TICKETS", "CONTRACTS", "ERP", "WIKI"])
    day = START + timedelta(days=rng.randint(0, (END - START).days))
    if not active(p, day) or any(g[0] == p["id"] and g[1] == s for g in grants):
        continue
    grants.append((p["id"], s, p["mgr"]["id"], office_time(day), ticket()))
fin_staff = [p for p in order if p["dept"] == "Finance" and p["kind"] == "staff" and p["id"] not in STORY and p["left"] is None
             and p["hired"] < d("2025-08-01")]
for p in rng.sample(fin_staff, 12):   # finance staff picking up a second finance system, ticketed, by their own manager
    have = {g[1] for g in grants if g[0] == p["id"]}
    s = next(x for x in rng.sample(FINANCE, 3) if x not in have)
    day = d("2025-10-06") + timedelta(days=rng.randint(0, 340))
    grants.append((p["id"], s, p["mgr"]["id"], office_time(day), ticket()))
# story 2: unticketed payroll and treasury grants by EMP-0007 to 4 operations reports
creep_grants = []
for i, p in enumerate(creep):
    for j, s in enumerate(("PAYROLL", "TREASURY")):
        day = d("2026-01-12") + timedelta(days=9 * i + 2 * j)
        while day.weekday() >= 5:
            day += timedelta(days=1)
        g = (p["id"], s, "EMP-0007", office_time(day), "")
        grants.append(g)
        creep_grants.append(g)
# look-alike: the mover into payroll, granted payroll access by the payroll manager the day after the move, ticketed
mover_ticket = ticket()
grants = [g for g in grants if not (g[0] == mover["id"] and g[1] == "PAYROLL")]
grants.append((mover["id"], "PAYROLL", pay_mgr["id"], dt(d("2026-03-03"), 10, 14, 5), mover_ticket))
# look-alike: the migration engineer's ticketed bulk-read grant on the customer archive
eng_ticket = ticket("CHG")
grants.append((engineer["id"], "CUSTARCH", engineer["mgr"]["id"], dt(d("2026-04-02"), 15, 31, 40), eng_ticket))
grants.sort(key=lambda g: (g[3], g[0], g[1]))

# ---- daily access counts (record-holding systems only) ---------------------------------------------------------------------------------

BASE = {"CRM": {"Customer Service": (40, 0.8), "Sales": (14, 0.3), "Marketing": (8, 0.2)},
        "CUSTARCH": {"IT": (25, 0.4)}, "HRIS": {"HR": (20, 0.6)}, "PAYROLL": {"Finance": (30, 0.6)},
        "TREASURY": {"Finance": (12, 0.6)}, "AP": {"Finance": (25, 0.7)}}
access = []   # (employee, system, day, records)
held = {}
for g in grants:
    held.setdefault((g[0], g[1]), g[3].date())
LEAVER_SPIKE = [x for x in days(d("2026-06-02"), d("2026-06-22")) if x.weekday() < 5]
ENG_SPIKE = [x for x in days(d("2026-04-06"), d("2026-04-24")) if x.weekday() < 5]
for (eid, s), since in sorted(held.items()):
    if s not in LOGGED:
        continue
    p = by_id[eid]
    base, pa = BASE[s].get(p["dept"], (6, 0.15))
    if p in creep:
        base, pa = 4, 0.1
    if p is leaver:
        base, pa = 40, 0.85
    if p is engineer and s == "CUSTARCH":
        base, pa = 25, 0.45
    base = base if p in (leaver, engineer) else max(2, lognorm(base, 0.4))
    for day in days(max(START, since), END):
        if not active(p, day):
            continue
        if p is leaver and day in LEAVER_SPIKE:
            access.append((eid, s, day, rng.randint(860, 960)))
            continue
        if p is engineer and s == "CUSTARCH" and day in ENG_SPIKE:
            access.append((eid, s, day, rng.randint(500, 600)))
            continue
        if p is leaver and day >= d("2026-06-23"):
            continue   # the last week: handover, nothing read
        if rng.random() < (pa if day.weekday() < 5 else 0.02):
            access.append((eid, s, day, max(1, round(lognorm(base, 0.35)))))

# ---- external mail, per employee, domain and day --------------------------------------------------------------------------------------

SYL = ["brack", "corv", "dun", "elm", "fenn", "garr", "hol", "ivers", "jun", "kest", "larch", "mor", "nett", "orr", "pell", "quill",
       "rook", "sedge", "tarn", "umber", "vell", "wick", "yarr", "zell"]
SUF = ["field", "ane", "mere", "worth", "ley", "stone", "ford", "brook", "gate", "holm"]
TAIL = ["-partners", "-logistics", "-supplies", "-consulting", "-group", "-systems", "-trading", ""]
business = []
while len(business) < 70:
    dom = rng.choice(SYL) + rng.choice(SUF) + rng.choice(TAIL) + ".example"
    if dom not in business:
        business.append(dom)
bus_w = [1 / (i + 1) ** 0.8 for i in range(len(business))]
SEND_P = {"Sales": 0.95, "Procurement": 0.9, "Legal": 0.8, "Customer Service": 0.3}
mail = {}   # (employee, domain, day) -> [messages, mb]


def send(eid, dom, day, msgs, mb):
    r = mail.setdefault((eid, dom, day), [0, 0.0])
    r[0] += msgs
    r[1] += mb


workdays = [x for x in days(START, END) if x.weekday() < 5]
for p in order:
    if p is engineer or p is leaver or rng.random() >= SEND_P.get(p["dept"], 0.4):
        continue
    live = [x for x in workdays if active(p, x)]
    if not live:
        continue
    heavy = 3 if p["dept"] in ("Sales", "Procurement") else 1
    for dom in sorted(set(rng.choices(business, bus_w, k=rng.randint(1, 4)))):
        for day in rng.sample(live, min(len(live), max(1, int(rng.expovariate(1 / (6 * heavy)))))):
            mb = 0.0 if rng.random() < 0.6 else round(lognorm(1.5, 0.9), 1)
            send(p["id"], dom, day, rng.randint(1, 5), min(mb, 25.0))
    if rng.random() < 0.25:
        dom = rng.choices(PERSONAL, [3, 2, 4])[0]
        total = 0.0
        for day in rng.sample(live, min(len(live), rng.randint(1, 8))):
            mb = 0.0 if rng.random() < 0.5 else min(round(lognorm(2, 0.8), 1), 12.0)
            if total + mb > 60:
                mb = 0.0
            total += mb
            send(p["id"], dom, day, rng.randint(1, 2), mb)
# the migration engineer: a little business mail, nothing personal
for day in rng.sample(workdays, 5):
    send(engineer["id"], business[3], day, rng.randint(1, 3), round(rng.uniform(0, 2), 1))
# story 1: the leaver's personal domain - a few small mails earlier in the year, then 1.2 GB after notice
LEAVER_DOMAIN = "postmail.example"
for day in (d("2025-12-19"), d("2026-02-27"), d("2026-04-10")):
    send(leaver["id"], LEAVER_DOMAIN, day, 1, round(rng.uniform(0.5, 3), 1))
mail_days = [x for x in LEAVER_SPIKE if x >= d("2026-06-04")][::1][:9]
chunks = [round(rng.uniform(110, 160), 1) for _ in mail_days]
chunks[-1] = round(1228.8 - sum(chunks[:-1]), 1)
for day, mb in zip(mail_days, chunks):
    send(leaver["id"], LEAVER_DOMAIN, day, rng.randint(6, 14), mb)

# ---- expenses ---------------------------------------------------------------------------------------------------------------------------------

LAMBDA = {"Field Sales Representative": 30, "Account Manager": 12, "Sales Team Lead": 8, "Buyer": 7, "Category Manager": 7,
          "Customer Account Specialist": 1.2, "Customer Service Advisor": 1.2, "Complaints Handler": 1.2}
expenses = []   # (requester, approver, amount, submitted_at)


def expense_time(day, weekend_ok):
    if not weekend_ok:
        while day.weekday() >= 5:
            day += timedelta(days=1)
    return dt(day, rng.randint(7, 22), rng.randint(0, 59), rng.randint(0, 59))


for p in order:
    if p is ceo:
        continue
    lam = LAMBDA.get(p["role"], 10 if p["kind"] != "staff" else 5.6) * 13 / 12
    if p in (mut_a, mut_b):
        lam = 6.5
    n = sum(1 for _ in range(int(lam * 3)) if rng.random() < 1 / 3)
    if p in (mut_a, mut_b):
        n = min(n, 8)
    for _ in range(n):
        day = START + timedelta(days=rng.randint(0, (END - START).days))
        if not active(p, day):
            continue
        appr = p["mgr"]
        if p["kind"] == "staff" and rng.random() < 0.07:
            appr = appr["mgr"]   # the manager away: the manager's manager approves
        amt = round(min(lognorm(55, 0.9), 1800), 2)
        if p is tl or p["mgr"] is tl:
            amt = round(min(lognorm(70, 0.7), 480), 2)
        at = expense_time(day, rng.random() < 0.06)
        if active(p, at.date()):   # a Saturday moved to Monday may fall after the last day
            expenses.append((p["id"], appr["id"], amt, at))
# story 3: mutual approvals just under the limit, mostly at weekends
mutual = []
slots = sorted(rng.sample(range(0, 330), 40))
weekend = set(rng.sample(range(40), 32))
who = [(mut_b, mut_a)] * 21 + [(mut_a, mut_b)] * 19   # (requester, approver): A approves B 21 times, B approves A 19
rng.shuffle(who)
for i, off in enumerate(slots):
    req, appr = who[i]
    day = d("2025-10-18") + timedelta(days=off)
    if i in weekend:
        day += timedelta(days=(5 - day.weekday()) % 7 + rng.choice([0, 1]))
    else:
        while day.weekday() >= 5:
            day += timedelta(days=1)
    e = (req["id"], appr["id"], round(rng.uniform(452, 499.9), 2), dt(day, rng.randint(9, 21), rng.randint(0, 59), rng.randint(0, 59)))
    mutual.append(e)
expenses += mutual
expenses.sort(key=lambda e: (e[3], e[0]))
expenses = [(f"EXP-{i + 1:06d}",) + e for i, e in enumerate(expenses)]

# ---- vendor master changes ----------------------------------------------------------------------------------------------------------

VENDORS = [f"VEN-{i + 1:04d}" for i in range(400)]
CREEP_VENDOR = VENDORS[263]
ap_staff = [p for p in order if p["role"] in ("Accounts Payable Clerk", "Accounts Payable Manager")]
FIELDS = ["address", "phone", "contact_email", "bank_account", "payment_terms"]
changes = []
others = [v for v in VENDORS if v != CREEP_VENDOR]
vend_w = [1 / (i + 1) ** 0.5 for i in range(len(others))]
while len(changes) < 599:
    day = START + timedelta(days=rng.randint(0, (END - START).days))
    if day.weekday() >= 5:
        continue
    who = [p for p in ap_staff if active(p, day)]
    if not who:
        continue
    changes.append((rng.choices(others, vend_w)[0], rng.choices(FIELDS, [3, 2, 3, 1.5, 1])[0], rng.choice(who)["id"], office_time(day)))
creep_change = (CREEP_VENDOR, "bank_account", vendor_editor["id"], dt(d("2026-05-14"), 10, 42, 17))
changes.append(creep_change)
changes.sort(key=lambda c: (c[3], c[0]))
changes = [(f"VC-{i + 1:04d}",) + c for i, c in enumerate(changes)]

# ---- checks the golden tests will pin --------------------------------------------------------------------------------------------------


def ratio_stats(rows):
    """Per (employee, system): median daily count and the largest 21-day average (windows of 5 read days or more)."""
    per = {}
    for e, s, day, n in rows:
        per.setdefault((e, s), []).append((day, n))
    out = {}
    for k, v in per.items():
        v.sort()
        vals = sorted(n for _, n in v)
        med = (vals[(len(vals) - 1) // 2] + vals[len(vals) // 2]) / 2
        best, lo = 0.0, 0
        for hi in range(len(v)):
            while v[lo][0] < v[hi][0] - timedelta(days=20):
                lo += 1
            if hi - lo + 1 >= 5:
                best = max(best, sum(n for _, n in v[lo:hi + 1]) / (hi - lo + 1))
        out[k] = (med, best, best / med if best else 0)
    return out


landed_access = [a for a in access if a[2] <= LANDED_END]
for rows in (landed_access, access):
    rs = ratio_stats(rows)
    spikes = sorted(k for k, v in rs.items() if v[2] >= 10)
    assert spikes == sorted([(leaver["id"], "CRM"), (engineer["id"], "CUSTARCH")]), spikes
    assert max(v[2] for k, v in rs.items() if k not in spikes) < 4, "background reads never spike"
    assert all(14 <= rs[k][2] <= 30 for k in spikes), [rs[k] for k in spikes]
pairs = {}
for _, req, appr, amt, at in expenses:
    pairs.setdefault((appr, req), []).append((amt, at))
recip = sorted(k for k in pairs if (k[1], k[0]) in pairs)
assert recip == sorted([(mut_a["id"], mut_b["id"]), (mut_b["id"], mut_a["id"])]), recip
heavy = {k for k, v in pairs.items() if len(v) >= 20}
assert not any(mut_a["id"] in k or mut_b["id"] in k for k in heavy - set(recip)), "the pair's other approvals stay light"
assert sum(1 for k in heavy if k[0] == tl["id"]) >= 5, "the team lead approves the team often"
assert all(c[1] != CREEP_VENDOR for c in changes if c[3] != vendor_editor["id"])
assert sum(1 for g in grants if g[1] in FINANCE and not g[4]) == 8, "only the 8 story grants to a finance system lack a ticket"

# ---- write ---------------------------------------------------------------------------------------------------------------------------------


def ym(day):
    return day.strftime("%Y%m")


def write(box, feed, name, header, rows):
    p = DATA / box / feed / name
    p.parent.mkdir(parents=True, exist_ok=True)
    with out.open(p) as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)


def by_month(feed, prefix, header, rows, day_of):
    for m in MONTHS:
        box = "staged" if m > LANDED_END else "inbox"
        write(box, feed, f"{prefix}_{ym(m)}.csv", header, [r for r in rows if ym(day_of(r)) == ym(m)])


def ts(x):
    return x.strftime("%Y-%m-%d %H:%M:%S")


iso = lambda x: x.isoformat() if x else ""
out = Outputs()
write("inbox", "employee", "EMPLOYEE_20260930.csv",
      ["employee_id", "dept", "role", "manager_id", "hired_at", "role_since", "notice_given_at", "left_at"],
      [[p["id"], p["dept"], p["role"], p["mgr"]["id"] if p["mgr"] else "", iso(p["hired"]), iso(p["role_since"]), iso(p["notice"]),
        iso(p["left"])] for p in sorted(people, key=lambda p: p["id"])])
GH = ["employee_id", "system", "granted_by", "granted_at", "ticket_id"]
write("inbox", "access_grant", "ACCESS_GRANT_20260930.csv", GH, [[g[0], g[1], g[2], ts(g[3]), g[4]] for g in grants if g[3].date() <= LANDED_END])
write("staged", "access_grant", "ACCESS_GRANT_20261031.csv", GH, [[g[0], g[1], g[2], ts(g[3]), g[4]] for g in grants if g[3].date() > LANDED_END])
access.sort(key=lambda a: (a[2], a[0], a[1]))
by_month("access_daily", "ACCESS_DAILY", ["employee_id", "system", "read_date", "records_read"],
         [[a[0], a[1], a[2].isoformat(), a[3]] for a in access], lambda r: d(r[2]))
mail_rows = sorted([[k[0], k[1], k[2].isoformat(), v[0], round(v[1], 1)] for k, v in mail.items()], key=lambda r: (r[2], r[0], r[1]))
by_month("email_external", "EMAIL_EXTERNAL", ["employee_id", "domain", "sent_date", "messages", "attachment_mb"], mail_rows, lambda r: d(r[2]))
by_month("expense", "EXPENSE", ["expense_id", "requester_id", "approver_id", "amount", "submitted_at"],
         [[e[0], e[1], e[2], f"{e[3]:.2f}", ts(e[4])] for e in expenses], lambda r: d(r[4][:10]))
VH = ["change_id", "vendor_id", "field_name", "changed_by", "changed_at"]
write("inbox", "vendor_change", "VENDOR_CHANGE_20260930.csv", VH, [[c[0], c[1], c[2], c[3], ts(c[4])] for c in changes if c[4].date() <= LANDED_END])
write("staged", "vendor_change", "VENDOR_CHANGE_20261031.csv", VH, [[c[0], c[1], c[2], c[3], ts(c[4])] for c in changes if c[4].date() > LANDED_END])

# ---- ground truth ------------------------------------------------------------------------------------------------------------------------

landed = lambda xs, f: sum(1 for x in xs if f(x) <= LANDED_END)
rs = ratio_stats(landed_access)
lv, en = rs[(leaver["id"], "CRM")], rs[(engineer["id"], "CUSTARCH")]
truth = {
    "seed": SEED,
    "landed": [START.isoformat(), LANDED_END.isoformat()],
    "staged_month": "2026-10",
    "limit": LIMIT,
    "finance_systems": list(FINANCE),
    "personal_domains": list(PERSONAL),
    "counts": {
        "employee": len(people),
        "access_grant_landed": landed(grants, lambda g: g[3].date()),
        "access_daily_landed": len(landed_access),
        "email_external_landed": landed(mail_rows, lambda r: d(r[2])),
        "expense_landed": landed(expenses, lambda e: e[4].date()),
        "vendor_change_landed": landed(changes, lambda c: c[4].date()),
    },
    "stories": {
        "leaver_exfiltration": {"view": "leaver_exfiltration", "hub": leaver["id"],
                                "entities": [leaver["id"], "SYS-CRM", LEAVER_DOMAIN],
                                "lookalikes": [engineer["id"], "SYS-CUSTARCH"]},
        "access_creep": {"view": "access_creep", "hub": "EMP-0007",
                         "entities": ["EMP-0007"] + [p["id"] for p in creep] + [CREEP_VENDOR],
                         "lookalikes": [mover["id"], pay_mgr["id"]]},
        "mutual_approvals": {"view": "mutual_approvals", "hub": mut_a["id"], "entities": [mut_a["id"], mut_b["id"]],
                             "lookalikes": [tl["id"]]},
    },
    "leaver_exfiltration": {
        "leaver": leaver["id"], "role": leaver["role"], "notice_given_at": iso(leaver["notice"]), "left_at": iso(leaver["left"]),
        "system": "CRM", "daily_median": lv[0], "peak_21d_avg": round(lv[1], 1), "peak_ratio": round(lv[2], 1),
        "spike_days": [x.isoformat() for x in LEAVER_SPIKE], "domain": LEAVER_DOMAIN,
        "after_notice_mb": round(sum(chunks), 1), "mail_days": [x.isoformat() for x in mail_days],
        "lookalike_engineer": engineer["id"], "lookalike_role": engineer["role"], "lookalike_system": "CUSTARCH",
        "lookalike_ticket": eng_ticket, "lookalike_daily_median": en[0], "lookalike_peak_ratio": round(en[2], 1),
        "lookalike_spike_days": [x.isoformat() for x in ENG_SPIKE],
    },
    "access_creep": {
        "manager": "EMP-0007", "manager_role": creep_mgr["role"], "reports": [p["id"] for p in creep],
        "report_roles": [p["role"] for p in creep], "systems": ["PAYROLL", "TREASURY"],
        "grants": [[g[0], g[1], ts(g[3])] for g in creep_grants],
        "vendor_editor": vendor_editor["id"], "vendor": CREEP_VENDOR, "vendor_changed_at": ts(creep_change[3]),
        "lookalike_employee": mover["id"], "lookalike_role": mover["role"], "lookalike_role_since": iso(mover["role_since"]),
        "lookalike_manager": pay_mgr["id"], "lookalike_ticket": mover_ticket,
    },
    "mutual_approvals": {
        "a": mut_a["id"], "b": mut_b["id"], "a_manager": mut_a["mgr"]["id"], "b_manager": mut_b["mgr"]["id"],
        "a_approved_b": sum(1 for m in mutual if m[1] == mut_a["id"]), "b_approved_a": sum(1 for m in mutual if m[1] == mut_b["id"]),
        "weekend": sum(1 for m in mutual if m[3].weekday() >= 5), "expenses": sorted(e[0] for e in expenses if e[1:] in [tuple(m) for m in mutual]),
        "lookalike_team_lead": tl["id"], "lookalike_team": sorted(p["id"] for p in reports[tl["id"]]),
        "lookalike_heavy_reports": sorted(k[1] for k in heavy if k[0] == tl["id"]),
    },
}
out.write_text(TRUTH, json.dumps(truth, indent=2) + "\n")
out.prune(DATA, "*/*/*.csv")
print(json.dumps(truth["counts"]), "grants", len(grants), "access", len(access), "mail", len(mail_rows), "expenses", len(expenses),
      "changes", len(changes))
