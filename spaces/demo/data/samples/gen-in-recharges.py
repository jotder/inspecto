#!/usr/bin/env python3
"""Generate the synthetic prepaid recharge export (fixed-width text) for the `in_recharges` pipeline.

A prepaid Intelligent Network platform exports its top-ups as a fixed-width file: a short header
record, one 57-character detail record per recharge, and a short trailer carrying the record count
as a control total. Amounts are in MINOR units with an implied two decimals (`000001000` = 10.00),
the classic fixed-width trap a mapping has to undo. This generator reproduces that shape.

Everything here is SYNTHETIC and deterministic (no RNG — every value is written out below).
MSISDNs are in the +99 reserved range and the currency is XTS, the ISO 4217 code reserved for
testing, so no value can be mistaken for real traffic or real money. See the repo rule: sample
data is hand-generated, never trimmed from a capture.

DETAIL LAYOUT (0-based start, length) - must match `parsing.fixedwidth.fields` in
`spaces/demo/config/recharge/in_recharges_pipeline.toon`:
    REC_TYPE      0  1   'D'
    SEQ_NO        1  6   zero-padded
    MSISDN        7 12
    RECHARGE_TS  19 14   yyyyMMddHHmmss
    CHANNEL      33  6   VOUCHR | USSD | RETAIL | BANK, space-padded
    AMOUNT_MINOR 39  9   zero-padded, implied 2 decimals
    CURRENCY     48  3
    STATUS       51  2   OK | RJ
    REASON       53  4   blank on OK, a platform reject code on RJ

THE INDEPENDENT EXPECTATION (why a hand-authored demo exists)
-------------------------------------------------------------
16 physical lines: 1 header + 14 details + 1 trailer. The header and trailer are shorter than a
detail record, so `min_record_length: 57` drops them before parsing. Of the 14 details:
    STATUS = OK  10  -> branch accepted -> 10 rows, AMOUNT summing to 177.50
    STATUS = RJ   4  -> branch rejected ->  4 rows
A run that writes anything but 10 : 4, or lands the header/trailer as a row, or sums the accepted
amounts to 17750 (implied decimals not undone), is wrong.

Usage:
    python gen-in-recharges.py              # rewrite the sample file next to this script
    python gen-in-recharges.py --seed-inbox # also copy it into the pipeline poll dir
"""

import argparse
import pathlib
import shutil

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE / "in_recharges"
INBOX = HERE.parents[1] / "data" / "inbox" / "in_recharges"
FILE_NAME = "RCH_20260801.dat"
RECORD_LENGTH = 57

# (msisdn index, time, channel, amount in minor units, status, reason)
RECHARGES = [
    (1,  "080112", "VOUCHR", 1000, "OK", ""),
    (2,  "081530", "USSD",    500, "OK", ""),
    (3,  "082205", "RETAIL", 2000, "OK", ""),
    (4,  "083348", "USSD",    500, "RJ", "E041"),   # voucher already used
    (5,  "084019", "BANK",   5000, "OK", ""),
    (6,  "085502", "VOUCHR", 1000, "RJ", "E017"),   # subscriber barred
    (7,  "090144", "USSD",    250, "OK", ""),
    (8,  "091827", "RETAIL", 1500, "OK", ""),
    (9,  "093310", "BANK",   2000, "RJ", "E052"),   # bank authorisation declined
    (10, "094456", "VOUCHR", 2500, "OK", ""),
    (11, "100203", "USSD",   1000, "OK", ""),
    (12, "101739", "RETAIL", 3000, "RJ", "E041"),
    (13, "103015", "BANK",   1000, "OK", ""),
    (14, "104648", "VOUCHR", 3000, "OK", ""),
]
DAY = "20260801"


def detail(seq, rec):
    idx, hhmmss, channel, minor, status, reason = rec
    line = "D{:06d}{}{}{:<6}{:09d}{}{}{:<4}".format(
        seq, "99{:010d}".format(idx), DAY + hhmmss, channel, minor, "XTS", status, reason)
    assert len(line) == RECORD_LENGTH, (len(line), line)
    return line


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--seed-inbox", action="store_true", help="also copy into the poll dir")
    args = ap.parse_args()

    lines = ["HIN-RCH {} PLATFORM-A".format(DAY)]
    lines += [detail(i + 1, r) for i, r in enumerate(RECHARGES)]
    lines.append("T{:06d}".format(len(RECHARGES)))

    OUT.mkdir(parents=True, exist_ok=True)
    target = OUT / FILE_NAME
    target.write_bytes(("\n".join(lines) + "\n").encode("ascii"))
    ok = [r for r in RECHARGES if r[4] == "OK"]
    print(f"wrote {target.relative_to(HERE)} - {len(RECHARGES)} details "
          f"({len(ok)} OK summing to {sum(r[3] for r in ok) / 100:.2f}, {len(RECHARGES) - len(ok)} RJ)")

    if args.seed_inbox:
        INBOX.mkdir(parents=True, exist_ok=True)
        shutil.copy2(target, INBOX / FILE_NAME)
        print(f"seeded {INBOX}")


if __name__ == "__main__":
    main()
