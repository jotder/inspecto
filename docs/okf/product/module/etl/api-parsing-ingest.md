---
type: Reference
title: "Parsing and ingest — API"
description: "Control-plane routes for Parsing and ingest, with their gates."
tags: [etl, parsing-ingest, api]
timestamp: 2026-10-19T00:00:00Z
---

# Parsing and ingest — API

Routes for Parsing and ingest. Design and internals: [Parsing and ingest architecture](architecture-parsing-ingest.md).

| Route | Purpose |
|---|---|
| `GET /parsers`, `POST /parsers/{id}/preview`, `/parsers/asn1/profile` | parser list and compute-only preview |
| `POST /config/preview/parsing`, `POST /config/preview/schema` | preview the parse and the cast of a draft |
| `GET /runs/{name}/batches`, `/files`, `/lineage`, `/quarantine`, `/commits`, `/outputs` | Run-detail ledgers |
| `GET /runs/{name}/errors?file=` | the reject rows for one file (bounded, reports `truncated`) |
| `POST /runs/{name}/trigger`, `/pause`, `/resume`, `/reprocess`, `/replay-rejects` | operator actions |
