---
type: Reference
title: "Storage — API"
description: "Control-plane routes for Storage, with their gates."
tags: [entity-store, storage, api]
timestamp: 2026-10-19T00:00:00Z
---

# Storage — API

Routes for Storage. Design and internals: [Storage architecture](architecture-storage.md).

| Route | Purpose |
|---|---|
| `GET /system/operational-db`, `POST /system/operational-db/test` | store roster and connection test |
| `GET /db/catalog`, `GET /db/table`, `POST /db/query` | data browser over stores |
| `GET /config/{kind}/{name}`, `POST /config/write`, `POST /config/patch` | configuration read and write |
| `/components/{type}/{id}/versions`, `…/versions/{version}/restore` | component history |
| `GET /pipelines/{name}/history`, `POST …/history/{version}/restore` | Pipeline history |
| `GET` / `PUT /settings/pipeline-history` | Pipeline history depth |
