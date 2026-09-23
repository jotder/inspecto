#!/usr/bin/env python3
"""Generate the synthetic general-ledger journal workbook for the `gl_journal` pipeline.

A finance team's month-end journal export: one sheet, a title row, a blank row, a header row, the
journal lines, and a TOTALS row directly under the last line. That layout is what makes the demo
worth having — the Pipeline must read the header + lines through an explicit `range` and must NOT
read the title or the totals row, and the only way to tell is to know the line count in advance.

Everything here is SYNTHETIC and deterministic: no randomness, a fixed zip timestamp, a hand-written
workbook (stdlib `zipfile` only, no openpyxl) so re-running the script reproduces the committed
bytes exactly. The company name is fictitious and the account plan is a textbook one. See the repo
rule: sample data is hand-generated, never trimmed from a real ledger.

CELL TYPES (deliberate — this is what a real export carries)
  * Posting Date is a real Excel date: a serial number styled with built-in number format 14,
    not a text cell. The Pipeline reads it with all_varchar, so the demo shows what that yields.
  * Debit / Credit are numeric cells; the side a line does not post to is an EMPTY cell (no <c>),
    not a zero — the mapping's signed AMOUNT has to COALESCE them.
  * One line's Currency is keyed " usd " (lower case, padded) to exercise the mapping's
    UPPER(TRIM(...)) normalisation — the output must hold exactly one currency, USD.
  * Account Class is clean on purpose: it is the partition source, and a partition reads the RAW
    column (the mapping has not run yet), so a padded class would land in a partition of its own.

INDEPENDENT EXPECTATION (what a test run over this file must produce)
  13 journal lines in 6 balanced entries, partitioned by account class:
      ASSET 5 · LIABILITY 3 · EXPENSE 3 · REVENUE 2          (5:3:3:2, and exactly 4 partitions)
  Total debits = total credits = 14925.45, so SUM(AMOUNT) over the output is exactly 0.
  A 14th row, a TOTALS/NULL-class row, or a second currency means the range or the mapping is wrong.

Usage:  python gen-gl-journal-xlsx.py          (writes gl_journal/JOURNAL_20260831.xlsx beside it)
"""

import datetime
import os
import zipfile
from xml.sax.saxutils import escape

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "gl_journal", "JOURNAL_20260831.xlsx")

SHEET = "Journal"
TITLE = "Example Ledger Co - General ledger journal, August 2026 (SYNTHETIC)"
HEADER = ["Entry ID", "Line No", "Posting Date", "Account", "Account Name", "Account Class",
          "Cost Center", "Debit", "Credit", "Currency", "Memo"]

# (entry, line, date, account, name, class, cost_center, debit, credit, memo)
LINES = [
    ("JE-0001", 1, "2026-08-03", "1100", "Cash at bank",        "ASSET",     "CC-100", 5000.00, None,    "Cash sale, week 1"),
    ("JE-0001", 2, "2026-08-03", "4000", "Sales",               "REVENUE",   "CC-100", None,    5000.00, "Cash sale, week 1"),
    ("JE-0002", 1, "2026-08-05", "6100", "Rent",                "EXPENSE",   "CC-900", 1200.00, None,    "August office rent"),
    ("JE-0002", 2, "2026-08-05", "1100", "Cash at bank",        "ASSET",     "CC-900", None,    1200.00, "August office rent"),
    ("JE-0003", 1, "2026-08-10", "1200", "Inventory",           "ASSET",     "CC-200", 3000.00, None,    "Stock purchase on credit"),
    ("JE-0003", 2, "2026-08-10", "2000", "Accounts payable",    "LIABILITY", "CC-200", None,    3000.00, "Stock purchase on credit"),
    ("JE-0004", 1, "2026-08-14", "6200", "Utilities",           "EXPENSE",   "CC-900", 310.45,  None,    "Power bill"),
    ("JE-0004", 2, "2026-08-14", "2000", "Accounts payable",    "LIABILITY", "CC-900", None,    310.45,  "Power bill"),
    ("JE-0005", 1, "2026-08-20", "1300", "Accounts receivable", "ASSET",     "CC-100", 2400.00, None,    "Invoice INV-0042"),
    ("JE-0005", 2, "2026-08-20", "4000", "Sales",               "REVENUE",   "CC-100", None,    2400.00, "Invoice INV-0042"),
    ("JE-0006", 1, "2026-08-28", "2000", "Accounts payable",    "LIABILITY", "CC-200", 3000.00, None,    "Supplier payment"),
    ("JE-0006", 2, "2026-08-28", "6300", "Bank fees",           "EXPENSE",   "CC-900", 15.00,   None,    "Supplier payment fee"),
    ("JE-0006", 3, "2026-08-28", "1100", "Cash at bank",        "ASSET",     "CC-200", None,    3015.00, "Supplier payment"),
]

COLS = "ABCDEFGHIJK"
HEADER_ROW = 3                              # row 1 title, row 2 blank
FIRST = HEADER_ROW + 1
LAST = FIRST + len(LINES) - 1               # 16
TOTALS_ROW = LAST + 1                       # 17 — directly under the lines, deliberately
RANGE = f"A{HEADER_ROW}:K{LAST}"            # what the pipeline reads: A3:K16

EPOCH = datetime.date(1899, 12, 30)         # Excel's 1900 date system, serial 1 = 1900-01-01
FIXED_ZIP_TIME = (2026, 8, 31, 0, 0, 0)


def serial(iso):
    return (datetime.date.fromisoformat(iso) - EPOCH).days


def s_cell(ref, text, style=0):
    st = f' s="{style}"' if style else ""
    return f'<c r="{ref}" t="inlineStr"{st}><is><t xml:space="preserve">{escape(text)}</t></is></c>'


def n_cell(ref, value, style=0):
    st = f' s="{style}"' if style else ""
    return f'<c r="{ref}"{st}><v>{value}</v></c>'


def f_cell(ref, formula, cached, style=0):
    st = f' s="{style}"' if style else ""
    return f'<c r="{ref}"{st}><f>{formula}</f><v>{cached}</v></c>'


def fmt(x):
    return f"{x:.2f}".rstrip("0").rstrip(".")


def sheet_xml():
    rows = [f'<row r="1">{s_cell("A1", TITLE)}</row>']
    rows.append(f'<row r="{HEADER_ROW}">'
                + "".join(s_cell(f"{COLS[i]}{HEADER_ROW}", h) for i, h in enumerate(HEADER)) + "</row>")
    for n, (entry, line, date, acct, name, cls, cc, dr, cr, memo) in enumerate(LINES):
        r = FIRST + n
        cells = [s_cell(f"A{r}", entry), n_cell(f"B{r}", line), n_cell(f"C{r}", serial(date), style=1),
                 s_cell(f"D{r}", acct), s_cell(f"E{r}", name), s_cell(f"F{r}", cls), s_cell(f"G{r}", cc)]
        if dr is not None:
            cells.append(n_cell(f"H{r}", fmt(dr), style=2))
        if cr is not None:
            cells.append(n_cell(f"I{r}", fmt(cr), style=2))
        currency = " usd " if (entry, line) == ("JE-0004", 1) else "USD"   # the one un-normalised key
        cells += [s_cell(f"J{r}", currency), s_cell(f"K{r}", memo)]
        rows.append(f'<row r="{r}">' + "".join(cells) + "</row>")
    total_dr = sum(l[7] or 0 for l in LINES)
    total_cr = sum(l[8] or 0 for l in LINES)
    assert round(total_dr, 2) == round(total_cr, 2) == 14925.45, (total_dr, total_cr)
    t = TOTALS_ROW
    rows.append(f'<row r="{t}">{s_cell(f"A{t}", "TOTALS")}'
                f'{f_cell(f"H{t}", f"SUM(H{FIRST}:H{LAST})", fmt(total_dr), style=2)}'
                f'{f_cell(f"I{t}", f"SUM(I{FIRST}:I{LAST})", fmt(total_cr), style=2)}</row>')
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
            '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
            f'<dimension ref="A1:K{TOTALS_ROW}"/><sheetData>' + "".join(rows) + "</sheetData></worksheet>")


CONTENT_TYPES = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    '<Default Extension="xml" ContentType="application/xml"/>'
    '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
    '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
    '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
    '</Types>')

ROOT_RELS = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
    '</Relationships>')

WORKBOOK = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
    '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
    'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
    f'<sheets><sheet name="{SHEET}" sheetId="1" r:id="rId1"/></sheets></workbook>')

WORKBOOK_RELS = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
    '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
    '</Relationships>')

# cellXfs: 0 = General, 1 = built-in date format 14 (m/d/yyyy), 2 = built-in 4 (#,##0.00)
STYLES = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
    '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
    '<fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>'
    '<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>'
    '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>'
    '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
    '<cellXfs count="3">'
    '<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>'
    '<xf numFmtId="14" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>'
    '<xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>'
    '</cellXfs></styleSheet>')


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    parts = [("[Content_Types].xml", CONTENT_TYPES), ("_rels/.rels", ROOT_RELS),
             ("xl/workbook.xml", WORKBOOK), ("xl/_rels/workbook.xml.rels", WORKBOOK_RELS),
             ("xl/styles.xml", STYLES), ("xl/worksheets/sheet1.xml", sheet_xml())]
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
        for name, body in parts:
            info = zipfile.ZipInfo(name, date_time=FIXED_ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, body.encode("utf-8"))
    print(f"wrote {os.path.relpath(OUT, HERE)}: {len(LINES)} lines, range {RANGE}, totals row {TOTALS_ROW}")


if __name__ == "__main__":
    main()
