package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.SecretResolver;
import net.schmizz.sshj.SSHClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Opens a JDBC {@link Connection} for a {@code db} {@link ConnectionProfile}. Shared by
 * {@link DbExportConnector} (query-export source) and {@link DbConnectionWorkbench} (the probe/explore/sample
 * surface) so the URL / driver / SSH-tunnel / secret-resolution logic lives in exactly one place.
 *
 * <p>An explicit {@code options.jdbc_url} (with optional {@code options.driver}) is honoured verbatim;
 * otherwise a PostgreSQL URL is built from the profile's host/port/database, forwarded through the profile's
 * SSH bastion when it declares a {@code tunnel}. Secrets resolve through {@link SecretResolver} — plaintext
 * values never appear in config.
 */
final class DbConnections {

    /** Default PostgreSQL port used when the profile leaves {@code port} unset and no {@code jdbc_url} is given. */
    static final int DEFAULT_PG_PORT = 5432;

    private DbConnections() {}

    /** A live JDBC connection plus the SSH tunnel opened for it (null when none). The caller closes both. */
    record Handle(Connection conn, SshTunnel tunnel) {}

    /**
     * Open a connection to the profile's database. On any failure after a tunnel is opened, the tunnel is
     * closed before the exception propagates, so a failed connect never leaks a forward.
     */
    static Handle open(ConnectionProfile profile) throws SQLException {
        String driverClass = profile.options().get("driver");
        if (driverClass != null && !driverClass.isBlank()) {
            try { Class.forName(driverClass.trim()); }
            catch (ClassNotFoundException e) { throw new SQLException("JDBC driver not found: " + driverClass, e); }
        }

        SshTunnel tunnel = null;
        String url = profile.options().get("jdbc_url");
        try {
            if (url == null || url.isBlank()) {
                String host = profile.host();
                int port = profile.port() > 0 ? profile.port() : DEFAULT_PG_PORT;
                if (profile.tunnel() != null && profile.tunnel().host() != null && !profile.tunnel().host().isBlank()) {
                    // the bastion is the only SSH hop here, so host_key/known_hosts pin it directly.
                    try {
                        tunnel = SshTunnel.open(profile.tunnel(), host, port, DbConnections::sshAuth,
                                HostKeyPolicy.from(profile));
                    } catch (IOException e) {
                        throw new SQLException("SSH tunnel for DB connection '" + profile.id() + "' failed", e);
                    }
                    InetSocketAddress local = tunnel.localEndpoint();
                    host = local.getHostString();
                    port = local.getPort();
                }
                url = "jdbc:postgresql://" + host + ":" + port + "/" + profile.database();
            }
            String user = profile.username();
            String pass = SecretResolver.resolve(profile.password());
            java.util.Properties props = new java.util.Properties();
            if (user != null) props.setProperty("user", user);
            if (user != null && pass != null) props.setProperty("password", pass);
            applyProxy(profile, url, tunnel != null, props);
            Connection conn = DriverManager.getConnection(url, props);
            return new Handle(conn, tunnel);
        } catch (SQLException e) {
            if (tunnel != null) try { tunnel.close(); } catch (IOException ignore) { /* best effort */ }
            throw e;
        }
    }

    /**
     * The JDBC half of the connector proxy dial-through (2026-09-06). {@code ConnectionProfile.proxy} was
     * honoured by SFTP/FTP since 2026-07-20/08-13 and ignored here — a DB Connection with a proxy dialled
     * the database directly, silently. PostgreSQL's driver exposes the same seam sshj does: a
     * {@code socketFactory=} class (+ a single-string {@code socketFactoryArg=}) it instantiates
     * reflectively and takes an unconnected socket from, so the two existing factories carry it with no new
     * tunnelling code. Fail-closed on what cannot be routed: a driver other than PostgreSQL (no such hook
     * on DuckDB or an unknown {@code jdbc_url}), an unknown proxy type, and a proxy COMBINED with an SSH
     * tunnel — the JDBC socket then dials the tunnel's local endpoint, which a proxy must not carry, and the
     * SSH hop itself is not proxied today; refusing beats routing half the path.
     */
    static void applyProxy(ConnectionProfile profile, String url, boolean tunnelled, java.util.Properties props)
            throws SQLException {
        ConnectionProfile.Proxy proxy = profile.proxy();
        if (proxy == null || proxy.host() == null || proxy.host().isBlank()) return;
        if (tunnelled)
            throw new SQLException("DB connection '" + profile.id() + "' declares both an SSH tunnel and a proxy; "
                    + "the JDBC socket dials the tunnel's local endpoint, which a proxy cannot carry — drop one");
        if (!url.startsWith("jdbc:postgresql:"))
            throw new SQLException("DB connection '" + profile.id() + "' declares a proxy but its driver has no "
                    + "per-connection proxy hook (only PostgreSQL's socketFactory= is wired); remove the proxy or "
                    + "route at the network layer");
        String type = proxy.type() == null ? "" : proxy.type().trim().toUpperCase(java.util.Locale.ROOT);
        switch (type) {
            case "SOCKS5" -> {
                props.setProperty("socketFactory", SocksProxySocketFactory.class.getName());
                props.setProperty("socketFactoryArg", ProxyArg.of(proxy, false));
            }
            case "HTTP" -> {
                props.setProperty("socketFactory", HttpProxySocketFactory.class.getName());
                props.setProperty("socketFactoryArg", ProxyArg.of(proxy, true));
            }
            default -> throw new SQLException("DB connection '" + profile.id() + "' supports proxy type SOCKS5 or HTTP only (got '"
                    + proxy.type() + "')");
        }
    }

    private static void sshAuth(SSHClient client, String user, String passwordRef) throws IOException {
        String password = SecretResolver.resolve(passwordRef);
        if (password == null) throw new IOException("no usable credential for SSH tunnel user '" + user + "'");
        client.authPassword(user, password);
    }
}
