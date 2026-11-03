# Mappings of Pipeline Datasets (`LA-PIPELINE-MAPPINGS-1`)

> **PLAN 2026-11-03 — decisions Q-1…Q-5 accepted by the operator 2026-11-03 (the recommended options, §6).** Operator ask, after a walkthrough of the
> Entities and Data pages in Basic: a saved **Mapping** (a Dataset of its own that reads another Dataset's rows its own
> way) should work for Datasets that **Pipelines** keep up to date, not only for Datasets added with *Add data*.
> Board row `LA-PIPELINE-MAPPINGS-1`.

## 1. The problem, seen on the Basic install (2026-11-03)

On the synthetic `la-mobile-money` Space the Data page gives `demo_transfers` (added with *Add data*) three cards:
Dataset, **Entities** and **Mappings** (*New mapping*, *Edit*, *Delete*, *Explore*). The five Datasets Pipelines fill
(`wallet_txn_dataset`, `mm_links_dataset`, `mm_kyc_dataset`, `agent_master_dataset`, `entity_list_struct-wallets`)
appear only as one line of names under **Kept up to date by Pipelines**. To read `wallet_txn_dataset` another way
(say *wallet → agent* instead of *wallet → wallet*), an analyst can only set columns in ⚙ **Settings ▸ Graph model**,
which is drawn once and never saved, and which no preset, Start box or Investigation can name.

Two places refuse it, on purpose, since `LA-DATA-PAGE-1`:

- **UI:** `inspecto-ui/projects/la-app/src/app/data-page.ts` `dataEntry` sorts a Dataset with no `origin` into
  `other`, and `data.page.ts` renders `other` as names only (line 279).
- **Server:** `DatasetEntryRules.validateMapping` (`inspecto/src/main/java/com/gamma/control/DatasetEntryRules.java`)
  refuses a base whose `origin` is not `import` (*"a Mapping reads an Add data Dataset; '…' is not one"*).

What makes it possible: a Pipeline Dataset is stored data like an import - a registry entry with a `physicalRef`
(`wallet_txn/database`), a `columns` list with classifications, an `eventDate`. A Mapping copies the base's
`physicalRef` and columns and adds its own `defaultLinkMapping`, Entity Types and event date; nothing else changes.

What makes it not free - **access**. Pipeline Datasets carry `owner` and `shares` (`wallet_txn_dataset` is shared with
five users). A Mapping is a Dataset entry of its own, and nothing on the read path looks at its `basedOn`: whoever may
read the Mapping reads the base's rows. For an Add data Dataset that has been tolerable (its maker usually makes its
Mappings); for a governed Pipeline Dataset it would let a Mapping widen who reads the data. It is also true of today's
import Mappings - this plan closes it for both.

## 2. The goal in the analyst's words

*"Our transactions come in by Pipeline. I save 'wallet to agent' once, under a name, and from then on it is in the
Start box, the presets and my Investigations like any Dataset - and nobody reads through it who could not read the
transactions."*

## 3. Design

1. **Which Datasets may be a base (server).** `validateMapping` accepts a base that is **stored and plain**: it has a
   `physicalRef`, it is not itself a Mapping, and it has none of `sql`, `view`, `sourceName`, `calculated` (a Dataset
   computed at read time has no stored rows a Mapping could copy a pointer to - Q-1). `origin: import` stays allowed;
   the refusal sentence names the rule (*"…reads a stored Dataset; '…' is computed (sql)"*).
2. **A Mapping never widens access (server, Q-2).** Every read that resolves a Dataset entry also requires read access
   to its `basedOn` base, fail-closed - P0 first finds the one place Dataset read access is decided (not yet located;
   `ComponentAccess` in `inspecto-access` is the candidate) so the rule lives there, not in each route:
   a caller who may read the Mapping but not the base is refused 403 with the base named, as for any unreadable
   Dataset. Applies to import Mappings too. Creating or editing a Mapping requires read access to the base.
3. **Columns that drift (Q-3).** A Pipeline's schema can change under a Mapping. The Mapping keeps its copied column
   list; the Data page compares it with the base's and marks a Mapping whose From, To, kind, when or amount column the
   base no longer lists (*"reads 'agent_id', which wallet_txn_dataset no longer has"*). A read of it must refuse with
   the column named, never answer empty - not yet checked; P0 adds a test and the refusal if it is missing.
4. **The Data page (UI, Q-5).** A Pipeline Dataset gets the same card stack as an import, minus what only an import
   has: a **Dataset card** that says *Kept up to date by the Pipeline &lt;name&gt;* with the Data status line (no *Add a
   file*, *Run now*, *Keep this up to date*), the **Entities** card, and the **Mappings** card. A Dataset that cannot be
   a base (computed, or an Entity List's store) keeps the names line, with the reason on hover.
5. **Link index (Q-4).** A Mapping has its own link index (an index is per Dataset and mapping), built only when a
   `la.index.build` Job names it. Out of scope to automate; the Data page shows *No link index* on a Mapping whose
   base has one, and the admin guide says how to add the Job.

## 4. Phases

| Phase | What | Size |
|---|---|---|
| **P0** | server: base rule (§3.1) and the base-access check on read, create and edit (§3.2), for import and Pipeline Mappings; real-HTTP tests: a Pipeline Mapping saved and read; computed base refused; a caller without base access refused on read and on create; delete of a base with Mappings refused (existing guard) | S |
| **P1** | Data page: Pipeline Dataset cards (§3.4) and the drifted-column mark (§3.3); spec + axe | S |
| **P2** | user guide §3.4, admin guide (Mappings and the index Job), live check on `la-mobile-money` (a *wallet → agent* Mapping of `wallet_txn_dataset` drawn and run as a preset) | XS |

## 5. Done means

On Basic over `la-mobile-money`: *New mapping* on `wallet_txn_dataset` saves a Mapping that appears in the Start box
and the presets and draws; a computed Dataset is refused with the reason; on a secured install a user without read
access to the base can neither read nor create a Mapping of it (tests); a Mapping whose base dropped a column it reads
is marked on the Data page.

## 6. Decisions (operator 2026-11-03: accepted as recommended)

- **Q-1 Which bases.** (a) any stored, plain Dataset of any origin except a Mapping ⟵ *recommended*; (b) Pipeline
  Datasets only by an allow-list in Settings; (c) also computed (`sql` / `view`) Datasets - a Mapping would then copy
  the SQL, which `validateMapping` forbids today because a Mapping must not read data of its own choosing.
- **Q-2 Access.** (a) reading or saving a Mapping also needs read access to its base, for import Mappings too ⟵
  *recommended* (a behaviour change for an import Mapping shared wider than its base: it stops reading for those
  users, with the reason); (b) copy the base's shares onto the Mapping at save (drifts when the base's shares change);
  (c) leave as is (a Mapping can widen access).
- **Q-3 Drifted columns.** (a) mark on the Data page, refuse on read with the column named ⟵ *recommended*;
  (b) re-copy the base's columns on every Pipeline run (silently changes a saved Mapping).
- **Q-4 Link index for a Mapping.** (a) out of scope - documented, *No link index* shown ⟵ *recommended*; (b) a Mapping
  inherits its base's index Job (doubles build time per Mapping).
- **Q-5 Pipeline Dataset card.** (a) the import card minus import-only controls, naming the Pipeline ⟵ *recommended*;
  (b) only the Entities and Mappings cards.

## 7. Not in scope

Editing a Pipeline Dataset's own entry from the Data page (its Pipeline owns it); Mappings of Mappings; Mappings that
filter rows (a Mapping reads every row of its base); building link indexes for Mappings automatically.
