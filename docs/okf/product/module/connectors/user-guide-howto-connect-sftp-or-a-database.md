---
type: How-to
title: "Connect SFTP or a database"
description: "Make a Connection to an SFTP server and collect its files into a Dataset on a schedule, or copy a PostgreSQL table into a Dataset — and what the console cannot do yet for databases."
item: module.connectors
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [providers/inspecto-connectors, inspecto-ui/src/app/inspecto/connections]
---

# Connect SFTP or a database

## Goal

Bring data in from another system: files from an **SFTP** server, kept up to date on a schedule, or a table from a
**PostgreSQL** database.

## Before you start

- **Edition:** remote Connections (SFTP, FTP/FTPS, databases) and *Keep this up to date* need Standard or higher.
  Copying a PostgreSQL table with Add data works in every edition.
- **Who:** creating a Connection needs `canOnboardConnections` (administrators); collecting from it needs the right
  to upload data (`canUploadData`) or to author Pipelines.
- **What you need:** the host, port, user and a way to sign in. Passwords travel only as a reference to an environment
  variable on the server, such as `${ENV:SFTP_PW}` — set it before you start. A private host may also need to be on
  the Space's egress allowlist (**Settings → Egress Allowlist**).

Concept: [Connection and Collector](../elt/user-guide-concept-connection-collector.md).

## Steps

### SFTP

1. Open **Settings → Connections** and click **New connection**.
2. Choose **Connection type** SFTP and fill **Host**, **Port** (22), **Username**, **Auth method** (password or key),
   the password as `${ENV:…}` or a **Private key path**, the **Remote directory** and **Strict host-key checking**.
3. Click **Test connection**, then **Continue**, give it a **Name** and **Create**.
4. Collect from it. Either open a Dataset made with Add data (**Platform → Catalog → Datasets**, its card), choose
   **Keep this up to date**, pick the Connection, a **Ready marker** if the sender writes one, and **Check** every 5 or
   15 minutes, every hour or every day, and click **Keep this up to date** — or, in a Pipeline's Collector Step,
   choose **Collect from → Connection** and the Connection.

### A PostgreSQL table

1. Open **Add data** ([Add a file](../../layer/product/user-guide-howto-add-a-file.md), step 1) and choose
   **A Postgres table**.
2. Fill host, port, database, user and the password as `${ENV:…}`, click **Test connection**, pick the table or write
   a query, and **Read the rows**.
3. It is a **snapshot**: refresh it with **Refresh now** on the Dataset's card.

## Check it worked

The Connection exists and answers (here one named `partner_sftp`). **Test** proves the host is reachable and the
password reference resolves — not that the sign-in is accepted; the first collection proves that.

```check
GET /spaces/{space}/connections
as builder
expect status 200
expect data[*].id has partner_sftp
```

```check
POST /spaces/{space}/connections/partner_sftp/test
as builder
expect status 200
expect data.reachable = true
```

## What can go wrong

- `connection '<id>' already exists` — choose another name, or edit the existing one.
- `egress refused by the Safety Policy: sftp connection '<id>' -> host:22` — the host is not on the egress
  allowlist.
- *Keep this up to date* refuses a Connection that is not SFTP, or answers that it needs Standard or higher.
- The password is shown as typed only when it is a `${…}` reference; any other value is refused or masked.
- **A database Collector cannot be made in the console yet.** The Database Connection type has no field for the
  query and export name a database Collector needs, so saving one for collection is refused (`needs options.query`).
  From the console, a PostgreSQL table comes in only as the Add data snapshot above.

## Next

- [Watch a folder](../elt/user-guide-howto-watch-a-folder.md)
- [Build a Pipeline from a sample file](../elt/user-guide-howto-pipeline-from-a-sample.md)
