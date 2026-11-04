# A domain Entity Type catalogue: families, pack types and record types (`ENTITY-TYPE-CATALOG-1`)

> **PLAN 2026-11-04 — decisions Q-1…Q-6 accepted by the operator 2026-11-04 (the recommended options, §5).** Operator ask, on the Data page's
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
| **P0** ✅ 2026-11-04 | Families in the core; each of the nine types gets one (contract unchanged); the picker groups by family and shows family + example + definition. No rule change yet. **As built:** `EntityTypes.FAMILIES` + `family`/`definition`/`example` on `EntityType` (optional on the wire and in TOON; a stated type without them takes the core default's of the same id, a new id has none → *Other*); a pack overlay keeps family/example and lends its term's definition; `GET /inv/entity-types` also answers `families`; the shared builder `link-analysis/api/entity-type-options.ts` feeds the Data page and Add data; the option picker's filter also matches hint and group | S |
| **P1** ✅ 2026-11-04 | Packs may add types under a family; value shape + example in the pack's TOON from the closed shape vocabulary; `EntityShapes` reads them; neutrality test rule; several packs per Space merge by id. **As built:** `ValueShapes` (entity-store) + `inspecto/graph/value-shape.ts`, one parity fixture `entity-shape-parity.fixture.json`; vocabulary `free · e164 · digits(n\|a-b) · luhn(n\|a-b) · iban · email · uuid` (*session decision:* `luhn` added so a Luhn-checked serial (IMEI) stays apart from 15 plain digits (IMSI); `e164` fits strong with `+`, weak as 8–15 bare digits; `digits` always weak — digits alone could be another number — so plain digits propose at low confidence; the narrower shape wins a tie, in Java and in the picker). `EntityType.shape` on the wire, in `link-analysis.toon` and in a pack; core shapes: imei `luhn(15)`, imsi `digits(14-15)`, msisdn `e164`, the rest `free`. A pack type that is not a core id must name its `family` and have an `entity_type.<id>` term (its label + definition, so the neutrality word list covers it); a restated core id keeps its core family. `IndustryPack.overlay(packs, base)` merges by id, first pack wins a label; one id in two packs must agree on family, normaliser, masking *and classifications* (*session decision*, beyond the plan's three: then installed packs read together in any subset and order), `IndustryPacks.load` refuses a pack that does not read together with those before it, and the PUT refuses a set that does not (422). *Session decision:* the further packs are a new `extra_packs` key beside `domain_profile` (the Domain Profile alone gives the vocabulary); `PUT …/domain-profile` takes `extraPacks` (left out = kept, `[]` clears). `EntityShapes` proposes the type whose shape the most sampled values fit, then the narrowest shape; a `free` type only by name (an IBAN column is proposed only to a type shaped `iban`; a name saying iban still reads as an account) | M |
| **P2** ✅ 2026-11-04 | Record types: defined by the pack (likely types, and likely roles with `LA-COLUMN-ROLES-1`); a Dataset's `recordType`; Add data proposes it; the picker's *Likely in a …* group with "shape matches" from the column's sample. **As built:** optional pack file `industry-pack-record-types.toon` `record_types[n]{id, likely_types[]}`, label + definition from the pack's `record_type.<id>` term (a new term namespace); `IndustryPack.recordTypes(packs)` merges by id, first pack wins; `LinkAnalysisSettings.effectiveRecordTypes()`; `GET /inv/entity-types` answers `recordTypes`. Dataset key `recordType` (shape-checked by `DatasetEntryRules`), set by the new `PUT /datasets/{id}/record-type` (canAuthorWorkbench, 422 for one the Space's packs do not define) and by the Add data commit (`recordType`; left out = the draft's proposal). The draft's `entityTypes.recordType` = the record type whose likely types the most proposed columns hold, high when two or more and no tie. UI: `entityTypeOptions(types, families, {recordType, sample})` puts the likely types first under *Likely in a <record type>* (or *Shape matches* with no record type), a strong fit before a weak one, then the narrower shape; the Data page asks "Each row is" per Dataset and narrows its Holds picker; Add data asks it beside the main kind of thing and narrows each column's choices with that column's sample. *Session decisions:* shape marks only in Add data — the Data page's row sample is masked for masked types (`BiResultMasking`), so a mark there would be wrong for exactly the columns that matter; **likely roles are not carried** — column roles are `LA-COLUMN-ROLES-1`'s, a follow-up once both lanes merge (board row) | M |
| **P3** ✅ 2026-11-04 | Content: the telecom and mobile-money packs made comprehensive (types + record types + examples), and a neutral starter catalogue for a Space with no pack. **As built:** `EntityTypes.DEFAULTS` is the **neutral starter catalogue** — the nine original ids first (ids and classifications unchanged), then `email`, `id_document`, `organisation`, `bank_account` (`iban`), `card` (`luhn(13-19)`), `ip_address`, `address`. **Membership (Q-6 a):** with packs in force a Space reads exactly the types its packs list, in pack order (`IndustryPack.overlay`), a starter id keeping its starter family and guidance; with none, the starter. Telecom lists 14 (adds `iccid` `luhn(19-20)`, `apn`, `roaming_partner` (shape `free`: a network code looks like any small number), `retailer`; relabels `account` *Billing account*) and 8 record types (voice call, SMS, data session, SIM registration, airtime top-up, roaming, handset change, billing statement); mobile money lists 13 (adds `merchant`, `biller`) and 8 record types (wallet transfer, cash-in, cash-out, merchant payment, bill payment, bank transfer, KYC registration, app sign-in). Every pack still claims what its Space Templates bind (telecom ACCOUNT/IMSI/MSISDN for la-showcase, mobile money AGENT/MSISDN/WALLET). Examples are synthetic and fit their own shapes (a test). *Session decisions:* the record-type proposal counts only high-confidence column proposals and breaks a hit tie by coverage (the share of the record type's likely types held: a call record's four over a data session's six), so a plain call file (two numbers, a handset serial) is a voice call record at low confidence, tied with a handset change; a set length is narrower than a range in the shape ranking; Add data matches a column's whole name to a type id before its tokens (`ip_address` is not a postal address); the core *Organisation* family definition no longer says "merchant" (now a pack word), nor does a preset's description | M |

## 5. Decisions (operator 2026-11-04: accepted as recommended)

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
