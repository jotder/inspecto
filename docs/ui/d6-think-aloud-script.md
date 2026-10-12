# D6 think-aloud session script — a real Case-desk lead

**Row:** `D6-REAL-USER-SESSION-1` · **Date prepared:** 2026-10-12 · **Surface:** Case Manager, *Findings* form editor.

The archived design (`docs/archived-documents/plans-archive/findings-spec-authoring-ui-design.md`, sections 1 and 8)
defines the persona (a case-management lead, not a developer), tasks T1-T8, and the real criterion: **T2, T6, T7
and T5 done unaided, PASS or FAIL per task**. The proxy session (`docs/okf/frontend/features/objects.md`, *Proxy
acceptance session 2026-09-29*) found five defects; all five are closed. A real lead has to be found and booked
(operator decision D14); an agent cannot run this session.

## Pre-session checklist

Do these before the participant arrives (about 30 minutes):

1. A build of current `master` with the `demo` Space and a bundle-local `demo-users.toon` holding a lead who has
   `canManageIncidents` and a second user who has not (the proxy used `package.ps1 -Edition Enterprise -DemoAuth`;
   use a clean port, 8080 may be held).
2. **Seed one Incident and one Case in the Space** (a `create-incident` Decision Rule over the API, as the proxy did) so
   task 5 does not dead-end on an empty Space. State openly in the notes that this is seeded.
3. Reset the `findings-spec` for the Space to the built-in (delete any custom component) so every participant starts
   from the same four fields.
4. A second person as note-taker; screen and audio recording with the participant's consent; no real customer data
   on screen (DATA-GOV-1).
5. **Known defect `FINDINGS-PER-CASE-TYPE-1`: the Findings form applies to EVERY Case, not per Case type.** A real
   lead will hit it first (they will ask whether a change alters the form for all their case types). It is open and
   is not a BACKLOG row yet. File or decide it before the session and have the answer ready; do not hide it from the
   participant, and log their reaction as a finding.

## Opening (read aloud, do not demonstrate)

"You are the lead of a fraud desk. Your team writes down what they find when they close a Case. We are testing the
product, not you. Please say out loud whatever you are thinking, including what you expect a button to do before you
click it. I will not help unless you ask to stop. There are no wrong answers."

Ask: role, how long on a desk, whether they have ever changed what the team records.

## Tasks

Verbatim wording to read. The facilitator never says field, dialog, key, or Findings spec.

| Task | Read aloud | Pass (all unaided) | Fail signals to log |
|---|---|---|---|
| T2 Add | "Your team never records how much money was recovered. Add a way to record that, as a number." | Opens the editor from Case Manager, adds a numeric field named by them, marks it required if asked "must it always be filled", sees it in the preview | Cannot find the editor; types over the wrong box; confuses name and label; gives up or asks for help |
| T6 Conditional | "Your team also wants a free-text note, but only when the Case was written off." | Adds a text field; sets it to show only for the right answer of the choice field; verifies in the preview that it appears and disappears | Cannot find "show only when"; reads the condition word wrongly; sets it on the wrong field |
| T7 Preview | "Before you save, show me what your team will see." | Uses the preview without being told it exists; chooses the answer that triggers the note | Saves first and checks on a real Case; does not notice the preview |
| T5 Remove | "Last year's field 'Root cause' is no longer wanted. Remove it. What do you think happens to Cases that already have an answer?" | Removes it; reads the warning (if any) and says correctly that old answers stay stored or are hidden, per the product | Removes it and expects old answers deleted or does not question it; cannot find the control; unclear whether it was saved |

Run order is T2, T6, T7, T5 (the design's order). After T7, have them save, then open a real Case and fill the form
once as the check that "what I designed is what my team sees". Optional T1 and T8 only if time remains. Then the
view-only user: "Sign in as your colleague, who is not a lead. What can they do here?"

## Facilitator rules

Silence for 20 seconds before any prompt; only "What are you thinking?" or "What did you expect to happen?"; log every
click and every verbal "I expected X"; time each task; stop a task at 5 minutes and mark it FAIL with the reason;
read the browser console after the session for errors (count click events before filing a dead-button defect: the
viewport-emulation trap).

## After the session

Ask: what was confusing, what would you change, would you use this. Record per task PASS / PASS-with-help / FAIL, the
verbatim quotes, and any new defect as a row in the same `objects.md` section (*Real-lead session YYYY-MM-DD*).
Compare against the proxy floor: any PASS the proxy gave that the lead failed is the headline. The row closes when
T2, T6, T7 and T5 are recorded; a FAIL becomes its own P2 row.
