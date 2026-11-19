# Inspecto Backlog Board

All 63 open rows of docs/BACKLOG.md as of 2026-10-18 night, after the residual sweep (0 P1 · 13 P2 · 50 P3), regrouped by module and graded by how much of the feature already exists. Rows that came from the competitive analysis carry a gap tag. The 13 externally gated items in BACKLOG §2 are listed at the end.

Not started design only, or build on request Partial core built, real work left Residual shipped, leftovers only Blocked waits on a decision, a run or an outside party

## Priority × maturity

## Competitive gaps → modules

From the competitive capability matrix (LATRO, Subex, Mobileum, Neural, TEOCO, Araxxe), every capability where Inspecto is partial or absent and that is in scope. Each gap now has a backlog row.

| Gap | Capability | Who has it | Inspecto | Module · row | Maturity |
| --- | --- | --- | --- | --- | --- |
| AA | Agentic investigation | Mobileum, Subex | P | AGENTIC-INVESTIGATION-1 P2 | Not started |
| I | Real-time / pre-call blocking | LATRO, Mobileum, Neural | N | REALTIME-DECISIONING-1 P3 | Not started |
| C | Re-rating / tariff validation | LATRO, Mobileum, Neural, Araxxe | P | PACK-RERATING-1 P3 | Not started plan written |
| Q | Partner settlement | Subex, TEOCO | P | PACK-PARTNER-SETTLEMENT-1 P3 | Not started plan written |
| H | Interconnect fraud (IRSF, Wangiri, CLI) | 5 of 6 | P | PACK-IRSF-WANGIRI-1 P3 | Not started plan written |
| V | Sales, marketing, churn | LATRO, Mobileum | N | PREDICTIVE-ANALYTICS-1 + PACK-SALES-MARKETING-1 P3 | Not started plans written |
| T | Regulator SAR / STR formats | LATRO | P | REGULATORY-SAR-STR-TEMPLATES-1 P3 | Partial engine shipped |
| U | 5G / IoT / cloud assurance | LATRO, Mobileum, TEOCO | N | PACK-5G-IOT-ASSURANCE-1 P3 | Not started plan written |
| E | Bypass / SIM-box detection | 5 of 6 | P | TELCO-BYPASS-SIMBOX-1 P3 | Partial |
| L | ML anomaly detection | LATRO, Subex, Mobileum, Neural | Y (statistical) | ANOMALY-ML-SCORING-1 P3 | Not started |

Out of scope by the matrix's own reading: signalling probes (F), test calls (G), identity / digital trust (W), network inventory (X), cybersecurity (Y), managed services (Z).

## By module

Priority Competitive Maturity

## Externally gated (BACKLOG §2, not counted above)

- ELT Phase 6 deletion half and X5 cross-lane drill-down: next MAJOR release
- D13 parser field tiers: onboarding-observation session
- OPS-5 provenance conservation: live-feed soak
- Release notes for the next MAJOR, X-Actor full removal
- Compliance program (NFR-7), SOC 2 Type II window, DATA-GOV-1 archive
- AGT-6b multi-step agent graphs: upstream approval gate
- Deployment topology live validation, E1 distributed tier, SPACES-FROM-PARTITION-MAP-1

Maturity grades are a judgement made from each row's own text; the board in docs/BACKLOG.md remains the source of truth.