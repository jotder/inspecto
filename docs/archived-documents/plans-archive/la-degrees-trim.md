# LA-DEGREES-TRIM-1 - lowering Degrees trims the drawn Working Set

Operator 2026-10-30: "when we reduce degrees, the working set on browser doesn't shrink" ... "it should reduce all
terminal nodes". Decision (operator): **Trim the last Expand** - Expand's Degrees stepper is bound to the last Expand
step: lowering N to M drops that step's outermost ring(s) (the terminal entities and the links only they carry) from the
canvas and from the recorded step; raising re-expands. Saved and unsaved Investigations alike.

## Open design points for the lane to settle (record the answers here)
- Unsaved: replace the last step's `walked` lists with the first M, redraw from the server preview of that step only.
- Saved: the log is append-only - Undo the last step then re-Expand with M degrees (or a trim step, if the server has one).
- What the stepper shows when the last step is not an Expand (a plain start, a held step): behaves as today.

## As built (design settled before the code)

**The stepper is the last Expand's size, not only the next Expand's request.** The Expand form takes one new input,
`lastExpand` (the Degrees of the last Expand, or null), and one output, `degreesChange`. While `lastExpand` is set the
stepper shows it and, once it has settled (300 ms, so +/- clicks batch), a changed value is handed to the screen. While it
is null (the last step is a Start, a clean-up, a held step, an Expand made by someone else, a reopened Investigation) the
stepper is the next Expand's request, exactly as before. A line under the stepper says which of the two it is.

**Lowering N to M removes the outermost N-M degrees; raising to M expands M-N more degrees from the outer ring.** The
first degree of an Expand is never removed (the stepper's minimum is 1).

- **Unsaved.** `UnsavedStep.stages` (screen-only, the Working Set after each degree, filled by `walkUnsaved`) makes the
  trim exact and free of server calls: the step keeps `walked` and `stages` cut to M, the request says M degrees, and the
  canvas redraws `stages[M-1]` - so the terminal entities and the links only they carried are gone from the canvas AND from
  what Save replays. Raising walks the extra degrees from the outer ring over the current Working Set (server previews, as
  an Expand does) and appends them to the same step. Undo (Ctrl+Z) of either restores the previous canvas and step.
- **Saved.** The server has no trim route and the log is append-only, so a lower Degrees is the `undo` op once per step
  removed, newest first, each naming its step (the same guard as Undo of an Expand: a colleague's newer step is refused,
  never undone). That equals Undo-then-Expand-with-M for the outer degrees without re-reading the inner ones. Raising is
  one more `growInvestigation` over the outer ring and joins the same run. The run (`GrowExcludeStore.expandRun`) is the
  steps the last Expand recorded in this browser; it counts only while they are still the newest effective steps, and
  Save binds it to the Expand it replayed last. A reopened Investigation is not bound until the next Expand.
- A refused trim/raise leaves the stepper at the last Expand's value (the screen calls `syncDegrees()`).
