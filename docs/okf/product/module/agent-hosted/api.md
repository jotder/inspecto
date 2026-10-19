---
type: Reference
title: HostedProviderPlugin SPI
description: The Java contract the hosted-model jar implements - HostedProviderPlugin, ProviderSettings, registration and the abstain-safe rules.
resource: features/inspecto-agent/
tags: [module, agent-hosted, spi, api]
timestamp: 2026-10-19T00:00:00Z
---

# HostedProviderPlugin SPI

This module has no HTTP routes of its own (settings go through `/assist/settings`, owned by the
[agent](../agent/index.md) module). Its API is the Java contract
`com.gamma.agent.model.HostedProviderPlugin` in
`features/inspecto-agent/src/main/java/com/gamma/agent/model/HostedProviderPlugin.java`.

```java
public interface HostedProviderPlugin {
    Set<String> providers();
    ModelRouter createRouter(ProviderSettings settings, String apiKey);
}
```

| Method | Semantics |
|---|---|
| `providers()` | Provider ids this plugin builds; a subset of `ProviderSettings.knownProviders()` (`anthropic`, `openai`, `gemini`, `grok`, `ollama`, `llamacpp`). They become selectable in the settings screen. |
| `createRouter(settings, apiKey)` | A `ModelRouter` mapping each `ModelTier` (SMALL, MEDIUM, LARGE) to a `ModelProvider`. `apiKey` is already resolved and may be `null`. |

`ProviderSettings` is a record: `provider`, `baseUrl`, `apiKeyRef` (a variable *name*, never the key),
`models` (tier → model id), `timeoutSeconds`. By the time it reaches a plugin, `baseUrl` has already
passed the egress allowlist and been pinned.

## Rules for an implementation

* **No network in construction.** Build clients lazily on the first `generate`.
* **Abstain, do not fail.** A missing key or an unmapped tier makes `available()` answer `false`;
  `apiKey == null` must not throw.
* **Do not enforce the deadline yourself.** `ModelProviderFactory` wraps every provider in
  `TimeoutModelProvider` with `timeoutSeconds`.
* **Do not persist keys.** The key arrives as an argument; never write or log it.

## Registration

List the class in `META-INF/services/com.gamma.agent.model.HostedProviderPlugin` (this jar:
`providers/inspecto-agent-hosted/src/main/resources/META-INF/services/com.gamma.agent.model.HostedProviderPlugin`)
and declare `provides: contracts: HostedProviderPlugin` plus `requires: modules: agent` in
`META-INF/inspecto/module.toon`. `ModelProviderFactory.create` iterates the plugins and uses the first
whose `providers()` contains the configured id.

## Minimal example (the shipped one)

```java
public final class LangChain4jProviderPlugin implements HostedProviderPlugin {
    public Set<String> providers() { return Set.of("anthropic", "openai", "gemini", "grok", "llamacpp"); }
    public ModelRouter createRouter(ProviderSettings s, String apiKey) {
        EnumMap<ModelTier, ModelProvider> byTier = new EnumMap<>(ModelTier.class);
        for (ModelTier t : ModelTier.values())
            byTier.put(t, new LangChain4jChatProvider(s.provider(), s, apiKey, t));
        return ModelRouter.of(byTier);
    }
}
```

Details of `LangChain4jChatProvider`: [spec](spec.md).
