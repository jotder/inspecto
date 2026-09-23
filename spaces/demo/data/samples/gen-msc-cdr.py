#!/usr/bin/env python3
"""Generate the synthetic MSC switch CDR file (ASN.1 BER) for the `msc_cdr` pipeline.

A mobile switching centre writes its call detail records as a stream of BER-encoded
`CallEventRecord` values, back to back, one file per collection interval. The record type is the
CHOICE alternative (its context tag), so one file carries voice originating, voice terminating,
SMS and supplementary-service records mixed together. This generator reproduces that shape over a
SMALL grammar written for this demo — it mimics the 3GPP TS 32.298 layout (tags, OPTIONAL location,
nested SEQUENCE) and copies no vendor grammar and no captured record.

Everything here is SYNTHETIC and deterministic (no RNG at all — every value is written out below).
IMSIs use the ITU test PLMN 001-01, MSISDNs the +99 reserved range, MSC ids are invented. See the
repo rule: sample data is hand-generated, never trimmed from a capture.

THE INDEPENDENT EXPECTATION (why a hand-authored demo exists)
-------------------------------------------------------------
13 records in one file, by CHOICE alternative:
    moCallRecord   5   -> segment moCallRecord  -> 5 rows
    mtCallRecord   4   -> segment mtCallRecord  -> 4 rows
    moSMSRecord    3   -> segment moSMSRecord   -> 3 rows
    ssActionRecord 1   -> NOT a declared segment -> counted as junk, lands nowhere
So a run over the file must write 5 : 4 : 3 and nothing else. Three records omit the OPTIONAL
`location` (one MO call, one MT call, one SMS), so LAC / CELL_ID are NULL on exactly one row of each
segment.

`parsing.asn1.grammar` in `spaces/demo/config/msc/msc_cdr_pipeline.toon` is the GRAMMAR constant
below with its whitespace collapsed to single spaces (TOON has no multi-line string, so the pipeline
carries it on one line; this file keeps the readable copy). `--print-grammar` prints that line.

Usage:
    python gen-msc-cdr.py                # rewrite the sample file next to this script
    python gen-msc-cdr.py --seed-inbox   # also copy it into the pipeline poll dir
    python gen-msc-cdr.py --print-grammar  # print the one-line grammar the pipeline carries
"""

import argparse
import pathlib
import shutil

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE / "msc_cdr"
INBOX = HERE.parents[1] / "data" / "inbox" / "msc_cdr"
FILE_NAME = "MSC01_20260801_0800.ber"

GRAMMAR = """
MSC-CDR DEFINITIONS IMPLICIT TAGS ::= BEGIN
CallEventRecord ::= CHOICE {
    moCallRecord   [0]  MOCallRecord,
    mtCallRecord   [1]  MTCallRecord,
    moSMSRecord    [6]  MOSMSRecord,
    ssActionRecord [10] SSActionRecord
}
MOCallRecord ::= SEQUENCE {
    servedIMSI     [0] IA5String,
    servedMSISDN   [1] IA5String,
    calledNumber   [2] IA5String,
    mscIdentity    [3] IA5String,
    location       [4] LocationInfo OPTIONAL,
    answerTime     [5] IA5String,
    callDuration   [6] INTEGER,
    causeForTerm   [7] INTEGER
}
MTCallRecord ::= SEQUENCE {
    servedIMSI     [0] IA5String,
    servedMSISDN   [1] IA5String,
    callingNumber  [2] IA5String,
    mscIdentity    [3] IA5String,
    location       [4] LocationInfo OPTIONAL,
    answerTime     [5] IA5String,
    callDuration   [6] INTEGER,
    causeForTerm   [7] INTEGER
}
MOSMSRecord ::= SEQUENCE {
    servedIMSI        [0] IA5String,
    servedMSISDN      [1] IA5String,
    destinationNumber [2] IA5String,
    mscIdentity       [3] IA5String,
    location          [4] LocationInfo OPTIONAL,
    originationTime   [5] IA5String,
    smsResult         [6] INTEGER
}
SSActionRecord ::= SEQUENCE {
    servedIMSI     [0] IA5String,
    ssCode         [1] INTEGER,
    actionTime     [2] IA5String
}
LocationInfo ::= SEQUENCE {
    lac            [0] INTEGER,
    cellId         [1] INTEGER
}
END
"""

# ── BER primitives (definite lengths, IMPLICIT context tags) ──────────────────────────────────

def length(n):
    if n < 0x80:
        return bytes([n])
    body = n.to_bytes((n.bit_length() + 7) // 8, "big")
    return bytes([0x80 | len(body)]) + body


def tlv(tag_byte, content):
    return bytes([tag_byte]) + length(len(content)) + content


def ctx(n, content, constructed=False):
    """Context-specific tag [n] (n < 31: single-byte tag)."""
    assert n < 31
    return tlv(0x80 | (0x20 if constructed else 0) | n, content)


def ia5(n, text):
    return ctx(n, text.encode("ascii"))


def integer(n, value):
    body = value.to_bytes(max(1, (value.bit_length() + 8) // 8), "big", signed=True)
    return ctx(n, body)


def location(lac, cell):
    return ctx(4, integer(0, lac) + integer(1, cell), constructed=True)


# ── the records ───────────────────────────────────────────────────────────────────────────────

MSC = "MSC-NORTH-01"


def imsi(i):
    return "00101{:010d}".format(i)


def msisdn(i):
    return "99{:010d}".format(i)


def call(tag, i, other, loc, at, dur, cause):
    body = ia5(0, imsi(i)) + ia5(1, msisdn(i)) + ia5(2, other) + ia5(3, MSC)
    if loc is not None:
        body += location(*loc)
    body += ia5(5, at) + integer(6, dur) + integer(7, cause)
    return ctx(tag, body, constructed=True)


def mo_call(i, other, loc, at, dur, cause=0):
    return call(0, i, other, loc, at, dur, cause)


def mt_call(i, other, loc, at, dur, cause=0):
    return call(1, i, other, loc, at, dur, cause)


def mo_sms(i, dest, loc, at, result=0):
    body = ia5(0, imsi(i)) + ia5(1, msisdn(i)) + ia5(2, dest) + ia5(3, MSC)
    if loc is not None:
        body += location(*loc)
    body += ia5(5, at) + integer(6, result)
    return ctx(6, body, constructed=True)


def ss_action(i, code, at):
    return ctx(10, ia5(0, imsi(i)) + integer(1, code) + ia5(2, at), constructed=True)


RECORDS = [
    mo_call(1, msisdn(21), (1001, 31001), "20260801080102", 183),
    mt_call(2, msisdn(22), (1001, 31002), "20260801080310", 47),
    mo_sms(3, msisdn(23), (1002, 31010), "20260801080455"),
    mo_call(4, msisdn(24), None, "20260801080907", 612, cause=16),  # no location
    mt_call(5, msisdn(1), (1002, 31011), "20260801081244", 95),
    ss_action(6, 33, "20260801081500"),                              # junk: not a segment
    mo_call(7, msisdn(27), (1003, 31020), "20260801081958", 1),
    mo_sms(8, msisdn(28), (1003, 31021), "20260801082233"),
    mt_call(9, msisdn(29), None, "20260801083011", 301),             # no location
    mo_call(10, msisdn(30), (1001, 31003), "20260801084125", 2400),  # multi-byte INTEGER
    mo_sms(11, msisdn(31), None, "20260801084802", result=2),
    mt_call(12, msisdn(32), (1004, 31030), "20260801085519", 130, cause=17),
    mo_call(13, msisdn(33), (1004, 31031), "20260801085947", 58),
]


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--seed-inbox", action="store_true", help="also copy into the poll dir")
    ap.add_argument("--print-grammar", action="store_true", help="print the one-line grammar and exit")
    args = ap.parse_args()
    if args.print_grammar:
        print(" ".join(GRAMMAR.split()))
        return

    OUT.mkdir(parents=True, exist_ok=True)
    target = OUT / FILE_NAME
    target.write_bytes(b"".join(RECORDS))
    print(f"wrote {target.relative_to(HERE)} - {len(RECORDS)} records, {target.stat().st_size} bytes")

    if args.seed_inbox:
        INBOX.mkdir(parents=True, exist_ok=True)
        shutil.copy2(target, INBOX / FILE_NAME)
        print(f"seeded {INBOX}")


if __name__ == "__main__":
    main()
