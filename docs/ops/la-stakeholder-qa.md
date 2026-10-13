# Link Analysis stakeholder Q&A (presenter prep and leave-behind)

Audience: bank and telecom risk, security, IT and compliance reviewers of the Link Analysis (LA) demo. Written
2026-10-12 against `origin/master` `22ccbe952`. Every figure below was checked against the repo on that date.

How to read an answer: two to five plain sentences you can repeat, then **Evidence** (where to look), then a
**Confidence** tag.

* **PROVEN** - a test or a measurement exists for it.
* **DOCUMENTED** - the design says so; it has not been shown live or by a test.
* **NOT YET** - an honest gap. Say so out loud; do not soften it.

Terms follow [`GLOSSARY.md`](../GLOSSARY.md) (Dataset, Collector, Pipeline, Incident, Measure, Alert Rule).
Companion pages: the [user manual](../ui/link-analysis-user-manual.md), the backend page
[`okf/backend/modules/link-analysis.md`](../okf/backend/modules/link-analysis.md) (cited below as *backend §n*), the
feature concept [`okf/frontend/features/link-analysis.md`](../okf/frontend/features/link-analysis.md) (*feature page*)
and the [demo readiness plan](../superpower/la-demo-readiness-plan.md) (*defence matrix* = its section 2).

---

## 1. Security and access

**Q1. Who can see what? Can an analyst see an Investigation they were not invited to?**
No. An Investigation is visible only to its members (and to the owner or assignee of its linked Case, read only). To
everyone else the answer is "not found", the same answer as for one that does not exist, so nobody can even confirm
it is there. A member who lacks a role gets a refusal only after the Dataset-visibility and policy checks have
passed. Visibility of the underlying Dataset is checked on every request, so losing access to a Dataset also closes
the Investigation built on it.
Evidence: backend §6.1 (gate order, step 6); `ControlApiInvestigationMembersTest`; user manual §12.
Confidence: PROVEN (the gate order and members behaviour have tests); the Postgres-store variant is DOCUMENTED.

**Q2. How does masking work, and can it be bypassed by a clever query?**
Identifiers are replaced on the way out of the server by a stable alias of the form `masked:` plus 16 hex
characters (a keyed hash), so an analyst can still group, count and follow an entity without seeing the value. The
default mode is `typed`: only columns whose classification an Entity Type claims (MSISDN, IMSI, account and similar)
are masked; `all` masks every id; `none` shows raw. The same rule applies on the canvas, node lists, paths,
patterns, Value Measures and the Working Set, and a raw id typed into an alias field is refused, so the route is not
an oracle for guessing.
Evidence: backend §3.4; `ControlApiExplorationMaskingTest` (the `everyInvReadOnAMaskedSpaceIsScannedForRawIds`
case scans every Investigation read for raw ids); `ControlApiInvestigationOversightTest` (typed by default).
Confidence: PROVEN for the routes the tests cover. A new route must be added to that scan; say so if asked about
future routes.

**Q3. Can anyone ever see the real value? Who, and is it recorded?**
Yes, per entity, through *Reveal*. It needs its own capability (`canRevealLinkEntities`), is a separate action from
approving, and every reveal writes an audit event. In the shipped demo the `admin` persona holds it.
Evidence: backend §3.4 and §6 (Four-eyes / reveal row); `ControlApiInvestigationOversightTest
.revealIsPerEntityGatedOnItsOwnCapabilityAndAudited`.
Confidence: PROVEN.

**Q4. What is audited, and is the audit trail itself protected from edits?**
Link Analysis writes `LINK_*` audit events (reveal, embed minted, viewed or denied, four-eyes inbox reads, reference
reads, and so on), and the host records every 401, 403 and 5xx. The platform audit ledger is append-only, but it is
NOT hash-chained: the security spec states that `AuditTrail` contains no hash, chain or signature. Our tamper-evidence
claim is about the Investigation record (Q13), not about the general audit ledger.
Evidence: backend §6.1 step 7, §6.2 (audit events); [`security.md`](../okf/capabilities/security/security.md) row
"Tamper-evidence of the audit log" (checked against `platform/inspecto-access` `AuditTrail.java`).
Confidence: DOCUMENTED for the events; the absence of a chain on the ledger is a stated gap (see Section 8).

**Q5. How do people sign in? What does the demo use versus production?**
The demo uses Demo User authentication: a picker of seeded personas (analyst, manager, admin) on a local server.
That is a token relay, not a real identity provider. Production uses OIDC against the customer's IAM; the host-side
OIDC authenticator exists and is specified, but the Link Analysis standalone app's OIDC sign-in has never been run
against a real IAM.
Evidence: feature page "la-app signs in over Demo User auth"; [`security.md`](../okf/capabilities/security/security.md)
section 3.4; `docs/BACKLOG.md` `LA-APP-REAL-SIGNIN-1`.
Confidence: DOCUMENTED for OIDC; real-IAM sign-in is NOT YET verified (Section 8).

**Q6. Can the analyst approve their own large expansion? Who holds the second key?**
No. A large expansion (above the Space's thresholds) is held as a pending request, the requester cannot decide it,
and the approver inbox never lists the caller's own requests. Self-approval is refused with 403 even for someone
holding the approve capability, and when several approvers decide at once exactly one decision lands. In the
shipped demo `fm.manager` holds the approve capability and `admin` holds reveal.
Evidence: feature page "Oversight surface" and "Approver inbox"; `ControlApiInvestigationOversightTest
.aSensitiveExpandWaitsForADifferentPersonsApproval`; `ControlApiInvestigationFourEyesRaceTest`; demo readiness plan
section 0.
Confidence: PROVEN.

**Q7. What are the "embed links", and could one leak an Investigation?**
A lead can mint a read-only link to ONE Investigation for a person with no login. The token is 256 random bits,
shown once, and only its hash is stored; it follows the Space's masking, shows no reveal, drafts, Case, Dataset names
or SQL, expires (default 72 hours, a Space may allow up to 30 days by default and up to 8760 hours at most) and dies
automatically if the minter loses the lead role, the Case closes or masking is loosened. Every bad, expired or
revoked token gets the same "not found". The feature is OFF by default (`-Dla.embed.enabled`) and the answer is
GET-only with no cookie and no framing.
Evidence: backend §6.2; `ControlApiInvestigationEmbedTest` (expiry, kill switch, same-404, auto-revoke, token in no
log or audit row).
Confidence: PROVEN for the API. There is no viewer page yet (Section 8).

## 2. Data handling

**Q8. Where does the data live? Does it leave the server?**
Source data stays in the customer's own Datasets on the host; Link Analysis reads it in place or from a derived
Parquet link index on the same disk. The Investigation record (op log, sealed Working Sets, Dossier inputs) is kept in
the Space's write root on the filesystem, or in the customer's PostgreSQL in Preview and Enterprise. The
standalone app is a second UI served by the same host process, not a separate service, and nothing is sent to an
external service.
Evidence: backend §1, §3.2, §4 (index under `<writeRoot>/la-index/`), §8.
Confidence: DOCUMENTED (the architecture; no external-egress test is cited here).

**Q9. What does "never store values" mean for ingest?**
When data is ingested, error and failure text must not carry the offending cell. The engine's messages quote the bad
value, so the platform replaces every quoted literal and "original line" tail with a salted fingerprint, and
file-name handling avoids raw paths. The aim is that a rejected record leaves a reason, not a copy of the personal
data.
Evidence: [`ingestion.md`](../okf/capabilities/ingestion/ingestion.md) "Failure text never stores a value"
(`FailureText.scrub`); [`spaces.md`](../okf/capabilities/spaces/spaces.md) (malformed-row rejects).
Confidence: DOCUMENTED (shipped 2026-10-03; this pass did not re-run it). Scope is ingest failure text, not a claim
that no personal data exists anywhere.

**Q10. How is data classified, and does classification drive masking?**
Columns carry a classification from the Dataset registry and lineage. In `typed` mode a column is masked when an
Entity Type claims its classification; untraceable lineage fails closed (masked). A Space can also refuse to mint an
embed link when the strictest classification of the Investigation's columns is on its refusal list.
Evidence: backend §3.4 and §6.2; `ControlApiInvestigationOversightTest
.aColumnClassificationIsTypedByTheEntityTypeClaimingIt`; `ControlApiInvestigationEmbedTest
.aSpaceCanRefuseToShareInvestigationsOfAClassificationAndAPolicyCanForbidTheActionEmbed`.
Confidence: PROVEN.

**Q11. How long is data kept, and how is it deleted?**
There is deliberately no purge today: the Investigation store is append-only evidence, with no retention period,
purge task or legal-hold record (operator decision D-U8, 2026-09-24). Drafts are different: they hibernate after
60 minutes and expire after 30 days by default, as a discard, with at most 50 open per Space. If a regulator
requires deletion, that decision is to be reopened, copying the existing incident purge pattern. There is also no
Investigation delete route today.
Evidence: feature page D-U8; backend §3.3 (Draft defaults) and §6.2 ("no Investigation delete or close route").
Confidence: DOCUMENTED. A retention and deletion capability for Investigations is NOT YET built.

**Q12. What copies of the data can leave the system, and are they masked?**
Exports a person can make are the Dossier (JSON or self-contained HTML), the sealed Dossier bundle with references, and
snapshots attached to a Case. Ids in them are masked as the Space masks them; the manifest hashes the raw store, so
the root is identical masked or not and a masked bundle still verifies. A Working Set is capped at 64 MiB per set
and 4 GiB per Investigation by default (413 above, audited).
Evidence: feature page (Dossier bundle: "Masking, R3, four-eyes"); `ControlApiDossierTest
.theHtmlDossierIsMaskedLikeTheJson`; `ControlApiDossierBundleTest`; settings in
[`configuration.md`](../okf/backend/config/configuration.md) (the Link Analysis Investigations row).
Confidence: PROVEN for masked output; DOCUMENTED for the size caps.

## 3. Integrity and evidence

**Q13. What is "sealed", and what does "tamper-evident" actually mean here?**
Each analyst step is appended to an op log and the resulting Working Set is sealed with a SHA-256 hash; the log is
folded deterministically, so replay reproduces the same set. The Dossier adds a manifest hashed over every stored
artefact, and Verify rebuilds it from the store and names anything changed, missing or added. "Tamper-evident"
means an edit made inside the store after the fact is detected when someone verifies; it does not mean the store
cannot be edited.
Evidence: backend §3.1; feature page Dossier section; `ControlApiDossierTest
.anUntouchedStoreVerifiesAndATamperedStepIsReported`, `.theManifestCatchesWhatTheRecordedHashesCannotAndCannotBeEdited`.
Confidence: PROVEN.

**Q14. What does tamper-evident NOT mean? Could an administrator with full disk access rewrite history?**
Correct: it is not a signature and not a write-once store. Our reading of the design is that someone who can rewrite
every file AND recompute every hash consistently would produce a store that verifies against itself; the defence is
to keep the Dossier root (or a sealed bundle) outside the store, for example attached to a Case, and verify
against that. The identity fact log is a hash chain, and a truncated tail is caught only if a head hash was cited
elsewhere. We have not run a test of the "rewrite everything consistently" attack.
Evidence: backend §3.5 (fact log), feature page Dossier ("the manifest remains the custody root"). The
full-rewrite reasoning is ours and is UNVERIFIED by a test.
Confidence: DOCUMENTED, with the attacker-model limit above stated openly.

**Q15. If the data changes afterwards, does an old Investigation change?**
No. Expands store the rows they read, and compare and temporal steps store their findings with a fingerprint, so a
replay needs no Dataset and a Dataset change cannot rewrite history. *Re-read (drift check)* compares against the
current Dataset and reports drift, for example when a file lands in the bound Dataset. A stored Alert watches the
sealed Working Set, not live data.
Evidence: backend §3.1 (sealed reads, D-E3); `ControlApiReplayDriftOnFileAppendTest`; user manual §12.
Confidence: PROVEN.

**Q16. How are a team's parallel experiments kept from corrupting the record?**
An analyst works in a private Draft forked from the Investigation. Promotion rebases onto the current head, reports
conflicts, and appends to the main log under a lock, all or nothing; if the Draft carries a sensitive expansion it
becomes a four-eyes request instead. The main log is never rewritten by a Draft.
Evidence: backend §3.3; feature page (Drafts, rebase and promote).
Confidence: DOCUMENTED (Draft behaviour is covered by tests in the module; the browser UI for Drafts was driven
once, per the readiness plan).

## 4. Analytics

**Q17. What algorithms are there, and where do they run?**
The catalogue holds 29 graph algorithms (communities, centrality, paths, similarity, a suspicion score and a
propagated-risk score among them). Over a Working Set they run in-process on the server in a bounded pool; over a
link index only a few (neighbourhood, ego network, seeds-only degree) run, as SQL. The server routes by input type,
never by size, and says when a budget is exceeded rather than returning a partial answer.
Evidence: backend §5; `ControlApiGraphRunTest.theCatalogueListsAll29WithCeilingsAndTheSpacesDefaultsClampedAndEchoed`.
Confidence: PROVEN.

**Q18. Is a result the same every time? Does the screen agree with the server?**
Results are deterministic: ties break by a fixed canonical order, and the Java and TypeScript implementations
assert the same hand-derived fixtures. The result cache is keyed on the relation, row scope, algorithm and
parameters, and only completed results are cached.
Evidence: backend §5 (Parity); `GraphAlgorithmsParityTest`, `GraphSuspicionParityTest`, `ControlApiGraphRunParityTest`.
Confidence: PROVEN for the fixtures; a statement about every input is not claimed.

**Q19. Can you explain why an entity scored high? What about false positives?**
The suspicion score is an explainable 0 to 100 composite with a per-node breakdown, and the top decile is
highlighted. Propagated risk returns each node's top five contributing factors (origin, distance, weight,
contribution). The scores are triage aids: a high score means "look here first", not "guilty", and we publish no
false-positive or precision rate, because that depends on the customer's data and thresholds.
Evidence: user manual section 6 (Suspicion score row); backend §5 (`propagatedRisk`);
`ControlApiGraphRunTest.propagatedRiskAnswersWithScoresAndFactorsAndRefusesBadWeights422`.
Confidence: PROVEN for explainability. Precision on real data: NOT YET measured.

**Q20. What does the demo corpus show, and is it realistic?**
It is synthetic. The shipped mule-ring corpus has 112 accounts over three landed days; the pass-through Measure
flags 13 of them, and a cash-out concentration Measure shows the till `TILL-06` receiving 97 percent of cash-out
value from 8 payers. A test reproduces those figures.
Evidence: user manual section 10; `ControlApiLaShowcaseFiguresTest` (asserts `TILL-06`, share 0.97).
Confidence: PROVEN for the synthetic figures; they say nothing about real-data accuracy.

## 5. Scale and performance

**Q21. How big a graph can it handle?**
The server-side path search over a Dataset is certified at about one million links. Above that a Space can switch
on the link index, a versioned Parquet layout, measured on one laptop up to 10^8 edges: a one-key lookup stays near
20 ms at 10^8 (it is flat in size) and a depth-2 walk of a small frontier is about 22 ms median. 10^9 edges is an
extrapolation (an index of about 34 GB and a build of roughly 2.5 hours) and has NOT been run.
Evidence: feature page measurement table (10^6, 10^7, 10^8 rows) and "Extrapolating to 10^9 edges ... NOT measured";
backend §10; `docs/superpower/link-analysis-roadmap.md` (`LA-INDEX-SCALE-MEASURE-1`, 10^9 open).
Confidence: PROVEN to 10^8 on a synthetic corpus; 10^9 is NOT YET.

**Q22. What happens when someone asks for too much?**
It is refused or limited out loud, never silently trimmed. A Graph Run has a size and time budget (shipped default
50,000 nodes, 500,000 edges, 30 seconds; Space ceiling 500,000 nodes, 5,000,000 edges, 300 seconds) and ends
`BUDGET_EXCEEDED` with no result when exceeded. The Run pool is 2 running plus 16 waiting; the next request beyond
that is refused with 503. Expensive exploration calls share a per-person bucket of 30, refilling one per 2
seconds (429). Large expansions go to four-eyes.
Evidence: backend §5 (Budgets, Pool), §6.1 step 2, §9; `ControlApiGraphRunTest
.aBudgetExceededRunIs200WithTheNumbersAndNeverAResultKey`; `LinkAnalysisRateClassCoverageTest`.
Confidence: PROVEN for the budget refusal; DOCUMENTED for the exact pool and queue figures.

**Q23. How does the link index work, and does it go stale?**
It stores each edge twice (by source and by target), in Parquet partitioned by a hash bucket, so a one-entity lookup
reads a small slice. Reads fall back to the plain Dataset read, with a stated reason, when no fresh index exists. For
a Dataset whose input files can be fingerprinted, a stale index is served and flagged. For one that cannot (a view),
the index serves only while younger than 24 hours by default (`index.max_age_hours`, 1 to 8760). The index serves
only shallow lookups (depth 2, at most 20 keys per level); deep or global algorithms use the other paths.
Evidence: backend §4, §9; feature page scope note (Path A, the 5-level walk FAILED its 1.5 s criterion).
Confidence: PROVEN for the measured cases; the age gate is DOCUMENTED.

**Q24. What are the known performance weak spots?**
At about 10^6 edges the index is not faster than the flat read, because each request pays a fresh sealed
connection of roughly 100 to 200 ms; it wins from about 10^7. Lookup cost grows with the degree of a hub entity
(the frontier cap bounds how many keys, not how heavy one key is). A 10^8 build took 447 seconds in the later
measurement under an 8 GB cap. Index build memory and time are laptop numbers, not a sizing guide.
Evidence: feature page "Where the index loses" and measurement table; backend §10.
Confidence: PROVEN as measured; sizing for a customer environment is NOT YET done.

## 6. Deployment and operations

**Q25. Which editions include it, and what is installed?**
Link Analysis is optional jars discovered at startup, not a separate server. It is present from Standard upward
(Standard is a Professional alias in the bundler), absent in Personal (the routes answer 503), and the PostgreSQL
Investigation store and the Enterprise policy engine are Preview and Enterprise only. Per Space it needs the
`geoLink` module enabled in `modules.toon`.
Evidence: backend §8 and §6.1 step 4; `ControlApiGeoLinkModuleGateTest`.
Confidence: PROVEN for the module gate; DOCUMENTED for packaging.

**Q26. Can it run on several servers for scale or high availability? Be honest.**
Not yet as a finished capability. A shared run lease exists and was proven against a real PostgreSQL, including a
handover between two processes on one laptop; a handover between two hosts, a standby promotion and a network
partition are NOT proven. The Kubernetes scale-out plan's own status line is stale: it says nothing is built, but the
lease is, and the extent of its Phases A and B has not been audited (row `SCALEOUT-PLAN-AUDIT-1`). For Link
Analysis specifically, the Postgres Investigation store is built, but embed links are filesystem-only (a Postgres
store answers 503 for them).
Evidence: [`ha-dr-drill-runbook.md`](ha-dr-drill-runbook.md) "What has actually been proven" and the laptop-scale
drill; [`enterprise-scale-out-plan.md`](../superpower/enterprise-scale-out-plan.md) status header;
`docs/BACKLOG.md` `SCALEOUT-PLAN-AUDIT-1`; backend §6.2.
Confidence: NOT YET for multi-pod; PROVEN only for the single-database lease at laptop scale.

**Q27. How do we back up, restore and upgrade? Can it run air-gapped?**
Backup and restore are covered by a runbook (Standard and above), including restoring into a new Space. Investigation
data on the filesystem store is ordinary files under the write root; on Postgres it is one schema per Space. The
product is self-contained and the bundle's install checks are specified to run offline. We have not run a
documented upgrade rehearsal for Link Analysis specifically.
Evidence: [`backup-restore-runbook.md`](backup-restore-runbook.md); `docs/okf/backend/config/configuration.md`
(Investigation store row); the preflight row in `docs/BACKLOG.md` (`SCR-1`, offline).
Confidence: DOCUMENTED. A Link Analysis upgrade and restore rehearsal: NOT YET (UNVERIFIED).

## 7. Integration

**Q28. What data can it read today?**
Files and CSV through the file Collectors, relational databases through the `db` Collector (a query with a
`:watermark`), and Parquet landing. Hive is not supported directly: the agreed route is to extract to Parquet files
and land them through a Collector, and there is no Hive or Kerberos connector. Dimension data (names, risk
indicators) is joined at read time and kept out of the index.
Evidence: `docs/BACKLOG.md` `LA-DAILY-INGEST-1` ("Confirmed today ... no Hive/Kerberos support"; decisions D-ING5,
D-ING6).
Confidence: DOCUMENTED; Hive is NOT YET.

**Q29. How do findings reach our case and alerting tools?**
An Investigation can be linked to a Case (the Case's owner or assignee then gets read-only access), a graph or
snapshot can be attached to a Case, and a standing detection watches a Measure and fires an Alert. A fired Alert is
aggregate only (Measure, threshold, breach count, worst value); entities are named only inside the Investigation
under its own gates. External references to other systems are stored as text and never opened.
Evidence: backend §7 (live detection); user manual §9.7, §9.12; `la.detect` Job in backend §7.
Confidence: DOCUMENTED (driven live in the 2026-10-12 telecom demo runs per the backlog; no single test cited).

**Q30. Is there an API, and is it controlled?**
Yes: 82 Link Analysis routes (76 in `la-api`, 6 in `geo-link`), all in the OpenAPI contract, each behind the same
gate order (authenticate, rate limit, authorize, module enabled, capability, Investigation access). A guard test
keeps the route modules behind the module gate.
Evidence: backend §6 and §6.1; `LaApiRoutesManifestParityTest`; `ControlApiGeoLinkModuleGateTest`.
Confidence: PROVEN. (The readiness plan still says 75 routes and 28 algorithms; those figures are stale.)

## 8. What we do NOT claim, and what is not yet

Say these before anyone asks.

* **Real-IAM sign-in is not verified.** The standalone app has only run over Demo User auth. (`LA-APP-REAL-SIGNIN-1`,
  security spec section 3.4.) Confidence: NOT YET.
* **Embed links (read-only Investigation view)** are built and driven live on both the file and the Postgres store (viewer
  page, frame headers, revoke); a few edge paths are unit-tested only (`LA-EMBED-VIEW-1` remainder). Confidence: PROVEN
  on a demo bundle; OFF unless the host runs with `-Dla.embed.enabled=true`.
* **No Hive or Kerberos connector;** feeds go in as files or database queries. Confidence: NOT YET.
* **Slowly changing dimensions (SCD2) are deferred.** The setting is accepted but never honoured (decision D-ING4).
  Confidence: NOT YET.
* **Multi-pod operation is not complete** (Q26); a two-host drill has not been run. Confidence: NOT YET.
* **10^9 edges is not measured,** and measurements are from a laptop on a synthetic corpus. Confidence: NOT YET.
* **The general audit ledger is not hash-chained** (Q4), and the Investigation seal is not a signature (Q14).
* **No retention or purge for Investigations** (Q11).
* **Accessibility:** the WCAG 2.2 AA audit is at specification and automated-scan level; the Link Analysis items
  were checked with axe in a browser with two vendor grid findings left, and a full assistive-technology audit is
  not done (`LA-A11Y-AUDIT-1`). Confidence: NOT YET.
* **No published precision or false-positive rate** for any score (Q19).

---

## Presenter notes

* Never promise a number that is not in this page; point to the Evidence line instead.
* If asked about a claim marked NOT YET, answer with the row id and say it is on the board.
* Run the pre-demo checks in the demo runbook first; this page does not replace them.

## Discrepancies between references, found while writing this page

These are in the repo itself; this page follows the figure named, and the others should be corrected at source.

1. Algorithms: 29 in code and the backend page; the readiness plan's defence matrix says 28.
2. Routes: 82 in the backend page; the readiness plan says 75.
3. `TILL-06` share: the manual and `ControlApiLaShowcaseFiguresTest` say 97 percent; the readiness plan's DR-T6 says 82 percent.
4. 10^8 build: the feature page's first spike says 368 s (17.9 GB peak), the later table says 447 s (8 GB cap), and the roadmap says "24 min under an 8 GB cap". Index size also differs (3.43 GB versus 2.23 GB across the two measurements). This page quotes the later table.
5. Accessibility: the stakeholder framing "spec-level" versus the board and feature page, which record axe runs in a browser.

## Claims not verified in this pass (UNVERIFIED)

* The 19th concurrent Graph Run being refused 503: stated by backend §5; no test method was located by name.
* Q14's "rewrite everything consistently" reasoning is ours; no test.
* Q16, Q29: no single test class was identified for Drafts end to end or for the standing-detection Alert path.
* Q8: that nothing leaves the host: design statement; no egress test.
* Q27: a Link Analysis upgrade or restore rehearsal.
