---
type: How-to
title: "Call the Control API with a machine client"
description: "Let another system call Inspecto without a person: a client-credentials client at the identity provider, the server flags that accept it, the Space that admits it, and what a machine client may do today."
item: module.oidc
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: providers/inspecto-oidc
---

# Call the Control API with a machine client

## Goal

Let another system — a case tool, an orchestrator, a script on a schedule — call the Control API with its own token,
attributed to it in the audit trail, without borrowing a person's sign-in.

## Before you start

- **Edition:** Standard, Advanced or Preview (sign-in through an OpenID Connect provider). Basic has no sign-in and
  no tokens.
- **Who:** an operator who can change the server's launch settings and the identity provider, and a Space
  administrator (`canConfigureAccess`).
- **What a machine client may do today — read this first.** A machine client is admitted to a Space and authenticated,
  but the only capability it can hold is `canOpenInboundInvestigation` (open an Investigation from outside, with the
  Investigations Add-on). Routes without a capability gate also answer it; every gated route answers 403. A general
  integration that reads or changes everything a person can is **not** supported yet.

## Steps

1. **At the identity provider**, create a confidential client with the client-credentials grant ("service accounts")
   enabled — here `case-bridge`. Give its service-account user the role `integration`, so the token's roles claim
   carries it. Keep the token lifetime short (five minutes).
2. **On the server**, name the client and cap token lifetime in the launch settings, next to the sign-in settings
   ([Connect the identity provider](admin-guide-howto-connect-the-identity-provider.md)):

   ```text
   AUTH_OIDC_AUDIENCE=inspecto-api
   INSPECTO_JAVA_OPTS=-Dauth.oidc.clients=case-bridge -Dauth.oidc.maxTokenLifetime=PT5M -Dauth.oidc.refuseReplay=true
   ```

   `auth.oidc.clients` needs an audience and an `https` JWKS address; `refuseReplay` needs `maxTokenLifetime`.
   Restart the server.
3. **In each Space the client may use**, admit it. Read the Space's Roles first
   (`GET /api/v1/spaces/<space>/access/roles`), then write them back with the client added — the write replaces the
   whole set: `roles` is required, and leaving out `identity` clears the admitted clients. `PUT /api/v1/spaces/<space>/access/roles` with:

   ```json
   {
     "roles": [ { "name": "collections", "capabilities": ["canWorkIncidents"] } ],
     "identity": { "machineClients": ["case-bridge"] }
   }
   ```

   where `roles` lists every Role the Space authored, as you read them (name, capabilities, data scopes).

   The console's Roles screen keeps this setting when you save there, but cannot edit it.
4. **From the machine**, get a token from the provider's token endpoint with the client-credentials grant, then call
   the Control API with `Authorization: Bearer <token>`. Every call is audited as actor `client:case-bridge`, type
   `service`.

## Check it worked

Admit `case-bridge` beside the Space's authored Role `collections` (step 3), and the Space lists it among the
clients it admits:

```check
PUT /spaces/{space}/access/roles
as admin
body {"roles": [{"name": "collections", "capabilities": ["canWorkIncidents"]}], "identity": {"machineClients": ["case-bridge"]}}
expect status 200
```

```check
GET /spaces/{space}/access/roles
as admin
expect status 200
expect data.identity.machineClients has case-bridge
```

Then, from the machine with its own client-credentials token, `GET /api/v1/spaces/<space>/inv/investigations` answers
200 — and 403 in a Space that does not admit the client.

## What can go wrong

- 403 `this Space does not admit the client '<id>' (roles.toon identity.machineClients)` — step 3 is missing in this
  Space.
- 403 `missing capability '…'` — the route needs a capability a machine client cannot hold (see above).
- 401 with no detail — the client is not listed in `auth.oidc.clients`, the token has no `azp` or `client_id`, it
  lives longer than `maxTokenLifetime`, or it was used twice while `refuseReplay` is on.
- The server does not start — `auth.oidc.clients` without an audience, or `refuseReplay` without
  `maxTokenLifetime`.

## Next

- [Connect the identity provider](admin-guide-howto-connect-the-identity-provider.md)
- [Define Roles and Access Policies](../access/admin-guide-howto-define-roles-and-access-policies.md)
