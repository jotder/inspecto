---
name: explain-inspecto
description: Answer "what is in this Space / what does this do / where does this number come from" about a running Inspecto, from its own catalog, lineage and Handbook, without changing anything. Use for any question about Inspecto concepts, a Space's contents, or why data looks the way it does.
---

# Explain Inspecto

Read-only. Ground every answer in what the server returns; quote ids and numbers, not guesses.

## Steps

1. **The words.** Read the resource `inspecto://glossary` and answer in its canonical terms (Pipeline, Dataset,
   Expectation, Alert Rule, Incident, Measure, Collector, Widget, Dashboard, Space). Never call a Pipeline a "flow" or an
   Incident an "issue".
2. **The install.** `edition_and_modules` — what the edition includes; a feature whose module is absent is not
   available here, whatever the Handbook says about higher editions.
3. **The Space.** The resource `inspecto://catalog`, or `list_datasets`, `list_pipelines`, `list_expectations`,
   `list_alert_rules`, `list_jobs`.
4. **Where a number comes from.** `get_lineage` on the Dataset, then `get_pipeline` on the Pipeline that writes it.
5. **Is it current?** `dataset_freshness`, and `list_job_runs` for the latest runs.
6. **Is it healthy?** `health`.

Masked values stay masked in your answer. If the person needs one revealed, that is their own audited act in the
console, never yours.

## Read more

- What Inspecto is: `/help/concept_overview.html`
- Spaces: `/help/concept_space.html`
- Datasets: `/help/concept_dataset.html`

## MCP Tools

- `edition_and_modules` — edition and modules
- `list_spaces` — the Spaces the person can see
- `list_datasets` — the Datasets
- `describe_dataset` — one Dataset
- `list_pipelines` — the Pipelines
- `get_pipeline` — one Pipeline
- `list_expectations` — the Expectations
- `list_alert_rules` — the Alert Rules
- `list_jobs` — the Jobs
- `list_job_runs` — recent runs
- `get_lineage` — what feeds a Dataset
- `dataset_freshness` — how current a Dataset is
- `health` — subsystem health
