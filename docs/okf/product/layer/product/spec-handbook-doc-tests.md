---
type: Spec
title: Handbook docs as tests — the check-block format
description: How a Handbook tutorial or how-to declares machine-checkable steps (```check for Control API calls, ```check-ui for console steps), how the pre-commit guard validates them without a server, how the renderer shows them to readers, and how and where the runner executes them against a live install.
item: layer.product
audience: internal
status: current
verified: 2026-11-19
code: [tools/handbook-checks.mjs, tools/run-handbook-checks.mjs, tools/check-handbook.mjs, tools/render-handbook.mjs]
---

# Handbook docs as tests

A tutorial or how-to page in the Handbook (`handbook.toon`, plan
[`2026-11-19-user-enablement-and-agent-kit-plan.md`](../../../../superpower/2026-11-19-user-enablement-and-agent-kit-plan.md)
§4.4) carries its checkable steps **in the page itself**, as fenced blocks. One block is one step. The steps of a page
run in the order they appear, against a running install, so a page whose product changed fails instead of rotting.

Three tools share one parser, `tools/handbook-checks.mjs`:

| Tool | When | What it does with a check block |
|---|---|---|
| `tools/check-handbook.mjs` | every commit (pre-commit and the CI `guards` job) | parses it, matches its route against `docs/api/openapi-v1.json`, refuses a `{variable}` used before a `save` sets it, and refuses a tutorial or how-to with no block at all. No server. |
| `tools/render-handbook.mjs` | building the Handbook | shows one quiet folded line — *Checked on every release · GET /spaces/{space}/entity-lists as analyst answers 200, 2 more expectations* — with the step itself under the fold. |
| `tools/run-handbook-checks.mjs` | on demand against an install; every release | runs the steps and reports each page pass or fail with the failing expectation. |

## The API step: ```` ```check ````

````markdown
## Check it worked

```check
GET /spaces/{space}/entity-lists
as analyst
expect status 200
expect data.lists[*].id has late-payers
expect data.lists.length >= 1
```
````

| Line | Meaning |
|---|---|
| `METHOD /path` | **First line, required.** `GET`, `POST`, `PUT`, `PATCH` or `DELETE`, and a path relative to `/api/v1` — the paths of `docs/api/openapi-v1.json`. A Space-scoped call is written `/spaces/{space}/<route>`; the guard matches `<route>` (the server's space seam). A query string is allowed. |
| `as <persona>` | Who makes the call: a Demo User or role name (`analyst`, `manager`, `admin`). **Never a credential** — see Sign-in. Omitted: the call goes as the default identity (no persona). |
| `body <json>` | The request body. The JSON may run over several lines; it ends where it parses. |
| `expect status <code>[\|<code>…]` | The answer's status. Without one, any 2xx passes. |
| `expect <path> <op> [value]` | An expectation on the answer's JSON body (the whole v1 envelope — read the resource under `data`). |
| `save <path> as <name>` | Keep a value for later steps of the same page: `{name}` in a later path or body. |
| `retry <n> every <d>ms\|s` | Re-send until every expectation holds, at most *n* times — for a Run that finishes later. |
| `# …` | A comment. |

**Paths** read the JSON body: `data.id`, `data.items[0].id`, `data.items[*].id` (every item's id, as a list),
`data.items.length` (an array's or string's length; a real key named `length` wins).

**Operators:** `=` and `!=` (deep JSON equality), `>` `>=` `<` `<=` (numbers), `has` (a list holds the value, a
string holds the text, an object has the key), `matches /regex/flags`, `exists`, `absent` (missing or null). A value
is JSON when it parses (`"text"`, `3`, `true`, `{"k":1}`), otherwise the bare word as text (`has o2c_orders`).

**Variables:** `{space}` is the runner's `--space`; every other `{name}` must be set by an earlier `save … as name`
on the same page (the guard refuses it otherwise). `--var name=value` sets one for a run.

A page's steps share one sequence: trigger a Job, keep its Run id, wait for it.

````markdown
```check
POST /spaces/{space}/jobs/o2c_detect/trigger
as analyst
expect status 202
expect data.job = "o2c_detect"
save data.runId as run
```

```check
GET /spaces/{space}/jobs/runs/{run}
as analyst
expect data.status = "SUCCESS"
retry 30 every 2s
```
````

## The console step: ```` ```check-ui ````

One JSON object per line, in the step verbs of the LA training series' recorder
(`tools/record-la-live.mjs`, its `episode` scenario — the same steps the `tools/la-training/*.episode.json` files use).
A step passes when the recorder can do every line; a `waitText` that never appears fails it.

````markdown
```check-ui
{"space":"order-to-cash"}
{"open":"/data"}
{"waitText":"o2c_orders"}
```
````

The guard knows the verbs (`UI_VERBS`), and a test keeps that list equal to the recorder's.

## Writing checks that help the reader

- Put the block **under the step it proves**, most often under *Check it worked*. The reader sees one quiet line.
- Check what the reader was told to expect — the Dataset name, the count, the state — not incidental fields
  (ids, timestamps, correlation ids) that change between installs.
- Prefer an API check. Use a UI check for what only the screen shows.
- A page that changes things (creates a Space, triggers a Job) must be safe to run again on the same install: check
  for "created or already there" (`expect status 200|409`) rather than assume a clean slate.
- A how-to the product cannot check yet is a product gap — a board row (plan §4.5), not a page without a check.

## Sign-in — never from the page

The page names a persona; the runner finds the credential:

1. A step with `as <name>`: the environment variable `INSPECTO_HANDBOOK_TOKEN_<NAME>` (upper case, other characters
   → `_`), else `tokens.<name>` in the credentials file, else the **Demo Auth exchange** (`demo:<name>`, no password —
   the sign-in the seed tools use; Standard and Preview demo builds). An exchange answered **503** means the install
   signs no one in (Basic): the step goes with no `Authorization` header. Any other refusal fails the step and names
   the variable to set.
2. A step without `as`: `INSPECTO_HANDBOOK_TOKEN`, else the file's `token`, else no header. The generic token never
   stands in for a named persona.

The credentials file (`--credentials <file>` or `INSPECTO_HANDBOOK_CREDENTIALS`) is JSON
`{"token": "…", "tokens": {"analyst": "…"}}`, kept outside the repository. A Standard install on a real OIDC provider
uses it or the variables.

## Running the checks

```
node tools/run-handbook-checks.mjs --base-url <url> [--page <id>] [--space <id>] [--var name=value]… [--credentials <file>] [--ui] [--manifest <file>] [--timeout <ms>]
```

Every manifest page with a check block runs (external and internal). A page stops at its first failing step. Exit 0:
every step that ran passed; 1: a step failed (the report names page, step, `file:line`, the expectation and what the
install answered); 2: usage — bad flags, an unknown `--page`, a page with no check, a block that does not parse.

UI steps run only with `--ui`, through the recorder (headless Chrome or Edge and ffmpeg on the machine). Without
`--ui` each is listed **NOT RUN** and counted in the summary — never reported as passed.

## Where it runs — on demand and at release, not in CI (2026-11-19)

The runner needs a running install, so it is **not** in the offline pre-commit gate; the guard covers syntax and
routes there. It is **not** a nightly workflow either, for now, because:

- no CI job boots an install today — `ci.yml`'s product matrix leaves the boot out on purpose, the reactor's
  `inspecto-processor` jar is thin since the module reorg (it no longer starts on its own), and the bundle bake that
  does start (`package.ps1`) runs only in `release.yml`;
- the checks need what a hosted runner does not have: the Order-to-cash Space seeded, the Demo Users of a Standard
  demo build, and for UI steps Chrome and ffmpeg (the recorder looks for them at Windows paths);
- no page carries a check yet — a nightly would bake a bundle every night to run nothing.

**The command**, against a Preview or Standard demo install (Demo Users, the showcase and worked-example Spaces):

```
node tools/run-handbook-checks.mjs --base-url http://127.0.0.1:8080 --space order-to-cash
node tools/run-handbook-checks.mjs --base-url http://127.0.0.1:8080 --space order-to-cash --ui
```

Run it before a release (plan §4.4: every tutorial and how-to on each release) and after changing a page's checks
(`--page <id>`). Revisit a nightly when the tutorials T1–T6 carry checks and a demo bundle can boot on a runner.

## As built (2026-11-19, P2 lane E)

- Parser, guard, renderer and runner are dependency-free Node. Tests: `tools/handbook-checks.test.mjs` (parser,
  paths, every operator, routes, variables, the reader's line, the UI verb parity with the recorder) and
  `tools/run-handbook-checks.test.mjs` (the CLI end to end against a stub Control API served in the test: demo
  sign-in, saves, retry, exit codes, NOT RUN UI steps), both run by the CI `guards` job beside the Handbook guard.
- Proven once by hand against the Preview demo install on :8080: demo sign-in, the v1 envelope, the `/spaces/{id}`
  seam, and a step with no persona failing on the 401 it got.
- Deliberately left out: per-edition skips (a page runs or fails on the install it is pointed at), header
  expectations, and a nightly job (above).
