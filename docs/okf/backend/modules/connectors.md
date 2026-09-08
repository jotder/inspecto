---
type: Module
title: Connectors Module (inspecto-connectors/)
description: Remote source connectors (SFTP/FTP/FTPS/DB) and all network dependencies, kept out of the core.
resource: inspecto-connectors/
tags: [module, connectors, network, sftp, ftp, jdbc]
timestamp: 2026-06-28T00:00:00Z
---

# Connectors Module (`inspecto-connectors/`)

artifactId `inspecto-connectors`. Holds **all network dependencies** (sshj + BouncyCastle for SFTP,
Apache commons-net for FTP/FTPS, the PostgreSQL JDBC driver, kafka-clients for the streaming collector,
and gson for the native GCS JSON API) so the [engine core](engine.md) JAR has none.

Drop this jar on the classpath and `ServiceLoader` auto-discovers the `CollectorConnectorFactory` providers;
without it, only the built-in `local` connector works. Eight schemes are registered today (`sftp`, `ftp`, `ftps`, `db`, `s3`, `kafka`, `azure`, `gcs`); NFS/SMB
is a **declined** design (OS-mounted share + the `local` connector). Further connectors plug in via the
same SPI without touching the core. See [Connectors](../acquisition/connectors.md) for the classes and the
`SshTunnel` bastion support.
