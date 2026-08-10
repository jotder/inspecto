import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { apiUrl, toParams } from './api-base';
import { JobRun, JobType, JobView } from './models';

/** A single scheduled job with its full config (GET /jobs/{name}) — the list `JobView` plus the type-specific
 *  `params`, the trigger guard and the catch-up flag. (List endpoint omits these; they're shown on the
 *  detail page.) This is the UI-facing shape; `jobFromWire` maps the server's own shape onto it. */
export interface JobDetail extends JobView {
  params?: Record<string, unknown>;
  catchUp?: boolean;
  /** Guard expression over the firing Signal's payload; only meaningful with `onSignal`. */
  when?: string | null;
}

/** The editable shape for create (POST /jobs) and edit (PUT /jobs/{name}). A job is cron-scheduled, event-driven
 *  (`onPipeline`), signal-driven (`onSignal`, optionally narrowed by `when`), or manual (none of them). */
export interface JobUpsert {
  name: string;
  type: JobType;
  cron?: string | null;
  onPipeline?: string | null;
  onSignal?: string | null;
  when?: string | null;
  enabled: boolean;
  catchUp?: boolean;
  params?: Record<string, unknown>;
}

/** The job keys the server recognises as config rather than as a type-specific parameter — `JobConfig.fromMap`'s
 *  known-key set. Anything else at the top level of a job body IS a parameter. */
const JOB_WIRE_KEYS = ['name', 'type', 'cron', 'on_pipeline', 'on_signal', 'when', 'enabled', 'catch_up', 'args', 'bind'];

/**
 * A `JobUpsert` as the write endpoints actually accept it (`POST /jobs`, `PUT /jobs/{name}`).
 *
 * The body **is** the `job:` TOON section in JSON, so it is **flat** (parameters sit alongside the config
 * keys, not nested under `params`) and **snake_case**. This matters more than it looks: the server sweeps
 * every unrecognised top-level key into the job's parameters instead of rejecting it, so a camelCase
 * `onPipeline` is silently absorbed as an inert parameter and the job ends up with no trigger at all.
 * Pinned server-side by `ControlApiJobCrudTest`.
 */
export function jobToWire(u: JobUpsert): Record<string, unknown> {
  const body: Record<string, unknown> = { name: u.name, type: u.type, enabled: u.enabled };
  if (u.cron) body['cron'] = u.cron;
  if (u.onPipeline) body['on_pipeline'] = u.onPipeline;
  if (u.onSignal) body['on_signal'] = u.onSignal;
  if (u.when) body['when'] = u.when;
  if (u.catchUp) body['catch_up'] = true;
  // Parameters are flattened in beside the config keys — never nested, and never shadowing one.
  for (const [k, v] of Object.entries(u.params ?? {})) {
    if (!JOB_WIRE_KEYS.includes(k) && v !== null && v !== undefined && v !== '') body[k] = v;
  }
  return body;
}

/** The inverse: the flat `job:` section the detail/enable/disable/reschedule endpoints return, mapped onto
 *  the UI-facing `JobDetail`. Every key the server does not treat as config is a type-specific parameter. */
export function jobFromWire(raw: Record<string, unknown>): JobDetail {
  const params: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(raw ?? {})) if (!JOB_WIRE_KEYS.includes(k)) params[k] = v;
  return {
    name: String(raw?.['name'] ?? ''),
    type: String(raw?.['type'] ?? '') as JobType,
    cron: (raw?.['cron'] as string) ?? null,
    onPipeline: (raw?.['on_pipeline'] as string) ?? null,
    onSignal: (raw?.['on_signal'] as string) ?? null,
    when: (raw?.['when'] as string) ?? null,
    enabled: raw?.['enabled'] !== false,
    catchUp: raw?.['catch_up'] === true || raw?.['catch_up'] === 'true',
    params,
  };
}

/**
 * One declared parameter of a Job Type (GET /jobs/types/{id}, R3) — drives the authoring form.
 *
 * Mirrors `ParameterDecl.toMap()`. The rendering + validation contract (job-parameter-contract §7.2)
 * is declared **optional here even though a current server always sends it**: this client also talks to
 * servers predating that contract, and every consumer already defaults the absent value to the
 * pre-contract behaviour. Unset strings/lists arrive as `''`/`[]`, never null — except `min`/`max`,
 * which stay null when unbounded because `0` is a meaningful bound.
 */
export interface JobParameterDecl {
  name: string;
  /** STRING | TEXT | EMAIL | INTEGER | DECIMAL | BOOLEAN | DATE | INSTANT | DATASET_REF */
  type: string;
  required: boolean;
  /** $-expression the platform deduces when unbound (e.g. `$day(-1)`); '' when none. */
  deduce: string;
  /** Literal fallback; '' when none. For a `multi` parameter this is CSV, matching the resolver. */
  default: string;
  description: string;
  /** Human field label; '' ⇒ the humanised `name`. */
  label?: string;
  /** REQUIRED | OPTIONAL | ADVANCED — disclosure, deliberately decoupled from `required`. */
  tier?: string;
  /** Allowed values ⇒ renders a choice; `[]` when unconstrained. */
  options?: string[];
  /** Regex the value must fully match; '' when none. Under `multi`, applies per item. */
  pattern?: string;
  /** Inclusive numeric bounds; null when unbounded. */
  min?: number | null;
  max?: number | null;
  placeholder?: string;
  /** Section heading that orders the form; '' when none. */
  group?: string;
  /** The value is a list of `type` (CSV on the wire), validated per item. */
  multi?: boolean;
  /** Mask on input; the server masks it in API reads at the response boundary. */
  secret?: boolean;
  /** Whether `$`-tokens are accepted here; false forces a literal. Defaults to true. */
  expressions?: boolean;
}

/** One Job Type's catalog metadata (GET /jobs/types[/{id}]) — the descriptor that drives authoring. */
export interface JobTypeDescriptor {
  id: string;
  title: string;
  description: string;
  parameters: JobParameterDecl[];
  emits: string[];
  artifacts: { name: string; kind: string }[];
  /** Platform Service ids this type is granted (`requires:`, platform-services S1-2); [] for most types. */
  requires: string[];
  /**
   * Provenance (§7.3) — where this type came from, so "what is this job" is answerable from the UI.
   * Assembled by the REGISTRY, not the descriptor: a provider cannot know its own provenance.
   * Optional here because a server predating step 9 omits them.
   */
  implClass?: string;
  /** `builtin` | `classpath` | `pack:<id>`. */
  source?: string;
  /** The owning Job Pack's version; '' for anything not from a pack. */
  version?: string;
}

/**
 * One declared Expression of the runtime vocabulary (GET /jobs/expressions, §4.3) — what the authoring
 * form's token picker offers, generated from the `ExpressionRegistry` so a Job Pack's tokens appear with
 * no UI change.
 *
 * Mirrors `ExpressionDecl.toMap(preview)`. ⚠ **`preview` is the server's own evaluation** — the registry
 * computes it with the same evaluator a Run uses, which is what makes the picker's preview correct by
 * construction. §4.3 forbids a client-side evaluator: a second implementation is a second answer.
 */
export interface JobExpressionDecl {
  /** The declared surface: `$today` (LITERAL), `$signal.` (PREFIX) or `$day(n)` (FUNCTION). */
  token: string;
  form: 'LITERAL' | 'PREFIX' | 'FUNCTION' | string;
  /** The `ParamType` it resolves to, so a field offers only tokens it can hold. */
  yields: string;
  description: string;
  /**
   * The worked sample. For a shaped token (PREFIX/FUNCTION) this is an instance an author can actually
   * type (`$day(-1)`) — the token itself is only a shape; for a LITERAL it is the value it resolves to.
   * That split is why {@link typeableForm} exists rather than reading one field.
   */
  example: string;
  /** Trigger kinds it is meaningful on, lowercase: `cron` | `on_pipeline` | `on_signal` | `manual`. */
  availableIn: string[];
  /** Resolves from fire time alone. When false there is no firing context at request time, so `preview`
   *  is the declared sample rather than a live value. */
  contextFree: boolean;
  preview: string;
}

/**
 * The form of a token an author can actually type — a LITERAL's own token, or a shaped token's example
 * (`$day(n)` is a shape; `$day(-1)` is what resolves). Mirrors `ExpressionDecl.sampleExpression()`, the
 * same rule the server previews through, so the picker inserts a value the registry can evaluate.
 */
export function typeableForm(decl: JobExpressionDecl): string {
  return decl.form === 'LITERAL' ? decl.token : decl.example;
}

/** One log line for a job run (GET /jobs/{name}/runs/{runId}/logs). */
export interface JobLogLine {
  ts: string;
  level: 'INFO' | 'WARN' | 'ERROR' | 'DEBUG' | string;
  message: string;
}

/** One domain event emitted during a job run. */
export interface JobEvent {
  ts: string;
  type: string;
  message: string;
}

/** A run's logs + events (GET /jobs/{name}/runs/{runId}/logs). */
export interface JobRunLogs {
  logs: JobLogLine[];
  events: JobEvent[];
}

/** A generated export (C6) behind a `type:'report'` job's completed run (GET .../runs/{runId}/artifact). */
export interface ReportArtifact {
  runId: string;
  filename: string;
  mime: string;
  content: string;
}

/** Aggregate job-execution metrics (GET /jobs/metrics) — the DuckDB reporting projection (T27). */
export interface JobMetrics {
  total: number;
  success: number;
  failed: number;
  successRate: number; // 0..1
  p50Ms: number;
  p95Ms: number;
  meanMs: number;
}

/** One durable job-run row (GET /jobs/runs) — survives restarts (unlike the in-memory /jobs/{n}/runs). */
export interface JobRunRow {
  runId: string;
  job: string;
  type: string;
  trigger: string;
  startTime: string;
  endTime: string;
  status: string;
  durationMs: number;
  message: string;
}

/** One recorded Run Artifact (R7) — a produced Dataset or file, from `/jobs/{name}/artifacts/latest`. */
export interface RunArtifactRow {
  runId: string;
  job: string;
  seq: number;
  name: string;
  kind: 'dataset' | 'file' | string;
  ref: string | null;
  rows: number;
  bytes: number;
  at: string;
}

/** A day in the failure trend (GET /jobs/failures): total runs and how many failed that day. */
export interface JobFailureDay {
  day: string; // yyyy-MM-dd
  total: number;
  failed: number;
}

/** Config-driven jobs: cron / event / manual (CONTROL scope). 404 when no jobs are registered. */
@Injectable({ providedIn: 'root' })
export class JobsService {
  private http = inject(HttpClient);

  list(): Observable<JobView[]> {
    return this.http.get<JobView[]>(apiUrl('/jobs'));
  }
  /** The registered Job Types (R3) — drives the authoring form's type picker. */
  types(): Observable<JobTypeDescriptor[]> {
    return this.http.get<JobTypeDescriptor[]>(apiUrl('/jobs/types'));
  }
  /** One Job Type's descriptor — its declared parameters generate the form (Workbench → Jobs, Phase D). */
  describeType(id: string): Observable<JobTypeDescriptor> {
    return this.http.get<JobTypeDescriptor>(apiUrl(`/jobs/types/${encodeURIComponent(id)}`));
  }
  /** The runtime Expression vocabulary (§4.3) — the token picker's source, previews included. */
  expressions(): Observable<JobExpressionDecl[]> {
    return this.http.get<JobExpressionDecl[]>(apiUrl('/jobs/expressions'));
  }
  runs(name: string): Observable<JobRun[]> {
    return this.http.get<JobRun[]>(apiUrl(`/jobs/${encodeURIComponent(name)}/runs`));
  }
  /** v1 async contract (W5): 202 + the submitted run's id; poll `/jobs/runs/{runId}` for status. */
  trigger(name: string): Observable<{ runId: string }> {
    return this.http.post<{ runId: string }>(apiUrl(`/jobs/${encodeURIComponent(name)}/trigger`), {});
  }

  // ── single job + management ──
  // These five all carry the server's flat `job:` section, so they go through `jobFromWire`/`jobToWire`
  // rather than being typed straight onto `JobDetail` — see those functions for why.
  get(name: string): Observable<JobDetail> {
    return this.http.get<Record<string, unknown>>(apiUrl(`/jobs/${encodeURIComponent(name)}`)).pipe(map(jobFromWire));
  }
  create(body: JobUpsert): Observable<JobDetail> {
    return this.http.post<Record<string, unknown>>(apiUrl('/jobs'), jobToWire(body)).pipe(map(jobFromWire));
  }
  update(name: string, body: JobUpsert): Observable<JobDetail> {
    return this.http
      .put<Record<string, unknown>>(apiUrl(`/jobs/${encodeURIComponent(name)}`), jobToWire(body))
      .pipe(map(jobFromWire));
  }
  remove(name: string): Observable<unknown> {
    return this.http.delete(apiUrl(`/jobs/${encodeURIComponent(name)}`));
  }
  setEnabled(name: string, enabled: boolean): Observable<JobDetail> {
    return this.http
      .post<Record<string, unknown>>(apiUrl(`/jobs/${encodeURIComponent(name)}/${enabled ? 'enable' : 'disable'}`), {})
      .pipe(map(jobFromWire));
  }
  reschedule(name: string, cron: string): Observable<JobDetail> {
    return this.http
      .post<Record<string, unknown>>(apiUrl(`/jobs/${encodeURIComponent(name)}/reschedule`), { cron })
      .pipe(map(jobFromWire));
  }
  runLogs(name: string, runId: string): Observable<JobRunLogs> {
    return this.http.get<JobRunLogs>(apiUrl(`/jobs/${encodeURIComponent(name)}/runs/${encodeURIComponent(runId)}/logs`));
  }
  /** C6: the generated artifact behind a `type:'report'` job's completed run. */
  runArtifact(name: string, runId: string): Observable<ReportArtifact> {
    return this.http.get<ReportArtifact>(apiUrl(`/jobs/${encodeURIComponent(name)}/runs/${encodeURIComponent(runId)}/artifact`));
  }
  /** Run Artifacts of a job's latest successful run (R7) — empty array when it never succeeded. */
  latestArtifacts(name: string): Observable<RunArtifactRow[]> {
    return this.http.get<RunArtifactRow[]>(apiUrl(`/jobs/${encodeURIComponent(name)}/artifacts/latest`));
  }

  // ── T27 reporting (404 unless the DuckDB backend is on: -Djobs.backend=duckdb) ──
  metrics(job?: string): Observable<JobMetrics> {
    return this.http.get<JobMetrics>(apiUrl('/jobs/metrics'), { params: toParams({ job }) });
  }
  recentRuns(limit = 100, job?: string): Observable<JobRunRow[]> {
    return this.http.get<JobRunRow[]>(apiUrl('/jobs/runs'), { params: toParams({ limit, job }) });
  }
  failures(days = 30): Observable<JobFailureDay[]> {
    return this.http.get<JobFailureDay[]>(apiUrl('/jobs/failures'), { params: toParams({ days }) });
  }
}
