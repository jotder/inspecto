---
type: How-to
title: "Let an AI agent operate runs"
description: "Let an AI agent watch and fix runs on a Standard install: signed in as a person it re-runs, resumes, reprocesses or drains within that person's rights at once; as an unattended machine agent every such action waits as a Pending Action for a person to approve on the Pending Changes screen."
item: module.mcp
audience: external
status: current
verified: 2026-11-25 code-reviewed
code: features/inspecto-mcp
---

# Let an AI agent operate runs

## Goal

An AI agent does an operations support engineer's work: it reads health, runs and freshness, diagnoses a failed run,
and — once the cause is known — re-runs, resumes, pauses, reprocesses a batch, replays rejected rows, retries or drains a
run, or triggers or replays a Job. Who the agent is decides what happens:

- **Signed in as a person** (through an agent client): the action runs **at once**, within that person's own rights,
  exactly as if the person had pressed the button. The audit row names the person and the agent client.
- **An unattended machine agent**: every action is **held** as a **Pending Action** and runs only once a person
  approves it.

An agent never approves, never releases a quarantined file and never takes a backup.

## Before you start

- **Edition:** Standard, Advanced or Preview with sign-in through an OpenID Connect provider.
- **A person's agent:** set up as in *Let an AI agent propose changes* (an agent client listed in
  `auth.oidc.agentClients`, the Agent Kit installed). The person needs `canOperateRuns` in the Space.
- **A machine agent:** a machine client already set up as in *Call the Control API with a machine client* (client
  credentials, listed in `auth.oidc.clients`, named in the Space's `roles.toon` `identity.machineClients`), holding a
  role that grants `canOperateRuns`.
- **An approver:** a person who holds `canApproveChanges` and is on the Space's approver roster.

## Steps

1. **For a machine agent**, list its client as an agent machine client too, and restart:

   ```text
   INSPECTO_JAVA_OPTS=-Dauth.oidc.clients=ops-bot -Dauth.oidc.agentMachineClients=ops-bot
   ```

   The server refuses to start if an agent machine client is not also in `auth.oidc.clients`. Such a client keeps at
   most `canAuthorWorkbench`, `canAuthorAlertRules`, `canWorkIncidents` and `canOperateRuns`, whatever its role grants.
2. **Give the agent the operations skills** — `diagnose-failed-run` and `operate-runs` in the bundle's
   `agent-kit/skills` folder (the vendor packages carry them already).
3. **Ask for an outcome**, for example "the shipments data stopped refreshing — find out why", then "resume it".
   The agent reads first (`health`, `list_runs`, `get_run_batches`, `dataset_freshness`) and acts only on a cause a
   re-run fixes. It asks before it drains or reprocesses.
4. **A machine agent's action waits.** It reports a Pending Action id (`pa-…`). On the **Pending Changes** screen,
   open the **Pending Actions** tab: each row names the action (`POST /runs/<run>/resume`), the machine that asked and
   its reason. Approve to run it — it runs as that machine, with every check made again — or decline it.

## Check it worked

The Pending Actions list answers, so the approver can see what machines asked for:

```check
GET /pending-actions
as admin
expect status 200
```

After a person's agent acts, the audit search for its agent client shows the action's own row (for example
`/runs/<run>/resume`, status 200) with the person as the actor. After a machine agent asks, the list holds a Pending
Action with status `pending`; once approved, its status is `approved` and the run's state has changed.

## What can go wrong

- 403 "missing capability canOperateRuns" — the person (or the machine's role) lacks the right to operate runs.
- 409 "already pending approval (pa-…)" — the same action is already waiting; approve or decline that one.
- An approved action ends `stale` — the run moved on before approval (it was resumed already, or nothing was left to
  drain); nothing ran. Ask the agent to check the run again.
- An approved action ends `failed` — the run's route refused it; the record keeps the status it answered.
- The machine's request is refused outright — its client is not named in the Space's `roles.toon`, or it is not in
  `auth.oidc.agentMachineClients` (then it is an ordinary machine client, which may not operate runs).

## Next

- [Let an AI agent propose changes](admin-guide-howto-agent-propose-changes.md)
- [Call the Control API with a machine client](../oidc/admin-guide-howto-machine-client.md)
