# Telecom fraud runbooks

One runbook per typology in this Space Template. Each Alert Rule `fraud_<typology>` is per-entity: it
raises one Alert and one Incident per offender (`by` = the offender key), with the Measure taken as the
`max` over every retained window. An offender seen on several days is one Alert. A key heals only when all its
evidence ages out of retention; that resolves its Alert but never its Incident. A person records the
Disposition. `stormCap` is 500 per rule: above that many retained offenders one storm Alert stands for them
all, so keep `stormCap` above `retention_days` x the expected daily offenders.

**Relapse.** The Measure is the `max` over retained windows, so a line that sits under the threshold every day
stays silent however many days it recurs (a `sum` would add the days up and false-alert). An offender who
offends again while still breached raises nothing new: the open Alert already covers it. Once the key has
healed (all its evidence aged out, the Alert resolved), a fresh offence raises a NEW Alert. Its Incident
dedupes against the offender's still-open Incident (D-P11: only a person closes an Incident), so the relapse
lands on the same case file instead of opening a second one.

**Running the windows.** Each detection is a `sql.template` Job (`config/jobs/fraud_<typology>_job.toon`)
with `window_start` / `window_end` parameters. Run a Job for another window by passing those parameters on
the run. A run keeps the rows of every other window within `retention_days` (default 30), so the next day's
run does not drop yesterday's offenders and their Alerts stay open. An offender's key heals only when its
window ages out of retention.

**Thresholds are configuration** in two places: the Job's parameters (what counts, e.g. `irsf_prefixes`,
`max_ring_seconds`) and the Alert Rule's `threshold` (how much is too much; `gt`, so a value exactly AT the
threshold stays silent). A parameter may not be empty: set an exemption list to `none` to exempt nothing.

**Exemption lists are a trust decision.** `exempt_msisdns` (SIM-box) and `exempt_doc_prefixes` (identity)
silence whatever they match. Fill them only from a verified register (the business-line register, the
corporate-account register), never from a value the monitored party controls. `exempt_doc_prefixes` matches a
PREFIX of `id_doc`, and `id_doc` is entered by the dealer, the party the dealer typology watches: a dealer who
types `CORP-FAKE` is exempted. The shipped default `CORP-` carries that risk, and the golden corpus pins it (a
`CORP-FAKE` document with 12 lines raises nothing). Replace it with a list of registered document prefixes, or
empty it (`none`), before relying on this typology. Matching the exemptions against a verified register
loaded as a Reference Dataset will be built when a customer supplies such a register; until then the
prefix-spoof risk stays pinned and documented here.

**Scaling.** A `sql.template` run reads and rewrites its whole sink: every retained window, about
`retention_days` x the daily candidate rows. Keep the candidates few: `fraud_simbox` keeps only lines with at
least `min_candidate_targets` voice targets (default 10). `fraud_identity`, `fraud_dealer`, `fraud_voucher` and
`fraud_reversal` still keep one row per document, dealer, serial or paying line, so their sinks grow with the
subscriber base.

**Sizing and retention.** Each run rewrites the whole retained sink, so size storage and run time by
subscriber count x retention: for the entity-keyed sinks (`fraud_identity`, `fraud_dealer`, `fraud_voucher`,
`fraud_reversal`) plan for about one row per active entity per retained window, that is up to
(active subscribers, documents, dealers, serials or paying lines) x `retention_days` rows, rewritten on every
run. The traffic sinks are bounded by daily candidates x `retention_days`. Shorten `retention_days` to cut
both the sink size and the per-run rewrite; keep it at least as long as an offender should stay alerted, since
a key heals when its evidence ages out.

**Number formats.** Subscriber numbers are E.164 digits with no `+`. Dialled numbers may be `+<E.164>`,
`00<E.164>`, national `0<NSN>` or bare E.164. The Jobs normalise all four (`home_cc` supplies the country
code of a national number) before matching. Prefix lists hold E.164 digit prefixes of any length; an entry
that is not 1-15 digits fails the run.

**Corpus.** `data/samples/` holds a synthetic corpus (fixed seed, fictional numbering: home country code
`999`, foreign callers `287` / `289`). It has planted offenders and look-alikes for every typology. Copy each
feed into `data/inbox/<feed>` to ingest it.

## IRSF (International Revenue Share Fraud) - `fraud_irsf`

- **Detects:** originated voice seconds to the ranges in `irsf_prefixes`, per caller, above `threshold` (default 3600 s).
- **Check:** is the line new or recently swapped? Are the calls long and back-to-back, at night, from one cell?
- **Act:** bar international calls on the line; ask the interconnect partner to withhold settlement for the ranges hit.
- **Silent by design:** heavy calls to unlisted ranges; national numbers that happen to start with a listed prefix.
- **Known false-positive source (no defence here):** a legitimate satellite-phone user calling a listed range heavily.

## Wangiri - `fraud_wangiri`

- **Detects:** subscribers rung for at most `max_ring_seconds` who then call the ringing number back within `callback_hours`, per ringing number, above `threshold` (default 3).
- **Check:** is the ringing number foreign? What did the callbacks cost?
- **Act:** block the ringing number at the gateway; warn the subscribers who called back.
- **Silent by design:** a flash-call OTP sender (rings, nobody calls back); a call centre (real conversations); calls to the number made before the ring.

## SIM-box - `fraud_simbox`

- **Detects:** distinct voice targets of a line that receives no calls, sends no SMS and uses at most `max_cells` cells, above `threshold` (default 50).
- **Check:** IMEI and cell history; the share of on-net targets; the activation dealer.
- **Act:** suspend the line; check other lines activated by the same dealer or identity document.
- **Silent by design:** a busy line that also receives calls or moves between cells; a registered PBX or outbound-dialler line listed in `exempt_msisdns`.
- **Known false-positive source (no defence here):** an unregistered PBX or dialler SIM looks exactly like a SIM-box. Register it in `exempt_msisdns`.

## Premium-rate - `fraud_premium_rate`

- **Detects:** charge to the ranges in `premium_prefixes` (E.164, so `999900` is national `0900`), per caller, above `threshold` (default 100).
- **Check:** is the premium-rate number owned by a known content partner? Is the traffic machine-regular?
- **Act:** bar premium-rate access on the line; hold the partner's revenue share.
- **Silent by design:** an occasional premium-rate user.

## Roaming high usage - `fraud_roaming`

- **Detects:** roaming charge per subscriber in the window, above `threshold` (default 500).
- **Check:** the visited network, and whether the usage fits the subscriber's history and credit limit.
- **Act:** apply the roaming spending cap; contact the subscriber.
- **Known false-positive source (no defence here):** a business traveller whose legitimate spend passes the threshold. The pack has no subscriber tier to tell them apart.

## SIM-swap - `fraud_sim_swap`

- **Detects:** payments out of a line within `window_hours` after its latest SIM swap, each payment counted once, above `threshold` (default 200).
- **Check:** how the swap was authorised (channel, agent, identity check), and whether the subscriber confirms it.
- **Act:** freeze outgoing payments on the line; reverse the swap if the subscriber did not ask for it.
- **Silent by design:** a swap with no payment, a payment with no swap, a payment outside the window or before the swap, several swaps before one small payment.
- **Known false-positive source (no defence here):** a genuine lost-phone swap followed by a large genuine payment.

## Subscription / identity - `fraud_identity`

- **Detects:** lines activated on one identity document in the window, above `threshold` (default 5).
- **Check:** the document's validity; whether the lines share a dealer, device or address.
- **Act:** suspend the extra lines pending identity checks; report the document.
- **Silent by design:** a family with a few lines on one document; a corporate account whose document starts with one of `exempt_doc_prefixes` (default `CORP-`).

## Dealer activations - `fraud_dealer`

- **Detects:** activations by one dealer with no originated traffic up to `traffic_grace_hours` (default 48) after the window, above `threshold` (default 20).
- **Check:** the dealer's commission claims against those lines; the identity documents used.
- **Act:** hold the dealer's commission; audit the dealer.
- **Silent by design:** a busy dealer whose lines are used; late-day activations whose lines are first used the next morning.
- **Known false-positive source (no defence here):** a dealer selling prepaid stock that buyers activate but use later than the grace period.

## Voucher / EVD - `fraud_voucher`

- **Detects:** redemptions of one voucher serial, above `threshold` (default 1: a serial redeems once).
- **Check:** the voucher platform's log for the serial; the channel that sold it.
- **Act:** block the serial and the batch it came from; claw back the credit.
- **Silent by design:** a heavy top-up user redeeming many different vouchers.

## Payment reversal - `fraud_reversal`

- **Detects:** reversals per subscriber in the window, above `threshold` (default 3).
- **Check:** what was bought with each payment before it was reversed; the payment instrument.
- **Act:** block the instrument; limit payments on the line.
- **Silent by design:** a subscriber with a few reversals, or many payments with none reversed.

## Recharge fraud - `fraud_recharge`

- **Detects:** card-testing / top-up velocity: PAYMENT top-ups under `small_amount` (default 5) per line in the window, above `threshold` (default 8). Only lines with at least `min_candidate_topups` (default 3) small top-ups are kept.
- **Check:** the payment instrument behind each small top-up; whether the line was then drained (SIM-swap, premium-rate or IRSF Alerts on the same line).
- **Act:** block the instrument; hold further top-ups on the line until the owner is verified.
- **Silent by design:** a line with a few small top-ups, a heavy bill-payer whose top-ups are all above `small_amount`, and small VOUCHER redemptions (a different channel, covered by `fraud_voucher`).

## Data-charging bypass - `fraud_data_bypass`

- **Detects:** seconds of originated DATA sessions rated at zero (`charge = 0`) per line in the window, above `threshold` (default 7200), outside the APNs in `zero_rated_apns`. In the CDR feed a DATA record carries the session seconds in `duration_s` and the APN in `b_number`. Only lines with at least `min_candidate_seconds` (default 600) unrated seconds are kept.
- **Check:** the APN and the rating plan of the line; whether the session was tethered or tunnelled through a zero-rated service.
- **Act:** fix the rating or charging-policy gap; bill back where the contract allows; suspend the line.
- **Silent by design:** properly charged data however long, and sessions on a zero-rated APN. **Exemption lists are a trust decision:** fill `zero_rated_apns` (comma-separated; `none` exempts nothing) from the operator's own product catalogue, never from a value a subscriber controls.

## Internal fraud - `fraud_internal`

- **Detects:** SIM swaps performed per staff user in the window (`dealer_id` of a SIM_SWAP event is the staff user), above `threshold` (default 5), outside the registered swap desks in `exempt_staff`.
- **Check:** the swapped lines against the owners' requests and the customer-care tickets; whether the staff user's own lines or relatives' lines are among them; the payments out of the swapped lines (`fraud_sim_swap`).
- **Act:** suspend the staff user's swap right pending review; refer to HR and internal audit.
- **Silent by design:** a staff user with a few swaps, and the exempt desks. `exempt_staff` (default `HELPDESK`) silences whatever it names: fill it only from the operator's verified register of back-office desks.
