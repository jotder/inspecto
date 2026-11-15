# Full-cycle import fixture (synthetic)

Written by `node tools/gen-la-import-fixture.mjs` from a fixed seed; every value is invented. Do not edit by hand:
change the generator and run it again (`--check` fails when these files differ from what it writes).

- `transfers.csv`: 461 events between accounts. A mule chain runs through `MULE-HUB-01` (six sources, twelve layer
  accounts, four cash-out accounts) and `MERCH-001` is one busy merchant.
- `accounts.csv`: 138 accounts. 137 of them are named by a transfer; `ACC-1999` is not.

## The four dirty rows of `transfers.csv`

| transfer_id | File line | What is wrong |
|---|---|---|
| T0042 | 43 | from_account is blank: the row is kept but draws no link |
| T0119 | 120 | booked_at is 'not-a-date': the value is left blank |
| T0206 | 207 | amount is 'abc': the value is left blank |
| T0312 | 313 | from_account is ' acc-1003 ' (stray spaces, lower case): read as the account ACC-1003 |

Used by `ControlApiOnboardFullCycleTest` (LA-FULL-CYCLE-TEST-1 phase 3).
