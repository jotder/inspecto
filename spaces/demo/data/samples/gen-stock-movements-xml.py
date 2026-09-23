#!/usr/bin/env python3
"""Generate the synthetic warehouse stock-movement feed for the `stock_movements` pipeline.

A warehouse management system's end-of-day export: ONE XML document per warehouse per business day,
whose root holds a mixed sequence of movement records — goods receipts, dispatches against sales
orders, internal transfers between bin locations, and stock-count adjustments. That mixed-kind
layout is what makes the demo worth having: the Pipeline leaves `record_element` blank and declares
one segment per movement kind, so each kind lands in its own store, and a kind it does not declare
(`adjustment`) is skipped as junk rather than written anywhere.

Everything here is SYNTHETIC and deterministic: fixed data, no randomness, byte-identical output on
every run. Warehouse, supplier, customer-order and SKU codes are invented. See the repo rule:
sample data is hand-generated, never trimmed from a real WMS export.

SHAPES THE SELECTORS HAVE TO REACH (deliberate)
  * the movement id is an ATTRIBUTE (`@id`);
  * the SKU is an attribute of a nested element (`item.@sku`);
  * the quantity is an element with BOTH text and an attribute (`<qty uom="EA">120</qty>`), so its
    value is `item.qty.#text` and its unit is `item.qty.@uom` — `item.qty` alone is a container
    and would land NULL;
  * one receipt carries a `<note>`; nothing selects it, and it must not disturb the row.

INDEPENDENT EXPECTATION (what a test run over this file must produce)
  12 movement elements under the root:
      receipt 5 · dispatch 4 · transfer 2     -> 11 rows written, one store per kind (5:4:2)
      adjustment 1                          -> NOT written: counted as a junk candidate
  Quantity totals in EA: receipts 480, dispatches 150, transfers 60 — a NULL QTY anywhere means a
  selector missed the `#text` step.

Usage:  python gen-stock-movements-xml.py   (writes stock_movements/MOVEMENTS_WH01_20260815.xml beside it)
"""

import os
from xml.sax.saxutils import escape, quoteattr

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "stock_movements", "MOVEMENTS_WH01_20260815.xml")

WAREHOUSE = "WH01"
BUSINESS_DATE = "2026-08-15"

# kind, id, time, sku, qty, uom, extra children (tag, value) in document order
MOVEMENTS = [
    ("receipt",    "RCV-0001", "06:05:00", "SKU-10001", 120, "EA", [("supplier", "SUP-001"), ("location", "A-01-01")]),
    ("receipt",    "RCV-0002", "06:40:00", "SKU-10002",  80, "EA", [("supplier", "SUP-001"), ("location", "A-01-02")]),
    ("dispatch",   "DSP-0001", "08:12:00", "SKU-10001",  30, "EA", [("order", "SO-5001"), ("location", "A-01-01")]),
    ("transfer",   "TRF-0001", "09:00:00", "SKU-10002",  40, "EA", [("from", "A-01-02"), ("to", "B-02-03")]),
    ("receipt",    "RCV-0003", "09:30:00", "SKU-10003", 200, "EA", [("supplier", "SUP-002"), ("location", "A-02-01"),
                                                                    ("note", "Pallet re-wrapped on arrival")]),
    ("dispatch",   "DSP-0002", "10:45:00", "SKU-10003",  50, "EA", [("order", "SO-5002"), ("location", "A-02-01")]),
    ("adjustment", "ADJ-0001", "12:00:00", "SKU-10002",  -2, "EA", [("reason", "CYCLE_COUNT"), ("location", "B-02-03")]),
    ("receipt",    "RCV-0004", "13:15:00", "SKU-10004",  60, "EA", [("supplier", "SUP-003"), ("location", "C-01-01")]),
    ("dispatch",   "DSP-0003", "14:20:00", "SKU-10004",  40, "EA", [("order", "SO-5003"), ("location", "C-01-01")]),
    ("transfer",   "TRF-0002", "15:05:00", "SKU-10003",  20, "EA", [("from", "A-02-01"), ("to", "B-01-01")]),
    ("receipt",    "RCV-0005", "16:30:00", "SKU-10005",  20, "EA", [("supplier", "SUP-002"), ("location", "C-02-04")]),
    ("dispatch",   "DSP-0004", "17:10:00", "SKU-10001",  30, "EA", [("order", "SO-5004"), ("location", "A-01-01")]),
]

EXPECTED = {"receipt": (5, 480), "dispatch": (4, 150), "transfer": (2, 60), "adjustment": (1, -2)}


def check():
    for kind, (count, qty) in EXPECTED.items():
        rows = [m for m in MOVEMENTS if m[0] == kind]
        assert len(rows) == count and sum(m[4] for m in rows) == qty, (kind, len(rows), sum(m[4] for m in rows))


def render():
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           f'<stockMovements warehouse={quoteattr(WAREHOUSE)} businessDate={quoteattr(BUSINESS_DATE)} source="SYNTHETIC">']
    for kind, mid, time, sku, qty, uom, extra in MOVEMENTS:
        out.append(f'  <{kind} id={quoteattr(mid)}>')
        out.append(f'    <at>{BUSINESS_DATE}T{time}</at>')
        out.append(f'    <item sku={quoteattr(sku)}><qty uom={quoteattr(uom)}>{qty}</qty></item>')
        for tag, value in extra:
            out.append(f'    <{tag}>{escape(value)}</{tag}>')
        out.append(f'  </{kind}>')
    out.append('</stockMovements>')
    return "\n".join(out) + "\n"


def main():
    check()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(render())
    print(f"wrote {os.path.relpath(OUT, HERE)}: {len(MOVEMENTS)} movements "
          + ", ".join(f"{k} {v[0]}" for k, v in EXPECTED.items()))


if __name__ == "__main__":
    main()
