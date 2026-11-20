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

5 test class(es): 2 unit, 3 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl features/inspecto-mcp
mvn -o test -pl features/inspecto-mcp -Dtest=ControlApiMcpDiscoveryTest,ControlApiMcpTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `ControlApiMcpDiscoveryTest` | `com.gamma.mcp` | real-HTTP control-plane test | `features/inspecto-mcp/src/test/java/com/gamma/mcp/ControlApiMcpDiscoveryTest.java` | MCP authorization discovery over real HTTP (AGENT-KIT-1 AK-2a, plan §4.3): {@code GET /.well-known/oauth-protected-resource} |
| `ControlApiMcpTest` | `com.gamma.mcp` | real-HTTP control-plane test | `features/inspecto-mcp/src/test/java/com/gamma/mcp/ControlApiMcpTest.java` | {@code POST /mcp} over real HTTP (AGENT-KIT-1 AK-1): the transport's refusals in order, both protocol eras, that |
| `ControlApiMcpWriteToolsTest` | `com.gamma.mcp` | real-HTTP control-plane test | `features/inspecto-mcp/src/test/java/com/gamma/mcp/ControlApiMcpWriteToolsTest.java` | The AK-2b write MCP Tools over real HTTP (AGENT-KIT-1, plan {@code 2026-11-20-agent-kit-ak2-plan.md} §4.4, D-AK1, D-AK3): |
| `McpRoutesManifestParityTest` | `com.gamma.mcp` | unit | `features/inspecto-mcp/src/test/java/com/gamma/mcp/McpRoutesManifestParityTest.java` | The module's {@code module.toon} {@code provides.routes} must list EXACTLY the routes {@link McpRoutes} registers - |
| `McpToolRoutesTest` | `com.gamma.mcp` | unit | `features/inspecto-mcp/src/test/java/com/gamma/mcp/McpToolRoutesTest.java` | Every MCP Tool's replayed route against the capability manifest (AGENT-KIT-1 AK-2b, plan §4.4 and §4.7): every |
