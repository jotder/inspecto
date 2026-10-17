package com.gamma.geolink;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.acquire.retry.RetryPolicy;
import com.gamma.auth.secrets.SecretResolver;
import com.gamma.etl.PipelineConfig;
import com.gamma.la.core.OutboundPort;
import com.gamma.pipeline.exec.EgressAllowlist;
import com.gamma.pipeline.exec.WebhookSink;
import com.gamma.pipeline.exec.WebhookSinkTransport;
import com.gamma.util.egress.EgressPolicy;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The BRIDGE's implementation of Link Analysis's {@link OutboundPort} (LA-EDITIONS-1 phase 6): the platform's outbound
 * machinery, reused rather than copied - the same three pieces the Action Request dispatcher composes.
 * <ol>
 *   <li><b>Where</b> - {@link WebhookSink#endpoint}: a Connection registered in this Space, connector {@code https},
 *       with a host and neither tunnel nor proxy, its token resolved from its {@code ${…}} reference at send time. On top,
 *       a Connection whose token is a LITERAL is refused here: integration never sends a secret that sits in config.</li>
 *   <li><b>Whether</b> - {@link EgressPolicy#resolve} before EVERY attempt: the host resolved once, every address checked
 *       against the deny-by-default classes less the Space's {@link EgressAllowlist}; a refusal sends nothing and is not
 *       retried.</li>
 *   <li><b>How</b> - the edition's {@link WebhookSinkTransport#exchange} (Standard and above:
 *       {@code inspecto-notify-channels}), which connects to the checked address, verifies TLS against the name and never
 *       follows a redirect. Retried under the Collector {@link RetryPolicy}: an I/O failure, a 5xx, 408 or 429.</li>
 * </ol>
 * Never logs a payload, a token or a response body.
 */
public final class HostOutboundPort implements OutboundPort {

    /** How many characters of an answer are kept (enough for a case id and a link). */
    static final int EXCERPT_CAP = 4096;

    /** The outbound wire - the edition's transport; a test swaps in its own. */
    static volatile Supplier<WebhookSinkTransport> transport = WebhookSink::discoveredTransport;
    /** Name resolution for the egress check - the platform's; a test gives its target a name and an address. */
    static volatile EgressPolicy.Resolver resolver = EgressPolicy.SYSTEM;
    /** Test seam: the retry sleeper ({@code null} = real sleeps with full jitter). */
    static volatile RetryPolicy.Sleeper sleeper = null;

    /** Thrown inside the retry loop for an answer worth retrying; carries the answer. */
    private static final class Retryable extends Exception {
        final WebhookSinkTransport.Response response;

        Retryable(WebhookSinkTransport.Response response) {
            super("HTTP " + response.status(), null, false, false);
            this.response = response;
        }
    }

    @Override
    public Result send(Path configRoot, String connection, String json, Map<String, String> headers, String authHeader, Retry retry) {
        WebhookSinkTransport wire = transport.get();
        if (wire == null)
            return Result.refusedBecause("this bundle ships no outbound HTTP transport — integration is a Standard and above "
                    + "capability (inspecto-notify-channels)");
        String who = "Link Analysis integration";
        WebhookSink.Endpoint endpoint;
        try {
            endpoint = WebhookSink.endpoint(connection, who);
        } catch (IllegalStateException refused) {
            return Result.refusedBecause(refused.getMessage());
        }
        ConnectionProfile p = ConnectionRegistry.find(connection).orElse(null);
        if (p != null && p.password() != null && !p.password().isBlank() && !SecretResolver.isReference(p.password()))
            return Result.refusedBecause(who + ": Connection '" + connection + "' holds a literal token — integration sends "
                    + "only a ${...} secret reference resolved at send time");
        Map<String, String> sent = new LinkedHashMap<>(headers);
        String bearer = endpoint.bearerToken();
        if (authHeader != null && bearer != null) {
            sent.put(authHeader, bearer);
            bearer = null;
        }
        String token = bearer;
        RetryPolicy policy = policy(retry == null ? Retry.DEFAULT : retry);
        String host = endpoint.url().getHost().replaceAll("^\\[|\\]$", "");
        AtomicInteger attempts = new AtomicInteger();
        String[] refusal = {null};
        try {
            WebhookSinkTransport.Response r = policy.execute(() -> {
                InetAddress to;
                try {
                    to = EgressPolicy.resolve(host, EgressAllowlist.of(configRoot), resolver);
                } catch (EgressPolicy.Refused refused) {
                    refusal[0] = "egress refused: " + refused.getMessage();
                    return null;   // never retried: the policy will say the same next time
                }
                attempts.incrementAndGet();
                WebhookSinkTransport.Response answer = wire.exchange("POST", endpoint.url(), to, token, endpoint.timeout(),
                        json, sent, EXCERPT_CAP);
                int code = answer.status();
                if (code / 100 == 5 || code == 408 || code == 429) throw new Retryable(answer);
                return answer;
            });
            if (r == null) return Result.refusedBecause(refusal[0]);
            boolean ok = r.status() / 100 == 2;
            return new Result(ok, false, r.status(), r.bodyExcerpt(), attempts.get(),
                    ok ? null : r.status() / 100 == 3 ? "the target answered a redirect (" + r.status() + ") — redirects are "
                            + "never followed" : "HTTP " + r.status());
        } catch (Retryable last) {
            return new Result(false, false, last.response.status(), last.response.bodyExcerpt(), attempts.get(),
                    "HTTP " + last.response.status());
        } catch (Exception io) {
            return new Result(false, false, null, null, attempts.get(),
                    io.getClass().getSimpleName() + (io.getMessage() == null ? "" : ": " + cap(io.getMessage())));
        }
    }

    private static RetryPolicy policy(Retry r) {
        RetryPolicy.Sleeper s = sleeper;
        if (s != null)
            return RetryPolicy.forTest(r.count(), RetryPolicy.Backoff.from(r.backoff()), r.initialDelayMillis(), r.maxDelayMillis(), s);
        return RetryPolicy.from(new PipelineConfig.Retry(r.count(), r.backoff(), r.initialDelayMillis(), r.maxDelayMillis()));
    }

    private static String cap(String s) {
        return s.length() > 256 ? s.substring(0, 256) : s;
    }
}
