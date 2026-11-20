---
type: Spec
title: "Video series spec — Build, Ship, Manage"
description: "The platform training video series: one episode per step of the six Handbook tutorials on the Order-to-cash worked example (31 episodes in three categories), the episode card, the recording gate (an episode is recorded only when its page's checks pass), and the LA series machinery it reuses — the declarative episode recorder, the series voice and the scene renderer."
item: layer.product
audience: internal
status: current
verified: 2026-11-20 code-reviewed
code: [tools/record-la-live.mjs, tools/render-la-demo-video.mjs, tools/la-video-voice.mjs, tools/run-handbook-checks.mjs]
---

# Video series spec — Build, Ship, Manage

**Board row:** `USER-TRAINING-1` (plan `docs/superpower/2026-11-19-user-enablement-and-agent-kit-plan.md` §6). This page
is the series' specification; nothing is recorded yet.

## 1. What the series is

A platform training series that follows the Handbook's six tutorials on the **Order-to-cash** worked example, **one
episode per tutorial step**, so a viewer can watch a step and then do it. The Link Analysis training series (41
episodes, `module/la-core`, `user-guide-training-*.md`) stays the Link Analysis track; this series does not repeat it
and links to it from the builder's *Analyse it* stage.

- **Audience:** the builder track (category B), the partner developer (S), the operator (M). Business users watch B13–B15.
- **Where it is listed:** each Learning Path (`user-guide-path-*.md`) names the episodes of its stages once they are
  published; each tutorial step links its episode.
- **Teaching rules:** the LA series' eight rules (`2026-11-16-la-training-series-plan.md` §2) apply unchanged —
  intent, action, impact every time; the control's exact on-screen name; one Space, one story (here always the
  Order-to-cash Space); the edition always visible; no record counts as a selling point; GLOSSARY words; every episode
  ends with *Try it*; honest about limits.

## 2. Episodes

Codes and titles follow the tutorials' step headings. File name `<code>_<Title>.mp4`, as the LA series.

| Code | Episode | Tutorial step | Edition | Recorded with |
|---|---|---|---|---|
| B1 | Create the Space from the template | T1 step 1 | Standard | console |
| B2 | See the files land | T1 step 2 | Standard | console |
| B3 | Look at the orders Dataset | T1 step 3 | Standard | console |
| B4 | Open the two Pipelines | T2 step 1 | Standard | console |
| B5 | Check what each run took in | T2 step 2 | Standard | console |
| B6 | Run the Expectations | T2 step 3 | Standard | console |
| B7 | Build the open items, then check for duplicates | T2 step 4 | Standard | console |
| B8 | Reconcile orders against payments | T3 step 1 | Standard | console |
| B9 | Raise Incidents on the orders that matter | T3 step 2 | Standard | console |
| B10 | Group them into collections Cases | T3 step 3 | Standard | console |
| B11 | Add a write-off approval to the Case workflow | T3 step 4 | Standard | console + Control API |
| B12 | Work a Case to a written-off close | T3 step 5 | Standard | console |
| B13 | Open the Dashboard | T4 step 1 | Standard | console |
| B14 | Read the three Widgets | T4 step 2 | Standard | console |
| B15 | Put the Dashboard where a business user finds it | T4 step 3 | Standard | console |
| S1 | Scaffold the App | T5 §1 | Standard | terminal |
| S2 | Put your Space into the template | T5 §2 | Standard | terminal |
| S3 | Say what the golden data must produce | T5 §3 | Standard | editor |
| S4 | Validate and test | T5 §4 | Standard | terminal |
| S5 | Sign the package | T5 §5 | Standard | terminal |
| S6 | Install it on the second server | T5 §6 | Standard | Control API + console |
| S7 | Ship version 2 | T5 §7 | Standard | terminal |
| S8 | Upgrade the installed App | T5 §8 | Standard | Control API + console |
| M1 | Check the zip and unpack it | T6 §1 | Standard | terminal |
| M2 | Connect sign-in | T6 §2 | Standard | identity provider + editor |
| M3 | Start it on loopback and sign in | T6 §3 | Standard | terminal + console |
| M4 | Put it behind the reverse proxy | T6 §4 | Standard | terminal |
| M5 | Back up and restore a Space | T6 §5 | Standard | console + Control API |
| M6 | Upgrade the bundle in place | T6 §6 | Standard | terminal |
| M7 | Verify the audit chain | T6 §7 | Standard | Control API |
| M8 | Open the Help | T6 §8 | Standard | console |

**Wave 1** is B1–B15: all console, all on one install, and their tutorials' checks were green on a seeded demo
install (2026-11-19). S and M follow once the recorder can capture a terminal (§5).

## 3. The episode card

Each episode is a `## <code>_<Title>` section in one script file per category (B, S, M), written in this hub when the
episodes are, in the LA series' card format, which `tools/render-la-demo-video.mjs --script` reads as is:

```
## B6_Run-the-Expectations
Edition · Space · story · target length     Standard · order-to-cash · the planted negative payment · 4 min
Objectives      3 things the viewer can do afterwards
Before          the state to record from (a fresh order-to-cash Space with samples; the steps before it)
Page            the tutorial page and step it shows (manifest id tutorial_pipeline_expectations, step 3)
Try it          the matching Lab task, or the tutorial step itself
Check           one question and its answer (the Learning Path's self-check where one fits)
Gotchas         what differs on a recorded screen
### S01 · <beat title>
- Voice: the narration, one line (the series voice reads it)
- Chip: the control's on-screen name
- Action: what the cursor does
- Impact: what changes on screen
```

Target length 3–5 minutes (about 150 spoken words a minute). Intro card: *Inspecto · Build, Ship, Manage*, the code and
title, the edition chip; category colours B blue, S amber, M slate.

## 4. Production — the LA machinery, reused

| Need | Reused as is | File |
|---|---|---|
| Click path | the declarative episode recorder: `{steps:[…]}` of verbs (`open`, `click`, `label`, `button`, `type`, `waitText`, `impact`, `scene` …) | `tools/record-la-live.mjs` scenario `episode` |
| Recording spec per episode | an episode JSON beside the LA ones (`{"scenario":"episode","edition":…,"base":…,"steps":[…]}`) | `tools/la-training/*.episode.json` — this series' go in `tools/bsm-training/` |
| Voice | the series voice (local Piper, D-TR1), ids read out the same way | `tools/la-video-voice.mjs` |
| Pacing, cut, marks, intro card | each scene held for its narration's length; computed impact marks; the card from the episode JSON's `card` | `tools/render-la-demo-video.mjs <code_Title> --script <file> --episode <json>` |
| Format | MP4 1920 × 1080, captions burned in and as `.srt`, chapter markers | LA plan §6.2 (D-TR2) |

**The recording gate (plan §6).** An episode is recorded only when its screens pass their checks: the tutorial page's
`check` blocks for the step, and a `check-ui` block that walks the same click path, run by
`node tools/run-handbook-checks.mjs --base-url <url> --page <id> --space order-to-cash --ui` on the install the
episode records from. The `check-ui` verbs are the recorder's own (`tools/handbook-checks.mjs` `UI_VERBS`), so the
episode JSON's steps are the page's `check-ui` lines plus `scene`, `card` and `impact` wrappers — one click path,
checked on every release and replayed on camera. When a check fails, the page is fixed first, then the episode.

**Fresh state per episode:** the Order-to-cash Space is created again from its Space Template with its samples for
each B episode (an episode changes state: checks run, Incidents open, a Case closes), as the LA series unzips a fresh
install per episode.

## 5. Not ready yet

- **The tutorials carry no `check-ui` blocks.** T1–T6 hold 51 `check` blocks (API) and none for the console, so the
  gate above has nothing to run for the click path. Wave 1 adds one `check-ui` block per step of T1–T4 before its
  episode is recorded.
- **The recorder captures a browser only.** S1–S5, S7, M1, M4 and M6 are terminal work (`tools/inspecto-app.mjs`, the
  bundle scripts); the recorder has no terminal capture, and the Handbook has no check kind for a command
  (`HANDBOOK-CLI-CHECK-1`). Until one exists they stay pages, not videos.
- **Install for recording:** an Advanced demo bundle (the demo Distribution exists for Advanced and Preview only)
  with a roster Space giving the recording user `admin` and `power`, as the tutorials' check runs did.

## References

- Plan `docs/superpower/2026-11-19-user-enablement-and-agent-kit-plan.md` §6; the LA series plan
  `docs/superpower/2026-11-16-la-training-series-plan.md` §2, §4, §6.
- `module/la-core/user-guide-training-scripts.md` — the LA episode card as read by the renderer.
- `spec-handbook-doc-tests.md` — the `check` / `check-ui` format and the runner.
