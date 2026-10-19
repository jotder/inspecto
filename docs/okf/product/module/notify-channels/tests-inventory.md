---
type: TestsInventory
title: Notification channels — tests inventory
item: module.notify-channels
description: Generated list of the test classes for module.notify-channels, with how to run them.
tags: [tests, module]
---

# Notification channels — tests inventory (`module.notify-channels`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `providers/inspecto-notify-channels/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.notify-channels`](../../../../../product-map/module/notify-channels.toon).

9 test class(es): 9 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl providers/inspecto-notify-channels
mvn -o test -pl providers/inspecto-notify-channels -Dtest=HttpWebhookSinkTransportTest,PinnedHttpTlsTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `HttpWebhookSinkTransportTest` | `com.gamma.notify.channel` | unit | `providers/inspecto-notify-channels/src/test/java/com/gamma/notify/channel/HttpWebhookSinkTransportTest.java` | The {@code sink.webhook} wire against a real in-process {@link HttpServer}: what reaches the receiver |
| `PinnedHttpTlsTest` | `com.gamma.notify.channel` | unit | `providers/inspecto-notify-channels/src/test/java/com/gamma/notify/channel/PinnedHttpTlsTest.java` | Pinning the address does not weaken TLS (ASSURE-ACTION-REQUESTS-1, verification finding 1b): over a real |
| `SmtpEmailChannelAttachmentTest` | `com.gamma.notify.channel` | unit | `providers/inspecto-notify-channels/src/test/java/com/gamma/notify/channel/SmtpEmailChannelAttachmentTest.java` | ASSURE-XLSX-ATTACHMENTS-1: the MIME structure {@link SmtpEmailChannel} builds for attachments, asserted on |
| `SmtpEmailChannelStarttlsRequiredTest` | `com.gamma.notify.channel` | unit | `providers/inspecto-notify-channels/src/test/java/com/gamma/notify/channel/SmtpEmailChannelStarttlsRequiredTest.java` | {@code NOTIFY-SMTP-STARTTLS-OPPORTUNISTIC-1} — once {@code starttls} is on, the channel must fail loudly |
| `SmtpEmailChannelTckTest` | `com.gamma.notify.channel` | unit | `providers/inspecto-notify-channels/src/test/java/com/gamma/notify/channel/SmtpEmailChannelTckTest.java` | {@link SmtpEmailChannel} against the platform's NotificationChannel TCK (MODULE-REORG-1 P5b). |
| `SmtpEmailChannelTest` | `com.gamma.notify.channel` | unit | `providers/inspecto-notify-channels/src/test/java/com/gamma/notify/channel/SmtpEmailChannelTest.java` | {@link SmtpEmailChannel} message construction and the {@code configured()} gate. Actual SMTP transport |
| `SmtpEmailChannelTlsIdentityTest` | `com.gamma.notify.channel` | unit | `providers/inspecto-notify-channels/src/test/java/com/gamma/notify/channel/SmtpEmailChannelTlsIdentityTest.java` | {@code NOTIFY-SMTP-TLS-VERIFY-1} — the STARTTLS session {@link SmtpEmailChannel} builds must verify that |
| `WebhookChannelTckTest` | `com.gamma.notify.channel` | unit | `providers/inspecto-notify-channels/src/test/java/com/gamma/notify/channel/WebhookChannelTckTest.java` | {@link WebhookChannel} against the platform's NotificationChannel TCK (MODULE-REORG-1 P5b). |
| `WebhookChannelTest` | `com.gamma.notify.channel` | unit | `providers/inspecto-notify-channels/src/test/java/com/gamma/notify/channel/WebhookChannelTest.java` | {@link WebhookChannel} against a real in-process HTTP server: JSON payload shape, bearer-token header, |
