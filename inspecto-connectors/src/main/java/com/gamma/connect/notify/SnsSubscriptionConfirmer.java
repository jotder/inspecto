package com.gamma.connect.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 🔒 Confirms an SNS subscription (D8-SES-SNS-1, design §3.5; D7, D8). Reached only for a VERIFIED, first-seen
 * {@code SubscriptionConfirmation} on an ALLOWLISTED topic — {@code SubscribeURL} and {@code TopicArn} are inside
 * the signature, so neither can have been changed in transit.
 *
 * <p>{@code SubscribeURL} must still be exactly {@code https://<the ARN-derived host>/?Action=ConfirmSubscription
 * &TopicArn=<this envelope's>&Token=<this envelope's>} — no other parameter, each once, no fragment. The GET goes
 * through the same {@link SnsSigningCerts.Fetcher} (address policy, TLS, no redirects, 16 KiB, 5 s) and the same
 * hourly budget as the certificate fetch. It runs on ONE background thread with a queue of 4 (D7): a full queue
 * drops the task and logs it, so the request thread never waits on an outbound call. If the GET fails SNS does
 * not resend; the operator uses <i>Request confirmation</i> in the SNS console.
 *
 * <p>{@code autoConfirm=false} (D8): nothing is fetched; an AUDIT row records the TopicArn and the {@code Token}
 * so the operator can confirm out of band ({@code aws sns confirm-subscription}). The Token can only confirm our
 * own endpoint's subscription to our own allowlisted topic.
 */
final class SnsSubscriptionConfirmer implements SesSnsDeliveryStatusAdapter.Confirmer {

    static final int QUEUE = 4;
    private static final Logger log = LoggerFactory.getLogger(SnsSubscriptionConfirmer.class);
    private static final Set<String> PARAMS = Set.of("Action", "TopicArn", "Token");

    private final SnsSigningCerts.Fetcher fetcher;
    private final SnsSigningCerts.Budget budget;
    private final boolean autoConfirm;
    private final Executor executor;

    SnsSubscriptionConfirmer(SnsSigningCerts.Fetcher fetcher, SnsSigningCerts.Budget budget, boolean autoConfirm,
                             Executor executor) {
        this.fetcher = fetcher;
        this.budget = budget;
        this.autoConfirm = autoConfirm;
        this.executor = executor;
    }

    /** The bounded single-thread executor of D7. */
    static Executor boundedExecutor() {
        ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUE), r -> {
            Thread t = new Thread(r, "sns-subscription-confirm");
            t.setDaemon(true);
            return t;
        }, (r, e) -> log.warn("SNS subscription confirmation dropped: the queue of {} is full", QUEUE));
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }

    @Override
    public void subscriptionConfirmation(SnsEnvelope env) {
        if (!autoConfirm) {
            SesSnsDeliveryStatusAdapter.audit("sns.subscription-pending", "SNS subscription to " + env.topicArn()
                    + " awaits out-of-band confirmation (autoConfirm=false)", env.topicArn(), env.token());
            return;
        }
        URI url;
        try {
            url = checkSubscribeUrl(env);
        } catch (SecurityException refused) {
            log.warn("SNS SubscribeURL for {} refused: {}", env.topicArn(), refused.getMessage());
            return;
        }
        executor.execute(() -> {
            try {
                budget.acquire();
                String body = new String(fetcher.get(url).body(), StandardCharsets.UTF_8);
                if (!body.contains("SubscriptionArn"))   // logged, never trusted for anything
                    log.warn("SNS confirmation for {} answered 200 without a SubscriptionArn", env.topicArn());
                SesSnsDeliveryStatusAdapter.audit("sns.subscription-confirmed", "SNS subscription to "
                        + env.topicArn() + " confirmed", env.topicArn(), null);
            } catch (Exception e) {
                log.warn("SNS subscription confirmation for {} failed ({}); use 'Request confirmation' in the SNS "
                        + "console", env.topicArn(), e.getMessage());
            }
        });
    }

    /** The SubscribeURL, or a {@link SecurityException} naming the first rule it breaks. */
    static URI checkSubscribeUrl(SnsEnvelope env) {
        String host = SnsSigningCerts.expectedHost(env.topicArn());
        URI u = SnsSigningCerts.strictHttpsUri(env.subscribeUrl(), host);
        if (!"/".equals(u.getRawPath())) throw new SecurityException("SubscribeURL path is not /");
        String q = u.getRawQuery();
        if (q == null) throw new SecurityException("SubscribeURL has no query");
        Map<String, String> params = new HashMap<>();
        for (String pair : q.split("&", -1)) {
            int eq = pair.indexOf('=');
            if (eq <= 0) throw new SecurityException("SubscribeURL has a malformed parameter");
            String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            if (!PARAMS.contains(k) || params.put(k, v) != null)
                throw new SecurityException("SubscribeURL carries an unexpected or repeated parameter " + k);
        }
        if (!"ConfirmSubscription".equals(params.get("Action"))) throw new SecurityException("Action is not ConfirmSubscription");
        if (!env.topicArn().equals(params.get("TopicArn"))) throw new SecurityException("TopicArn differs from the envelope's");
        if (!env.token().equals(params.get("Token"))) throw new SecurityException("Token differs from the envelope's");
        return u;
    }
}
