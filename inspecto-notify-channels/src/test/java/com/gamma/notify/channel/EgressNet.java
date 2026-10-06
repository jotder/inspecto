package com.gamma.notify.channel;

import com.gamma.util.egress.EgressPolicy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * A fake network for {@link WebhookEgress} tests. Loopback is never allowlistable, so the target name
 * {@code hook.test} resolves to a PRIVATE address the allowlist lifts, and the dial seam maps exactly that checked
 * address onto the in-process server on 127.0.0.1 — recording every address dialled. {@code private.test} is a
 * private address with no entry; {@code meta.test} is the metadata service; anything else does not resolve.
 */
final class EgressNet implements AutoCloseable {

    static final InetAddress HOOK = InetAddress.ofLiteral("10.77.0.1");
    static final InetAddress LOOPBACK = InetAddress.ofLiteral("127.0.0.1");

    final List<InetAddress> dialled = new CopyOnWriteArrayList<>();

    private final EgressPolicy.Resolver priorResolver = WebhookEgress.resolver;
    private final Supplier<EgressPolicy.Allowlist> priorAllow = WebhookEgress.allowlist;
    private final UnaryOperator<InetAddress> priorDial = WebhookEgress.dial;

    EgressNet(String... allow) {
        EgressPolicy.Allowlist list = EgressPolicy.Allowlist.of(List.of(allow));
        WebhookEgress.allowlist = () -> list;
        WebhookEgress.resolver = host -> switch (host) {
            case "hook.test" -> new InetAddress[] {HOOK};
            case "private.test" -> new InetAddress[] {InetAddress.ofLiteral("10.77.0.2")};
            case "meta.test" -> new InetAddress[] {InetAddress.ofLiteral("169.254.169.254")};
            case "127.0.0.1", "localhost" -> new InetAddress[] {LOOPBACK};
            default -> throw new UnknownHostException(host);
        };
        WebhookEgress.dial = a -> {
            dialled.add(a);
            return HOOK.equals(a) ? LOOPBACK : a;
        };
    }

    @Override
    public void close() {
        WebhookEgress.resolver = priorResolver;
        WebhookEgress.allowlist = priorAllow;
        WebhookEgress.dial = priorDial;
    }
}
