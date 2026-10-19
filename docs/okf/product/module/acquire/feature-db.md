---
type: FeatureDb
title: Acquisition and collectors — feature database
item: module.acquire
description: Generated feature list for module.acquire — features, routes, config kinds and processors, with Editions and add-ons.
tags: [feature-db, module]
---

# Acquisition and collectors — feature database (`module.acquire`)

> **Generated** by `tools/render-feature-db.mjs` from the module manifests, `offerings/*.toon`, the processor catalog
> contract and `docs/FEATURE_INVENTORY.md` — never edit this table; change its sources and re-run the tool. The whole
> product is in `target/product-docs/feature-db.csv` (sortable). Hub: [index](index.md) · Product Map Item
> [`module.acquire`](../../../../../product-map/module/acquire.toon).

4 row(s).

| feature | type | module | editions | addon | status | key | source |
|---|---|---|---|---|---|---|---|
| DB-export source (JDBC + watermark) | inventory | acquire | basic;standard;advanced;preview |  | ga | `connection: { connector: db, options: { watermark_column: updated_at } }` | `docs/FEATURE_INVENTORY.md §1H` |
| FTP passive port range (NAT) | inventory | acquire | basic;standard;advanced;preview |  | ga | `options: { passive_ports: "10000-10100" }` | `docs/FEATURE_INVENTORY.md §1H` |
| FTPS (TLS) | inventory | acquire | basic;standard;advanced;preview |  | ga | `options: { tls: explicit }` | `docs/FEATURE_INVENTORY.md §1H` |
| SSH host-key pinning | inventory | acquire | basic;standard;advanced;preview |  | ga | `options: { host_key: "SHA256:<base64>" }` — a **fingerprint** (SHA256/SHA1 base64, or MD5 colon-hex); a raw `ssh-rsa AAAA…` key line is REJECTED. Use `{ known_hosts: … }` to pin from an OpenSSH file. | `docs/FEATURE_INVENTORY.md §1H` |
