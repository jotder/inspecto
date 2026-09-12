# `dev-infra/` — the local services the skipping test suites need

Two suites in this repo **skip** unless a real backing service is running, and a skip is not a pass. This
directory exists so each shift gets the same services instead of re-deriving them, and so the commands
that turn those suites on are written down — several are not guessable and cost a run each to find.

```bash
docker compose -f dev-infra/docker-compose.yml up -d
```

⚠ **Not a deployment artifact.** The shipped topology is [`docs/api/deployment/README.md`](../docs/api/deployment/README.md).
Credentials here are dev-only and deliberately trivial.

---

## PostgreSQL — `PostgresStateStoreTest` (15 tests)

```bash
MAVEN_OPTS="-Duser.timezone=Asia/Kolkata" \
INSPECTO_TEST_PG_URL='jdbc:postgresql://localhost:5432/postgres?user=postgres&password=postgres' \
mvn -o -B test -Pedition-enterprise -pl inspecto-ops -am \
    -Dtest=PostgresStateStoreTest -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=0
```

Three traps, each of which cost a run on 2026-09-12:

1. 🔴 **Use the env var, not `-D`, on Windows.** `mvn.cmd` re-parses the argument list, so the `&` in the
   JDBC URL splits it and the password is silently lost — the driver then fails SCRAM with *"no password
   was provided"*.
2. 🔴 **PostgreSQL 18 dropped the legacy `Asia/Calcutta` alias** that a Windows JVM reports. pgjdbc sends
   `TimeZone.getDefault().getID()` as a startup parameter, so the handshake dies with *invalid value for
   parameter "TimeZone"*. ⚠ The fix must preserve the **offset** — `Asia/Kolkata`, never `UTC`, which
   would move `record_day` boundaries and change what the assertions mean.
3. 🔴 **`-DforkCount=0` is load-bearing.** The parent POM's surefire config is
   `<argLine>@{argLine} …</argLine>`, and that late-bound `@{argLine}` resolves the *project* property —
   a command-line `-DargLine` does not override it, so nothing you pass that way reaches a forked JVM.
   Running in the Maven JVM is what lets `MAVEN_OPTS` apply.

## WSO2 Identity Server — `OidcAgainstRealProviderTest` (2 tests)

### One-time: register the OAuth application

⚠ Needed again after any `docker rm` — the registration lives in the container layer.

⚠ The secret is supplied from the environment, never typed into a file the repo tracks — the
committed-secret guard refuses a literal here, and it is right to: a runbook that teaches pasting
credentials onto command lines teaches the habit that leaks the real ones.

```bash
read -rsp 'choose a dev client secret: ' INSPECTO_OIDC_SECRET; export INSPECTO_OIDC_SECRET; echo

curl -sk -u admin:admin -H "Content-Type: application/json" -d '{
  "client_name": "inspecto-spa",
  "grant_types": ["client_credentials", "authorization_code", "refresh_token"],
  "redirect_uris": ["http://localhost:4200/callback"],
  "ext_param_client_id": "inspecto_spa_client",
  "ext_param_client_secret": "'"$INSPECTO_OIDC_SECRET"'",
  "ext_token_type": "JWT"
}' https://localhost:9443/api/identity/oauth2/dcr/v1.1/register
```

- ⛔ **`ext_token_type: JWT` is mandatory.** WSO2 issues an **opaque** access token by default, and
  `OidcAuthenticator` is a JWT validator — every request 401s with nothing in the log saying why.
- ⚠ **The client id must match `[a-zA-Z0-9_]{15,30}`**, which is why it is not the documented default
  `inspecto-spa` — that is too short and hyphens are refused.

### Trust the self-signed certificate

There is **no TLS-skip option in `inspecto-security`, by design**: JWKS is fetched with stock Nimbus over
the JVM default truststore.

```bash
echo | openssl s_client -connect localhost:9443 -servername localhost 2>/dev/null \
  | openssl x509 > wso2is.crt
keytool -importcert -noprompt -alias wso2is -file wso2is.crt -keystore ts.jks -storepass changeit
```

### Run the suite

```bash
MAVEN_OPTS="-Djavax.net.ssl.trustStore=$PWD/ts.jks -Djavax.net.ssl.trustStorePassword=changeit" \
mvn -o -B test -Pedition-enterprise -pl inspecto-security -am -DforkCount=0 \
    -Dtest=OidcAgainstRealProviderTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dinspecto.test.oidc.issuer=https://localhost:9443/oauth2/token \
    -Dinspecto.test.oidc.clientId=inspecto_spa_client \
    -Dinspecto.test.oidc.clientSecret="$INSPECTO_OIDC_SECRET"
```

⚠ `OidcAgainstRealProviderTest` also reads `INSPECTO_TEST_OIDC_ISSUER` / `_CLIENT_ID` /
`_CLIENT_SECRET` from the environment, which is the better route on Windows — an env var never passes
through `mvn.cmd`'s argument re-parsing.

⚠ **Read every value from the provider's own `/.well-known/openid-configuration`**, never derive it.
`-Dauth.oidc.tokenEndpoint` is required precisely because no vendor path layout is assumed (BACKLOG D15),
and WSO2's issuer is `…/oauth2/token` rather than a Keycloak-style realm path.

🔴 **Roles are a known gap.** A client-credentials token carries **no roles claim at all** — neither
`roles` nor Keycloak's `realm_access.roles` — so `RoleMapper` grants nothing and the caller authenticates
with **zero capabilities**. Fail-closed and correct, but *authenticated is not authorized*: issuing roles
needs a user-bearing grant and IdP-side claim mapping, which is not configured here.

Console: <https://localhost:9443/console> (`admin` / `admin`).
