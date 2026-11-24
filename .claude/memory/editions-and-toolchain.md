---
name: editions-and-toolchain
description: "Editions are Basic/Standard/Advanced/Preview (-Pedition-standard|advanced|preview); default build = Standard; Maven needs JAVA_HOME = JDK 27 because the shell's JAVA_HOME is 26; AI modules are opt-in -Pai"
metadata:
  type: project
---

**Editions (since LA-EDITIONS-1, 2026-10-17):** Basic (default build, no profile) / Standard / Advanced / Preview —
Maven `-Pedition-standard`, `-Pedition-advanced`, `-Pedition-preview` (verified in root `pom.xml`).
`edition-professional` / `edition-enterprise` / "Personal" are GONE; any older note or doc using them is stale.
The operator's standing default (2026-09-21, "Professional") maps to **Standard** unless asked otherwise.
Bundle: `pwsh -File inspecto/package.ps1 -Edition <Basic|Standard|Advanced|Preview>`. Standard+ fail closed at boot
without an OIDC issuer/JWKS — ask for IdP details before `serve.bat`, but never block the build on them.

**AI is opt-in: `-Pai` (AI-OPTIN-1, operator 2026-11-24).** `inspecto-agent`, `inspecto-agent-hosted` and
`inspecto-intelligence` (the only modules that need `com.eoiagent`, built from source jotder/inspect-agent) live in
the root pom's `ai` profile — not the default reactor, not any edition profile. Combine: `-Pedition-advanced,ai`.
Callers that pass it themselves: `package.ps1` (Standard/Advanced/Preview), `tools/sbom.mjs` (`mavenProfiles`),
`tools/check-dependencies.mjs`, `tools/affected-modules.mjs` (when an AI module is in its set), ci.yml, release.yml.
No eoiagent in `~/.m2` → build without `,ai` and report the AI modules as NOT built.

**JDK:** the shell's `JAVA_HOME` is `C:\.jdks\openjdk-26.0.1` while `java` on PATH is 27, so Maven dies
"release version 27 not supported". Prefix: `JAVA_HOME="C:/.jdks/jdk-27-win"` (toolchain) or
`"C:/Program Files/Java/latest/jdk-27"`. That failure is a compile failure — see stale-report trap in
[[maven-verdict-traps]].

**Pre-push secret guard scans EVERY commit in the push range**: a `secret-allow` added in a later commit does not
help; put it in the introducing commit.
