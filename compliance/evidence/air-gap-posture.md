# Air-gap / no-egress posture (ISO 27001 8.12, NFR-4)

**What this document is:** the data-leakage-prevention writeup for ISO 8.12, grounded in the code
2026-08-30. The posture is a genuine differentiator — but it is a **packaging** guarantee, not a
runtime network control, and the difference is the whole substance of the claim.

**Requirement:** `docs/REQUIREMENTS.md:262` — *NFR-4 Air-gap operation: "Full function without egress;
hosted-AI SDKs physically absent; offline basemap/geocoder"* (SHIPPED).

---

## 1. The claim, scoped precisely

> The standard distribution **physically cannot call hosted AI or cloud LLM endpoints**, because the
> SDK classes for those providers are absent from the classpath — verified by an automated test, not
> asserted by policy. Enabling hosted AI requires deliberately adding a **separate optional module**
> at build time: an explicit, auditable choice, not a runtime configuration flag an operator can trip
> by accident.

⛔ **Do not extend this to "the application enforces a network air gap."** It does not. There is no
outbound firewall, proxy allowlist, socket interceptor or DNS control anywhere in the application.
Network-level isolation remains the operator's deployment responsibility.

---

## 2. What enforces it

| Mechanism | Evidence |
|---|---|
| Hosted-provider SDK classes are **absent from the classpath**, proven by test | `inspecto-agent/src/test/java/com/gamma/agent/EgressGuardTest.java:14-30` asserts `ClassNotFoundException` for `dev.langchain4j.model.openai.OpenAiChatModel`, the Anthropic / Google / Vertex models, and `com.openai.client.OpenAIClient` |
| `inspecto-agent` ships **local-only** model support and excludes the hosted SDK transitively | `inspecto-agent/pom.xml:46-58` — `langchain4j-core` + `langchain4j-ollama` only, with `langchain4j-open-ai` explicitly excluded; the comment states the intent: *the air-gapped guarantee stays a packaging fact* |
| Hosted providers live in a **separate optional module** behind an SPI | `inspecto-agent-hosted`, via `HostedProviderPlugin` (`inspecto-agent/src/main/java/com/gamma/agent/model/HostedProviderPlugin.java:8-13`); absent that jar, `ModelProviderFactory` finds no plugin |
| Both AI modules are optional and edition-scoped | root `pom.xml:19-25, 54-57, 66-82` (`edition-standard` / `edition-enterprise` profiles) |
| The RAG embedding model runs **in-JVM with weights bundled in the jar** — no network at ingest | `inspecto-intelligence/src/main/java/com/gamma/intelligence/pack/InspectoKnowledgeSources.java:11-17`; corpus is one local file, `docs/GLOSSARY.md` |

**This is the differentiator worth stating to an auditor:** absence-by-packaging is a stronger control
than a runtime toggle, because it cannot be misconfigured at deploy time and it is verifiable by
inspecting the shipped artifact. It is also testable, and it *is* tested.

---

## 3. The honest boundary ⚠

What a careful reader will ask, answered plainly:

- **No runtime egress control exists.** The "guard" is a classpath fact. If an operator deploys the
  enterprise edition *with* `inspecto-agent-hosted`, or drops a hosted SDK jar onto the classpath
  themselves, the physical-absence guarantee no longer holds and **nothing in the running application
  detects or blocks it**. The control is build-time, and build-time only.
- **Local model access is still a network call.** Ollama is reached over loopback/LAN. "Local" here
  means *not a third-party hosted endpoint*; it does not mean *no sockets*.
- 🔴 **The product makes deliberate outbound connections where an operator configures them.** The
  Kafka connector (`inspecto-connectors/src/main/java/com/gamma/acquire/connectors/KafkaConnector.java`,
  `KafkaConnectorFactory`, `KafkaConnectionWorkbench`) connects to whatever brokers the operator
  configures. That is data acquisition working as designed — but a data-leakage-prevention statement
  that omitted it would be incomplete, and an auditor who found it independently would rightly
  discount the rest. Acquisition connectors generally are operator-directed network I/O.
- **Scope of the claim is the AI/LLM egress path**, which is where the "no hosted SDK" guarantee
  applies — not a blanket statement about all network activity.

---

## 4. What the operator still owns

The application's contribution is that it does not *initiate* hosted-AI egress. Everything that would
make a deployment genuinely air-gapped belongs to the operator: host and network firewalling, egress
filtering, choosing an edition/bundle without `inspecto-agent-hosted`, and controlling what connector
endpoints are configured. State this division explicitly in any customer-facing version — a
certification claim that quietly attributes the operator's controls to the product is the kind an
audit is designed to find.

---

## 5. Review triggers

Re-verify when: a new optional module adds a network client · `EgressGuardTest`'s asserted class list
falls behind the providers `inspecto-agent-hosted` actually ships · a connector gains outbound
capability · anyone proposes citing this as a runtime network control.
