# Link Analysis right panel redesign (`LA-PANELS-REDESIGN-1`)

Status: **ACTIVE 2026-10-29**, one lane (`C:\sandbox\la-panels`). Item: `module.la-core`.

## Request (operator, verbatim)

> "redesign Link analysis Panels. panel content design bit confusing, new dialog form -> Investigation tab -> Expand
> Form too much flow. Keep three Tabs - 1. Browser, Server, and investigation. split controls of Analysis tab into
> suitable for Browser / Server. Keep pencil edit style everywhere, dropdown as popup dialog. for new: select (One
> entity/ A list/A paste/The whole Dataset), like Entity Type drop down; set a default name and reason (user would
> change) based on input selected. move open, explore dataset, examples under file menu(toolbox)"

## Decisions

1. **Three tabs: Browser · Server · Investigation**, in the tab strip and in the collapsed rail; they replace
   Analysis · Investigation.
   - The toolbox component stays ONE component, mounted ONCE and kept alive with `[hidden]`, so tool results survive
     tab switches. The host passes `side` (`browser | server`) from the active tab. The component no longer has its
     own Browser | Server toggle.
   - The split is driven by `analysis-catalogue.ts` (`listedOnSide`).
     - **Browser** lists the tools whose `runsOn` is `browser` or `both`.
     - **Server** lists the server algorithms (`GET /inv/graph/algorithms`), the tools whose `runsOn` is `server`
       (server paths, value measures, burst and periodicity, edge index), and the `both` tools that have no
       server-algorithm twin (pattern match, explain).
     - A `both` tool that has a twin (centrality, communities, components, …) is represented on Server by its twin
       rows. Its browser group is also listed there while it hands its run over to the server (graph above the
       browser cap), as before.
   - Inside a `both` group, each side shows only its own controls. The over-cap server hand-off, the index reads of
     *Explain* and the pattern's *Run on server* show on Server only. On Browser, a line says when the graph is above
     the browser limit and points to the Server tab.
   - Discover (the three plain questions) runs on the drawn graph, so it sits in the Browser tab.
   - On the Explorer level, the Server tab keeps the Dataset reads that need no Investigation, as they were before the
     split: Find paths (server), Value Measures, Burst and periodicity, and the pattern's Run on server. Edge index is
     hidden there, and one locked line, *Run a server algorithm*, comes after them.
     - The over-cap hand-off stays locked inside its own tool in the Browser tab, because on this level there is no
       server algorithm to hand over to.
2. **Pen fields and popup pickers everywhere in the three tabs.**
   - Free-text and number settings use `inspecto-pen-field`.
   - Single choices use `inspecto-option-picker`.
   - Button toggles for two or three options (path metric, direction) stay as toggles. They are not dropdowns.
3. **One New form.**
   - File ▸ New and the Investigation tab's *New Investigation* open the same single form, in the Investigation tab.
   - The form reuses the start box component (`inspecto-link-analysis-start-box`, `mode="investigation"`), so the
     type-ahead, masking reveal and whole-Dataset warning are not duplicated. It is laid out top to bottom:
     1. **Start from** (option picker): One entity / A list / A paste / The whole Dataset.
        - While a graph is drawn, it also offers **The graph on screen** (review fix 2026-10-29). This starts a seedless
          Investigation over the drawn graph's one mapping, as the old projection start form did.
        - Its defaults are *Investigation — {view or Dataset name}* / *Review of the graph on screen*. Under masking
          the name is left out (*Investigation — graph on screen*).
     2. **Dataset**.
     3. The inputs for that choice.
     4. **Name** and **Reason** (pen fields).
     5. **More** (time column, zone; collapsed).
     6. **Expand on start** (collapsed; degrees *Don't expand* / 1 / 2 / 3, capped at the profile's maximum degree).
   - Name and Reason are re-defaulted whenever the input changes, until the user edits them. From then on the user's
     text is kept. Defaults (`newInvestigationDefaults` in `start-from.ts`):
     - entity: *Investigation — {Type} {value}* / *Review of {Type} {value} and its links*.
     - list: *Investigation — list {title}*.
     - paste: *Investigation — {n} pasted values*.
     - dataset: *Investigation — whole {Dataset}*.
     - **Masking:** a title is stored and shown like any value. In a Space not known to be unmasked, the entity value is
       therefore left out (*Investigation — MSISDN*), the same rule `suspectTitle` follows.
   - Starting creates the Investigation and adds the seed step. When Expand on start is given, it also expands that
     many degrees with the profile's presets. Entity / list / paste go through the store's `investigateNumber` chain;
     the whole Dataset is drawn and an Investigation is created over its mapping, with no seed step.
     **Expand on start defaults to *Don't expand*.**
   - The standalone Expand… stays for later expands. The deep-link *Investigate {number}* form, and *Start with N
     queued seeds* for top results queued from the toolbox, stay; they are not the New flow.
   - **The Query zone's start box** keeps only the draw: Dataset, Start from picker and its inputs. It is the Explorer
     level's way to draw. On the Investigations level, *One entity* there still draws and queues, and the New form is
     one click away.
4. **File menu** gains *Open…* (already there), *Explore dataset…* and *Examples ▸ Follow the money*. The starter cards
   (Follow the money, Open a saved view, Explore a Dataset) are removed from the start box. *Add data* stays as a link.
   The Investigation tab's *Open…* button is removed.
5. Vocabulary per `docs/GLOSSARY.md`: Dataset, Pipeline, Incident, Collector; never a banned synonym in UI text.

## Files

- `inspecto-ui/projects/link-analysis/src/link-analysis/`
  - `analysis-catalogue.ts`
  - `link-analysis-toolbox.component.{ts,html}`
  - `link-analysis.component.{ts,html}`
  - `link-analysis-start-box.component.ts`
  - `start-from.ts`
  - `link-analysis-investigation.component.{ts,html}`
  - `link-analysis-investigation.store.ts`
  - their specs
- `docs/okf/product/module/la-core/user-guide-link-analysis.md`

## Test plan

- Catalogue: `listedOnSide` for browser, server and both tools.
- Toolbox: `side` drives the sections and the hand-off; no internal toggle.
- Host: three tabs in the strip and the rail; the panel ids follow the tab; `[hidden]` keeps the toolbox mounted.
- New form:
  - the Start from picker shows only that choice's inputs;
  - the default Name and Reason for each choice;
  - a user edit is kept when the input changes;
  - `create` carries the start, title, purpose and degrees.
- File menu: Explore dataset… and Examples ▸ Follow the money call the existing handlers.
- Run: the link-analysis, graph and components specs (`npx ng test`), `npx ng build`, `npx ng lint`.
