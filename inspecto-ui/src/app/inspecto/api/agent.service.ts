import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { A2uiArtifact, isRecord } from 'app/inspecto/a2ui/a2ui-artifact';
import { apiUrl } from './api-base';
import { Finding } from './models';
import { SessionService } from './session.service';
import { SpacesService } from './spaces.service';

/** POST /agent/sessions result — a live multi-turn session on the deliberative loop. */
export interface AgentSessionResult {
    sessionId: string;
    openedAt: string;
}

/** A grounding source backing part of an answer (mirrors AgentAskResult.Citation). */
export interface AgentCitation {
    source: string;
    locator: string;
}

/** A complete agent answer — the wire shape of POST /agent/sessions/{id}/ask and the terminal
 *  `event: complete` SSE frame (mirrors the backend AgentAskResult record). */
export interface AgentAskResult {
    kind: 'TEXT' | 'NAVIGATION' | 'CLARIFICATION' | 'ERROR' | 'INLINE_ARTIFACT';
    text: string;
    citations: AgentCitation[];
    navigationTarget: string | null;
    artifact: A2uiArtifact | null;
}

/**
 * POST /agent/tools/{name}/derive result (AGT-6a A5.1). `value` is exactly what the tool itself
 * returned — the same shape {@link AgentService.runTool} yields — and `derivedArgs` is what the
 * operator's sentence became.
 *
 * `derivedArgs` is not decoration. With a model in the loop the operator has to see the structure
 * before they apply anything, or the draft is magic; the surface renders it above the diff.
 */
export interface DeriveResult<T> {
    value: T;
    derivedArgs: Record<string, unknown>;
}

/**
 * POST /agent/tools/component_draft result (AGT-6a A1) — a config validated against the same
 * structural spec + safety gate the control plane enforces on write. `clean` false with `findings`
 * is a SUCCESSFUL call: the findings are the repair loop, anchored to the offending field by
 * `fieldPath`, and are what the inline surface renders. Nothing is persisted — applying the `draft`
 * is a separate, explicit human action through the pane's own validated route.
 */
export interface ComponentDraftResult {
    kind: string;
    type: string;
    clean: boolean;
    findings: Finding[];
    draft: Record<string, unknown>;
}

/** Callbacks for one streamed ask — tokens, then optionally an artifact, then complete or error. */
export interface AgentStreamHandlers {
    onToken(token: string): void;
    onArtifact(artifact: A2uiArtifact): void;
    onComplete(result: AgentAskResult): void;
    onError(message: string): void;
}

/** One parsed SSE frame: `event` is the frame name (null for unnamed token frames). */
export interface SseFrame {
    event: string | null;
    data: string;
}

/**
 * Incremental SSE frame parser for the POST-stream reader (EventSource can't POST, so the agent
 * stream is read via `fetch` + `ReadableStream` — this is the framing half, kept pure so it's
 * testable without fetch). Feed it decoded chunks in arrival order; it buffers across chunk
 * boundaries and returns each completed frame: named `event:` lines, multiple `data:` lines per
 * frame joined with `\n` (standard SSE multi-line data), blank-line frame separation, tolerant of
 * CRLF. Other fields (`id:`, `retry:`, comments) are ignored.
 */
export class SseFrameParser {
    private buffer = '';
    private eventName: string | null = null;
    private dataLines: string[] | null = null;

    push(chunk: string): SseFrame[] {
        this.buffer += chunk;
        const frames: SseFrame[] = [];
        let nl: number;
        while ((nl = this.buffer.indexOf('\n')) >= 0) {
            let line = this.buffer.slice(0, nl);
            this.buffer = this.buffer.slice(nl + 1);
            if (line.endsWith('\r')) line = line.slice(0, -1);
            if (line === '') {
                if (this.dataLines !== null) frames.push({ event: this.eventName, data: this.dataLines.join('\n') });
                this.eventName = null;
                this.dataLines = null;
            } else if (line.startsWith('data:')) {
                (this.dataLines ??= []).push(stripLeadingSpace(line.slice(5)));
            } else if (line.startsWith('event:')) {
                this.eventName = stripLeadingSpace(line.slice(6));
            }
        }
        return frames;
    }
}

/** SSE field values carry at most one leading space after the colon (per spec). */
function stripLeadingSpace(s: string): string {
    return s.startsWith(' ') ? s.slice(1) : s;
}

function safeParse(json: string): unknown {
    try {
        return JSON.parse(json);
    } catch {
        return null;
    }
}

/**
 * Embedded intelligence agent (AGT-5 / S4): open a session, then ask it questions — plain or
 * streamed. The agent lives in the optional file-processor-intelligence module; when absent every
 * route answers 503 and callers degrade gracefully (mirror the assist-panel UX).
 *
 * The non-streaming calls ride HttpClient (v1 envelope unwrapped, auth/space interceptors apply).
 * {@link askStream} POSTs with a raw `fetch` (SSE over POST — EventSource can't POST), so the
 * interceptor work is replicated by hand: the Standard-edition bearer (authInterceptor) and the
 * `/spaces/<id>` path scoping (spaceInterceptor). SSE frames are written raw to the response body
 * (never enveloped), so no unwrap applies there.
 */
@Injectable({ providedIn: 'root' })
export class AgentService {
    private http = inject(HttpClient);
    private session = inject(SessionService);
    private spaces = inject(SpacesService);

    /** Open a multi-turn agent session. 503 when the intelligence module is absent. */
    openSession(role?: string, page?: Record<string, unknown>): Observable<AgentSessionResult> {
        return this.http.post<AgentSessionResult>(apiUrl('/agent/sessions'), { role, page });
    }

    /** One complete (non-streamed) ask. 404 unknown session, 400 missing question. */
    ask(sessionId: string, question: string, page?: Record<string, unknown>): Observable<AgentAskResult> {
        return this.http.post<AgentAskResult>(apiUrl(`/agent/sessions/${encodeURIComponent(sessionId)}/ask`), { question, page });
    }

    /**
     * Invoke ONE named agent tool deterministically (AGT-6a A1) and get its own result back — no
     * model in the loop. This is the seam the inline authoring surface uses: {@link ask} runs the
     * deliberative loop and answers in prose, which cannot be diffed or applied, whereas this returns
     * the tool's structure (for `component_draft`: {@link ComponentDraftResult}).
     *
     * Status contract: 503 the intelligence module is absent · 404 unknown tool · 403 the tool is
     * mutating (only the approval spine may run those — a draft surface must never act) · 422 the tool
     * rejected the arguments, message in the error body. A draft carrying findings is 200.
     */
    runTool<T>(name: string, args: Record<string, unknown>): Observable<T> {
        return this.http.post<T>(apiUrl(`/agent/tools/${encodeURIComponent(name)}`), { args });
    }

    /**
     * {@link runTool}, but the tool's arguments come from one operator sentence (AGT-6a A5.1). The model
     * produces only the ARGUMENTS; the tool then runs deterministically, so the draft-only, diff-and-apply
     * contract above is unchanged — this adds a natural-language input, not a new way to act.
     *
     * `args` is the pane's own context and is applied AFTER the model's, so it wins: the screen knows which
     * dataset is open and a model can hallucinate one.
     *
     * Status contract adds one code to {@link runTool}'s: **503 also means "no local model is configured"**,
     * which is deliberately not a 422 — the operator's sentence is not the problem and asking them to
     * rephrase would be a lie. 422 stays "the model produced nothing usable", and is retryable.
     */
    deriveTool<T>(name: string, prompt: string, args: Record<string, unknown>): Observable<DeriveResult<T>> {
        return this.http.post<DeriveResult<T>>(
            apiUrl(`/agent/tools/${encodeURIComponent(name)}/derive`), { prompt, args });
    }

    /**
     * Streamed ask over SSE: unnamed `data:` frames → {@link AgentStreamHandlers.onToken}, an
     * optional `event: artifact` → `onArtifact` (dropped when malformed — fail closed), terminal
     * `event: complete` → `onComplete` or `event: error` → `onError`. Cancel via the AbortSignal
     * (an abort ends the stream silently — no error callback).
     */
    async askStream(sessionId: string, question: string, handlers: AgentStreamHandlers, signal?: AbortSignal): Promise<void> {
        const headers: Record<string, string> = { 'Content-Type': 'application/json' };
        // authInterceptor equivalent: bearer only in Standard-edition OIDC mode, never on Personal.
        if (this.session.authMode() === 'oidc') {
            const token = this.session.token();
            if (token) headers['Authorization'] = `Bearer ${token}`;
        }
        let response: Response;
        try {
            response = await fetch(this.spaceScopedUrl(`/agent/sessions/${encodeURIComponent(sessionId)}/ask/stream`), {
                method: 'POST',
                headers,
                body: JSON.stringify({ question }),
                signal,
            });
        } catch {
            if (!signal?.aborted) handlers.onError('The agent stream could not be opened.');
            return;
        }
        if (!response.ok || !response.body) {
            handlers.onError(
                response.status === 503
                    ? 'The intelligence module is not available on this backend.'
                    : `The agent stream failed (HTTP ${response.status}).`,
            );
            return;
        }
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        const parser = new SseFrameParser();
        try {
            for (;;) {
                const { done, value } = await reader.read();
                if (done) break;
                for (const frame of parser.push(decoder.decode(value, { stream: true }))) this.dispatch(frame, handlers);
            }
        } catch {
            if (!signal?.aborted) handlers.onError('The agent stream was interrupted.');
        }
    }

    /** Route one SSE frame to its handler. Unknown named frames are ignored (fail closed). */
    private dispatch(frame: SseFrame, handlers: AgentStreamHandlers): void {
        switch (frame.event) {
            case null:
                handlers.onToken(frame.data);
                break;
            case 'artifact': {
                const artifact = safeParse(frame.data);
                // A malformed artifact frame is dropped — the complete frame still carries the answer.
                if (isRecord(artifact) && typeof artifact['kind'] === 'string') handlers.onArtifact(artifact as unknown as A2uiArtifact);
                break;
            }
            case 'complete': {
                const result = safeParse(frame.data);
                if (isRecord(result)) handlers.onComplete(result as unknown as AgentAskResult);
                else handlers.onError('The agent answer could not be parsed.');
                break;
            }
            case 'error':
                handlers.onError(frame.data || 'The agent reported an error.');
                break;
        }
    }

    /** spaceInterceptor equivalent for the raw fetch: `/api/v1/<p>` → `/api/v1/spaces/<id>/<p>`. */
    private spaceScopedUrl(path: string): string {
        const space = this.spaces.currentSpaceId();
        return apiUrl(space ? `/spaces/${encodeURIComponent(space)}${path}` : path);
    }
}
