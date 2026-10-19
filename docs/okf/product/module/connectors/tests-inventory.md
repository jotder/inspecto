---
type: TestsInventory
title: Collector and export connectors — tests inventory
item: module.connectors
description: Generated list of the test classes for module.connectors, with how to run them.
tags: [tests, module]
---

# Collector and export connectors — tests inventory (`module.connectors`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `providers/inspecto-connectors/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.connectors`](../../../../../product-map/module/connectors.toon).

36 test class(es): 36 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl providers/inspecto-connectors
mvn -o test -pl providers/inspecto-connectors -Dtest=AwsSigV4Test,AzureBlobConnectionWorkbenchTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `AwsSigV4Test` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/AwsSigV4Test.java` | Pins {@link AwsSigV4} to AWS's published SigV4 test-vector credentials/date, plus the S3-specific parts. |
| `AzureBlobConnectionWorkbenchTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/AzureBlobConnectionWorkbenchTest.java` | The Azure Blob {@link ConnectionWorkbench} (contributed by {@link AzureBlobConnectorFactory}) against an |
| `AzureBlobConnectorFactoryTckTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/AzureBlobConnectorFactoryTckTest.java` | {@link AzureBlobConnectorFactory} against the platform's CollectorConnectorFactory TCK (MODULE-REORG-1 P5b). |
| `AzureBlobConnectorTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/AzureBlobConnectorTest.java` | Drives {@link AzureBlobConnector} end-to-end against an in-process Blob-service stub (JDK |
| `AzureSharedKeyTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/AzureSharedKeyTest.java` | {@link AzureSharedKey} signing, pinned offline: the exact 2009-09-19 string-to-sign (positional headers, |
| `CollectorProcessorRemoteCycleTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/CollectorProcessorRemoteCycleTest.java` | {@code PROCESSOR-RELEASE-READINESS-1} gap G6 — the Collector features driven through a REAL poll cycle |
| `DbConnectionsProxyTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/DbConnectionsProxyTest.java` | The JDBC half of the connector proxy dial-through. The rule from the SFTP/FTP tests holds here: a proxy test |
| `DbConnectionWorkbenchTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/DbConnectionWorkbenchTest.java` | DB connection workbench — exercises {@link DbConnectionWorkbench} against an in-process DuckDB JDBC database |
| `DbExportConnectorFactoryTckTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/DbExportConnectorFactoryTckTest.java` | {@link DbExportConnectorFactory} against the platform's CollectorConnectorFactory TCK (MODULE-REORG-1 P5b). |
| `DbExportConnectorFactoryValidateTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/DbExportConnectorFactoryValidateTest.java` | The {@code db} Connection's required options are judged at the SAVE (`PROCESSOR-RELEASE-READINESS-1`, |
| `DbExportConnectorTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/DbExportConnectorTest.java` | DB-export source — exercises {@link DbExportConnector} against an in-process DuckDB JDBC database (the driver is <!-- vocab-allow: quoted Javadoc --> |
| `DbExportWatermarkCycleTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/DbExportWatermarkCycleTest.java` | KAFKA-OFFSET-REKEY-1 (design slice 0, stream-consumer-design §2.2), DB-export half: the REAL |
| `FtpConnectorFactoryTckTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/FtpConnectorFactoryTckTest.java` | {@link FtpConnectorFactory} against the platform's CollectorConnectorFactory TCK (MODULE-REORG-1 P5b). |
| `FtpConnectorTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/FtpConnectorTest.java` | Phase E — exercises {@link FtpConnector} (commons-net client) against an in-process Apache FtpServer rooted |
| `FtpsConnectorFactoryTckTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/FtpsConnectorFactoryTckTest.java` | {@link FtpsConnectorFactory} against the platform's CollectorConnectorFactory TCK (MODULE-REORG-1 P5b). |
| `FtpsConnectorTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/FtpsConnectorTest.java` | FTPS hardening — exercises {@link FtpConnector} in explicit-TLS mode against an in-process Apache FtpServer |
| `FtpTunnelConnectorTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/FtpTunnelConnectorTest.java` | FTP through an SSH bastion — exercises {@link FtpConnector}'s tunnel path against an in-process Apache |
| `GcsConnectionWorkbenchTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/GcsConnectionWorkbenchTest.java` | The GCS {@link ConnectionWorkbench} (contributed by {@link GcsConnectorFactory}) against an in-process GCS |
| `GcsConnectorFactoryTckTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/GcsConnectorFactoryTckTest.java` | {@link GcsConnectorFactory} against the platform's CollectorConnectorFactory TCK (MODULE-REORG-1 P5b). |
| `GcsConnectorTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/GcsConnectorTest.java` | Drives {@link GcsConnector} end-to-end against an in-process GCS stub (JDK {@link HttpServer}) — offline, no |
| `ObjectStoreEgressTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/ObjectStoreEgressTest.java` | The object-store connectors dial through the egress policy (OBJECT-STORE-EGRESS-POLICY, 2026-09-27): a host |
| `ObjectStoreExportJobTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/ObjectStoreExportJobTest.java` | EXPORT-1: the {@code objectstore.export} Job Type end-to-end — the engine job, the ServiceLoader-found |
| `PinnedObjectStoreHttpTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/PinnedObjectStoreHttpTest.java` | The pinned object-store wire against a hostile or odd server: a scripted raw socket answers each request with |
| `S3ConnectionWorkbenchTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/S3ConnectionWorkbenchTest.java` | The S3 {@link ConnectionWorkbench} (contributed by {@link S3ConnectorFactory}) against an in-process S3 stub |
| `S3ConnectorFactoryTckTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/S3ConnectorFactoryTckTest.java` | {@link S3ConnectorFactory} against the platform's CollectorConnectorFactory TCK (MODULE-REORG-1 P5b). |
| `S3ConnectorTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/S3ConnectorTest.java` | Drives {@link S3Connector} end-to-end against an in-process S3 stub (JDK {@link HttpServer}) — offline, no |
| `S3ExportConnectorFactoryTckTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/S3ExportConnectorFactoryTckTest.java` | {@link S3ConnectorFactory}'s export side against the platform's ExportConnectorFactory TCK (MODULE-REORG-1 P5b). |
| `SafetyPolicyEgressTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/SafetyPolicyEgressTest.java` | Slice S4 of {@code policy-narrowing-design.md}: the {@code EgressGate} at the act sites. A server Safety Policy |
| `SftpConnectorFactoryTckTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/SftpConnectorFactoryTckTest.java` | {@link SftpConnectorFactory} against the platform's CollectorConnectorFactory TCK (MODULE-REORG-1 P5b). |
| `SftpConnectorTest` | `com.gamma.acquire.connectors` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/SftpConnectorTest.java` | Phase E — exercises {@link SftpConnector} (sshj client) end-to-end against an in-process Apache MINA SSHD |
| `HmacDeliveryStatusAdapterTest` | `com.gamma.connect.notify` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/connect/notify/HmacDeliveryStatusAdapterTest.java` | The generic HMAC delivery-status adapter (D8 §4.3): signature, replay window, and normalisation. |
| `SendGridDeliveryStatusAdapterTest` | `com.gamma.connect.notify` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/connect/notify/SendGridDeliveryStatusAdapterTest.java` | The SendGrid Event Webhook adapter (D8 §4.3). |
| `SesSnsAdapterEventsTest` | `com.gamma.connect.notify` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/connect/notify/SesSnsAdapterEventsTest.java` | D8-SES-SNS-1 slice S2 — the {@code control} path (D10) and the SES → {@link DeliveryStatus} mapping (§4.3, |
| `SnsFetchSecurityTest` | `com.gamma.connect.notify` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/connect/notify/SnsFetchSecurityTest.java` | 🔒 D8-SES-SNS-1 slice S3 — the outbound certificate fetch and the subscription confirmation an UNAUTHENTICATED |
| `SnsFixturesTest` | `com.gamma.connect.notify` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/connect/notify/SnsFixturesTest.java` | S0: the fixture set is self-consistent — every envelope parses, the test PKI issues a certificate that chains <!-- vocab-allow: quoted Javadoc --> |
| `SnsVerificationTest` | `com.gamma.connect.notify` | unit | `providers/inspecto-connectors/src/test/java/com/gamma/connect/notify/SnsVerificationTest.java` | D8-SES-SNS-1 slice S1 — the envelope, the signature and the certificate trust, with NO network: the |
