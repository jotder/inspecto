---
type: Feature
title: "Platform — user guide"
description: "What the platform does for an end user — signing in, Spaces, Lenses and navigation — with links onward to each module's user guide."
tags: [platform, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Platform — user guide

This page is the entry point for end users. It does not repeat the screen-by-screen walkthrough; that
lives in [`docs/USER_GUIDE.md`](../../../../USER_GUIDE.md) (§1 *Getting around*) and the product-wide
UI guides under [`layer/product`](../product/).

## What the platform does for you

Inspecto takes files dropped into folders (or delivered by a Collector), runs them through Pipelines
into Datasets, checks them with Expectations, raises Incidents and Alerts when something is wrong, and
lets you query, chart and share the results. See the lifecycle in `USER_GUIDE.md` (*The data
lifecycle*) and the walkthrough [file to dashboard](../product/user-guide-journey-file-to-dashboard.md).

## Signing in

- If single sign-on is configured, you see a sign-in page with a single **Sign in with SSO** button; you
  sign out from the user menu in the top bar.
- If the deployment has no sign-in (the Basic edition), there is no user menu, and **Home** warns that
  anyone who can reach the server port can use the control plane. Ask your administrator to restrict it
  (see the [product admin guide](../product/admin-guide.md)).
- What you may do after sign-in is decided by your Role, not by your Lens — see the
  [access user guide](../../module/access/user-guide-access.md).

## Spaces

A Space is an isolated project: its own Connections, Collectors, Pipelines, Datasets, dashboards and
menus. The **Space switcher** in the top bar appears only when the server hosts more than one Space;
switching reloads the app in the new Space. Details: [Spaces](../product/user-guide-spaces.md).

## Getting around

- **Home** — what needs your attention (failed Runs, open Incidents, pending approvals) and quick
  actions you are allowed to perform.
- **Lens (View as)** — Business, Builder or Ops. It only chooses your starting screen and emphasis;
  it never hides a screen and is not a permission.
- **Sidebar** — your Space's custom business menus above the divider, then the fixed Business,
  Operations, Platform, Settings and Assistant groups; the menu search filters screens.
- **Global search** — jumps to a named Pipeline, Collector, Dataset, Widget, Dashboard or Query.

First-time users: [onboarding](../product/user-guide-onboarding.md).

## Where to go next

| Task | Guide |
|---|---|
| Build or edit a Pipeline | [Pipeline editor](../../module/etl/user-guide-pipeline-editor.md) |
| Browse Streams, References, Tables and lineage | [Catalog](../product/user-guide-catalog.md) |
| Query, chart and build dashboards | [Studio](../product/user-guide-studio.md) · [Dashboard](../product/user-guide-dashboard.md) |
| Schedule and watch Jobs | [Jobs](../../module/processor/user-guide-jobs.md) |
| From Alert to Case | [journey](../product/user-guide-journey-alert-to-case.md) |
| Change configuration safely | [journey](../product/user-guide-journey-safe-config-change.md) |

Every other module's user guide is listed in its hub under [`docs/okf/product/module/`](../../module/).
