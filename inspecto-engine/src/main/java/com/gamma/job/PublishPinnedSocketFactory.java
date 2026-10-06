package com.gamma.job;

import com.gamma.util.egress.EgressPolicy;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Properties;

/**
 * The pgjdbc {@code socketFactory} {@code publish.postgres} supplies (ASSURE-BI-PUBLICATION-1, TLS fix): every socket
 * it makes connects to the ADDRESS the egress policy checked ({@value #PINNED}), whatever address the driver asks
 * for — while the JDBC URL keeps the AUTHORED host, so pgjdbc's SNI and {@code verify-full} hostname check run
 * against the name the Connection names (the {@code PinnedHttp} idea, for JDBC). pgjdbc instantiates it by class
 * name with the connection {@link Properties}; the name and the pinned address are set by the Job only — a
 * {@code socketFactory} URL parameter is refused before the driver sees it.
 */
public final class PublishPinnedSocketFactory extends SocketFactory {

    /** The connection property carrying the checked IP literal. */
    public static final String PINNED = "inspectoPinnedAddress";

    private final InetAddress pinned;

    public PublishPinnedSocketFactory(Properties info) {
        String ip = info == null ? null : info.getProperty(PINNED);
        if (ip == null || !EgressPolicy.isIpLiteral(ip))
            throw new IllegalArgumentException("publish.postgres: no pinned address — refusing to let the driver resolve the host");
        this.pinned = InetAddress.ofLiteral(ip.startsWith("[") ? ip.substring(1, ip.length() - 1) : ip);
    }

    InetAddress pinned() { return pinned; }

    @Override
    public Socket createSocket() {
        return new Socket() {
            @Override
            public void connect(SocketAddress endpoint, int timeout) throws IOException {
                int port = endpoint instanceof InetSocketAddress i ? i.getPort() : 5432;
                super.connect(new InetSocketAddress(pinned, port), timeout);
            }
        };
    }

    @Override public Socket createSocket(String host, int port) throws IOException { return connected(port); }
    @Override public Socket createSocket(String host, int port, InetAddress la, int lp) throws IOException { return connected(port); }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException { return connected(port); }
    @Override public Socket createSocket(InetAddress a, int port, InetAddress la, int lp) throws IOException { return connected(port); }

    private Socket connected(int port) throws IOException {
        Socket s = createSocket();
        s.connect(new InetSocketAddress(pinned, port));
        return s;
    }
}
