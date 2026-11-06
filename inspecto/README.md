# Inspecto

Inspecto takes data from files, folders, feeds and databases, checks it, keeps it as Datasets you can query,
and shows it on Dashboards and in Inspecto LA (Link Analysis). In Standard and Advanced it also turns what it
finds into Incidents and Cases that someone owns. It is one server with a web console, run from this folder.

This README is for the person who installs and runs Inspecto. To use it, read the [User guide](../docs/USER_GUIDE.md).

## What is in this bundle

| Folder | Holds |
|---|---|
| `README.md` | this file |
| `bin/` | the launchers: `serve.bat` / `serve.sh` (the server and web console), `run.bat` / `run.sh` (run one Pipeline once), `install-service.ps1` / `install-service.sh` (run as an OS service) |
| `lib/` | every jar; `modules.list` (the classpath the launchers read); `edition.properties` (your edition); `duckdb/<platform>/` (DuckDB extensions, so no download is needed) |
| `deploy/` | `Dockerfile` and `Dockerfile.dockerignore`; `inspecto.service` (the systemd unit) |
| `logs/` | output captured by the Windows service (`logs/service.log`) |
| `docs/` | the [User guide](../docs/USER_GUIDE.md), the [Advanced guide](../docs/ADVANCED_GUIDE.md), [Editions](../docs/EDITIONS.md) and the [API documentation](../docs/api/) |
| `runtime/` | the bundled Java runtime; every launcher uses it before any `java` on the machine |
| `sbom/` | software bills of materials (CycloneDX and SPDX) |
| `spaces/` | your Spaces, plus `_templates/` (the Space templates offered in the console) |
| `ui/` | the web console |

An Advanced bundle also carries `licenses/`, the licence texts of its bundled native libraries.

Every launcher changes to the bundle folder before it starts, so you can run it from any directory.

**Spaces.** A Space is one area of work: its configuration, Pipelines, Datasets and Dashboards. The bundle ships
no Spaces. Create one in the console (below), or put an existing one under `spaces/` in either form:

- a folder, `spaces/<id>/`, holding the Space's `config/` folder; or
- a file, `spaces/<id>.ref`, holding the absolute path of a Space folder kept elsewhere. That folder's name must
  be `<id>`, and it must not be inside `spaces/`.

## Requirements

- Windows x64 or Linux x64. Each bundle is built for one of them; the zip's name says which
  (`inspecto-deploy-windows_amd64.zip`, `inspecto-deploy-linux_amd64.zip`).
- Java comes with the bundle in `runtime/`. A bundle without `runtime/` needs Java 27 or later as `java` on the
  `PATH`.
- Standard and Advanced: an OpenID Connect identity provider (see [Sign-in](#sign-in-standard-and-advanced)).
- Advanced: a PostgreSQL database (see [PostgreSQL](#postgresql)).

## Start it

```
bin\serve.bat          (Windows)
bin/serve.sh           (Linux)
```

Then open `http://localhost:8080/`. The server runs in the foreground and logs to the console; stop it with
Ctrl+C. To keep it running, install it as a service (below).

The launchers read these environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | the port the server and web console listen on |
| `SPACES_ROOT` | `spaces` | the folder that holds your Spaces |
| `INSPECTO_JAVA_OPTS` | (none) | extra Java options, separated by spaces, for example `-Xmx4g` or `-Dcontrol.bind=127.0.0.1`. They are added after the launcher's own options. `JAVA_OPTS` is **not** read. |

For example, on Linux: `PORT=9090 INSPECTO_JAVA_OPTS="-Xmx4g" bin/serve.sh`. On Windows:
`set "PORT=9090"` and `set "INSPECTO_JAVA_OPTS=-Xmx4g"`, then `bin\serve.bat`.

> **Listen address.** The server listens on every network interface by default. Basic has no sign-in, so anyone
> who can reach the port can use it, including the screens that change configuration. Read the *Listen address*
> section of [Editions](../docs/EDITIONS.md) before running Basic on a shared network.

## First steps in the console

In the Inspecto console (the web console a bundle ships unless it was built with the Inspecto LA application):

1. **Create a Space.** On **Home**, *Getting started* offers *Try it with sample data*, which creates a sample
   Space, loads its files and opens its Dashboard. To start from a template instead, go to **Settings → Spaces**
   and choose **New from template**, pick a template, and name the Space. The Space menu in the top bar also has
   **New Space…** and **New from sample…**.
2. **Add data.** In your Space, choose **Add data** and drop a CSV, Parquet or JSON file. Inspecto detects its
   columns, lets you review them, and makes a Dataset you can query.

The [User guide](../docs/USER_GUIDE.md) describes every screen.

## Which edition you have

`lib/edition.properties` names it: `edition=Basic`, `Standard` or `Advanced` (`Preview` in a preview build). The
launchers read it and set the edition's sign-in mode for you. [Editions](../docs/EDITIONS.md) explains what each
edition includes.

## Run a Pipeline once from the command line

```
bin\run.bat <name>     (Windows)
bin/run.sh <name>      (Linux)
```

`<name>` is the Pipeline's folder in a Space: the launcher runs the first `spaces/*/config/<name>/*_pipeline.toon`
it finds, then exits. It uses the same `INSPECTO_JAVA_OPTS` as `serve`. Its limits:

- it looks only in Space folders directly under `spaces/`. It does not read `SPACES_ROOT`, and it does not follow
  `<id>.ref` files;
- if two Spaces have a Pipeline folder of the same name, the first one found runs;
- it is not the server: nothing is scheduled, and the console is not started.

For everyday work, run Pipelines from the console instead.

## Run as an OS service

The service restarts the server if it stops, and starts it when the machine starts.

**Linux (systemd):**

```
sudo bin/install-service.sh            # options: --user <account>  --name <service>  --uninstall
systemctl status inspecto
journalctl -u inspecto -f
```

The installer writes `/etc/systemd/system/inspecto.service` from `deploy/inspecto.service`, enables and starts
it. On first install it creates `inspecto.env` in the bundle folder (mode 600). Put the service's settings there
(`PORT`, `SPACES_ROOT`, `AUTH_OIDC_*`, `INSPECTO_JAVA_OPTS` and the others in this README) and restart the service.

**Windows**, from an elevated PowerShell in the bundle folder:

```
.\bin\install-service.ps1              # options: -Name Inspecto  -Uninstall
Get-ScheduledTask -TaskName Inspecto | Get-ScheduledTaskInfo
Get-Content .\logs\service.log -Tail 50
```

On Windows the service is a Scheduled Task that runs `bin\serve.bat` as SYSTEM at start-up and restarts it within
about a minute if it stops. Its output goes to `logs\service.log`.

## Docker

The Dockerfile works in the Linux bundle only (it uses the bundled Linux runtime). From the bundle folder:

```
docker build -f deploy/Dockerfile -t inspecto .
docker run -p 8080:8080 -v /srv/inspecto/spaces:/app/spaces inspecto
```

Pass settings with `-e` (`PORT`, `SPACES_ROOT`, `AUTH_OIDC_*`, `INSPECTO_JAVA_OPTS` and the others). Mount your
Spaces at `/app/spaces` so they outlive the container. The container runs as user 10001, logs to `docker logs`,
and checks its own health at `/health`.

## Sign-in (Standard and Advanced)

Standard and Advanced sign people in through your OpenID Connect identity provider (for example Keycloak or
WSO2); Basic has no sign-in. Set these before you start the server:

| Variable | Meaning |
|---|---|
| `AUTH_OIDC_DISCOVERY_URL` | your provider's discovery document (`.../.well-known/openid-configuration`). With it, the issuer and key set are read from the provider. |
| `AUTH_OIDC_ISSUER`, `AUTH_OIDC_JWKS_URI` | the issuer and its key set, if you do not use discovery (or to override it) |
| `AUTH_OIDC_AUDIENCE` | the audience Inspecto expects in a token |
| `AUTH_OIDC_CLIENT_ID` | the console's client id at the provider (default `inspecto-spa`) |
| `AUTH_OIDC_PROVIDER` | `generic` (default), `keycloak` or `wso2` |
| `AUTH_OIDC_CLIENT_SECRET` | only for a confidential client. The launcher passes a reference to the variable, not its value. |

A Standard or Advanced server does not start without its identity provider settings. Example Keycloak and WSO2
configurations are in [`docs/api/deployment/`](../docs/api/deployment/README.md).

## PostgreSQL

Advanced keeps Incidents, Cases, notes, links and tags in PostgreSQL and does not start until you set
`INSPECTO_DB_URL` (for example `jdbc:postgresql://dbhost:5432/inspecto`), with `INSPECTO_DB_USER` and
`INSPECTO_DB_PASSWORD`. On Standard these variables are optional; when set, the server's processing records (file
status, batches and lineage) move to PostgreSQL. Basic always uses its local files.

## HTTPS

The server speaks plain HTTP by default. To serve HTTPS, set `HTTPS_KEYSTORE` to a PKCS12 file holding the
certificate and key, and `HTTPS_KEYSTORE_PASSWORD` to its password. You can also end TLS at a load balancer.

## Upgrading

1. Stop the server or the service.
2. Unzip the new bundle and replace everything **except** `spaces/` (your Spaces), `inspecto.env` (if you created
   it) and any scripts of your own.
3. Start it again. If the new bundle is in a different folder and you run Inspecto as a service, run the
   installer again from the new folder.

Read the *Upgrading* section of [Editions](../docs/EDITIONS.md) first: it lists what changed between versions
for each edition.

## Logs and troubleshooting

The server writes its log to standard output. Where it ends up depends on how you run it:

| Run as | Log |
|---|---|
| `bin/serve.sh`, `bin\serve.bat` | the console |
| Linux service | `journalctl -u inspecto` |
| Windows service | `logs\service.log` |
| Docker | `docker logs <container>` |

`http://localhost:8080/health` answers without sign-in and tells you whether the server is up.

Common start-up messages:

- *no Space attached under ./spaces*: the server started with no Space. Create one in the console, or add one to
  `spaces/` and restart.
- *no pipeline file found* (from `run`): no Space folder under `spaces/` holds `config/<name>/*_pipeline.toon`.
- *lib/edition.properties names an unknown edition*: the file was edited; restore it from the bundle.
- A Standard or Advanced server stops at start-up: check the `AUTH_OIDC_*` settings and, on Advanced,
  `INSPECTO_DB_URL`. The log names what is missing.

The [Advanced guide](../docs/ADVANCED_GUIDE.md) has troubleshooting playbooks and every start-up setting.

## Read more

- [User guide](../docs/USER_GUIDE.md): how to use Inspecto, screen by screen.
- [Advanced guide](../docs/ADVANCED_GUIDE.md): operations, start-up settings and troubleshooting.
- [Editions](../docs/EDITIONS.md): what Basic, Standard and Advanced include, and upgrade notes.
- [API documentation](../docs/api/): the HTTP contract and identity provider examples.
