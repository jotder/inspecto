---
type: TestsInventory
title: MCP endpoint — tests inventory
item: module.mcp
description: Generated list of the test classes for module.mcp, with how to run them.
tags: [tests, module]
---

# MCP endpoint — tests inventory (`module.mcp`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `features/inspecto-mcp/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.mcp`](../../../../../product-map/module/mcp.toon).

2 test class(es): 1 unit, 1 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl features/inspecto-mcp
mvn -o test -pl features/inspecto-mcp -Dtest=ControlApiMcpTest,McpRoutesManifestParityTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `ControlApiMcpTest` | `com.gamma.mcp` | real-HTTP control-plane test | `features/inspecto-mcp/src/test/java/com/gamma/mcp/ControlApiMcpTest.java` | {@code POST /mcp} over real HTTP (AGENT-KIT-1 AK-1): the transport's refusals in order, both protocol eras, that |
| `McpRoutesManifestParityTest` | `com.gamma.mcp` | unit | `features/inspecto-mcp/src/test/java/com/gamma/mcp/McpRoutesManifestParityTest.java` | The module's {@code module.toon} {@code provides.routes} must list EXACTLY the routes {@link McpRoutes} registers - |
