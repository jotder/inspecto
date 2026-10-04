# Payment fraud runbooks

One runbook per typology in this Space Template, plus the card-number tripwire, the Risk Score and the labels.
Each Alert Rule `pf_<typology>` is per-entity: it raises one Alert and one Incident per offender (`by` = the
offender key), with the Measure taken as the `max` over the feature Dataset's rows. A person records the
Disposition; only a person closes an Incident.

**Thresholds are configuration** in two places: the Job's parameters (what counts, e.g. `small_amount`,
`window_hours`, `risky_amount`) and the Alert Rule's `threshold` (how much is too much). Every shipped rule
uses `gte`, so a value exactly AT the threshold fires.

## Card-number tripwire (all three Pipelines)

Each Pipeline sets `refusal: restricted_quarantine` with `refusal_scan: card_number`. Before anything lands,
every raw cell not listed in `refusal_scan_exempt` is scanned for a card-shaped, Luhn-valid number. A hit, or
a scan that cannot run (`SCAN_FAILED`), moves the WHOLE file to `<data root>/.restricted/<pipeline>/`, which
no route, Dataset, replay, backup or mapping expression reads; it is kept `refusal_retention_days` (30).

1. Do not open the restricted file to "check" it. Treat it as holding a real card number.
2. Ask the feed owner to fix the export (tokenise the column) and re-send. Re-sending the same file refuses again.
3. If a column legitimately holds long digit strings (order ids, IBANs, phone numbers), add only that column to
   `refusal_scan_exempt`. Every exempt column is a column the scan no longer protects; an exempt list covering
   every raw field is refused, and every change to it is audited.

The scan does NOT catch a number split across cells, glued to other digits, in an unlisted layout or brand,
or in an exempt column. It is a tripwire, not a guarantee.

## Card testing (`pf_card_testing`)

One `device_id` tried 8 or more distinct instruments at amounts up to `small_amount` (2.00) inside
`window_hours` (24). Typical cause: a fraudster validating stolen cards with tiny charges.

1. Open the Incident; list the device's instruments in `pf_device_small_amounts`.
2. Block the device at the gateway; flag every listed instrument for re-issue review.
3. Exclude known test harnesses (merchant QA devices) at the source, never by raising the threshold.

## BIN attack (`pf_bin_attack`)

15 or more distinct instruments of one `bin` declined inside 24 hours. Typical cause: enumeration of card
numbers under one issuer range.

1. Confirm the decline spread in `pf_bin_declines`; tell the issuer of that BIN.
2. Apply a temporary BIN-level velocity limit at the gateway; lift it once declines return to baseline.

## Velocity burst (`pf_velocity_burst`)

One `instrument_token` made 6 or more attempts inside a window shorter than `window_minutes` (60).

1. Check whether the attempts were declines being retried (scripted) or approvals (cash-out).
2. Step up authentication on the instrument; contact the cardholder if approvals went through.

## SIM-swap takeover (`pf_sim_swap_takeover`)

An approved payment of at least `risky_amount` (200) on a new device within `window_hours` (24) of a SIM
change on the same account. A single occurrence fires (threshold 1).

1. Treat as account takeover: freeze outgoing payments on the account.
2. Verify the SIM change with the subscriber through a channel other than the swapped number.
3. If the swap was fraudulent, reverse the payment and record the dealer or channel that performed the swap.

## Payment Risk Score (`payment_account`)

The `pf_risk_score` Job scores each account from four capped factors (SIM-swap payments, velocity, declines,
disputes); `highThreshold` is 60. The factor table is a DEFAULT: tune weights and caps to your book. No Alert
Rule ships over the Score's own output (a template cannot seed that store's Schema); add one after the first
run if you want the Score to raise Alerts.

## Labels and maturity (`pf_attempt_labels`)

Every attempt is labelled `DISPUTED`, `NEGATIVE` or `UNMATURED`. An undisputed attempt becomes a `NEGATIVE`
label only once it is older than `maturity_days` (default 120) measured against the newest attempt date in
the data. Train or measure a model only on `mature` rows; a shorter window turns late chargebacks into false
negatives.
