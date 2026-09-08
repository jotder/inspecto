# Acquisition

The data-acquisition engine that feeds the [engine](../engine) — discover, gate, dedup, retrieve, finalize —
plus the remote [connectors](../modules/connectors.md). All six roadmap phases (A–F) have shipped.

> **Start at the capability spec.** For what was *required*, what is *left* and what was *refused* — not just how it works — read
> [**Acquisition & connectivity (`ACQ`)**](../../capabilities/acquisition/acquisition.md). The concepts below are the mechanism tier it points at.

# Concepts

* [Framework](framework.md) - the poll cycle and phases A–F: discovery, stability gate, dedup/watermark ledgers, gap detection, retry + circuit breaker.
* [Connectors](connectors.md) - the `CollectorConnector` SPI and its **eight** registered schemes (`sftp`, `ftp`, `ftps`, `db`, `s3`, `kafka`, `azure`, `gcs`), SSH tunnelling and proxy dial-through, connection profiles, secret resolution.
* [Data-acquisition framework (full design)](data-acquisition-framework.md) - the complete framework doc (moved from `docs/data_acquisition_framework.md`).
