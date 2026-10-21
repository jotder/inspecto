# Inspecto LA — grow the graph and exclude noise, made easy for a new analyst (`LA-GROW-EXCLUDE-1`)

**Status: PLAN 2026-10-21 — recommendations written for operator review, nothing built.** Decisions GE-1…GE-12 are
the assistant's recommendations (the operator asked for the plan "with your recommendations" and to improve
intuitiveness for new users); confirm before phase 1 starts. When a phase ships, distil the as-built facts into
the la-core hub (`docs/okf/product/module/la-core/`), move open items to `docs/BACKLOG.md`, archive this file
when the last phase ships.

## 1. Why

An investigation grows by "next degree, all directions, minus the noise". The pieces exist — the `expand` op with
direction, the hub threshold that holds super nodes, the `exclude` and `excludeBy` ops, Entity Lists with purpose
*exclusion* — but a new analyst has to know the op vocabulary and find each one. The operator wants:

- an easy control to grow the working set to the next degree, in every direction;
- removing super nodes in one action;
- creating and maintaining excluded nodes from the UI, including **named exclusion lists** (marketing VIPs, call
  centres, politicians, defence, intelligence);
- those category lists **defined by an administrator**, except the call-centre kind, which analysts keep.

## 2. What exists (as built)

| Need | Today | Gap |
|---|---|---|
| Next degree | `expand` op, "Expand next degree" (Investigation, Standard+) | Options spread across a form; direction not obvious; no preview of what a step will add |
| Super nodes | `hub_threshold` (default 500) holds candidates above it; per-expand override | Held hubs are shown, but "remove them all" is not one action |
| Exclude a node | `exclude` op (ids picked) | No right-click; no "add to a list" from the graph |
| Named exclusion lists | Entity Lists with purpose `exclusion`; `excludeBy` op | Lists are managed on a separate page; nothing applies a list automatically; no admin-only lists |
| Basic | Explorer graph (projection, neighbours) | No working set, so no recorded grow / exclude |

## 3. Design

### 3.1 The "Grow" control (one button, sensible defaults)

A single **Grow** button on the graph toolbar, with a small panel:

- **Direction:** *Both ways* (default) · *Outgoing* · *Incoming* — shown as three icons with words, never just
  arrows.
- **How far:** *1 step* (default); *2 steps* only when the frontier is small (the panel says why it is greyed).
- **Link types and time window:** collapsed under "More options"; defaults to everything and the Investigation's
  window.
- **Busy numbers (super nodes):** *Hold numbers with more than 500 contacts* — on by default, the number editable
  (Space limit `hub_threshold`).
- **Exclusion lists in force:** the chips from §3.3, each with its count.
- **Preview before applying:** "This step adds about 342 nodes and 1,210 links; 3 busy numbers will be held; 41
  nodes are removed by your exclusion lists." Then **Grow** or **Cancel**. The preview is a dry run (no seal).
- **After the step:** a one-line result with **Undo**.

Every Grow is one recorded `expand` operation in an Investigation (replayable, evidence-grade). In Basic it acts on
the explorer graph and is not recorded (§3.5).

### 3.2 Super nodes

- Held busy numbers are drawn with a distinct ring and a "held" badge, and listed in a **Held** tray: name, contact
  count, why it was held.
- **Remove held super nodes:** one action that excludes every held node (an `exclude` op with the reason "busy
  number above N contacts"), or **Expand this one anyway** per node.
- A first-time hint explains what a busy number is ("call centres, switchboards and shops connect to thousands of
  people and hide the real links") with a link to the user guide.

### 3.3 Exclusions from the graph

- **Right-click a node (or a selection) → Exclude…**, two choices: *From this Investigation only* (an `exclude` op)
  or *Add to a list…* (pick or create a list; the node is excluded now and in every later step).
- **Exclusions panel** beside the graph: the lists in force as chips (name, type, nodes removed in this
  Investigation), each switchable off for one step (recorded), and *Show what was removed* (a table, respecting the
  list's visibility, GE-8).
- **Undo** is always one click (the `undo` op), and the panel reads in plain words: "41 nodes hidden by 3 lists".

### 3.4 List ownership

| Kind | Who creates and edits | Examples | Applies |
|---|---|---|---|
| **Category lists** | Space administrator only | Marketing VIPs, politicians, defence, intelligence | Automatically to every new Investigation in the Space (admin can mark a list *optional* instead) |
| **Analyst lists** | Any analyst (owner + Investigation team) | Call centres, known shops, test numbers | When the analyst switches it on; remembered per Investigation |

Both are Entity Lists with purpose `exclusion`, so the hash-chained history, normalisers and the `excludeBy` op are
reused unchanged. New list attributes: `owner_kind: admin | analyst`, `auto_apply: boolean`, `visibility: open |
restricted`.

### 3.5 Basic

Basic is the free explorer (no Investigations). It gets the same **Grow** panel and **Exclude** right-click on the
explorer graph, unrecorded: Grow calls the neighbours read, Exclude hides nodes locally and can add them to an
analyst list. Category lists exist in Basic too (admin = whoever runs the install, since Basic has no sign-in).
Recorded, replayable steps remain the reason to upgrade.

## 4. Usability for new analysts (applies to every surface above)

1. **Plain words first, terms second:** "Grow", "Busy numbers", "Hidden by lists"; the technical term (expand,
   super node, exclusion list) in the tooltip and the user guide.
2. **Preview before any step that changes the graph**, with numbers, and **Undo after it**.
3. **One primary action per panel**; advanced options collapsed.
4. **Empty and first-time states teach:** the first time the Grow panel opens, a three-line explanation; the
   Exclusions panel with no lists offers "Create your first list" and shows the category lists the admin has set up.
5. **Industry words through the active pack** (Telecom: "numbers", "calls"; Mobile Money: "wallets",
   "transfers").
6. **Keyboard and accessibility:** Grow `G`, Exclude `X`, Undo `Ctrl+Z`; every icon has a text label; axe-clean.
7. **Say why something is not possible**, never a silent greyed button.

## 5. Decisions (assistant's recommendations; operator to confirm)

| # | Decision |
|---|---|
| GE-1 | One **Grow** control replaces the separate expand form for the common case; the full form stays under "More options". |
| GE-2 | Default direction **Both ways**, depth **1**; depth 2 only under the existing frontier cap. |
| GE-3 | **Preview (dry run) before every Grow**, showing nodes, links, held and excluded counts. |
| GE-4 | Super nodes are **held by default** at the Space `hub_threshold`; **Remove held super nodes** is one recorded `exclude` with a reason. |
| GE-5 | **Right-click → Exclude** with two choices: this Investigation only, or add to a list. |
| GE-6 | **Category lists are admin-only** (create, edit, retire); analysts apply them but cannot change them. |
| GE-7 | **Call-centre-style lists are analyst lists** (operator's "excluding call center"), editable by the analyst who owns them and the Investigation team. |
| GE-8 | **Category lists can be `restricted`**: analysts see the list name and how many nodes it removed, not the members; members are visible to administrators and holders of `canRevealLinkEntities`. Politicians, defence and intelligence default to restricted. |
| GE-9 | Admin lists **auto-apply to every new Investigation** in the Space unless the admin marks them optional; turning one off for a step is recorded. |
| GE-10 | **Basic gets Grow and Exclude on the explorer graph**, unrecorded (§3.5). |
| GE-11 | New capability **`canManageExclusionCategories`** for category lists (granted to administrators by default); analyst lists need the existing `canManageIncidents`. |
| GE-12 | Every Grow, exclusion and list toggle in an Investigation is an existing op (`expand`, `exclude`, `excludeBy`, `undo`): no new op, so replay and the Dossier stay as they are. |

## 6. Phases

1. **Grow panel + preview + Undo** (Standard+, Investigation) — reuse `expand`; preview via a dry-run read.
2. **Super-node tray + Remove held** — reuse the held-hub data `expand` already returns.
3. **Exclude right-click + Exclusions panel** — `exclude` / `excludeBy`, list creation from the graph.
4. **List ownership** — `owner_kind`, `auto_apply`, `visibility` on Entity Lists; admin-only category lists;
   restricted members; capability GE-11; Settings page for categories. **Built 2026-10-22** (wire names `ownerKind`,
   `autoApply`, `visibility`): as built in the entity-list hub [spec](../okf/product/module/entity-list/spec.md)
   "Category lists" and la-core [architecture §6.8](../okf/product/module/la-core/architecture.md).
5. **Basic explorer variant** (GE-10).
6. **New-user polish** — first-time hints, keyboard shortcuts, pack-aware wording, user-guide chapter
   "Grow and clean a graph", a think-aloud test with two new users (`tests-think-aloud-script.md`).

Each new route clears the four endpoint gates; each phase runs the affected-build protocol and SPA specs with axe.

## 7. Risks

- **Restricted lists and masking:** a restricted list must not leak its members through counts on a tiny graph
  (removing 1 node from a 3-node graph reveals who it was). Show counts only above a minimum, or round.
- **Auto-apply surprises:** an analyst may not realise nodes were removed — the Exclusions panel and the Grow
  preview always show the count, and the Dossier lists which lists were in force.
- **Preview cost:** a dry-run read per Grow doubles the reads; it runs under the same rate class and limits.

## References

- la-core hub: `docs/okf/product/module/la-core/` (architecture, user guides, the op vocabulary).
- Entity Lists: `docs/okf/product/module/entity-list/`.
- Configurable limits (`hub_threshold`, frontier caps): the archived `la-configurable-limits-plan.md`.
