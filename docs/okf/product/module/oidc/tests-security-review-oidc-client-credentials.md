# Security review: inbound OIDC client-credentials tokens for Link Analysis

Reviewed 2026-11-01 against master `a24fca153`, for item (5b) of `LA-EDITIONS-RESIDUALS-1` ([BACKLOG](../../../../BACKLOG.md)).
The checklist is the one in [la-core `architecture-backend.md` §6.5](../la-core/architecture-backend.md), *Machine-to-machine trust (I1) is NOT enabled*.
The task also asked about four more points: the `azp` check, Space binding, token lifetime and logging of the principal.
This is a review only. No product code was changed.

## Verdict: enable with conditions

**There is no switch to turn on.** `OidcAuthenticator` does not tell a client-credentials (machine-to-machine) token from a
user's token. A machine token that is signed by the trusted issuer, has a `sub`, and is in date is accepted today, and its
roles map to capabilities exactly as a person's do ([la-core `architecture-backend.md` §6.4](../la-core/architecture-backend.md), *Auth*). So the
real question is whether a deployment may give such a token a role. The answer is yes, for one named integration at a time,
under the **deployment conditions** below. Without them the token can get much more than one integration role. Five BACKLOG
rows (end of this page) would move the conditions from the runbook into code; one is still open. `OIDC-KEYCLOAK-AZP-ROLES-1`
was fixed on 2026-11-01, so condition 2 is now held by code. `OIDC-CC-CLIENT-PIN-1` and `OIDC-CC-LIFETIME-REPLAY-1` were
built on 2026-11-01 as opt-in settings ([admin guide](admin-guide.md) section 5): with `-Dauth.oidc.clients` set, conditions
1 and 5 are held by code, and a client not on the list is refused (G2). `OIDC-CC-MACHINE-ACTOR-1` was built on 2026-11-01,
so condition 6 is held by code (G7). `OIDC-CC-INTEGRATION-ROLE-1` waits on operator decisions, so conditions 3 and 4 stay
deployment rules.

**Deployment conditions (all required):**

1. Set `-Dauth.oidc.clients` to the integration's client id. Boot then refuses an unset `auth.oidc.audience` (without it,
   a token the issuer minted for any other API is accepted, G1) and a plain-http JWKS, and only the listed clients and
   the SPA client may call (G2).
2. ~~On `auth.oidc.provider=keycloak`, set `auth.oidc.clientId` explicitly.~~ No longer required (G5 fixed 2026-11-01):
   an unset `clientId` means `inspecto-spa`, and the token's `azp` is never read. If the SPA's client has another id,
   set it, or no client roles are read at all (realm roles only).
3. ~~Give the integration client a non-seed role authored in one Space only.~~ Held by code since 2026-11-01 (G3, G9
   built, `OIDC-CC-INTEGRATION-ROLE-1`): give it the seed role `integration` (`canOpenInboundInvestigation` only) and name
   its client in the target Space's `roles.toon` `identity.machineClients`. A machine keeps only that capability and is
   refused 403 in every other Space.
4. ~~In the IdP, never put `admin` or `super` on the service account.~~ Held by code since 2026-11-01: a machine keeps
   only `canOpenInboundInvestigation`, so a misgranted role gives it nothing more (B1 allowlist).
5. Set `-Dauth.oidc.maxTokenLifetime=PT5M` (or less) and `-Dauth.oidc.refuseReplay=true`, and give the client an IdP
   access-token lifetime within it. A longer-lived machine token, or one sent a second time on the same node, is then
   refused (G4, G6). Replay refusal is per node; sender-constrained tokens (DPoP / mTLS) are not built.
6. ~~Map the IdP's `sub` for the service account to the client in the audit runbook.~~ No longer required (G7 built
   2026-11-01): a machine token (`gty: client_credentials`, or a client listed under condition 1) is recorded as actor
   `client:<client id>` with `actor_type: service`, and the token's `sub` rides on the row as `idp_subject`. A Keycloak
   client-credentials token may carry no `gty`, so on Keycloak condition 1 is what makes the token a machine's.

## Checklist

PASS = the code does what the item asks. GAP = it does not, and a BACKLOG row is filed. N/A = the item does not apply to
inbound trust.

| # | Checklist item | Result | Evidence |
|---|---|---|---|
| 1a | No inbound route accepts a URL to call back | PASS | `POST /inv/inbound/investigations` takes `reference.url` only as text. `InvestigationReferenceRoutes.checkUrl` checks that it is absolute http(s) with no credentials (`la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationReferenceRoutes.java:175-187`), and `InvestigationInboundRoutes.java:167-170` stores it. Nothing fetches it (`trusted:false`, la-core `architecture-backend.md` §6.4). |
| 1b | Every outbound target is a registered https Connection under the egress allowlist, with a pinned address, and redirects are not followed | PASS | `HostOutboundPort` composes `WebhookSink.endpoint`, `EgressPolicy.resolve` and the edition's transport (`la/inspecto-geo-link/src/main/java/com/gamma/geolink/HostOutboundPort.java`). `PinnedHttp` sends one request with no redirects, so a 3xx comes back as itself (`providers/inspecto-notify-channels/src/main/java/com/gamma/notify/channel/PinnedHttp.java:26-32`). Covered by `ControlApiLaIntegrationTest` (egress denied, nothing sent). |
| 2a | Issuer pinned | PASS | Exact `iss` match (`providers/inspecto-oidc/src/main/java/com/gamma/oidc/OidcAuthenticator.java:137-139`). Boot fails without it (`:65`, `:244-248`). A discovery document whose issuer differs from the configured one is refused (`OidcConfig.java:209-213`). Only RS256 is accepted (`:127`), so there is no `none` or HS/RS confusion. The `typ` allowlist is `JWT`, `at+jwt` or absent (`:136`). |
| 2b | Audience pinned | **PASS when `auth.oidc.clients` is set, since 2026-11-01** (was GAP G1) | **Built (opt-in):** with a client allowlist set, boot refuses an unset `auth.oidc.audience` (`MachineTrust.fromSystemProperties`, `OidcMachineTrustTest.aClientAllowlistWithoutAnAudienceRefusesBoot`). Without the allowlist audience stays warn-only by decision. *As found:* audience is checked only when it is configured (`OidcAuthenticator.java:138`). When it is unset, a warning is logged and every token is accepted (`:110-116`). This is by decision (oidc plan, *Decided*). Throwaway test: a token with `aud: another-api` and role `admin` got `canRevealLinkEntities` with no audience configured, and was refused once it was set. |
| 2c | `azp` / client allowlist | **PASS when `auth.oidc.clients` is set, since 2026-11-01** (was GAP G2) | **Built (opt-in):** `MachineTrust.admits` reads `azp`, else `client_id`, and admits only a listed client or the SPA client (`auth.oidc.clientId`); a token naming neither is refused. Bearer path only, after the signature verifies. `OidcMachineTrustTest.onlyAnAllowedClientIsAdmitted`, `.azpDecidesAndClientIdIsTheFallback`, `.theBearerPathRefusesWhatThePolicyRefuses`. *As found:* neither `azp` nor `client_id` is read when a token is validated. The only read was the Keycloak role source's fallback (`RoleMapper.java:121-122`), removed with G5 on 2026-11-01. Throwaway test: a token with `azp: some-other-client` and the right audience was accepted. |
| 2d | One integration role only, never a human capability | **PASS since 2026-11-01** (was GAP G3) | **Built** (`OIDC-CC-INTEGRATION-ROLE-1`, operator decisions A1 + B1 operator-decided 2026-11-01): the inbound route's gate is its own capability `canOpenInboundInvestigation` (seed role `integration`), which opens no other Investigation write; `Roles.admitMachine`, called from `ControlApi.authenticate`, drops every capability of a machine Subject not on `Roles.MACHINE_CAPABILITIES`. `RolesMachineAdmissionTest`, `ControlApiInvestigationInboundTest.anAdmittedMachineOpensAndReadsWhatItLeadsAndKeepsOnlyAllowlistedCapabilities`. *As found:* the gate was `canManageIncidents`, bundled in the seed only with human roles, and it opens every other Investigation write, hand-off included. |
| 2e | Short lifetime | **PASS when `auth.oidc.maxTokenLifetime` is set, since 2026-11-01** (was GAP G4) | **Built (opt-in):** for a listed machine client, `iat` is required and `exp - iat` above the ceiling is refused; the SPA client's tokens are not checked. `OidcMachineTrustTest.aMachineTokenAboveTheLifetimeCeilingIsRefused`, `.aMachineTokenWithoutIatIsRefusedUnderACeiling`. *As found:* only `sub` and `exp` are required (`OidcAuthenticator.java:139`). There is no maximum `exp - iat` and no check that `iat` is present. Throwaway test: a 30-day token was accepted. The lifetime is whatever the IdP is set to (condition 5). |
| 2f | No refresh token | PASS | A resource server never mints a token or accepts a refresh token on an API route. The only refresh path is the SPA's BFF `POST /auth/refresh`, which reads the httpOnly `inspecto_rt` cookie ([architecture](architecture.md)). A client-credentials grant has no refresh token (RFC 6749 §4.4.3). |
| 2g | Role source cannot be chosen by the caller | **PASS since 2026-11-01** (was GAP G5, defect) | **Fixed:** the role source is `RoleMapper.Keycloak(System.getProperty("auth.oidc.clientId", "inspecto-spa"))`, the same default as `OidcTokenRelay` and `GET /bootstrap`, and `RoleMapper` no longer reads `azp` (no client id: realm roles only). `OidcProviderPresetTest.anUnsetClientIdNeverGrantsTheCallingClientsOwnRoles` repeats the throwaway test below and gets neither `canAdminister` nor `canRevealLinkEntities`. Refusing boot without `clientId` was not chosen: it is a documented optional setting with a default, and the preset refuses boot only for settings that have none (`issuer`). *As found:* with `auth.oidc.provider=keycloak` and no explicit `rolesClaim`, the role source is `RoleMapper.Keycloak(System.getProperty("auth.oidc.clientId"))`, with **no default** (`OidcAuthenticator.java:221-226`). A null client id falls back to the token's own `azp` (`RoleMapper.java:121-122`). The admin guide documents the default as `inspecto-spa` ([admin-guide](admin-guide.md) line 26), and `OidcTokenRelay` applies that default (`OidcTokenRelay.java:59`), but the authenticator does not. So any client of the realm can define a client role named `super`, and its own token then gets every Inspecto capability. Throwaway test: `resource_access.evil-client.roles=[super]` plus `azp=evil-client` gave `canAdminister` and `canRevealLinkEntities`. With `clientId=inspecto` set, the same token got no capabilities. The SPA's human users are not affected, because their `azp` is the SPA client. |
| 3a | Inbound requests carry a nonce or timestamp checked inside a tolerance window | **PASS per node when `auth.oidc.refuseReplay` is set, since 2026-11-01** (was GAP G6) | **Built (opt-in):** for a listed machine client, `jti` is required and accepted once per node until `exp` + 60 s; it needs the lifetime ceiling, which bounds the cache. Behind a load balancer a token can still be used once per node, and there is still no proof of possession (DPoP / mTLS, not built). `OidcMachineTrustTest.aReplayedJtiIsRefused`, `.aMachineTokenWithoutJtiIsRefusedUnderReplayRefusal`. *As found:* nothing checks `jti` or a nonce, and there is no proof of possession (DPoP or mTLS binding). A captured bearer token works again until `exp`, plus Nimbus's 60 s clock skew (`OidcAuthenticator.java:49-50`). Throwaway test: one token with one `jti`, sent twice, was accepted both times. What limits the harm today: TLS, a short IdP lifetime (condition 5), and the inbound call being idempotent (3c). |
| 3b | Outbound webhooks sign the timestamp | PASS | `X-Inspecto-Signature` = HMAC-SHA256 over `X-Inspecto-Timestamp + "." + body` (`la/inspecto-geo-link/src/main/java/com/gamma/geolink/IntegrationWebhooks.java:46-62`). Covered by `IntegrationWebhooksTest` (an independent HMAC vector). |
| 3c | Idempotency keys honoured on both sides | PASS | Inbound: the Investigation id comes from `ext-` + sha256(system, id) (`InvestigationInboundRoutes.java:151-152`), with header CREATE_NEW, so a replay creates nothing new. Generic: the `Idempotency-Key` replay cache is keyed per caller and per issuer (`inspecto/src/main/java/com/gamma/control/ControlApi.java:970-991`, `Subject.stampIssuer`, called at `OidcAuthenticator.java:173`). Outbound: one `Idempotency-Key` for every retry (la-core `architecture-backend.md` §6.5). |
| 4a | Every machine call names the client id as actor | **PASS since 2026-11-01** (was GAP G7) | **Built:** a machine token (`gty` `client_credentials` / `client-credentials`, or a client in `-Dauth.oidc.clients`) becomes a Subject with id `client:<client id>` (`azp`, else `client_id`) and `machine()` true (`MachineTrust.machineClient`); `RequestAttrs.actorType` says `service` and `AuditTrail` adds `idp_subject` = the token's `sub`. A person's token and a gateway assertion are unchanged. `OidcMachineActorTest`, `ControlApiAuthV1Test.aMachineCallIsAuditedAsAServiceUnderItsClientId`. *As found:* The actor was `Subject.id()`, which is the token's `sub` (`spi/inspecto-auth-spi/src/main/java/com/gamma/spi/auth/RequestAttrs.java:148-149`, `OidcAuthenticator.java:159`). `actorType` is `"user"` for every authenticated Subject (`RequestAttrs.java:159-160`). On Keycloak a service account's `sub` is a user UUID, and the client id is in `azp` / `client_id`, which nothing records. Throwaway test: `s.id()` was the UUID. Whether WSO2 sets `sub` to the client id depends on the application's settings, so this cannot be relied on. A machine call is recorded as a user, under an id that only the IdP can resolve. |
| 4b | Remote case id and our delivery id cross-recorded | PASS | Inbound: `(reference.system, reference.id)` becomes an external reference on the Investigation. Hand-off: the remote case id at `caseIdPointer` is linked as an external reference `{system, type: case}`. Webhooks: `X-Inspecto-Delivery-Id` / `Idempotency-Key` = the source event id, plus `LINK_WEBHOOK_DELIVERED` / `_FAILED` on the Space's ledger (la-core `architecture-backend.md` §6.4 and §6.5). |
| 5a | Client secrets only behind `SecretResolver` references | N/A inbound · PASS outbound | Inbound trust holds no client secret: tokens are checked against the issuer's public JWKS. Outbound: a Connection with a literal token is refused at send (`HostOutboundPort.java:73-74`). |
| 5b | Rotation without downtime | PASS (one hardening note) | `RemoteJWKSet` fetches the JWKS again when it sees an unknown `kid`, so an IdP key rollover needs no restart (`OidcAuthenticator.java:231`). Hardening note (filed under G2's row): an explicit `auth.oidc.jwksUri` is not required to be https. Only the discovery URL is (`OidcConfig.java:224-228`), so a plain-http JWKS would let anyone on the path supply the signing keys. **Built 2026-11-01 (opt-in):** with `auth.oidc.clients` set, boot refuses an effective `jwksUri` that is not https (loopback http allowed, `OidcConfig.requireHttps`). The gateway JWKS is not checked. |
| 5c | Nothing logged | PASS | Every failure to verify returns empty without logging (`OidcAuthenticator.java:177-179`). No log statement in the module or in `AuthRoutes` writes a token or the `Authorization` header (grep, 2026-11-01). The boot warnings name only property keys (`:112`, `OidcConfig.java:237`). |
| 6 | A machine principal never holds `canRevealLinkEntities` by default | **PASS by code since 2026-11-01** | A machine Subject keeps only `canOpenInboundInvestigation` (B1 allowlist, `Roles.admitMachine`), so no role, seed or authored, can give it the reveal capability. The inbound test grants a machine `canRevealLinkEntities` and `canAdminister` and gets 403 on both. Caveat: a token is a machine's only when it carries `gty` or its client is in `-Dauth.oidc.clients` (condition 1 on Keycloak). |

**Space binding (asked in the task, not in the checklist): PASS since 2026-11-01** (was GAP G9; decision C2 operator-decided
2026-11-01). A machine Subject is admitted only in a Space whose `roles.toon` lists its client in `identity.machineClients`
(`Roles.admitMachine`, from `ControlApi.authenticate` once the bound Space's config root is stamped); elsewhere, with no Space
bound, or with an unreadable doc, it is refused 403. A person's token is still not bound to a Space (unchanged).
`ControlApiInvestigationInboundTest.aMachineInASpaceThatDoesNotNameItsClientIsRefused`.

**Gateway trust mode.** A gateway-signed assertion (`auth.oidc.gateway.*`) goes through the same processor, with its own issuer
and audience (`OidcAuthenticator.java:95-97`, `:150-153`), so every finding above applies to it too.

## How this was checked

- Read `providers/inspecto-oidc` (`OidcAuthenticator`, `RoleMapper`, `OidcConfig`), `platform/inspecto-access` (`Roles`),
  `inspecto/.../control/ControlApi` (the authenticate, bindSpace and Idempotency stages), `spi/inspecto-auth-spi`
  (`RequestAttrs`, `Subject`) and the la-api / geo-link integration routes.
- A throwaway JUnit class in `com.gamma.oidc` (never committed, deleted after the run) minted RS256 `at+jwt` tokens shaped
  like Keycloak client-credentials tokens (`sub` = service-account UUID, `azp` / `client_id`, `gty: client_credentials`).
  `mvn -o -Pedition-standard -pl providers/inspecto-oidc test -Dtest=ThrowawayCcReviewTest` ran **5 tests, 0 failures**.
  The tests proved G1, G2, G4, G5, G6 and G7, and that no roles means no capabilities. Caveat: the run resolved
  `inspecto-access` from `~/.m2`, not from this tree. The seed facts it relied on match the source lines cited above.
- Not checked: a live Keycloak or WSO2 client-credentials token. The claim shapes come from those providers' documented
  service-account tokens and `.claude/memory/oidc-wso2-keycloak.md`.

## Filed rows

| Gap | BACKLOG row | Kind |
|---|---|---|
| G5 | `OIDC-KEYCLOAK-AZP-ROLES-1` (P2) — closed 2026-11-01 | defect |
| G1, G2, 5b note | `OIDC-CC-CLIENT-PIN-1` — closed 2026-11-01 (opt-in) | feature |
| G3, G9 | `OIDC-CC-INTEGRATION-ROLE-1` — closed 2026-11-01 (A1 + B1 + C2, operator-decided 2026-11-01) | feature |
| G7 | `OIDC-CC-MACHINE-ACTOR-1` — closed 2026-11-01 | feature |
| G4, G6 | `OIDC-CC-LIFETIME-REPLAY-1` — closed 2026-11-01 (opt-in; DPoP / mTLS not built) | feature |
