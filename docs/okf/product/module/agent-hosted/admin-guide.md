---
type: Guide
title: Hosted model providers — admin guide
description: Install or omit the hosted-model jar, the settings file, API key handling, -D flags, endpoint allowlist, TLS and failure modes.
resource: providers/inspecto-agent-hosted/
tags: [module, agent-hosted, llm, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Hosted model providers — admin guide

`inspecto-agent-hosted.jar` lets the assistant use hosted LLMs: **anthropic, openai, gemini, grok** and
the OpenAI-compatible **llamacpp** server. Without it only local Ollama is selectable. The default
(air-gapped) packaging omits this jar (root `pom.xml` description), and no `offerings/*.toon` Edition lists
it, so an offline install carries no hosted-model client at all. It requires the `agent` module.

## 1. Install or omit

* **Air-gapped:** do nothing. The settings screen offers only `ollama`; choosing a hosted id answers
  "provider '<id>' requires the inspecto-agent-hosted jar on the classpath".
* **Cloud AI allowed:** put `inspecto-agent-hosted.jar` on the classpath beside `inspecto-agent.jar` and
  restart. `ModelProviderFactory.availableProviders()` then lists the hosted ids.

## 2. Choose provider and models

Use the Model Settings screen ([user guide](../agent/user-guide-model-settings.md)), backed by
`GET/POST /assist/settings`, or edit the settings file directly.

| Flag | Default | Meaning |
|---|---|---|
| `-Dassist.settings.file=<path>` | `config/assist-settings.properties` (working directory) | Settings file. If it is absent the assistant falls back to the environment-configured Ollama. |

```properties
provider=anthropic
base.url=
api.key.ref=ANTHROPIC_API_KEY
model.small=claude-haiku-4-5
model.medium=claude-sonnet-4-6
model.large=claude-opus-4-8
timeout.seconds=60
```

Defaults per provider (`ProviderSettings`):

| Provider | Key reference (env var) | Default base URL | Notes |
|---|---|---|---|
| `anthropic` | `ANTHROPIC_API_KEY` | vendor SDK endpoint | max 4096 output tokens |
| `openai` | `OPENAI_API_KEY` | vendor SDK endpoint | |
| `gemini` | `GOOGLE_API_KEY` | vendor SDK endpoint | |
| `grok` | `XAI_API_KEY` | `https://api.x.ai/v1` | OpenAI-compatible client |
| `llamacpp` | none (keyless) | `http://localhost:8080/v1` | model name is whatever the server was started with (`default`) |

`timeout.seconds` (default 60) is both the client timeout and a hard deadline around every call.

## 3. API keys

**A raw key is never written to disk.** The file holds only `api.key.ref`, the *name* of an
environment variable or system property. Resolution order: a key submitted through
`POST /assist/settings` (held in memory until restart) → the system property named by the ref → the
environment variable named by the ref. For a durable setup export the variable in the service
environment, for example `ANTHROPIC_API_KEY=...`, rather than pasting the key in the screen. A key is
never echoed by the API.

## 4. Endpoint allowlist and TLS

Every configured `base.url` (and the llamacpp loopback default) is checked against the Space's `models`
list in `egress.toon` before a client is built, and the client is built on the checked address
([assist agent spec](../agent/spec-assist-agent.md)). A refused endpoint makes the provider unavailable;
nothing dials it. With no `base.url`, anthropic, openai, gemini and grok use their fixed vendor
endpoints. All vendor calls use HTTPS through the JVM default trust store; there is no hosted-specific
keystore flag. Behind a TLS-intercepting proxy, import the proxy CA into the runtime `cacerts`.

## 5. Failure modes

Construction never touches the network or needs a key; a provider reports `available() == false` from
configuration alone, and the assistant abstains instead of failing.

| Symptom | Cause | Fix |
|---|---|---|
| Hosted ids missing from the provider list | jar not on the classpath | Stage the jar and restart |
| `requires the inspecto-agent-hosted jar on the classpath` | hosted id saved on an install without the jar | Stage the jar or switch to `ollama` |
| Provider unavailable, no error | no key resolves (env var unset after a restart), or a tier has no model | Export the variable named by `api.key.ref`; fill `model.*` |
| `model endpoint refused: ...` | `base.url` not in the Space's `models` egress list | Add it to `egress.toon`, or clear `base.url` |
| Calls time out | slow vendor or `timeout.seconds` too low | Raise `timeout.seconds` |
| `unsupported hosted provider` | unknown id in the file | Use one of the ids above |
| llamacpp unavailable | no base URL | Set `base.url` to the server's `/v1` URL |

## References

* `providers/inspecto-agent-hosted/src/main/java/com/gamma/agent/hosted/LangChain4jChatProvider.java`
* `features/inspecto-agent/src/main/java/com/gamma/agent/model/AssistModelSettings.java`
* `features/inspecto-agent/src/main/java/com/gamma/agent/model/ModelProviderFactory.java`
* [spec](spec.md) · [api](api.md) · [architecture](architecture.md)
