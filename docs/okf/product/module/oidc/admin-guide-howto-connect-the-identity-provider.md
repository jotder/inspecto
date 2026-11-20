---
type: How-to
title: "Connect the identity provider"
description: "Point an Inspecto server at your OpenID Connect provider — discovery or issuer and JWKS, audience, client id, roles claim — so people sign in with single sign-on and get their Roles from the token."
item: module.oidc
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: providers/inspecto-oidc
---

# Connect the identity provider

## Goal

Make people sign in to Inspecto through your organisation's identity provider (Keycloak, WSO2 or another
OpenID Connect provider), and give them their Inspecto Roles from the token.

## Before you start

- **Edition:** Standard, Advanced or Preview — the sign-in module is not in Basic or a demo bundle.
- **Who:** an operator who can change the server's launch settings, and an administrator of the identity provider.
- **What you need:** at the provider, a public client for the console (by default its id is `inspecto-spa`) with the
  console's address as a redirect URI; tokens that are JWTs carrying an audience Inspecto checks and a claim listing
  the person's role names. If the provider's certificate comes from a private certificate authority, its certificate.

Concept: [Roles and Access Policies](../access/user-guide-concept-roles-access-policies.md).

## Steps

1. **Name the provider** in the server's launch settings. The shortest form is the discovery address:

   ```text
   AUTH_OIDC_DISCOVERY_URL=https://id.example.com/realms/inspecto/.well-known/openid-configuration
   AUTH_OIDC_AUDIENCE=inspecto-api
   AUTH_OIDC_CLIENT_ID=inspecto-spa
   AUTH_OIDC_PROVIDER=keycloak
   ```

   Instead of the discovery address you may give `AUTH_OIDC_ISSUER` and `AUTH_OIDC_JWKS_URI` — the way to name a
   provider that answers on plain HTTP, such as a test set-up, since a discovery address must be `https`. `AUTH_OIDC_PROVIDER` is
   `generic`, `keycloak` or `wso2`. Set the audience: without it the server starts but warns that audience validation
   is off.
2. **Say where the roles are.** By default the role names are read from the `roles` claim; the Keycloak preset also
   adds realm and client roles. For another claim add `-Dauth.oidc.rolesClaim=<claim>` to `INSPECTO_JAVA_OPTS` (WSO2:
   `groups`, with JWT access tokens turned on at WSO2).
3. **Give people role names Inspecto knows** — the built-in Roles are `admin`, `power`, `business`, `developer`,
   `pipeline-developer`, `app-developer`, `operations`, `support`, `super` and `integration`, or Roles you authored in
   a Space ([Define Roles and Access Policies](../access/admin-guide-howto-define-roles-and-access-policies.md)).
   Assign them at the provider; Inspecto does not assign Roles to people.
4. **A private certificate authority:** import its certificate into the bundle's `runtime\lib\security\cacerts`. An
   upgrade replaces the runtime, so do it again after each upgrade.
5. **Restart** and run `.\bin\preflight.ps1` — the `sign-in` check passes (its `proxy` check passes once Inspecto
   is behind a reverse proxy: [Put Inspecto behind a reverse proxy](../../layer/platform/admin-guide-howto-reverse-proxy.md)).
   Open the console and click
   **Sign in with SSO**.

## Check it worked

Signed in as an administrator, the bootstrap reports sign-in through OpenID Connect and the administrator's
capabilities; without a token the Spaces list is refused:

```check
GET /bootstrap
as admin
expect status 200
expect data.features.authMode = "oidc"
expect data.session.authenticated = true
expect data.session.capabilities has canAdminister
```

```check
GET /spaces
expect status 401
```

## What can go wrong

- The server does not start: `inspecto-oidc requires -Dauth.oidc.issuer` (or `jwksUri`), `discoveryUrl must be https`,
  or `could not load the OIDC discovery document` — often the provider's certificate is not trusted (step 4).
- Every call answers 401 without detail — the token is not a JWT, or its `iss` or `aud` differs from the settings
  (`localhost` and `127.0.0.1` are different issuers), it has no `sub`, or the server cannot fetch the JWKS.
- Sign-in works but the person sees nothing — the role names in the token match no Role, or the roles are in another
  claim (step 2).
- A closed Space admits a person only through a Role that **that Space** defines, unless they are an administrator or
  its creator.
- An administrator alone cannot create a Space from a template that has an Alert Rule — give them `power` as well
  (it carries `canAuthorAlertRules`).

## Next

- [Define Roles and Access Policies](../access/admin-guide-howto-define-roles-and-access-policies.md)
- [Call the Control API with a machine client](admin-guide-howto-machine-client.md)
- [Tutorial: run it](../../layer/platform/user-guide-tutorial-run-the-platform.md) — a Keycloak set-up, end to end.
