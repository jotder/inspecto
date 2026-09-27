package com.gamma.acquire.connectors;

import com.gamma.pipeline.exec.EgressPolicy;

import javax.net.ssl.SSLSocketFactory;
import java.net.InetAddress;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The object-store egress seams for tests whose stub server listens on {@code 127.0.0.1}. Loopback is never
 * allowlistable, so the name {@code 127.0.0.1} is given the stand-in LAN address {@link #LAN} (an allowlisted private
 * address — what a MinIO on a LAN looks like to the policy) and that checked address is dialled as the loopback
 * stub. Everything else — host syntax, resolve-once, the address check — runs as in production.
 */
final class ObjectStoreEgressFixture {

    static final InetAddress LAN = InetAddress.ofLiteral("10.255.0.1");
    private static final InetAddress LOOPBACK = InetAddress.ofLiteral("127.0.0.1");

    private ObjectStoreEgressFixture() {}

    static void allowLoopbackStubAsLan() {
        AbstractHttpObjectStoreConnector.resolver =
                h -> "127.0.0.1".equals(h) ? new InetAddress[] {LAN} : EgressPolicy.SYSTEM.resolve(h);
        AbstractHttpObjectStoreConnector.allowlist = () -> EgressPolicy.Allowlist.of(List.of(LAN.getHostAddress()));
        AbstractHttpObjectStoreConnector.dial = a -> LAN.equals(a) ? LOOPBACK : a;
    }

    static void reset() {
        AbstractHttpObjectStoreConnector.resolver = EgressPolicy.SYSTEM;
        AbstractHttpObjectStoreConnector.allowlist = com.gamma.pipeline.exec.EgressAllowlist::forCurrentSpace;
        AbstractHttpObjectStoreConnector.dial = UnaryOperator.identity();
        AbstractHttpObjectStoreConnector.tls = () -> (SSLSocketFactory) SSLSocketFactory.getDefault();
    }
}
