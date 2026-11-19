---
type: Concept
title: Connection and Collector
description: What a Connection and a Collector are — how to reach a remote system, and what to fetch from it and when — and how they feed a Pipeline.
item: module.elt
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: platform/inspecto-etl
---

# Connection and Collector

## What they are

A **Connection** is a named way to reach a remote system: where it is, and the credentials to get in. It can point
to an SFTP, FTP or FTPS server, a database, or a local folder. A Connection says **how to reach** a system, never
what to take from it.

A **Collector** is a collection task that uses one Connection: **what** to fetch (file paths, file name patterns or
a database query), **how often**, and how to recognise a file it has already fetched so it is not taken twice.

```
  Connection "Billing SFTP"          how to reach it: host, port, user, secret
     ├─ Collector "Daily CDR files"     what: /out/cdr/*.csv  ·  when: every 15 minutes
     └─ Collector "Monthly invoices"    what: /out/inv/*.xml  ·  when: on the 1st
                     │
                     ▼
               Pipeline reads what was collected
```

## Why they are two things

One remote system usually delivers many kinds of files. Keeping the Connection separate means you type the address
and credentials once and reuse them in many Collectors. When a password changes, you change one Connection.

## How they relate to their neighbours

- A **[Pipeline](user-guide-concept-pipeline.md)** reads what a Collector fetched and turns it into a
  **[Dataset](../../layer/product/user-guide-concept-dataset.md)**.
- Each Collector run shows up in **Runs**, with the files it fetched.
- *Add data* is the short way in for a single file, an import folder or a PostgreSQL table snapshot; it needs no
  Connection or Collector of your own.
- Connections and Collectors belong to one **[Space](../../module/config/user-guide-concept-space.md)**.

## What you do with them in the console

- **Connections** (in **Settings → Connections**): create one from a form, with optional SSH tunnel and proxy
  sections. Use **Test** to check it can connect, and the probe and sample actions to see which paths or tables it
  can reach. Secrets are stored as references, never shown in clear.
- **Collectors** (in **Platform → Workbench**): create one on a Connection, set its paths or query, schedule and
  file patterns, and use **Run now** to collect once.
- The **Streams** and **References** tabs of **Catalog → Data Catalog** describe where data comes from: a Stream for events and facts, a
  Reference for lookup data. *Onboard Stream* opens the Pipeline editor in a guided mode — Collect, Parse, Schema,
  an optional Enrich, then Publish — so you can set up collection and parsing in one place.

## Which edition has it

*Add data* (upload, import folder, PostgreSQL snapshot) is in every edition. A new Connection to a remote system
(SFTP, FTP or FTPS, a database), and *Keep this up to date* on a schedule, need Standard or Advanced. Streaming inputs (Kafka) come with the **Premium Connectors** Add-on, offered from Standard. See
[The editions on one page](../../../../EDITIONS.md) and [Edition and Add-on](../../layer/product/user-guide-concept-edition-addon.md).

## Read next

- [Pipeline](user-guide-concept-pipeline.md)
- [Dataset](../../layer/product/user-guide-concept-dataset.md)
