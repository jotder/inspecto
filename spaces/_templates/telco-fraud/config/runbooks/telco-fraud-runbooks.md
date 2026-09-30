# Telecom fraud runbooks

One runbook per typology in this Space Template. Each Alert Rule `fraud_<typology>` is per-entity: it
raises one Alert and one Incident per offender (the `by` key), and a healed key resolves its Alert but
never its Incident. A person records the Disposition.

**Running the windows.** Each detection is a `sql.template` Job (`config/jobs/fraud_<typology>_job.toon`)
with `window_start` / `window_end` parameters. Run a Job for another window by passing those parameters on
the run. Detection thresholds sit in two places, both configuration: the Job's parameters (what counts, e.g.
`irsf_prefixes`, `max_ring_seconds`) and the Alert Rule's `threshold` (how much is too much).

**Corpus.** `data/samples/` holds a synthetic corpus (fixed seed). It has planted offenders and planted
look-alikes for every typology. Copy each feed into `data/inbox/<feed>` to ingest it.

## IRSF (International Revenue Share Fraud) - `fraud_irsf`

- **Detects:** originated voice seconds to the high-risk ranges in `irsf_prefixes`, per caller, above `threshold` (default 3600 s).
- **Check:** is the line new or recently swapped? Are the calls long and back-to-back, at night, from one cell?
- **Act:** bar international calls on the line; ask the interconnect partner to withhold settlement for the ranges hit.
- **Look-alike:** heavy callers to ordinary international ranges, or a few calls to a listed range.

## Wangiri - `fraud_wangiri`

- **Detects:** distinct subscribers hit by terminated calls of at most `max_ring_seconds` from one calling number, above `threshold` (default 20).
- **Check:** is the calling number foreign? Do the called subscribers call it back?
- **Act:** block the calling number at the gateway; warn the subscribers that were called.
- **Look-alike:** a foreign call centre with real conversations.

## SIM-box - `fraud_simbox`

- **Detects:** distinct voice targets of a line that receives no calls, sends no SMS and uses at most `max_cells` cells, above `threshold` (default 50).
- **Check:** IMEI and cell history; the share of on-net targets; the activation dealer.
- **Act:** suspend the line; check other lines activated by the same dealer or identity document.
- **Look-alike:** a busy line that also receives calls, or one that moves between cells.

## Premium-rate - `fraud_premium_rate`

- **Detects:** charge to the premium-rate ranges in `premium_prefixes`, per caller, above `threshold` (default 100).
- **Check:** is the premium-rate number owned by a known content partner? Is the traffic machine-regular?
- **Act:** bar premium-rate access on the line; hold the partner's revenue share.
- **Look-alike:** an occasional premium-rate user.

## Roaming high usage - `fraud_roaming`

- **Detects:** roaming charge per subscriber in the window, above `threshold` (default 500).
- **Check:** the visited network, and whether the usage fits the subscriber's history and credit limit.
- **Act:** apply the roaming spending cap; contact the subscriber.
- **Look-alike:** an ordinary traveller.

## SIM-swap - `fraud_sim_swap`

- **Detects:** payments out of a line within `window_hours` after its SIM swap, above `threshold` (default 200).
- **Check:** how the swap was authorised (channel, agent, identity check), and whether the subscriber confirms it.
- **Act:** freeze outgoing payments on the line; reverse the swap if the subscriber did not ask for it.
- **Look-alike:** a swap with no payment, a payment with no swap, or a payment outside the window.

## Subscription / identity - `fraud_identity`

- **Detects:** lines activated on one identity document in the window, above `threshold` (default 5).
- **Check:** the document's validity; whether the lines share a dealer, device or address.
- **Act:** suspend the extra lines pending identity checks; report the document.
- **Look-alike:** a family with a few lines on one document.

## Dealer activations - `fraud_dealer`

- **Detects:** activations by one dealer that make no originated traffic in the window, above `threshold` (default 20).
- **Check:** the dealer's commission claims against those lines; the identity documents used.
- **Act:** hold the dealer's commission; audit the dealer.
- **Look-alike:** a busy dealer whose lines are used, or a small dealer with a few idle lines.

## Voucher / EVD - `fraud_voucher`

- **Detects:** redemptions of one voucher serial, above `threshold` (default 1: a serial redeems once).
- **Check:** the voucher platform's log for the serial; the channel that sold it.
- **Act:** block the serial and the batch it came from; claw back the credit.
- **Look-alike:** a heavy top-up user redeeming many different vouchers.

## Payment reversal - `fraud_reversal`

- **Detects:** reversals per subscriber in the window, above `threshold` (default 3).
- **Check:** what was bought with each payment before it was reversed; the payment instrument.
- **Act:** block the instrument; limit payments on the line.
- **Look-alike:** a subscriber with one or two reversals, or many payments with none reversed.
