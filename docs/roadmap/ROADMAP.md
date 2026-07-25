# Inspecto — Forward Roadmap

**Status:** **§3 NEXT horizon, §6 sequence and vocabulary reconciled 2026-07-25** — §3.1–§3.3 have all shipped, and the banned term *Flow* is renamed to **Pipeline** per the binding [GLOSSARY](../GLOSSARY.md). The **§1 theme horizons and the §2 NOW horizon still read as of 2026-06-19** and need their own status pass (see §9). · **Companion:** [STAKEHOLDER_OVERVIEW.md](STAKEHOLDER_OVERVIEW.md) · **Engineering detail:** [../okf/backend/pipeline-graph/pipeline-graph-design.md](../okf/backend/pipeline-graph/pipeline-graph-design.md)

> **Timeline convention.** This roadmap sequences work into **Now / Next / Later** horizons and gives **relative effort** sizing (S/M/L). It deliberately does **not** assign calendar dates — the cadence is one minor release per milestone on the active line, and dates are set per planning cycle, not here. Where an item gates revenue or another item, that dependency is called out explicitly.

---

## 1. Strategic themes

| # | Theme | Why it matters | Primary horizon |
|---|---|---|---|
| T1 | **Commercial readiness** | Standard-edition security is the gate to selling into regulated buyers. | Next |
| T2 | **Breadth of ingestion** | Object storage and more native formats widen the addressable feed set. | Next |
| T3 | **Self-service authoring** | Visual Pipelines + AI assist move authoring from expert-only to operator-owned. | Now → Next |
| T4 | **Trust & transparency** | Provenance/lineage + conservation checks as a default operational guarantee. | Now |
| T5 | **Scale-out optionality** | Keep Enterprise distributed seams open without compromising the lean single-node core. | Later |

---

## 2. Horizon: NOW — in mainline, hardening toward the next release

These are built and integrated on the development line; the work remaining is verification, polish, and the release decision.

| ID | Item | Effort | State | Exit criteria |
|---|---|---|---|---|
| N1 | **Pipeline-graph platform** — authoring, validation, execution as first-class jobs, multi-source merge, incremental Pipelines, materialized views, visual editor | L | Built & tested | A representative `type: pipeline` job runs end-to-end against seeded data; visual editor verified live; design doc §14 closed |
| N2 | **Data-plane provenance** — per-edge counts, conservation invariant → managed alerts, Sankey overlay | M | Built & tested (off by default) | Provenance verified against a real Pipeline-job run (not just synthetic injection); overlay confirmed against recorded data |
| N3 | **`sink.view` consumer** — REST query of a Pipeline's logical views | S | Shipped in mainline | Done — `/views`, `/views/{name}`, `/views/{name}/data` live with tests |
| N4 | **Edition realignment** — auth-free common core | M | In mainline, uncommitted/ungated | Stakeholder go-ahead to commit/release; confirms the three-edition model is real |

**Now-horizon focus:** finish live end-to-end verification of N1/N2 with real job configs (current verification used synthetic data on a config-less dev backend), then make the release call on N4.

---

## 3. Horizon: NEXT — committed direction

Ordered by recommended sequence (see §6 for the rationale). **§3.1–§3.3 have all shipped since this
horizon was written** (2026-07-07 → 2026-07-24); their scope prose is kept as the record of intent, each
now headed by what was actually delivered. **§3.4–§3.5 remain the live NEXT items.**

### 3.1 `inspecto-security` module — Standard edition (T1) · Effort: **L** · ✅ **SHIPPED 2026-07-24**

> **Delivered.** `inspecto-security/` implements the core `Authenticator` / `Subject` / `TokenRelay` SPIs —
> `OidcAuthenticator` (Nimbus JOSE+JWT, JWKS-validated), `RoleMapper`, `KeycloakTokenRelay` — joining the
> reactor only under the `edition-standard` Maven profile. The browser never holds tokens (BFF exchange +
> httpOnly `inspecto_rt` cookie with an `Origin` CSRF check); HTTPS is served by the pure-JDK `HttpsServer`.
> **RBAC/ABAC completed 2026-07-24:** data-driven roles + a capability manifest, Access-Profile enforcement,
> component sharing, WSO2 gateway trust, and the Enterprise **`inspecto-policy`** module
> (`-Pedition-enterprise`) for authored Access Policies, seeded space isolation, and decision audit.
> Personal remains genuinely auth-free. As-built:
> [`../okf/backend/editions/auth-security.md`](../okf/backend/editions/auth-security.md); residual
> non-blocking opens in [`../BACKLOG.md`](../BACKLOG.md) §6.

The single most important item for commercialization.

- **Scope (in):** an `Authenticator` SPI seam in the core; an OIDC/OAuth2 **resource-server** implementation (validate IAM-issued JWTs — issuer/audience/expiry via JWKS); **RBAC + ABAC** enforcement from token claims/groups; HTTPS (keystore; FIPS-provider option for Gov); actor-attributed, tamper-evident audit; the Angular UI as an OIDC Authorization-Code-+-PKCE public client.
- **Scope (out, by design):** user management, AD/LDAP federation, SAML brokering — these are the **external IAM's** job (Keycloak / WSO2 / Okta / Entra). No identity store in the Java core.
- **Approach:** incremental hardening on the framework-free core — **explicitly not** a Spring/Quarkus migration. At target user counts a framework buys nothing the IAM + small libraries don't, and a lean dependency tree is a compliance asset.
- **Packaging:** delivered as the `inspecto-security` Maven module, assembled into the Standard build via a profile; Personal simply doesn't bundle it.
- **Dependency:** unblocks revenue. Should precede anything that needs per-tenant or per-role gating.
- **Exit criteria:** a Standard build authenticates against a reference IAM, enforces a role matrix, serves over HTTPS, and produces an actor-attributed audit log — with the Personal build unchanged and still auth-free.

### 3.2 Object-storage & network-share connectors (T2) · Effort: **M–L** · ✅ **SHIPPED (object storage) 2026-07-22**

> **Delivered (ACQ-4).** `s3` (AWS / MinIO / GCS-interop), `azure` (Blob + Azurite) and native `gcs`
> connectors on the existing `CollectorConnector` SPI in `inspecto-connectors/` — all three **SDK-free**:
> raw REST over `java.net.http.HttpClient` with hand-rolled SigV4 / Shared Key / service-account OAuth2 on
> plain JDK crypto, so no cloud SDK jar enters the build and it stays air-gappable. The etag/version
> follow-on landed with them (listing ETag → `RemoteFile.etag`, GCS `generation` → `RemoteFile.version`,
> consumed by `source.duplicate.mode: etag`, ACQ-7).
> **Not delivered:** the **NFS/SMB-CIFS** half of this item — there is no share connector; mounted shares
> are read through the local input path. As-built:
> [`../okf/backend/acquisition/connectors.md`](../okf/backend/acquisition/connectors.md).

- **Scope:** S3 / GCS / Azure Blob / MinIO and NFS/SMB-CIFS connectors on the **existing connector SPI**, in the `inspecto-connectors` module (keeping all new deps out of the core).
- **Leverage:** the embedded analytical engine already reads object storage natively; this is the most-requested ingestion gap; it reuses a proven SPI and the readiness/dedup/watermark machinery.
- **Follow-on:** **etag/version fingerprint dimensions** for richer dedup (depends on these connectors landing).
- **Exit criteria:** a feed collects from each new backend through the standard acquisition path (discover → validate → fetch → dedup) with metrics and gap detection intact.

### 3.3 Unified `parsing:` grammar + JSON/regex frontends (T2) · Effort: **M** · ✅ **SHIPPED 2026-07-07**

> **Delivered (ING-5).** The unified `parsing:` block — aliasing `csv_settings` / `processing.ingester` so
> existing configs keep working — plus the **JSON/NDJSON** and **text/regex** frontends, with no engine
> change. **Deferred:** LDIF block-records remain PROPOSED (`REQUIREMENTS.md` §3.2).

- **Scope:** promote today's frontends under one `parsing:` block (with `csv_settings`/plugin aliases so existing configs keep working), and add two new thin frontends producing rows for the shared backend:
  - **JSON** — wrap native JSON/NDJSON reads; lean on expression-mapping rules for nesting.
  - **text/regex** — read-text + split + named-group regex extraction; covers LDIF and flat XML (nested XML and binary stay on the plugin frontend by design).
- **Exit criteria:** a JSON feed and a regex feed each onboard via `parsing:` with no engine change; all existing delimited/fixed-width/plugin configs continue to pass unchanged.

### 3.4 Pipeline authoring polish & streaming (T3) · Effort: **M**

- **Scope:** round out the visual Pipeline editor; add a dedicated **run endpoint** for authored Pipelines (today they run via a job config); implement the **adapter stream-consumer runtime** for streaming sources (the land-then-ack seam exists; the consumer loop is the remaining piece).
- **Exit criteria:** an operator can author, validate, run, and observe a Pipeline entirely from the console; a streaming source lands records through the adapter with at-least-once semantics.

### 3.5 Config-authoring completion (T3) · Effort: **S**

- **Scope:** finish the config CRUD-from-body surface (a full listing/`PUT` route) so the assist agent's draft-only skills become one-click apply; jail the database temp directory in the safety validator.
- **Exit criteria:** every assist skill that produces a config can persist it through a validated endpoint.

---

## 4. Horizon: LATER — future / vision (demand-gated)

| ID | Item | Effort | Trigger |
|---|---|---|---|
| L1 | **Enterprise distributed tier** — shared-state backends (Postgres status store, object-store events, shared secrets), distributed scheduler coordination, work distribution, per-tenant ABAC | XL | A deployment whose scale or multi-tenancy actually exceeds the single-node design |
| L2 | **Richer "AI behind every screen" UX** — inline natural-language authoring across the console | M | **Promoted 2026-07-25 → AGT-6a, MoSCoW `Should`, scoped in [`../superpower/agt-6-plan.md`](../superpower/agt-6-plan.md) §3** — no longer demand-gated: it reuses the shipped L1 draft tools (no new backend capability) and local models suffice, so GPU availability is not a gate. Still listed here pending a horizon refresh of this table. |
| L3 | **Multi-step agent graphs** — provision → watch → roll back orchestration | L | **= AGT-6b**, scoped in [`../superpower/agt-6-plan.md`](../superpower/agt-6-plan.md) §4. Demand beyond the three code-defined seeded runbooks — **plus** one upstream prerequisite: the eoiagent per-tool `DryRunProvider` seam, without which a model-composed plan cannot be previewed per step. |
| L4 | **Push/event-notification discovery** — react to source-side notifications instead of polling | M | A source that emits change notifications |
| L5 | **Cross-unit parallelism / Stage-2 streaming** — finer-grained parallelism within a run | M | A workload bottlenecked on per-unit sequencing |

**Guiding rule for Later:** these are deliberately deferred against the single-JVM, crash-isolated ethos. The seams are kept open (stateless engine, pluggable stores, stateless-JWT auth), so none of them require a rewrite when pulled forward — only assembly.

---

## 5. Cross-cutting & continuous

- **UI platform currency** — track the Angular release train; sequence Material/grid deps with each bump.
- **Agent library bump** — adopt the latest reusable agent-kernel when convenient (optional; no behavior change for Inspecto).
- **Living documentation** — keep the operations source-of-truth and these stakeholder docs current with every behavioral change (repository-enforced).
- **Release discipline** — semantic versioning + conventional commits; one mainline; editions assembled per-build; guarded merge-forward (fixes land on the oldest supported line and flow forward; features land on mainline).

---

## 6. Recommended sequence & rationale

The sequence below was executed as planned: **3.1 → 3.2 → 3.3 all shipped in July 2026**, leaving 3.4–3.5
as the live NEXT band.

```
NOW (§2)                    DELIVERED (Jul 2026)                    NEXT                              LATER
─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
N1 Pipeline-graph (verify)  3.1 inspecto-security + RBAC/ABAC ✓  →  3.4 Pipeline authoring polish  →  L1 Distributed tier
N2 Provenance (verify)          [revenue gate cleared 07-24]             + streaming                    L2 Inline AI UX
N3 Views consumer ✓         3.2 Object-storage connectors ✓      →  3.5 Config-authoring           →  L3 Agent graphs
N4 Edition realignment          [NFS/SMB-CIFS still open]                completion                    L4 Push discovery
   (release decision)       3.3 Unified parsing / JSON / regex ✓                                       L5 Finer parallelism
```

**Why this order** (1–3 are now retrospective):

1. **Security first (3.1)** ✓ — it was the gating item for commercial deployment. Nothing else converted to revenue until a buyer could deploy securely. It was also self-contained (a new module behind an SPI), so it did not block other tracks.
2. **Object storage second (3.2)** ✓ — the highest-demand ingestion gap, lowest technical risk (proven SPI + native engine support), compounding the value of the acquisition framework already shipped.
3. **Parsing breadth third (3.3)** ✓ — widened the addressable feed set; modest effort; backward-compatible by construction.
4. **Authoring polish fourth (3.4–3.5)** — compounds the value of everything beneath it and is the visible face of self-service, but depends on the platform underneath being solid first. **This is now the front of the queue.**
5. **Distributed tier is demand-gated (L1)** — pulled forward only when a real workload requires it, never speculatively, to protect the lean single-node ethos.

---

## 7. Success measures

| Theme | Measure |
|---|---|
| Commercial readiness | A Standard build deployable against a reference IAM with a working role matrix and HTTPS; Personal build unchanged. |
| Breadth of ingestion | Number of source backends and parsing frontends onboarding with zero core change. |
| Self-service | Share of feeds authored/operated entirely from the console vs. hand-edited config. |
| Trust & transparency | Provenance enabled on production Pipelines; conservation alerts surfaced and triaged as managed objects. |
| Leanness preserved | Core fat-JAR size and core dependency count holding flat as connectors/editions grow. |

---

## 8. What is explicitly *not* on the roadmap

- **Separate edition branches/forks** — editions are build flavors; there is one mainline.
- **A web-framework migration (Spring/Quarkus)** — the framework-free core is a feature (small SBOM, fewer CVEs), not a gap.
- **Fine-tuning / model training** — off-the-shelf instruct models + retrieval + grammar-constrained decoding instead.
- **Distributed-by-default execution** — against the crash-isolated single-JVM ethos; available as the opt-in Enterprise tier only.

---

## 9. Known-stale sections (pending their own status pass)

The 2026-07-25 pass reconciled §3, §6 and the *Flow → Pipeline* vocabulary only. Deliberately **not**
restated, because each needs a status call rather than a doc edit:

- **§1 theme horizons** — T1 and T2 still read *Primary horizon: Next*, though their gating items (§3.1,
  §3.2) have shipped.
- **§2 NOW horizon** — N1/N2/N4 still read as of 2026-06-19. At least two are known to have moved:
  N1's live end-to-end verification is recorded as **RESOLVED 2026-07-07** in
  [`../REQUIREMENTS.md`](../REQUIREMENTS.md) §R1 (seeded `type: pipeline` run,
  `examples/06-serve/pipeline-job`), and N4's edition model shipped as real Maven build flavors
  (`-Pedition-standard` / `-Pedition-enterprise`). N2 provenance is genuinely still open — it needs a live
  feed (**OPS-5** in [`../BACKLOG.md`](../BACKLOG.md) §2).
- **Residual banned vocabulary** — the acquisition-entity sense of *Source* (⛔ → **Collector**) still
  appears in §3.2, §3.4, §4 L4 and §7; and [STAKEHOLDER_OVERVIEW.md](STAKEHOLDER_OVERVIEW.md) still uses
  *Flow* throughout (§5.3, §7, §10, the glossary entry, the architecture diagram). Both are larger passes
  than this one.

---

*For the business framing of these items see [STAKEHOLDER_OVERVIEW.md](STAKEHOLDER_OVERVIEW.md) §10. For engineering-level task detail see [../okf/backend/pipeline-graph/pipeline-graph-design.md](../okf/backend/pipeline-graph/pipeline-graph-design.md) §14.*
