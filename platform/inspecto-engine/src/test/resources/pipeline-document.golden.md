# Pipeline: subscriber_etl

| | |
|---|---|
| Pipeline | `subscriber_etl` |
| Status | Active |
| Trigger | cron=0 * * * * |
| Config fingerprint | `abc123` |

> Generated from configuration — never hand-authored. The fingerprint above binds this
> document to the exact configuration that produced it: if it no longer matches, the
> configuration has changed and this document's sign-off is stale.

## Steps

| # | Step | Summary |
|---|---|---|
| 1 | Collect | connections/sftp_main |
| 2 | Parse | grammars/subscriber |
| 3 | Map | schemas/subscriber |
| 4 | Transform | join references/lookup |
| 5 | Transform | filter AMOUNT > 0 |
| 6 | Dedup | key MSISDN, EVENT_DATE |
| 7 | Summarize | by REGION |
| 8 | Sink | warehouse |

### 1. Collect

| Setting | Value |
|---|---|
| `connection` | connections/sftp_main |
| `files` | *.csv |
| `password` | •••• |

### 2. Parse

| Setting | Value |
|---|---|
| `grammar` | grammars/subscriber |
| `format` | CSV |

### 3. Map

| Target | Source | Kind | Type | Unit | Description | Classification |
|---|---|---|---|---|---|---|
| MSISDN | MSISDN | DIRECT | VARCHAR |  | Subscriber number | PII |
| AMOUNT | CAST(AMOUNT AS DOUBLE) | EXPR | DOUBLE | EUR |  |  |
| REGION | 2 |  | VARCHAR |  |  |  |

### 4. Transform

| Setting | Value |
|---|---|
| `join` | references/lookup |
| `on` | MSISDN |

### 5. Transform

| Setting | Value |
|---|---|
| `filter` | AMOUNT > 0 |

### 6. Dedup

| Setting | Value |
|---|---|
| `key` | MSISDN, EVENT_DATE |
| `order_by` | ts DESC |

### 7. Summarize

| Setting | Value |
|---|---|
| `group_by` | REGION |
| `measures` | sum(AMOUNT) |

### 8. Sink

| Setting | Value |
|---|---|
| `database` | warehouse |
| `format` | PARQUET |

## Guarantees

| Guarantee | Configuration |
|---|---|
| File dedup | mode=hash |
| Quarantine | dirs/quarantine |
| Retention | 30 |

## Referenced components

| Reference | Resolved |
|---|---|
| `grammars/subscriber` | yes |
| `schemas/subscriber` | yes |
| `mappings/subscriber` | yes |
