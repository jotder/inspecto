---
type: TestsInventory
title: Hosted model providers for the assistant — tests inventory
item: module.agent-hosted
description: Generated list of the test classes for module.agent-hosted, with how to run them.
tags: [tests, module]
---

# Hosted model providers for the assistant — tests inventory (`module.agent-hosted`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `providers/inspecto-agent-hosted/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.agent-hosted`](../../../../../product-map/module/agent-hosted.toon).

2 test class(es): 2 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl providers/inspecto-agent-hosted
mvn -o test -pl providers/inspecto-agent-hosted -Dtest=LangChain4jChatProviderTest,LangChain4jProviderPluginTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `LangChain4jChatProviderTest` | `com.gamma.agent.hosted` | unit | `providers/inspecto-agent-hosted/src/test/java/com/gamma/agent/hosted/LangChain4jChatProviderTest.java` | Unit coverage for the hosted provider's config-only {@link LangChain4jChatProvider#available()} check — |
| `LangChain4jProviderPluginTest` | `com.gamma.agent.hosted` | unit | `providers/inspecto-agent-hosted/src/test/java/com/gamma/agent/hosted/LangChain4jProviderPluginTest.java` | Hosted-provider plugin (v4.1): discovery, per-tier routing, and config-only availability. |
