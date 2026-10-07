package com.gamma.acquire.connectors;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;

/**
 * A {@link SocketFactory} that routes every socket it creates through a SOCKS5 proxy (Data Acquisition —
 * {@code ConnectionProfile.Proxy}). sshj's {@code SocketClient.connect(host, port)} calls the no-arg
 * {@link #createSocket()} to obtain an <em>unconnected</em> socket, then itself calls
 * {@code socket.connect(new InetSocketAddress(host, port), timeout)} on it — a plain JDK {@link Socket}
 * constructed with a {@link Proxy.Type#SOCKS} proxy tunnels that {@code connect} call through the proxy
 * transparently, so no protocol-specific handshake is needed here (unlike an HTTP proxy, which needs an
 * explicit {@code CONNECT} to a target that isn't known until that later {@code connect} call — not
 * supported by this factory).
 */
public final class SocksProxySocketFactory extends SocketFactory {

    private final Proxy proxy;

    SocksProxySocketFactory(String proxyHost, int proxyPort) {
        this.proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(proxyHost, proxyPort));
    }

    /** {@code host:port} — the reflective single-string form a JDBC {@code socketFactoryArg=} passes (see {@link HttpProxySocketFactory#HttpProxySocketFactory(String)}). */
    public SocksProxySocketFactory(String arg) {
        String[] p = ProxyArg.split(arg, 2, "SocksProxySocketFactory");
        this.proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(p[0], ProxyArg.port(p[1], arg)));
    }

    @Override
    public Socket createSocket() {
        return new Socket(proxy);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket s = createSocket();
        s.connect(new InetSocketAddress(host, port));
        return s;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        Socket s = createSocket();
        s.bind(new InetSocketAddress(localHost, localPort));
        s.connect(new InetSocketAddress(host, port));
        return s;
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        Socket s = createSocket();
        s.connect(new InetSocketAddress(host, port));
        return s;
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        Socket s = createSocket();
        s.bind(new InetSocketAddress(localAddress, localPort));
        s.connect(new InetSocketAddress(address, port));
        return s;
    }
}
