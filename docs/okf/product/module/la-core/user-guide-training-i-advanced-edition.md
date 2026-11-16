---
type: Guide
title: "Inspecto LA training videos — I · Advanced edition: narration scripts"
description: "The spoken script of the training episodes in group I (Advanced edition), scene by scene, with the action on screen and the change it causes. Format and status: the overview, user-guide-training-scripts.md."
resource: tools/render-la-demo-video.mjs
tags: [module, la-core, link-analysis, user-guide, training, video]
timestamp: 2026-11-16T00:00:00Z
---

# Inspecto LA training videos — I · Advanced edition

> Narration scripts for group I of the Inspecto LA training series. How to read an episode, the voice and the
> renderer: [the overview](user-guide-training-scripts.md). Draft, not recorded, not reviewed by product; `[check in UI: …]` marks a detail to
> confirm on the live product before recording.

## Episodes in I

| Episode | Title | Edition | Scenes | Spoken words | UI checks open |
|---|---|---|---|---|---|
| [I1](#i1_access-policies) | Access Policies: narrowing what roles allow | Advanced | 9 | 661 | 6 |
| [I2](#i2_investigations-on-postgresql) | Investigations shared by several servers (PostgreSQL) | Advanced | 9 | 646 | 2 |
| [I3](#i3_scale-server-runs-link-index-daily-ingestion) | Scale: graph runs on the server, the link index, daily ingestion | ? | 11 | 826 | 7 |

## I1_Access-Policies

> **I1 · Access Policies: narrowing what roles allow** · Advanced · Banking and finance Space · the sanctioned owner ·
> 5 min · user guide §14; `config/examples/access-policy-deny-persona.md`; Advanced hubs `module/policy`, EDITIONS

- **Objectives:** say what an Access Policy adds to roles and why it can only narrow them; write a deny policy on a
  resource kind with a condition; preview its impact and know what the preview cannot show; ask *Why denied?*; see the
  refusal as the denied person sees it, and take the policy away.
- **Before:** an **Advanced** build with the policy module and a demo sign-in (the Advanced edition signs in through an
  identity provider by default; the five Demo Users need the Demo Auth sign-in, or provider test users named *admin* and
  *reviewer*), the Banking and finance Space from `la-standard.zip` seeded as for F4, signed in as Demo Admin (Access
  configuration). The policy editor is the Inspecto console's Settings ▸ Access ▸ Policies; the Inspecto LA app's own
  Settings ▸ Access shows the roles only.
- **Covers (plan §5):** Settings ▸ Access (roles, in Inspecto LA); the console's Access ▸ Policies: Effective policies
  (Name, Effect, Target, Condition, the built-in rows), New policy (Policy name, Effect Deny / Allow, Actions, Resource kinds,
  Condition (when)), Impact ▸ Preview impact, *Check this policy*, *Why denied?* (Route, Method, Resource kind); the
  refusal as the reviewer sees it.
- **Try it:** after the episode, sign in as Demo Analyst and open *Sanctions exposure two ownership hops down*. Does the
  policy stop you? Why or why not? *Answer: no. Its condition names only the person `reviewer`; the analyst does not
  match, so the roles and the membership decide as before.*
- **Check:** can an Access Policy give a role a permission it does not have? *Answer: no. The engine can only narrow; a
  deny removes access, nothing adds what the role does not grant.*
- **Gotchas:** the sample is illustrative and inert outside Advanced; the repository's own test denies by Dataset, not by
  person (`access-policy-deny-persona.md`), so confirm the deny before recording. Preview impact evaluates roles by their
  capabilities and name only: a condition on a person shows no change there, by design. The denied person gets the same
  "not available" answer as an outsider. Delete the policy at the end: it applies to the whole Space.

### S01 · Roles say what; policies say when not
- Voice: This is Inspecto LA training, episode I1: Access Policies. Roles say what each kind of user may do: read, investigate, approve, reveal. Some rules do not fit a role. This reviewer must not see these Investigations. Nobody outside the head office may open that Dataset. An Access Policy is a condition on who, what and where, and it can only narrow what the roles already allow. Nothing a policy says can grant more. We are in the Advanced edition, in the Banking and finance Space, signed in as the administrator.
- Action: intro card (edition chip *Advanced*).
- Impact: none.

### S02 · Before the policy
- Voice: First, the state today. The reviewer is a member of the Investigation Sanctions exposure two ownership hops down, so the reviewer can open it and read it, as episode F5 showed. Suppose the compliance team decides that this reviewer must no longer read any Investigation in this Space. The role is shared with other reviewers, so changing the role is the wrong tool. A policy is the right one.
- Chip: Members · reviewer
- Action: (as Demo Reviewer, recorded first) File ▸ Open… ▸ Investigations ▸ the list shows *Sanctions exposure two ownership hops down* ▸ open it ▸ sign out.
- Impact: mark the Investigation in the reviewer's list.

### S03 · Where policies live
- Voice: In Inspecto LA, Settings, Access shows the roles: what each one may do. Policies are written in the Inspecto console, under Settings, Access, on the Policies tab. Effective policies lists every policy in force: its name, whether it denies or allows, what it targets, its condition, and where it comes from. Some rows are built in: the engine keeps every Space's data apart, and those denies are shown, never hidden.
- Chip: Access · Policies · Effective policies
- Action: Inspecto LA ▸ Settings ▸ Access (roles) ▸ the console ▸ Settings ▸ Access ▸ Policies tab ▸ hold on Effective policies. [check in UI: the console's address on the Advanced build, and the built-in rows]
- Impact: mark the table's columns and a built-in row.

### S04 · Write the policy
- Voice: Press New policy. Give it a name. Effect: Deny. Actions: leave them all unticked, which means every action. Resource kinds: tick investigation, so it targets Investigations and nothing else. And the condition: when the subject's id is reviewer. The hint under the box lists what a condition can read: who the person is, their roles and permissions, the action, the route, the Space, and the resource itself.
- Chip: New policy · Effect · Resource kinds · Condition (when)
- Action: New policy ▸ Policy name (the sample's name, `deny-ra-analyst-investigations`, or a clearer one) ▸ Effect: Deny ▸ Actions: none ▸ Resource kinds: `investigation` ▸ Condition (when) `subject.id == 'reviewer'`. [check in UI: the resource kind is listed as `investigation`]
- Impact: mark each field as the voice names it.

### S05 · Preview, and what it cannot show
- Voice: Before saving, press Preview impact. The table shows, role by role, which decisions would change. Here it says none change, and it tells you why: each role is evaluated by its permissions and name only, so a condition on one person or one claim shows no change here. That is honest, not wrong. A policy on a person is tested on that person, which we will do in a moment. Save the policy. If the server has a warning about it, it shows under Check this policy.
- Chip: Impact · Preview impact
- Action: Preview impact ▸ hold on the sentence ▸ Save. [check in UI: *0 of N decisions change. Each role is evaluated by its capabilities and name only — a condition on subject.id or a claim shows no change here.*]
- Impact: mark the sentence; then the new row in Effective policies.

### S06 · Why denied?
- Voice: Under the table, Why denied? evaluates an action against the policies, for your own session, without performing it. Enter the route of the Investigation, method GET, resource kind investigation, and evaluate. For the administrator, no policy matched: the standard roles and sharing decide. The tool is for the person who was refused: they run it on their own session, and see which policy said no.
- Chip: Why denied?
- Action: Why denied? ▸ Route `/inv/investigations/sanctions-exposure` ▸ Method GET ▸ Resource kind `investigation` ▸ evaluate. [check in UI: the evaluate button's label and the answer *No policy matched — the standard capability and sharing gates decide.*]
- Impact: mark the answer.

### S07 · The refusal, as the reviewer sees it
- Voice: Now sign in as the reviewer. In File, Open, the Investigations are gone. Open the link to Sanctions exposure directly. This Investigation is not available: it does not exist, is not yours, or its Dataset is no longer shared with you. A policy answers exactly like absence, so a denied person learns nothing about what they may not see. In Why denied, the reviewer would see the policy by name.
- Chip: not available
- Action: sign out ▸ Demo Reviewer ▸ File ▸ Open… ▸ Investigations (empty) ▸ address bar `/link-analysis?investigation=sanctions-exposure` ▸ hold. [check in UI: the deny holds for this persona-based condition; the sample notes it was tested by Dataset only]
- Impact: before/after hold: the reviewer's Investigation list, then empty; mark the sentence.

### S08 · Narrow only, and Advanced only
- Voice: Two rules to remember. A policy can only narrow: an allow policy never grants what the role does not, it only marks where a deny does not apply. And policies are enforced only on the Advanced edition. On Standard you can read the page, and the preview says the engine is not active. To take the reviewer's access back, sign in as the administrator and delete the policy. The next request is decided by the roles alone.
- Chip: Deny · Allow · Delete
- Action: sign out ▸ Demo Admin ▸ the console ▸ Access ▸ Policies ▸ delete the policy ▸ confirm. [check in UI: the delete control's label in Effective policies]
- Impact: mark the row leaving the table.

### S09 · Try it
- Voice: So: roles say what, policies say when not; a policy can only narrow, a person-based one is tested on that person, and a denied person sees nothing at all. Your turn. Write the same policy again, sign in as the analyst, and open Sanctions exposure. Does the policy stop you, and why? The answer is in the description. Next, in I2, Investigations shared by several servers.
- Action: outro card: *Try it* and *Next: I2 · Investigations on PostgreSQL*.
- Impact: none.

## I2_Investigations-on-PostgreSQL

> **I2 · Investigations shared by several servers (PostgreSQL)** · Advanced · no Space (an administrator's explainer) · 5
> min · Advanced hubs `module/la-store-pg` (admin guide, architecture), EDITIONS; user guide §15

- **Objectives:** say what changes, and what does not, when Investigations live in PostgreSQL; switch a server to it and
  check that it took; know the three things to settle first (no migration, no delete yet, back-ups); read the store's
  refusal when the database is down.
- **Before:** an **Advanced non-demo build** with the `la-store-pg` module, a PostgreSQL database and a user that may create
  a schema, and two servers started with the same settings (plan §6.4; the Preview demo build cannot record this). On
  2026-10-23 the operator decided that the next release ships without PostgreSQL (`LA-INVESTIGATION-STORE-DESIGN-1` is
  postponed): record this episode only once that hold is lifted. Until then S06–S09 are title cards and terminal shots.
- **Covers (plan §5):** Settings ▸ Link Analysis ▸ *Investigation store* (filesystem / PostgreSQL); the start-up properties
  (`-Dinvestigations.backend=db`, `-Dinvestigations.db.url`, `-Dinvestigations.db.user`, `-Dinvestigations.db.password` with
  `${ENV:…}`); `/health/details` ▸ `live.investigations`; the 503 *Investigation store unavailable* refusal; size limits
  `max_set_bytes`, `max_investigation_bytes` (413).
- **Try it:** on a test server, switch an existing Space to PostgreSQL and open File ▸ Open… ▸ Investigations. What do
  you find, and why? *Answer: no Investigations. There is no import from the filesystem store; a Space uses one backend,
  so switching starts it with none.*
- **Check:** what does an analyst see differently on PostgreSQL? *Answer: nothing: the same steps, Drafts, four-eyes,
  masking and Dossier. Only where the Investigations are kept changes, and the administrator sees it on Settings.*
- **Gotchas:** deleting an Investigation is refused on PostgreSQL for now (`LA-INVESTIGATION-DELETE-PG-1`): sealed rows are
  append-only by trigger. A wrong connection setting needs a restart; a database outage recovers on its own. Keep the
  password out of command lines.

### S01 · One store, several servers
- Voice: This is Inspecto LA training, episode I2: Investigations shared by several servers. This one is for administrators, in the Advanced edition, and it is about the server, not about one Space. By default, a Space keeps its Investigations as files, on the one server that runs it. When a team grows, you may want several servers to serve the same Space, or the Investigations to live in a managed database. The Advanced edition can keep them in PostgreSQL. This episode explains what changes, how to switch it on, and what to settle before you do.
- Action: intro card (edition chip *Advanced*).
- Impact: none.

### S02 · Where they live today
- Voice: In Settings, This Space, Link Analysis, the last line says where this server keeps Investigations: filesystem, chosen when the server starts; not a setting. There is no switch on this page on purpose. The store is decided by whoever starts the server, because every server serving the Space must agree.
- Chip: Investigation store
- Action: Settings ▸ Link Analysis ▸ scroll to *Investigation store: filesystem (chosen when the server starts; not a setting)*.
- Impact: mark the line.

### S03 · What changes, and what does not
- Voice: On PostgreSQL, the step log, the sealed Working Sets, the members, the Drafts and the approvals live in the database, one schema per Space. Several servers can write at once safely: each step is taken in one short transaction, and a sealed row cannot be changed afterwards, the database itself refuses it. For the analyst, nothing changes. The same steps, the same Drafts and four-eyes, the same masking, the same Dossier, verified the same way.
- Chip: one schema per Space
- Action: title card: a diagram, two servers → one PostgreSQL database, schema `space_<space id>`.
- Impact: mark the two servers and the one schema.

### S04 · Switching it on
- Voice: Use an Advanced bundle: the store's module ships there. Give it a PostgreSQL database, and a user that may create a schema and tables. Then start the server with four settings: the Investigations backend set to db, the database address, the user, and the password, taken from an environment variable, never typed on the command line. Restart. On first use, the store creates the Space's schema itself. There is no separate migration step.
- Chip: start-up settings
- Action: terminal shot of the start command with `-Dinvestigations.backend=db`, `-Dinvestigations.db.url=jdbc:postgresql://…`, `-Dinvestigations.db.user=…`, `-Dinvestigations.db.password=${ENV:INSPECTO_INV_DB_PASSWORD}` (values blurred).
- Impact: mark the backend setting and the environment reference.

### S05 · Check that it took
- Voice: Open Settings, Link Analysis again. The line now says PostgreSQL. The server's health details list the Investigation store for each Space as up or down, with the cause. Now the proof: open the same Investigation on two servers, take a step on one, and refresh the other. The step is there, with the same number.
- Chip: Investigation store · PostgreSQL
- Action: Settings ▸ Link Analysis ▸ *Investigation store: PostgreSQL* ▸ `/health/details` ▸ `live.investigations` UP ▸ two browser windows, one per server ▸ a Keep on one ▸ Step log refreshed on the other. [check in UI: the step appears on the second server after Refresh]
- Impact: mark the store line; then the new step on the second server.

### S06 · Settle three things first
- Voice: Three things before you switch a Space that is already in use. There is no migration: existing Investigations do not move, so the Space starts on PostgreSQL with none. Deleting an Investigation is refused on PostgreSQL for now, because sealed rows are append-only. And back-ups move with the data: back up the Space's schema in the database, not the Space folder. The size limits stay the same on both stores.
- Chip: no migration · no delete · back-ups
- Action: title card with the three points and the back-up command `pg_dump --schema=space_<id>`.
- Impact: mark each point as the voice names it.

### S07 · Keep it safe
- Voice: The database now holds the evidence, so treat it that way. Keep the password in an environment variable: the server never writes it to a log, only the address before any options. When the database is on another machine, connect over an encrypted, verified connection. Sealed rows are protected by the database itself: an update or a delete on them is refused. But whoever owns the schema could still drop it whole, so give that role to as few people as you can.
- Chip: password · encryption · schema owner
- Action: title card with the three safeguards: `${ENV:NAME}` for the password, `sslmode=verify-full` on the address, a restricted schema owner.
- Impact: mark each safeguard as the voice names it.

### S08 · When the database is away
- Voice: If the database cannot be reached, the server still starts. Every Investigation request of that Space answers that the Investigation store is unavailable, with the reason, and the health details show it down. When the database comes back, it recovers on its own, with no restart. A wrong setting is different: the connection comes from the start-up settings, so fixing it needs a restart.
- Chip: Investigation store unavailable
- Action: stop the database ▸ File ▸ Open… ▸ the refusal ▸ `/health/details` DOWN ▸ start the database ▸ Refresh. [check in UI: how the 503 *Investigation store unavailable: …* reads in Inspecto LA (expected *Investigations are not available: …* from the Investigation tab)]
- Impact: mark the refusal; then the recovery.

### S09 · Try it
- Voice: So: PostgreSQL changes where Investigations live, not how anyone works with them. It is chosen at start-up, checked on the Settings page and in the health details, kept safe like any store of evidence, and needs three decisions first. Your turn, on a test server. Switch an existing Space to PostgreSQL, and open the list of Investigations. What do you find, and why? The answer is in the description. Next, in I3, scale: server runs, the link index, and daily ingestion.
- Action: outro card: *Try it* and *Next: I3 · Scale*.
- Impact: none.

## I3_Scale-server-runs-link-index-daily-ingestion

> **I3 · Scale: graph runs on the server, the link index, daily ingestion** · Standard and Advanced (recorded on Standard) ·
> Other Examples Space · the mule transfers feed · 6 min · user guide §11.11, §15; EDITIONS (*Scale*);
> `solution-pack/la-daily-feed` hub

- **Objectives:** say when a question must run on the server instead of the screen; read and build the link index, and
  choose Full, Append or Compact; follow a new day of data from the inbox to the graph; read the data strip when a day is
  missing or the index is out of date.
- **Before:** a fresh unzip of `la-standard.zip`, the Other Examples Space, signed in as Demo Analyst (it may run server
  analysis and build the link index here). The Space's `mule_transfers` feed holds 1 to 3 September 2026; its staged
  folder holds 4 September and 6 September (5 is missing on purpose), and the Job `la_index_build` appends the index after
  every committed delivery. The seeded Investigations *Mule hub: six feeder accounts and a relay out* and *Delivery gap
  check* (`tools/seed-la-showcase.mjs`). **Prerequisite:** the packaged index Job must carry its owner (plan §6.4), or every
  build is refused.
- **Covers (plan §5):** 5.3 the Whole Dataset tab, Run on server, the Results dock; Link index (*Link index serving is on*,
  the Link index / Dataset only chips, version and appended deltas, Suggested, Build mode Full / Append / Compact, Build
  link index, Cancel build, *Link index out of date*); the daily chain (Collector, Pipeline, daily Dataset, index Job); the
  data strip, Data details (Files), Coverage, Rebuild; the claim "search and filter stay fast on millions of records".
- **Try it:** copy the 6 September file into the inbox and open *Delivery gap check*. What does the data strip say, and
  what does Coverage name? *Answer: the strip warns of one missing day; Check coverage names 2026-09-05, the day no file
  delivered (the 6th arrived, the 5th did not).*
- **Check:** which build mode do you choose after one new day arrives? *Answer: Append, which indexes only the files
  added since the live version; Full rebuilds from the whole Dataset, Compact merges appended deltas into one.*
- **Gotchas:** the plan files this episode under Advanced, but every feature in it is Standard and up (EDITIONS, *Scale*);
  Advanced adds only the shared Investigation store of I2. Plan §3 named Telecom; only Other Examples ships a daily feed
  with staged next days and an index Job, so it is used instead. Copying the staged files is done off camera in the
  Space's data folder (`data/staged/mule_transfers` → `data/inbox/mule_transfers`). No performance figure but the D-TR6
  sentence is said.

### S01 · More data than a screen
- Voice: This is Inspecto LA training, episode I3: scale. A screen can draw a few thousand nodes. Real data is millions of records, and it grows every day. Three things let Inspecto LA work at that size: tools that run on the server over the whole Dataset, a link index that answers neighbour questions fast, and daily ingestion that keeps both up to date on their own. All three are in Standard and Advanced. We record in the Standard edition, in the Other Examples Space, on the mule transfers feed.
- Action: intro card (edition chips *Standard* and *Advanced*).
- Impact: none.

### S02 · Screen or server
- Voice: The toolbox has two tabs. On screen works on the graph you can see, in your browser, up to a size each tool states. Whole Dataset works on everything, on the server. When the graph on screen is a cut of a bigger Dataset, an answer from On screen is an answer about the cut. Open Whole Dataset: the same questions, how are they connected, who matters most, which groups, what looks unusual, with the server's own tools among them.
- Chip: Whole Dataset
- Action: toolbox ▸ On screen (point at an *Up to N nodes* chip) ▸ Whole Dataset ▸ scroll the four question groups.
- Impact: mark the two tabs and one server-only row.

### S03 · Run on server
- Voice: Pick a question, say who matters most, and press Run on server. The run is queued and runs on the server; you can cancel it. Its answer comes back as a table in the Results dock, the top rows first, and you choose what to show on the graph. If the answer is larger than the server will return, it says over the limit, no result, and asks you to narrow, rather than hand you a part that looks whole.
- Chip: Run on server · Results
- Action: Whole Dataset ▸ *Who matters most?* ▸ a centrality ▸ Run on server ▸ hold on the progress ▸ dock ▸ Results ▸ Show on graph. [check in UI: the tool and button labels on this tab, and the result table's actions]
- Impact: mark the progress line; then the Results table.

### S04 · The link index
- Voice: Under How are they connected, open Link index. Serving is on for this Space: a read is answered from a fresh link index, and from the Dataset otherwise. Each mapping says which: link index, with its version, or Dataset only. The index is what makes an expand or a path search over millions of links answer in moments: search and filter stay fast on millions of records. If it is out of date, the reads fall back to the Dataset, slower but the same answer.
- Chip: Link index · Link index serving is on
- Action: Whole Dataset ▸ *How are they connected?* ▸ Link index ▸ hold on *Link index serving is on* ▸ zoom on the chip and *mule_transfers_dataset: version N*. [check in UI: the version line and whether deltas are shown]
- Impact: mark the serving line, the chip and the version.

### S05 · Full, Append, Compact
- Voice: The panel suggests what to build, and why. Three modes. Full rebuilds the index from the whole Dataset. Append indexes only the files added since the live version: the everyday choice when a new day lands. Compact merges the appended pieces into one sorted index, when many days have piled up. Choose Append and press Build link index. It reports each phase, can be cancelled, and ends with the new version.
- Chip: Build mode · Full · Append · Compact
- Action: Link index ▸ Suggested ▸ Build mode: Full, Append, Compact (read each help line) ▸ Append ▸ Build link index ▸ hold to *Built version N…*. [check in UI: the Suggested line on a fresh unzip; if it reads *nothing to do*, build nothing and hold on the modes]
- Impact: mark each mode's help line; then *Built version N*.

### S06 · A new day arrives
- Voice: In production nobody presses that button. The data arrives by itself: a Collector picks up each day's file, a Pipeline lands it in a daily Dataset, and when the delivery is committed, a Job appends the link index. Here, the file for the fourth of September is placed in the feed's inbox. Within a poll it is picked up, landed, and indexed. Back in Link index, the version has moved on, with one more appended piece.
- Chip: Collector · Pipeline · link index Job
- Action: off camera: copy `TRANSFERS_20260904.csv` from `data/staged/mule_transfers` to `data/inbox/mule_transfers` ▸ on camera: wait for the next poll ▸ Link index ▸ hold. [check in UI: the version line after the append]
- Impact: before/after hold: the version number and the appended count.

### S07 · The data strip knows
- Voice: Open the Investigation Mule hub: six feeder accounts and a relay out, and look at the data strip above it: how current the data is, and how complete. Open Data details. Files lists each delivered file with its day, rows, outcome, and its index state: the new file reads indexed, with the version. You do not have to trust that the feed ran. The screen shows it.
- Chip: Data strip · Data details · Files
- Action: File ▸ Open… ▸ *Mule hub: six feeder accounts and a relay out* ▸ Data details ▸ Files ▸ the `TRANSFERS_20260904.csv` row. [check in UI: the strip's sentence and the file's index state *INDEXED vN*]
- Impact: mark the strip's sentence; then the new Files row.

### S08 · A missing day
- Voice: Now the sixth of September arrives, but the fifth never came. Open the Investigation Delivery gap check, whose window covers those days. The strip turns to Check data, and its sentence says one day is missing. In the Coverage tab, Check coverage names it. That is the difference between no activity and no data, and at scale it is the question that matters most.
- Chip: Check data · Coverage
- Action: off camera: copy `TRANSFERS_20260906.csv` into the inbox ▸ wait for the poll ▸ File ▸ Open… ▸ *Delivery gap check* ▸ hold on the strip ▸ dock ▸ Coverage ▸ Check coverage. [check in UI: the strip's missing-day sentence and *Days with no data* naming 2026-09-05]
- Impact: mark the badge, the sentence and the missing day.

### S09 · When the index falls behind
- Voice: If a delivery lands and the index has not caught up, three places say so with one judgement: the strip adds that the link index is out of date, the Data page's graph says out of date, and an expand says it was answered from the Dataset because the link index is out of date. Nothing is wrong with the answer; it is slower. Rebuild, beside the words, builds it again the way the server advises, for someone allowed to build the index.
- Chip: Link index out of date · Rebuild
- Action: point at the strip's index sentence and Rebuild, if shown. [check in UI: whether an out-of-date state can be shown on camera, for example by pausing the index Job before a delivery; else a still from the guide]
- Impact: mark the out-of-date sentence and Rebuild.

### S10 · What Advanced adds
- Voice: Everything in this episode is Standard. What the Advanced edition adds at scale is the shared store of episode I2: several servers serving one Space, with its Investigations in one PostgreSQL database. The data path, the server runs and the link index are the same.
- Chip: Standard · Advanced
- Action: title card: the EDITIONS rows *Scale* (Standard, Advanced) and *Investigations stored on PostgreSQL* (Advanced).
- Impact: mark the two rows.

### S11 · Try it
- Voice: So: when the question is bigger than the screen, run it on the server; keep the link index fresh with Append; and let daily ingestion feed both, while the data strip and Coverage tell you what arrived. Your turn. Bring in the sixth of September, open Delivery gap check, and read the data strip. What does it say, and which day does Coverage name? The answer is in the description. Next, in category J, the worked cases: twenty stories, from the first question to the Dossier.
- Action: outro card: *Try it* and *Next: J · Worked cases*.
- Impact: none.
