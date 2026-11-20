---
type: Concept
title: "Learning Path — Partner developer"
description: "The order in which a partner or customer developer reads the Handbook: the concepts, the developer pages Requirements to Release, the ship-it tutorial on the Order-to-cash worked example, and the App kit how-tos, with a self-check at the end of each stage."
item: layer.product
audience: external
status: current
verified: 2026-11-20 last-edit
---

# Learning Path — Partner developer

You turn a working Space into an **App**: a Space Template with golden data and conformance, packaged, signed,
installed on another server and upgraded there. This path assumes you can already build in the console — if not, do
stages 1 and 2 of the [builder path](user-guide-path-builder.md) first: the App you ship here is the Space built there.

**Time:** about two days. **Edition:** Standard and up for installing and upgrading Apps.

## Stage 1 — Understand (about 2 hours)

1. [How the pieces fit](user-guide-concept-overview.md)
2. [Space Template](../../module/config/user-guide-concept-space-template.md) · [App](user-guide-concept-app.md) ·
   [Edition and Add-on](user-guide-concept-edition-addon.md)
3. [Roles and Access Policies](../../module/access/user-guide-concept-roles-access-policies.md)
4. The developer pages that explain:
   [5.1 Requirements](../platform/user-guide-develop-requirements.md) ·
   [5.2 Architecture](../platform/user-guide-develop-architecture.md) ·
   [5.3 Design](../platform/user-guide-develop-design.md)

**Self-check 1.**

1. An App declares no edition of its own. Where does the edition it needs come from?
2. Where do an App's personas come from?
3. What is the difference between a Space Template and an App?

## Stage 2 — Build and test (about 1 day)

1. [5.4 Build](../platform/user-guide-develop-build.md) — the App kit, Pipelines as configuration, the Control API.
2. [Create an App with the kit](../platform/user-guide-howto-create-an-app.md)
3. [Add golden data and conformance](../platform/user-guide-howto-golden-data-conformance.md)
4. [5.5 Test](../platform/user-guide-develop-test.md)
5. When your App needs them: [Add a parser](../../module/elt/user-guide-howto-add-a-parser.md) ·
   [Call the Control API with a machine client](../../module/oidc/admin-guide-howto-machine-client.md)

**Self-check 2.**

1. What does conformance compare today — and what does it not check yet?
2. Your App passes `test --bundle`, yet creating a Space from it on a server is refused. How can that happen?
3. Which capabilities may a machine client hold today?

## Stage 3 — Ship (about half a day)

1. [Tutorial: ship it — from a Space to an installed App](../platform/user-guide-tutorial-ship-an-app.md) — on the
   Order-to-cash worked example, end to end, on two servers.
2. [Package and sign an App](../platform/user-guide-howto-package-and-sign-an-app.md) ·
   [Install an App](../platform/user-guide-howto-install-an-app.md) ·
   [Upgrade an App](../platform/user-guide-howto-upgrade-an-app.md)
3. [5.6 Release](../platform/user-guide-develop-release.md)

**Self-check 3.**

1. Which files of a working Space must you remove before it becomes an App's template?
2. Installing, upgrading and removing an App: console or Control API?

## Stage 4 — Manage (about 1 hour)

Your App runs on someone else's server. Read the operator's side so you can support it:
[Tutorial: run it](../platform/user-guide-tutorial-run-the-platform.md) and
[Upgrade the platform](../platform/admin-guide-howto-upgrade-the-platform.md).

**Self-check 4.**

1. The platform is upgraded under your App. What must your App's release say for that to be safe?

## Self-check answers

- **1.1** From the Add-ons it requires (`requires.addons`): the first edition that ships them all. **1.2** The
  template's Roles (`config/roles.toon`). **1.3** A Space Template is the content; an App adds its landing, navigation,
  personas, golden data, required Add-ons and a version, and is packaged and signed.
- **2.1** The keys of the findings Dataset the golden data must produce; not its Expectations, Reconciliations, Alert
  Rules, Cases or Dashboards. **2.2** The test builds its scratch Space by copying files, so it skips the checks
  `POST /spaces` runs at create time. **2.3** Only `canOpenInboundInvestigation`.
- **3.1** Space-only files such as `egress.toon`, backup Jobs and the `maintenance_backups` Dataset — the tutorial
  lists them. **3.2** The Control API (`/apps`); there is no console for it yet.
- **4.1** The Platform versions it is compatible with — its compatibility range.
