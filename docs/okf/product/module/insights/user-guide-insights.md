---
type: Feature
title: "Business Insights — screens"
description: "How to author an Insight Profile in Studio, preview it on history and read and judge its Insights."
tags: [insights, studio, user-guide]
timestamp: 2026-11-26T00:00:00Z
item: module.insights
audience: internal
status: current
verified: 2026-11-26 code-reviewed
code: features/inspecto-insights
---

# Business Insights — screens

Business Insights is an add-on, in every edition (Basic included). Without it the screens below say it is not installed. How it
works underneath: [`architecture-insights.md`](architecture-insights.md).

## Explain this change

On a Widget over a date, the light-bulb tile action **Explain this change** names the dimension values that carry the
change between one period and the one before it. See [`architecture-insights.md`](architecture-insights.md) §4.

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
