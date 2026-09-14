# `dev-infra/` — the local services the skipping test suites need

Three suites in this repo **skip** unless a real backing service is running, and a skip is not a pass. This
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
3. 🔴 **`-DforkCount=0` is load-bearing** for the `MAVEN_OPTS` route. The parent POM's surefire config is
   `<argLine>@{argLine} …</argLine>`, and that late-bound `@{argLine}` resolves the *project* property —
   a command-line `-DargLine` does not override it, so nothing you pass that way reaches a forked JVM.
   Running in the Maven JVM is what lets `MAVEN_OPTS` apply.
   ✅ **There is a better route, measured 2026-09-14: `JDK_JAVA_OPTIONS` reaches the FORK**, so the run
   stays forked and nothing has to be unforked. The JVM prints `NOTE: Picked up JDK_JAVA_OPTIONS` twice,
   once for Maven and once for the fork. ⛔ Prefer it — `-DforkCount=0` is what **hung** the reactor in
   `CORECOUNT-SWEEP-1`, and the core count it was blamed on turned out to be innocent.

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

### Roles — three settings, and missing any one looks identical

⚠ A client-credentials token carries **no roles claim at all**, so the caller authenticates with **zero
capabilities** — fail-closed and correct, but *authenticated is not authorized*. Roles need a
**user-bearing** grant plus all three of the following. 🔴 Miss any one and the symptom is the same: the
token validates and the Subject has no capabilities.

1. **A group whose name matches a seeded role** (`Roles.SEED`: `pipeline-developer`, `app-developer`,
   `developer`, `operations`, `support`, `admin`, `power`, `super`, `business`). An unrecognised name
   grants nothing — deliberately.
2. **The application requests the claim** — add `http://wso2.org/claims/groups` to its `requestedClaims`
   (`PATCH /api/server/v1/applications/{appId}`; `requestedClaims` is empty by default).
3. 🔴 **The application lists `groups` in `accessTokenAttributes`**
   (`PUT /api/server/v1/applications/{appId}/inbound-protocols/oidc`). **Without this WSO2 puts the claim
   in the id_token and userinfo ONLY** — and `OidcAuthenticator` reads the Bearer **access** token, so the
   role silently never arrives.

⛔ **The claim is `groups`, so `-Dauth.oidc.rolesClaim=groups` is required.** It is neither the `roles`
default nor Keycloak's `realm_access.roles` fallback — that fallback does not apply to this vendor at all.

```bash
read -rsp 'test user password: ' INSPECTO_TEST_OIDC_PASSWORD; export INSPECTO_TEST_OIDC_PASSWORD; echo
export INSPECTO_TEST_OIDC_USERNAME=inspecto_tester
# … then re-run the suite command above; it picks up the third test automatically.
```

Verified 2026-09-13: a user in group `pipeline-developer` authenticates with `CAN_AUTHOR_WORKBENCH`,
`CAN_AUTHOR_ALERT_RULES` and `CAN_REQUEST_SHARES` — pinned by
`OidcAgainstRealProviderTest.aRealUsersGroupBecomesRealCapabilities`.

Console: <https://localhost:9443/console> (`admin` / `admin`).

---

## MinIO — `PartitionWriterObjectStoreTest` (4 tests)

```bash
docker exec minio mc alias set local http://127.0.0.1:9000 minioadmin minioadmin
docker exec minio mc mb -p local/inspecto-lakehouse

INSPECTO_TEST_S3_ENDPOINT=127.0.0.1:9000 \
INSPECTO_TEST_S3_KEY=minioadmin INSPECTO_TEST_S3_SECRET=minioadmin \
mvn -o -B test -pl inspecto-etl -am \
    -Dtest=PartitionWriterObjectStoreTest -Dsurefire.failIfNoSpecifiedTests=false
```

⚠ **Create the bucket first — DuckDB will not**, and the failure reads as a credentials problem.
⚠ The suite SKIPS all 4 without those three variables, and **a skip is not a pass**: it was run green
on 2026-09-14 against this service.

⚠ Every object-store measurement in the scale-out plan §5.4 was originally taken against a MinIO started
ad hoc with `docker run`, on one workstation, with no compose project label and no mention in this repo.
It is declared in `docker-compose.yml` as of 2026-09-14 so the numbers are reproducible; ⛔ **treat any
earlier object-store figure as unreplicated until it has been re-taken against this service.**

Console: <http://localhost:9001> (`minioadmin` / `minioadmin`). S3 API on `127.0.0.1:9000`, loopback only.

⛔ **Adopting this service on a machine that already runs the ad-hoc container is a DESTRUCTIVE step, so
it was not done for you.** The compose file was validated (`docker compose config`) but not brought up:
the running container holds the `inspecto-lakehouse` bucket in its own storage, and `docker rm`-ing it to
let compose create the `miniodata` volume **discards that bucket**. Either mirror the contents out first
(`mc mirror local/inspecto-lakehouse …`) or accept the loss deliberately — do not let a later shift
discover it by finding an empty bucket.

Create a bucket before writing to it — DuckDB will not create one:

```bash
docker exec minio mc alias set local http://127.0.0.1:9000 minioadmin minioadmin
docker exec minio mc mb -p local/inspecto-lakehouse
```

What was measured against it on 2026-09-14, with `duckdb_jdbc` 1.5.2.1 and **no** `LOAD` statement
anywhere (`httpfs` autoloads; `aws` never loaded at all):

```sql
SET s3_endpoint='127.0.0.1:9000'; SET s3_use_ssl=false; SET s3_url_style='path';
SET s3_access_key_id='minioadmin'; SET s3_secret_access_key='minioadmin';
COPY t TO 's3://inspecto-lakehouse/parts' (FORMAT PARQUET, PARTITION_BY (part), OVERWRITE_OR_IGNORE 1);
SELECT file FROM glob('s3://inspecto-lakehouse/parts/**/*.parquet');
SELECT file_name, sum(total_compressed_size) FROM parquet_metadata('s3://…/**/*.parquet') GROUP BY 1;
```

Three findings that shape the write path, recorded in the plan's §5.4 bullet 1:

1. ⚠ **Five `SET s3_*` statements are required** and nothing in the Java source issues any of them —
   there is no DuckDB S3 wiring in the product at all.
2. ✅ **The outputs are enumerable after the fact** — `glob()` lists them and `parquet_metadata()` gives
   per-file compressed sizes — which is what lets an object-store lane still produce the
   `PartitionOutput(partition, file, bytes)` rows the local lane produces by walking a staging dir.
   ⚠ `total_compressed_size` is the column-chunk sum, **not** the object's byte length.
3. 🔴 **A repeat write ACCUMULATES, it does not replace.** Two partitioned writes with different
   `FILENAME_PATTERN` values left 6 files where the local lane's atomic reveal would have left 3.
   `FILENAME_PATTERN 'batch42_out'` yields `batch42_out0.parquet` — DuckDB appends its own index, so the
   name is controllable but not identical to the local lane's `<baseName>_out.parquet`.
