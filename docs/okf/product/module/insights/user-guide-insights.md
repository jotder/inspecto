---
type: Feature
title: "Business Insights — screens"
description: "How to author an Insight Profile in Studio, preview it on history and read and judge its Insights."
tags: [insights, studio, user-guide]
timestamp: 2026-11-26T00:00:00Z
item: module.insights
audience: internal
status: current
verified: 2026-11-27 code-reviewed
code: features/inspecto-insights
---

# Business Insights — screens

Business Insights is an add-on, in every edition (Basic included). Without it the screens below say it is not installed. How it
works underneath: [`architecture-insights.md`](architecture-insights.md).

## Explain this change

On a Widget over a date, the light-bulb tile action **Explain this change** names the dimension values that carry the
change between one period and the one before it. See [`architecture-insights.md`](architecture-insights.md) §4.

For a sum or a count, **Look at pairs of dimensions** in the dialog also shows which pairs of values (`product × partner`)
carry the change. Pairs are not computed on the spot: they are what the `insight.scan` Job of an Insight Profile with
**Pairs of dimensions** on stored for that period, over the same Dataset, Measure, date column, grain and filters. The
dialog names that Profile and when it ran, warns when the data changed since the scan (run the scan again), lists the
pairs that were not explained with their reason (too many value pairs: raise **Value pairs per pair** or pair coarser
columns), and otherwise says how to get pairs. A ratio has no pairs: its mix and rate are shown instead.

## Insight Profiles

**Studio ▸ Insight Profiles** lists the Space's Insight Profiles. An Insight Profile says what the `insight.scan` Job
looks at and how sure it must be before it reports an Insight.

To create one (needs the Workbench author permission), choose **New Insight Profile** and answer:

| Field | What it means |
|---|---|
| Id | Names the Profile and its Insights Dataset `insights_<id>`. |
| Dataset, Measure, Measure column | What is measured: the sum of a column or the count of rows. Only an additive Measure is allowed. |
| Date column, Grain | The period an Insight is about: day, week or month. |
| Dimensions | Up to 12 columns to slice by. **Propose dimensions from column roles** fills them from the Dataset's dimension columns. The whole Measure is always scanned. |
| Insight Types | `anomaly` (one period far from usual), `trend-break` (the level changed and stayed), `drift` (a steady rise or fall), or another type the server lists. The server refuses one it does not run, in words. |
| Sensitivity | Low, Normal or High — how many false alarms are tolerated. |
| Advanced | Minimum impact and support, values per dimension, lookback, season, novelty runs, Insights per run. Blank keeps the server default. |
| Pairs of dimensions | Advanced, off by default. Each scan also stores which pairs of dimensions explain the last change, for *Explain this change*. Needs at least 2 dimensions. **Value pairs per pair** (10–1000, default 200) caps each pair; a pair with more is refused in words. |
| Business calendar | Holidays and promotions: **Exclude** leaves the period out of every series; **Expected** drops a finding dated in it. |

**Preview on history** replays the Profile as it is in the form over the last 4–52 periods (12 by default), as you:
a bar per period with the number of Insights, the total ("you would have seen N Insights in the last P periods") and
the top Insights with their sentences. Nothing is saved until **Save**. A refused save shows the server's reason.

A scan always counts back from today's last complete period, so a Dataset whose data stopped weeks ago gives no
Insights. The **Business assurance** Space Template ships a worked example: the Profile `ba_margin_revenue` (daily
revenue of `ba_margin_lines` by product, channel and partner, whose columns carry their roles) and the disabled Job
`ba_insight_scan`. Its synthetic margin lines end yesterday, whatever the day, with a planted drop (product P2 at a fifth
of its volume over the last four days), so **Preview on history** and **Run scan now** find Insights at once. The other pilot, `telco-ra`, has no such corpus yet (`INSIGHT-PILOT-TELCO-RA-1`).

## Reading and judging Insights

Select a Profile to see the Insights of its latest run, best score first: the band (High, Elevated, Normal), the
slice ("region = North" or the whole Measure), the Insight Type, the period, the score and the sentence.

**Useful**, **Not useful** and **Expected** record your word on that Insight's type and slice; the next run applies
it — Not useful and Expected stop it being reported again. Anyone who can see the feed may use them.

**Open a Case** opens a Case holding the Insight's evidence — its sentence, type, slice, period, value, expected
value and change, score and band, and a link back to this feed. Opening a second Case from the same Insight reuses
the Incident the first one made for it. **Request action…** asks which https Connection to send to and why, opens
that Case, and raises an Action Request from it carrying the same evidence; a second person approves it before
anything is sent. When it is done the Insight shows the new Case (and Action Request) as links. The buttons appear
only where Case Management (and Action Requests) are installed, and are disabled with the reason without the Manage
Incidents and Cases permission (and Incident work, for a request).

No Insights yet? Add an `insight.scan` Job on the **Jobs** screen with the parameter `profile` set to the Profile's
id, and run it.

## Dashboard Brief

A **Dashboard Brief** is the top Insights of one Insight Profile's latest scan in plain sentences: elevated or high band, best score
first, at most five, each with a link to the Insight. Add an **Insight Brief** Widget to a Dashboard and pick the Profile (it is offered
where Business Insights is installed). It works in **every edition**, Basic included: the sentences are the ones stored with each
Insight. When nothing is unusual it says "Nothing unusual in *period*"; when the last scan is old it says "Last scan was *n* days ago";
when no scan has run it tells you to run the `insight.scan` Job.

**Narrate** (Standard and up, where AI Assist is installed) rewrites the Brief as a short paragraph above the lines. Each sentence names
the Insight it is about, and the figures in it are filled in by the server from that Insight — the model never writes a number. A sentence
that fails a check is replaced by the stored one and marked *Template*. The result is shown, not saved. Without AI Assist the button is
absent.

**Privacy.** A Brief never shows a classified value: Insights are stored with the Space alias. Values that are **not** classified (region,
product…) **are sent to the configured model provider** when you press Narrate.

**In a Report PDF.** Put the Brief Widget's id in a Report Job's `widgets:`; its page is drawn at that position (first in the list = first
page). The Job reads the Insights as its author, so an author who may not view the Profile fails the run with a message. The footer reads
"Templated from the stored Insights" or "Model-written, checked against the Insights". A public share has no Brief.

## Ask the Dashboard

On the Dashboard editor, type a question about a Dataset you may view. It only **drafts**: you get a draft Widget and a one-line caption,
previewed live with your own rights; nothing is saved by itself. **Save** goes through the same gated route as any Widget, so a Pending
Change policy still asks for approval. A question it cannot express (an unknown column or value, a masked column, a Measure it does not
allow) is refused in words. A filter or window in your question is not part of the draft Widget, and the box says so. The box appears only
where the intelligence feature is reported, which no build does yet (architecture page § 7.5).
