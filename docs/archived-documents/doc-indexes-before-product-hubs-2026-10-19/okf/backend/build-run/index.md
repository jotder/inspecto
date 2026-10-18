# Build & Run

How to verify, package, and launch the backend.

# Concepts

* [Build & test](build-test.md) - the offline Maven verify loop, the mandatory DuckDB native-access flag, packaging per edition.
* [Guards that cannot fail](guard-coverage.md) - the enforcement surface: which guards are wired, the two
  shapes that silently cannot fail (never reached / subject is its own module), the rules for writing one
  that can, and why a reopen trigger nobody can check is a permanent refusal by accident.
* [Operations](operations.md) - launch flags, run modes, and where to look when investigating production.
* [Operations reference](operations-reference.md) - utilities, batching, deployment recipes (moved from the retired root-level `operations.md`).
* [Troubleshooting](troubleshooting.md) - symptom → cause → fix playbooks (moved from the retired root-level `troubleshooting.md`).
* [Performance](performance.md) - measured throughput baselines and tuning levers (moved from the retired root-level `performance.md`).
