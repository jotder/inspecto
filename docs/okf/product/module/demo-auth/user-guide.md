---
type: Guide
title: Demo authenticator — user guide
description: Signing in as a Demo User in a demo build, switching users, and what the sign-in page messages mean.
resource: providers/inspecto-demo-auth/
tags: [module, demo-auth, user-guide, demo]
timestamp: 2026-10-19T00:00:00Z
---

# Demo authenticator — user guide

In a demo build the sign-in page (`inspecto-ui/src/app/inspecto/auth/sign-in.component.ts`) shows a list of
**Demo Users** under a *Not secure, local only* notice instead of a single sign-on button.

## Sign in

1. Open the demo address (it only answers on the same machine).
2. Pick a Demo User. Each shows a name and a job title, for example *Demo RA Analyst — Revenue Assurance analyst*.
3. You land on the user's start page, if one is set, otherwise the Space's landing page. A deep link you opened
   before signing in is kept.

What you can see and do is exactly what that user's roles allow; nothing is unlocked because it is a demo.

## Switch user

Sign out from the user menu and pick another Demo User. Every user is available in every Space; the Space you
are in decides which of the user's roles apply, so the same user can see different things in different Spaces.

## Sessions

A session refreshes itself for up to 8 hours. Restarting the server signs everyone out.

## Messages on the sign-in page

| Message | Meaning |
|---|---|
| No Demo Users, and no Spaces attached | The server has no Space loaded; ask whoever runs the demo. |
| No Demo Users listed | The Spaces define no `demo-users.toon`. |
| An error naming a Demo User and two Spaces, with *Try again* | Two Spaces define that user differently; the demo operator must align them. |

Operator setup: [admin-guide](admin-guide.md).
