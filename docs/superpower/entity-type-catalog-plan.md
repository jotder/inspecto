# A domain Entity Type catalogue: families, pack types and record types (`ENTITY-TYPE-CATALOG-1`)

> **PLAN 2026-11-04 — decisions Q-1…Q-6 OPEN (recommendations marked, §5).** Operator ask, on the Data page's
> "what does this column hold" picker: *"this list would be different for different types of record, is it possible
> to make a comprehensive Entity Type for a domain? how user will read and use generic"*. Board row
> `ENTITY-TYPE-CATALOG-1`. Companion of `LA-COLUMN-ROLES-1` (the Columns card this picker lives in) and
> `UI-PICKER-OPTIONS-1` (groups and nested options in the picker).

## 1. Where we are (read on master `a7bf726ad`, 2026-11-04)

| Layer | Where | What it can do |
|---|---|---|
| **Core** | `EntityTypes.DEFAULTS` (`platform/inspecto-entity-store/.../EntityTypes.java`) | 9 types with NEUTRAL labels: Subscriber, Subscriber identity, Device identity, Phone number, Stored-value account, Account, Intermediary / till, Device, Location. Each: `id`, `label`, `normaliser` (closed set: default, digits, e164, upper-trim — the browser mints the same keys), `masked`, `classifications` |
| **Industry pack** | `META-INF/inspecto/industry-packs/<pack>/industry-pack-entity-types.toon` + `-terms.toon` (`IndustryPack.java`, `IndustryPacks.java`) | names a SUBSET of the core types (telecom: 6; mobile money: wallet, agent, msisdn…) and relabels them ("MSISDN", "Wallet"). ⛔ *"A pack relabels; it never adds a concept"* — a pack type that is not a core id is refused; `IndustryPackNeutralityTest` keeps industry words out of the core |
| **Space** | `link-analysis.toon` `entity_types` (`LinkAnalysisSettings.effectiveEntityTypes()`) | a stated list replaces the defaults wholesale, by hand |
| **Value shapes** | `EntityShapes` (`inspecto/.../control/EntityShapes.java`) | hard-coded Java patterns (IMEI, phone, IBAN, email, digits) that Add data uses to propose a type from sample values |
| **Picker** | Data page Entities card; Add data | offers EVERY Space type for EVERY column of EVERY Dataset, flat, label only |

So: the catalogue is narrow (telecom and money words only, nine ids), cannot grow per domain without editing the core,
knows nothing about what a record is (a call record's column is offered "Stored-value account"), and tells a reader
nothing beyond a label.

## 2. The target

1. **Families** — a small, closed, neutral core layer that anyone understands: *Party · Organisation · Account ·
   Identifier · Device · Place · Document*. Every Entity Type belongs to exactly one.
2. **A comprehensive Entity Type list per domain**, defined by its pack. Each type: `id`, `family`, `label` (the
   domain word), an optional abbreviation, a one-line definition, `normaliser`, `masked`, `classifications`, a
   **value shape** and an **example value**. Telecom gains e.g. ICCID, APN; mobile money gains Merchant, Biller,
   Bank account, Customer; a new domain (insurance, banking) is a new pack, not a core edit.
3. **Record types** — what one row of a Dataset is (*call record, SMS record, data session, transfer, cash-in,
   KYC registration, claim…*), defined by the pack: which Entity Types such a record usually holds, and (with
   `LA-COLUMN-ROLES-1`) which columns are usually its times, measures and categories. A Dataset says its record type;
   Add data proposes it.
4. **The picker reads like a guide**, grouped: *Likely in a <record type>* (with "shape matches" where the column's
   sample values fit the type's shape) · *Other <domain> types* · *Other families* (family → type, nested) ·
   *Nothing: a detail*. Each option shows the domain label, its family, an example value and a hover definition. A
   reader who does not know the jargon picks by family and example.
5. **Identity is unchanged by narrowing**: the record type only orders and groups the list. The same phone number in a
   call record and in a wallet registration is the same Entity Type id, so the two Datasets still join into one entity.

## 3. Design notes

- **The neutrality guarantee moves from "never add" to "always under a family".** A pack may add a type, but only
  under a core family, and the family label is what the core screens and other packs read. `IndustryPackNeutralityTest`
  grows a rule: every pack type names a family; no industry word appears in the core.
- **Ids and classifications are data contracts.** The nine current ids (`msisdn`, `wallet`, …) and their
  classifications stay byte for byte; they move into the packs that use them, with their families. Stored entities,
  Entity Lists and index manifests keep reading the same ids.
- **Normalisers stay a closed set** (the browser mints the same keys; the parity fixture guards it). A new type picks
  one of the four; a new normaliser is its own change with a parity fixture.
- **Value shapes become data, from a closed vocabulary**: `shape: e164 | digits(15) | iban | email | uuid | free`,
  each with a fixed matcher in Java and TypeScript (the same parity idea as normalisers) — never a free regex in TOON,
  which would be a ReDoS and parity risk. `EntityShapes` reads the type's shape instead of its own switch.
- **Several packs in one Space** (a telecom operator that runs mobile money): types merge by id; the same id in two
  packs must agree on family, normaliser and masking or the Space refuses to load.
- **Picker**: groups and hints already exist in `<inspecto-option-picker>`; nested family → type options are
  `UI-PICKER-OPTIONS-1` (this plan is its first customer and moves it off its demand hold).

## 4. Phases

| Phase | What | Size |
|---|---|---|
| **P0** | Families in the core; each of the nine types gets one (contract unchanged); the picker groups by family and shows family + example + definition. No rule change yet | S |
| **P1** | Packs may add types under a family; value shape + example in the pack's TOON from the closed shape vocabulary; `EntityShapes` reads them; neutrality test rule; several packs per Space merge by id | M |
| **P2** | Record types: defined by the pack (likely types, and likely roles with `LA-COLUMN-ROLES-1`); a Dataset's `recordType`; Add data proposes it; the picker's *Likely in a …* group with "shape matches" from the column's sample | M |
| **P3** | Content: the telecom and mobile-money packs made comprehensive (types + record types + examples), and a neutral starter catalogue for a Space with no pack | M |

## 5. Decisions (open)

- **Q-1 Families.** (a) a closed core set: Party · Organisation · Account · Identifier · Device · Place · Document ⟵
  *recommended*; (b) families are pack data too.
- **Q-2 Who adds types.** (a) packs add types, each under a core family ⟵ *recommended* (a domain is complete without
  editing the core; neutrality kept by the family); (b) keep "packs only relabel" and grow the core list instead.
- **Q-3 Record types.** (a) defined by the pack, chosen per Dataset, proposed by Add data ⟵ *recommended*; (b) none —
  order the picker by value-shape match alone.
- **Q-4 Value shapes.** (a) a closed shape vocabulary named in the pack's TOON, matched in Java and TypeScript ⟵
  *recommended*; (b) free regular expressions in TOON; (c) keep the Java switch.
- **Q-5 Several packs in one Space.** (a) allowed; types merge by id and must agree on family, normaliser and masking
  ⟵ *recommended*; (b) one pack per Space.
- **Q-6 The nine current types.** (a) move into the telecom and mobile-money packs under families, ids and
  classifications unchanged; a Space with no pack gets a neutral starter catalogue ⟵ *recommended*; (b) keep them as
  core defaults beside pack types.

## 6. Not in scope

Entity resolution across types (one person behind a phone number and a wallet — that is the entity store's merge,
unchanged); new normalisers; translating labels into other languages; the Standard console's Entity Types settings
page beyond reading the new fields.
