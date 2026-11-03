# Mappings of Pipeline Datasets (`LA-PIPELINE-MAPPINGS-1`)

> **PLAN 2026-11-03 — decisions Q-1…Q-5 accepted by the operator 2026-11-03 (the recommended options, §6). P0 and P1 BUILT 2026-11-03 (§8, §9).** Operator ask, after a walkthrough of the
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

## 8. As built: P0 (2026-11-03)

- **The base rule** (`DatasetEntryRules.validateMapping`): a base is any Dataset with a `physicalRef` that is not itself
  a Mapping and has none of `sql`, `view`, `sourceName`, `calculated`. The refusals say which (*"…is itself a
  Mapping"*, *"…is computed (sql)"*, *"…has no stored data (physicalRef)"*). The `origin: import` requirement is gone.
- **Reading a Mapping needs its base** (`ComponentAccess.canView` → `baseVisible`): for an `origin: mapping` entry, with a
  Subject attached, the base named by `basedOn` must resolve under the request's config root (`Roles.ATTR_CONFIG_ROOT`)
  and be viewable by the caller; otherwise the Mapping reads as absent (404, and left out of lists), as every hidden
  Dataset does. A Mapping whose base is gone is absent too. With no Subject (Basic) nothing changes. The lookup is a seam
  (`ComponentAccess.DatasetEntries`) the control plane installs over `ComponentStore`, because `inspecto-access` cannot
  read the registry. Every one of the ~30 `canView` call sites gets it without change. Bases are read once per request
  (a request-scoped cache: a list of Datasets would otherwise scan the registry once per Mapping), and a `basedOn` the
  store refuses as an id reads as absent rather than failing the list. The answer is the R3 404, not the 403 §3.2
  sketched: a hidden base must not be told apart from an absent one.
- **Paths that do not go through `canView`, closed too** (verification round 1):
  - caller-less sweeps - `ComponentAccess.canViewAs(subject, caps, content, configRoot)` also judges a Mapping's base
    (an unresolvable base is DENIED; a role-only base is ROLE_ONLY); `ScheduledIndexBuild.decide` and
    `StandingDetection.decide` use it, so an owner who lost the base stops indexing or sweeping through a Mapping;
  - the `publish.postgres` Job, whose engine-side copy of the view rule cannot reach `inspecto-access`:
    `PostgresPublishJobType.canRead` adds the same base check through its own `ComponentStore`.
- **Deleting** judges the Mapping's own envelope only (`requireDelete`): it reads no rows, and a Mapping whose base was
  removed out of band must stay removable.
- **Writing a Mapping** already needed a viewable base (`ComponentRoutes.writeComponent`, LA-DATA-PAGE-1: 404 when the
  base is hidden or absent) - §3.2's "creating or editing requires read access" was already true; nothing added.
- **Deleting a base** with Mappings was already refused (409, any Dataset type) - now exercised for a Pipeline base.
- **Moved to P1:** the drifted-column read check (§3.3) needs a real read of a Mapping over data that lost a column; it
  goes with the Data page mark.
- Tests: `ControlApiOnboardFilesTest` - a Pipeline-style base accepted, computed / pointer-less / Mapping bases refused
  with the reason, delete of a Pipeline base held by its Mapping; `aMappingIsReadOnlyByThoseWhoMayReadItsBase` - an
  unrestricted Mapping of a base shared away is 404 by id and absent from the list, visible once the base is shared,
  absent again when the base is gone, and still deletable; `ComponentAccessAsOwnerTest.aMappingIsJudgedOnItsBaseToo`;
  `PostgresPublishJobTest.aMappingIsPublishedOnlyByAnAuthorWhoMayReadItsBase`.

## 9. As built: P1 (2026-11-03)

- **The Data page** (`inspecto-ui/projects/la-app/src/app/data.page.ts`) renders every Dataset that may be a base,
  imports first, then those Pipelines keep up to date. Each gets the same **Entities** and **Mappings** cards. Only the
  top card differs: an import keeps its Dataset card (Add a file, Run now, arrivals), and a Pipeline Dataset gets a short
  *Kept up to date by a Pipeline* card.
- **Which Datasets** (`data-page.ts` `DataEntry.mappable`): the server's base rule (a `physicalRef`, not computed when
  read, not a Mapping), plus two things the UI needs. The Dataset must not be an Entity List's store (`entity_list_`,
  `EntityListSidecar.PREFIX`), and it must list at least two columns, which the Mapping form offers as From and To. The
  rest stay a line of names under **Other Datasets**, with the reason.
- **Deviation from §3.4:** the Pipeline card has no Data status line. `GET /datasets/{id}/arrivals` serves only Add data
  Datasets (`DatasetArrivals`: a Pipeline Dataset's arrivals are its Pipeline's runs), and the entry does not name its
  Pipeline. Showing either would need server work this phase did not take on.
- **The card says "a Pipeline or a Job"**: a stored Dataset outside Add data is written by a Pipeline or by a Job
  (`MaterializeTask` writes a `physicalRef` and columns), and the entry does not say which.
- **§3.4 against §7, settled:** the Entities card on a Pipeline Dataset sets what its columns hold - the column
  classifications the Entities page's Columns tab already set for every Dataset (`OnboardImport`'s column route). §7's
  "editing a Pipeline Dataset's own entry" means its source definition (`physicalRef`, `sql`, its Pipeline), which
  stays out of reach here.
- **A known gap, recorded for P2's guide:** the server accepts a base the page does not offer (one with fewer than two
  columns, or an Entity List's store). A Mapping of such a base, made through the API, has no section on this page; it
  is still listed, explored and deleted from the Start box and the components API.
- **Drifted columns** (`driftedColumns`): a Mapping whose From, To, kind, when or amount column its base no longer lists
  is marked under it ("Reads a column wallet_txn no longer has: agent_id. Edit the Mapping, or it cannot be read."). The
  comparison is case-insensitive, like the server's column check. A base that lists no columns is not compared.
- **The read of a drifted Mapping** (moved from P0) is not tested here. A Mapping cannot be *saved* with a column its
  base lacks (`validateMapping`), so drift only appears when the base changes afterwards. LA reads turn DuckDB's binder
  error into a 422 that keeps the column name (`DatasetReadFault.scrub` strips only paths and the statement echo). P2's
  live check confirms it on a real drifted Mapping.
- Tests: `data.page.spec.ts` covers a Pipeline Dataset with its own cards and no import card, a Mapping saved with it as
  the base, the drift mark naming only the missing column, computed and column-less Datasets left under Other
  Datasets, and axe. `data-page.spec.ts` covers `mappable` for each case and `driftedColumns`. Ran the la-app specs
  (21 files, 130 tests), eslint and `lint:tokens`, all clean.
