---
name: author-pipeline
description: Draft, dry-run and propose an Inspecto Pipeline - the import that lands a file in a Dataset, or a Pipeline that derives one Dataset from others (join, summarize, pivot). Use when the person asks to load, clean, join, aggregate or reshape data in a Space.
---

# Author a Pipeline

A **Pipeline** reads from a Collector or from other Datasets, runs its Steps, and writes Datasets. You draft it as a
graph, prove it on sample rows, and propose it (skill `safe-config-change`).

## Steps

1. **Know what is there.** `list_datasets`, then `describe_dataset` for each input: its columns, types and
   classifications. `list_pipelines`, then `get_pipeline` on the closest existing Pipeline — copying a working graph's
   shape is far more reliable than writing one from nothing.
2. **Look at real rows.** `query_dataset` with a small `limit` (for example `SELECT * FROM <dataset> LIMIT 20`). The
   query is read-only and masked columns come back masked; never ask for them unmasked.
3. **Draft the graph.** Keep the node ids readable; one Step per idea. For a derived Dataset (a join, a daily summary,
   a pivot), follow the recipe page below rather than guessing Step names.
4. **Validate and dry-run in one call** with `dry_run_pipeline`: argument `pipeline` = the Pipeline id (a new one
   too), and `body` `{pipeline: <draft graph>, sampleRows: [<rows from step 2>]}`. The draft graph is parsed and validated before it
   runs, so a refusal (400 or 422) names what to fix. (`validate_config` with `type: pipeline` judges the Pipeline's
   *config file* form, not the graph `propose_change` takes — do not use it for a graph.)
5. **Read the result.** Check the per-step row counts and the output columns against what the person asked for. If a
   count is zero or a column is missing, fix the draft and dry-run again.
6. **Propose** with `propose_change` (`kind: pipeline`, `operation: create` or `update`, `id`, `content: <graph>`,
   `reason`). Report the Pending Change id.
7. **After approval** (the person tells you, or `get_pending_change` shows `approved`), run it once with
   `run_pipeline_now` if the person wants data now, then check the result with `get_run` and `query_dataset`.

## What the agent cannot do — say so, do not work around it

- **Upload a file or register a new Dataset**: these routes are closed to an agent token (403). Ask the person to add
  the file in the console (Add data), then build on the Dataset it creates.
- **Create a Collector or a connection**: an administrator's step.
- A machine (unattended) agent may not run a Pipeline; only a delegated person's agent may.

## Read more

- Build a Pipeline from a sample: `/help/howto_pipeline_from_a_sample.html`
- Derive a Dataset from others: `/help/howto_derive_a_dataset.html`
- The Pipeline concept: `/help/concept_pipeline.html`
- Tutorial — Pipeline and Expectations: `/help/tutorial_pipeline_expectations.html`

## MCP Tools

- `list_datasets` — the Datasets of the Space
- `describe_dataset` — columns, types, classifications
- `query_dataset` — read-only SQL on one Dataset, masked
- `list_pipelines` — the Pipelines of the Space
- `get_pipeline` — one Pipeline's graph
- `dry_run_pipeline` — the draft validated and run on sample rows
- `validate_config` — not for a graph (see step 4)
- `propose_change` — the held write
- `get_pending_change` — whether it was approved
- `run_pipeline_now` — one run, after approval
- `get_run` — the run's result
