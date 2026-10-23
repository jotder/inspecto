---
type: TestsInventory
title: Backup and restore — tests inventory
item: module.backup
description: Generated list of the test classes for module.backup, with how to run them.
tags: [tests, module]
---

# Backup and restore — tests inventory (`module.backup`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `features/inspecto-backup/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.backup`](../../../../../product-map/module/backup.toon).

10 test class(es): 9 unit, 1 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl features/inspecto-backup
mvn -o test -pl features/inspecto-backup -Dtest=BackupSignalsTest,BackupTaskProviderTckTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `BackupSignalsTest` | `com.gamma.backup` | unit | `features/inspecto-backup/src/test/java/com/gamma/backup/BackupSignalsTest.java` | Pins every emitted Signal type byte-for-byte: they are persisted and matched, so a rename is a data break. |
| `BackupTaskProviderTckTest` | `com.gamma.backup` | unit | `features/inspecto-backup/src/test/java/com/gamma/backup/BackupTaskProviderTckTest.java` | {@link BackupTaskProvider} against the platform's MaintenanceTaskProvider TCK (MODULE-REORG-1 P5b). |
| `BackupTaskSecretExclusionTest` | `com.gamma.backup` | unit | `features/inspecto-backup/src/test/java/com/gamma/backup/BackupTaskSecretExclusionTest.java` | What no backup may carry, as {@link BackupTask#secret} decides it. |
| `ControlApiRestoreJobGateTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-backup/src/test/java/com/gamma/control/ControlApiRestoreJobGateTest.java` | MAINT-RESTORE-ESCALATION-1: a {@code restore} maintenance Job writes an archive's files into any jailed |
| `ProfessionalSinkFeaturesAcceptedTest` | `com.gamma.control` | unit | `features/inspecto-backup/src/test/java/com/gamma/control/ProfessionalSinkFeaturesAcceptedTest.java` | The STANDARD half of `PROCESSOR-RELEASE-READINESS-1` G9 for the two sink features: with this module on |
| `BackupCatalogSpaceRootTest` | `com.gamma.job` | unit | `features/inspecto-backup/src/test/java/com/gamma/job/BackupCatalogSpaceRootTest.java` | {@code BACKUP-CATALOG-SPACE-ROOT-1} — <b>a backup in a named Space registers its {@code maintenance_backups} |
| `BackupJobPathResolutionTest` | `com.gamma.job` | unit | `features/inspecto-backup/src/test/java/com/gamma/job/BackupJobPathResolutionTest.java` | The three backup tasks resolve a RELATIVE path value against the <b>Space config root</b>, not the |
| `BackupPathContainmentTest` | `com.gamma.job` | unit | `features/inspecto-backup/src/test/java/com/gamma/job/BackupPathContainmentTest.java` | Path containment for the three backup tasks — every operator-supplied directory is jailed against the |
| `BackupTaskTest` | `com.gamma.job` | unit | `features/inspecto-backup/src/test/java/com/gamma/job/BackupTaskTest.java` | The backup / backup_verify / restore tasks (MNT-5 / MNT-6), driven the way production drives them — |
| `ModuleRemovalBackupTest` | `com.gamma.job` | unit | `features/inspecto-backup/src/test/java/com/gamma/job/ModuleRemovalBackupTest.java` | {@code MODULE-REORG-1 P4a} (plan §2.5): backup and restore carry the config and store families of a module |
