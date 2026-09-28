package com.gamma.connect.notify;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * 🔒 The SNS signing certificate for a message (D8-SES-SNS-1, design §3.2 steps 5–10 and §3.2.1). This is the
 * product's first outbound request that an UNAUTHENTICATED caller can cause, so every rule runs before the
 * {@link Fetcher} is touched, and the fetcher itself is the only place a socket is opened.
 *
 * <ol>
 *   <li><b>Host from our own ARN</b> ({@link #expectedHost}) — the allowlisted {@code TopicArn}'s partition and
 *       region give the ONE host we will contact: {@code sns.<region>.amazonaws.com} ({@code .amazonaws.com.cn}
 *       for {@code aws-cn}). Never a pattern: {@code sns\.[a-z0-9-]+\.amazonaws\.com} also matches
 *       {@code sns.s3.amazonaws.com}, an S3 bucket someone else controls (D2).</li>
 *   <li><b>URL grammar</b> ({@link #checkCertUrl}) — {@code https}, no userinfo, port absent or 443, host EXACTLY
 *       the expected one (so no trailing dot, no case games, no IDN, no suffix tricks), path exactly
 *       {@code /SimpleNotificationService-<32 hex>.pem}, no query, no fragment. The fixed grammar is what bounds
 *       the number of distinct URLs an attacker can make us miss on.</li>
 *   <li><b>Pinned mode</b> (D1 opt-in): the operator's certificate, and NO fetch ever — the URL rules still apply.</li>
 *   <li><b>Cache</b>: 8 entries LRU by exact URL, each re-checked on use (so expiry bites); failures cached 10
 *       minutes; in process only — persisting it would create a second trust anchor to protect.</li>
 *   <li><b>Single-flight</b>: concurrent misses for one URL share one fetch.</li>
 *   <li><b>Global budget</b>: at most {@code maxFetchesPerHour} (default 12) across the process, shared with the
 *       subscription confirmer. Spent ⇒ the miss is refused and makes no request.</li>
 *   <li><b>Trust</b> ({@link SnsCertTrust}), independent of how the certificate arrived.</li>
 * </ol>
 */
final class SnsSigningCerts implements SesSnsDeliveryStatusAdapter.CertSource {

    /** One GET. The implementation owns every network rule (address policy, TLS, redirects, size, time). */
    interface Fetcher {
        /** The 200 body, and the TLS peer chain it arrived over (path material only; may be empty). */
        record Fetched(byte[] body, List<X509Certificate> tlsChain) {}

        Fetched get(URI url) throws IOException;
    }

    static final int CACHE_ENTRIES = 8;
    static final long NEGATIVE_TTL_MILLIS = 10 * 60_000L;
    static final int DEFAULT_MAX_FETCHES_PER_HOUR = 12;
    /** A PEM file may carry the leaf and its intermediates, never a bundle. */
    static final int MAX_CERTS_IN_PEM = 4;

    private static final Pattern CERT_PATH = Pattern.compile("/SimpleNotificationService-[0-9a-f]{32}\\.pem");
    private static final Pattern REGION = Pattern.compile("[a-z]{2}(-[a-z]+)+-[0-9]{1,2}");
    private static final Pattern ACCOUNT = Pattern.compile("[0-9]{12}");
    private static final Pattern TOPIC = Pattern.compile("[A-Za-z0-9_-]{1,256}(\\.fifo)?");

    private record Cached(X509Certificate cert, List<X509Certificate> intermediates) {}

    private final Fetcher fetcher;
    private final SnsCertTrust trust;
    /** The pinned chain (leaf first), or {@code null} in fetch mode. */
    private final List<X509Certificate> pinned;
    private final Budget budget;
    private final LongSupplier clock;
    private final Map<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };
    private final Map<String, Long> failedUntil = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 64;
        }
    };
    private final ConcurrentHashMap<String, CompletableFuture<Cached>> inflight = new ConcurrentHashMap<>();

    SnsSigningCerts(Fetcher fetcher, SnsCertTrust trust, List<X509Certificate> pinned, Budget budget, LongSupplier clock) {
        this.fetcher = fetcher;
        this.trust = trust;
        this.pinned = pinned == null ? null : List.copyOf(pinned);
        this.budget = budget;
        this.clock = clock;
    }

    @Override
    public X509Certificate certificateFor(SnsEnvelope env) throws Exception {
        String host = expectedHost(env.topicArn());
        URI url = checkCertUrl(env.signingCertUrl(), host);
        long now = clock.getAsLong();
        if (pinned != null) {
            trust.check(pinned.get(0), pinned.subList(1, pinned.size()), host, now);
            return pinned.get(0);
        }
        String key = url.toString();
        Cached hit;
        synchronized (this) {
            hit = cache.get(key);
            Long until = failedUntil.get(key);
            if (hit == null && until != null && until > now)
                throw new SecurityException("the signing certificate at " + key + " failed recently; not re-fetched");
        }
        if (hit != null) {
            trust.check(hit.cert(), hit.intermediates(), host, now);   // re-checked on every use: expiry bites
            return hit.cert();
        }
        CompletableFuture<Cached> mine = new CompletableFuture<>();
        CompletableFuture<Cached> shared = inflight.putIfAbsent(key, mine);
        if (shared != null) return shared.join().cert();   // single-flight: another request is fetching it
        try {
            Cached e = fetchAndCheck(url, host, now);
            synchronized (this) {
                cache.put(key, e);
                failedUntil.remove(key);
            }
            mine.complete(e);
            return e.cert();
        } catch (Budget.Spent spent) {
            mine.completeExceptionally(spent);   // a spent budget is not the URL's fault: not negatively cached
            throw spent;
        } catch (Exception failed) {
            synchronized (this) {
                failedUntil.put(key, now + NEGATIVE_TTL_MILLIS);
            }
            mine.completeExceptionally(failed);
            throw failed;
        } finally {
            inflight.remove(key, mine);
        }
    }

    private Cached fetchAndCheck(URI url, String host, long now) throws Exception {
        budget.acquire();
        Fetcher.Fetched got = fetcher.get(url);
        List<X509Certificate> certs = parsePem(got.body());
        List<X509Certificate> intermediates = new ArrayList<>(certs.subList(1, certs.size()));
        if (got.tlsChain() != null) intermediates.addAll(got.tlsChain());
        trust.check(certs.get(0), intermediates, host, now);
        return new Cached(certs.get(0), List.copyOf(intermediates));
    }

    /** The certificates in a PEM body — the first is the signer. Refuses none, or more than a chain. */
    static List<X509Certificate> parsePem(byte[] body) throws Exception {
        Collection<? extends Certificate> all = CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(body));
        if (all.isEmpty() || all.size() > MAX_CERTS_IN_PEM)
            throw new SecurityException("the signing certificate body holds " + all.size() + " certificates");
        List<X509Certificate> out = new ArrayList<>();
        for (Certificate c : all) out.add((X509Certificate) c);
        return out;
    }

    /**
     * The one host a certificate or confirmation for {@code topicArn} may come from, from the ARN's own partition
     * and region (D2): {@code arn:<partition>:sns:<region>:<12-digit account>:<topic>}. Throws for anything else.
     */
    static String expectedHost(String topicArn) {
        String[] p = topicArn == null ? new String[0] : topicArn.split(":", -1);
        if (p.length != 6 || !"arn".equals(p[0]) || !"sns".equals(p[2]) || !REGION.matcher(p[3]).matches()
                || !ACCOUNT.matcher(p[4]).matches() || !TOPIC.matcher(p[5]).matches())
            throw new SecurityException("not an SNS topic ARN: " + topicArn);
        String region = p[3];
        return switch (p[1]) {
            case "aws" -> {
                if (region.startsWith("cn-") || region.startsWith("us-gov-"))
                    throw new SecurityException("region " + region + " is not in the aws partition");
                yield "sns." + region + ".amazonaws.com";
            }
            case "aws-us-gov" -> {
                if (!region.startsWith("us-gov-")) throw new SecurityException("region " + region + " is not GovCloud");
                yield "sns." + region + ".amazonaws.com";
            }
            case "aws-cn" -> {
                if (!region.startsWith("cn-")) throw new SecurityException("region " + region + " is not in aws-cn");
                yield "sns." + region + ".amazonaws.com.cn";
            }
            default -> throw new SecurityException("unsupported partition " + p[1]);
        };
    }

    /** {@code SigningCertURL} checked against {@code host}; throws naming the first rule it breaks. */
    static URI checkCertUrl(String raw, String host) {
        URI u = strictHttpsUri(raw, host);
        if (u.getRawPath() == null || !CERT_PATH.matcher(u.getRawPath()).matches())
            throw new SecurityException("SigningCertURL path is not /SimpleNotificationService-<hex>.pem");
        if (u.getRawQuery() != null) throw new SecurityException("SigningCertURL carries a query");
        return u;
    }

    /**
     * The URI rules the certificate URL and the SubscribeURL share: {@code https}, an authority that is EXACTLY
     * {@code host} or {@code host:443} (compared on the raw authority, so userinfo, a trailing dot, upper case,
     * percent-encoding and IDN all fail), no fragment.
     */
    static URI strictHttpsUri(String raw, String host) {
        if (raw == null) throw new SecurityException("no URL");
        URI u;
        try {
            u = new URI(raw);
        } catch (Exception e) {
            throw new SecurityException("not a URI");
        }
        if (!"https".equals(u.getScheme())) throw new SecurityException("scheme is not https");
        String authority = u.getRawAuthority();
        if (authority == null || !(authority.equals(host) || authority.equals(host + ":443")))
            throw new SecurityException("host is not exactly " + host);
        if (u.getRawUserInfo() != null || !host.equals(u.getHost()))
            throw new SecurityException("host is not exactly " + host);
        if (u.getRawFragment() != null) throw new SecurityException("URL carries a fragment");
        return u;
    }

    /** The process-wide outbound budget: {@code max} requests per sliding hour (design §3.2 step 8). */
    static final class Budget {
        static final class Spent extends SecurityException {
            Spent() { super("the SNS outbound fetch budget for this hour is spent"); }
        }

        private final int max;
        private final LongSupplier clock;
        private final Deque<Long> recent = new ArrayDeque<>();

        Budget(int max, LongSupplier clock) {
            this.max = max > 0 ? max : DEFAULT_MAX_FETCHES_PER_HOUR;
            this.clock = clock;
        }

        synchronized void acquire() {
            long now = clock.getAsLong();
            while (!recent.isEmpty() && recent.peekFirst() <= now - 3_600_000L) recent.pollFirst();
            if (recent.size() >= max) throw new Spent();
            recent.addLast(now);
        }
    }
}
