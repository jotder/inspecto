package com.gamma.notify.channel;

import com.gamma.pipeline.exec.EgressAllowlist;
import com.gamma.pipeline.exec.EgressPolicy;
import com.gamma.pipeline.exec.WebhookSinkTransport;

import javax.net.ssl.SSLSocketFactory;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * <b>The one egress-checked JSON POST of both outbound webhooks</b> ({@code WEBHOOK-EGRESS-POLICY-1}) — the
 * {@code sink.webhook} Step ({@link HttpWebhookSinkTransport#post}) and the webhook notification channel
 * ({@link WebhookChannel}). The Action Request rule, applied here too: the host must pass
 * {@link EgressPolicy#checkHost}; it is resolved ONCE and EVERY address is checked against the deny-by-default
 * policy less the current Space's allowlist ({@link EgressAllowlist}); the request goes over {@link PinnedHttp} to
 * the checked address, so a second resolution cannot swap it. Any refusal — a bad host, a name that does not
 * resolve, a denied address — throws naming why, and nothing is dialled. A non-2xx answer is a failure; a 3xx is
 * never followed.
 */
final class WebhookEgress {

    private WebhookEgress() {}

    /** Name → addresses; a test swaps it. */
    static volatile EgressPolicy.Resolver resolver = EgressPolicy.SYSTEM;
    /** The allowlist in force; the current Space's in production. */
    static volatile Supplier<EgressPolicy.Allowlist> allowlist = EgressAllowlist::forCurrentSpace;
    /**
     * Test seam ONLY: the address actually dialled for a checked one — identity in production. A test gives a name
     * a private address (loopback is never allowlistable) and maps that address to its in-process server.
     */
    static volatile UnaryOperator<InetAddress> dial = UnaryOperator.identity();

    /** How many response characters a failure may quote. */
    private static final int EXCERPT_CAP = 256;

    static void post(SSLSocketFactory tls, URI url, String token, Duration timeout, String json,
                     Map<String, String> headers) throws Exception {
        String host = url.getHost();
        if (host == null || url.getUserInfo() != null)
            throw new IllegalStateException("webhook URL '" + url + "' has no plain host — refused");
        String bare = host.replaceAll("^\\[|\\]$", "");
        try {
            EgressPolicy.checkHost(bare);
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException("webhook egress refused: " + refused.getMessage(), refused);
        }
        InetAddress to;
        try {
            to = EgressPolicy.resolve(bare, allowlist.get(), resolver);
        } catch (EgressPolicy.Refused refused) {
            throw new IllegalStateException("webhook egress refused: " + refused.getMessage(), refused);
        }
        WebhookSinkTransport.Response r = PinnedHttp.exchange(tls, "POST", url, dial.apply(to), token, timeout, json,
                headers, EXCERPT_CAP);
        if (r.status() / 100 != 2) throw new IllegalStateException("webhook returned HTTP " + r.status());
    }
}
