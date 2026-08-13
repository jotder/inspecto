package com.gamma.acquire.connectors;

import javax.net.SocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A {@link SocketFactory} that routes every socket it creates through an HTTP proxy's {@code CONNECT}
 * tunnel (Data Acquisition — {@code ConnectionProfile.Proxy} type {@code HTTP}). Unlike a SOCKS proxy, a
 * plain JDK socket cannot transparently tunnel an arbitrary protocol through HTTP: the socket must dial the
 * proxy, send an explicit {@code CONNECT host:port} naming the real target, and read back a {@code 200}
 * before the caller (sshj / commons-net) layers its own protocol on top.
 *
 * <p>The redirect therefore lives in {@link TunnellingSocket#connect}, not in the factory methods: sshj's
 * {@code SocketClient.connect(host, port)} takes the <em>unconnected</em> socket from the no-arg
 * {@link #createSocket()} and calls {@code connect(target)} on it itself, so a factory that only tunnelled in
 * its connecting overloads would be silently bypassed and dial the target directly.
 */
final class HttpProxySocketFactory extends SocketFactory {

    private final String proxyHost;
    private final int proxyPort;
    private final String username;
    private final String password;

    HttpProxySocketFactory(String proxyHost, int proxyPort, String username, String password) {
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.username = username;
        this.password = password;
    }

    @Override
    public Socket createSocket() {
        return new TunnellingSocket();
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

    /** A socket whose {@code connect(target)} dials the proxy and CONNECT-tunnels to {@code target} instead. */
    private final class TunnellingSocket extends Socket {
        @Override
        public void connect(java.net.SocketAddress endpoint) throws IOException {
            connect(endpoint, 0);
        }

        @Override
        public void connect(java.net.SocketAddress endpoint, int timeout) throws IOException {
            if (!(endpoint instanceof InetSocketAddress target))
                throw new IOException("HTTP proxy tunnel needs an InetSocketAddress target, got " + endpoint);
            super.connect(new InetSocketAddress(proxyHost, proxyPort), timeout);
            String host = target.getAddress() != null ? target.getAddress().getHostAddress() : target.getHostString();
            connectTunnel(this, host, target.getPort());
        }
    }

    private void connectTunnel(Socket socket, String targetHost, int targetPort) throws IOException {
        OutputStream out = socket.getOutputStream();
        StringBuilder req = new StringBuilder();
        req.append("CONNECT ").append(targetHost).append(':').append(targetPort).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(targetHost).append(':').append(targetPort).append("\r\n");
        if (username != null && !username.isBlank()) {
            String cred = Base64.getEncoder().encodeToString(
                    (username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
            req.append("Proxy-Authorization: Basic ").append(cred).append("\r\n");
        }
        req.append("Connection: keep-alive\r\n\r\n");
        out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();

        InputStream in = socket.getInputStream();
        String statusLine = readLine(in);
        if (statusLine == null || !statusLine.contains(" 200 "))
            throw new IOException("HTTP proxy CONNECT to " + targetHost + ":" + targetPort + " failed: "
                    + (statusLine == null ? "no response" : statusLine));
        String line;
        do {
            line = readLine(in);
        } while (line != null && !line.isEmpty());
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\r') sb.setLength(sb.length() - 1);
                return sb.toString();
            }
            sb.append((char) c);
        }
        return any ? sb.toString() : null;
    }
}
