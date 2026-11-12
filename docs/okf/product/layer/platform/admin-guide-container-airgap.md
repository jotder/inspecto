---
type: Feature
title: "Platform — container image, Helm chart and air-gapped install"
description: "How to build a Product's OCI image and Helm chart, and an air-gapped bundle that installs on a host or a cluster with no network; what each holds and what has been proven."
tags: [platform, admin-guide, container, helm, airgap, install]
timestamp: 2026-11-12T00:00:00Z
---

# Platform — container image, Helm chart and air-gapped install

Every Product build can ship as a container image with its own Helm chart, and as an **air-gapped bundle**: one tar
that installs on a site with no network. Both are made from the same `linux_amd64` bundle as the Linux zip, so the
image, the chart and the host install run identical bits (PRODUCT-ASSEMBLY-1 PA-8, decisions D-PA26 to D-PA30 in the
[product assembly plan](../../../../superpower/2026-11-10-product-assembly-plan.md)).

## Build

```powershell
# the image + chart
.\inspecto\package.ps1 -Product telco -Edition Standard -Target container -GraalvmCache C:\.jdks
# the air-gapped bundle (image + chart + the Linux bundle + an offline installer)
.\inspecto\package.ps1 -Product telco -Edition Standard -Target airgap -GraalvmCache C:\.jdks
```

Both targets need:
- **Docker.** The build is refused before it starts when Docker is not reachable.
- **The `linux_amd64` zip.** Its JVM is the image's JVM. Point `-GraalvmCache` at a folder holding a `*linux*` JDK with
  `jmods/`. The build is refused when no Linux zip was made.

The demo Distribution is refused for both, because it binds 127.0.0.1 and signs in Demo Users. A `trial` build works and
tags its image `-trial`.

`-Target airgap` implies `-RequireExtensions`: every DuckDB extension `package.ps1` stages must be in the cache, because
an offline host cannot `INSTALL` one. Populate the cache first with `node tools/fetch-duckdb-extensions.mjs`. The switch
applies to every zip the run makes, the Windows one included.

## What you get

| Target | Output under `inspecto-ship/` |
|---|---|
| `container` | `<image>-<tag>/<image>-<tag>.image.tar` (`docker save`), `<chart>-<version>.tgz` and the unpacked chart |
| `airgap` | `<image>-<tag>-airgap/` and `<image>-<tag>-airgap.tar` (+ `.sha256`) |

- **The image** is `inspecto-<product>:<platform version>-<edition>[-trial]`. The Platform product keeps the plain name
  `inspecto`. It is built with the bundle's own `deploy/Dockerfile` and carries OCI labels: `org.opencontainers.image.title`,
  `.description` and `.version`, plus `io.inspecto.product`, `.edition`, `.distribution` and `.apps`. It runs as uid 10001
  on a digest-pinned `debian:stable-slim`.
- **The chart** is stamped from the one source `deploy/helm/inspecto` (see
  [Operations & Launch Flags](admin-guide-launch-flags.md) § Kubernetes). Its name is the image name and its `appVersion`
  the image tag. It is single-replica: the N-pod chart (`SCALEOUT-HELM-NPOD-1`) waits on Postgres.
- **The air-gapped folder** holds:

  | Path | What |
  |---|---|
  | `install-offline.sh` | the installer (below) |
  | `airgap.json` | the manifest: Product, Edition, image, chart, the DuckDB extensions required and missing, `complete` |
  | `SHA256SUMS` | every other file's SHA-256, in `sha256sum -c` form |
  | `bundle/inspecto-deploy/` | the unpacked Linux bundle, with its own Java runtime |
  | `image/<image>-<tag>.image.tar` | the image |
  | `chart/<chart>-<version>.tgz` | the chart |

  No Helm, kubectl or registry binary is shipped: the site brings its own.

`tools/ship.mjs` does this work (`chart`, `image`, `airgap`). Before it builds anything, it checks that the bundle is the
Assembly Plan's build: `lib/product.json`, an ELF `runtime/bin/java`, and the Dockerfile's product label. After the
build it checks the image's label too.

## Install with no network

Copy the `.tar` and its `.sha256` to the site. Check the tar with `sha256sum -c <name>.tar.sha256`, then unpack it as
a normal user: `tar -xf <name>.tar`. A tar made on Windows records every file as writable by all, and only root keeps
those modes when it unpacks; `host` copies the bundle with your umask either way. Every installer command first checks
every file against `SHA256SUMS`.

```bash
bash install-offline.sh verify               # the check alone
bash install-offline.sh host /opt/inspecto   # a Linux host: copies the bundle, sets the exec bits
/opt/inspecto/inspecto-deploy/bin/serve.sh   # or: sudo /opt/inspecto/inspecto-deploy/bin/install-service.sh
bash install-offline.sh load registry.local:5000   # a cluster: docker load (or ctr import), retag, push
helm install telco chart/inspecto-telco-<version>.tgz --set image.repository=registry.local:5000/inspecto-telco \
     --set secrets.existingSecret=<a Secret holding AUTH_OIDC_*>
```

Standard and Advanced refuse to start without `AUTH_OIDC_ISSUER`, `AUTH_OIDC_JWKS_URI` and `AUTH_OIDC_AUDIENCE`. The
identity provider must be reachable from the site; it is the one service the install does not carry.

## Proven (2026-11-12)

- **The charts.** `helm lint --strict` (Helm v3.19, run from a local container with `--network none`) passes on the source
  and on the stamped charts for inspecto, la and telco. `helm template` renders the Product's labels and image.
- **The image.** `package.ps1 -Product telco -Edition Standard -Target container -NoUi -GraalvmCache C:\.jdks` built
  `inspecto-telco:4.0.0-SNAPSHOT-standard`. Run with `--read-only --cap-drop ALL`, it answered `/health` `UP` and
  `/ready` `READY` as uid 10001.
- **The offline host.** `ship.mjs airgap` made a 409 MB tar from that build's Linux zip. Then:
  - A `debian:stable-slim` container with `--network none` unpacked it and ran `install-offline.sh verify` and `host`.
    It started `bin/serve.sh` with the bundle's Java 27 and answered `/health` `UP` and `/ready` `READY`.
    `lib/product.json` is Inspecto Telco Standard, and the Telco templates are present.
  - The local copy of the image was removed. `install-offline.sh load` then loaded it from a fresh extraction of the
    tar, and it booted with `--network none` and a read-only root: `/health` `UP`, `/ready` `READY`.
- **Not proven here:**
  - **A Kubernetes install.** The build desk has no cluster, so `helm install` has never run against one.
  - **A complete air-gapped bundle.** The desk holds no DuckDB 2.0 Linux extensions, so the proof bundle was made with
    `--allow-missing-extensions`. Its `airgap.json` says `complete: false`, and the installer warned. That `ship.mjs airgap`
    refuses without them is covered by its tests. `package.ps1 -Target airgap` relies on the existing
    `-RequireExtensions` gate, and no packaged `-Target airgap` run was made here.
  - **The UI.** The proof build used `-NoUi`.
