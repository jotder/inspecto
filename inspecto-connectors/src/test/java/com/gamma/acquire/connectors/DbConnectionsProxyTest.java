package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The JDBC half of the connector proxy dial-through. The rule from the SFTP/FTP tests holds here: a proxy test
 * must prove the proxy was USED — so each case runs the real PostgreSQL driver against a relay that records the
 * target it was asked to CONNECT to, and asserts that target is the database, not nothing. The "database" is
 * a bare listener that closes on accept: the handshake fails afterwards, which is fine — the socket had
 * already gone through the relay, and that is the whole claim.
 */
class DbConnectionsProxyTest {

    private static ConnectionProfile profile(int dbPort, ConnectionProfile.Proxy proxy, ConnectionProfile.Tunnel tunnel) {
        return new ConnectionProfile("test-db", "db", "127.0.0.1", dbPort, "orders", "", "u", "p",
                Map.of(), tunnel, proxy);
    }

    @Test
    @Timeout(30)
    void aPostgresConnectionDialsThroughTheHttpConnectProxy() throws Exception {
        try (ServerSocket db = new ServerSocket(0); MiniHttpConnectRelay relay = MiniHttpConnectRelay.start()) {
            ConnectionProfile p = profile(db.getLocalPort(),
                    new ConnectionProfile.Proxy("HTTP", "127.0.0.1", relay.port(), null, null), null);
            assertThrows(SQLException.class, () -> DbConnections.open(p),
                    "the bare listener speaks no PostgreSQL, so the connect fails AFTER the tunnel — expected");
            assertEquals("127.0.0.1:" + db.getLocalPort(), relay.firstConnectTarget(2000),
                    "the driver's socket asked the proxy for the database, proving the CONNECT tunnel carried it");
        }
    }

    @Test
    @Timeout(30)
    void aPostgresConnectionDialsThroughTheSocks5Proxy() throws Exception {
        try (ServerSocket db = new ServerSocket(0); MiniSocks5Relay relay = MiniSocks5Relay.start()) {
            ConnectionProfile p = profile(db.getLocalPort(),
                    new ConnectionProfile.Proxy("SOCKS5", "127.0.0.1", relay.port(), null, null), null);
            assertThrows(SQLException.class, () -> DbConnections.open(p));
            assertEquals("127.0.0.1:" + db.getLocalPort(), relay.firstConnectTarget(2000),
                    "the SOCKS relay saw the database as the requested target");
        }
    }

    @Test
    void theProxyPropertiesNameTheFactoriesTheDriverCanInstantiate() throws Exception {
        Properties props = new Properties();
        DbConnections.applyProxy(profile(5432,
                new ConnectionProfile.Proxy("HTTP", "proxy.example", 3128, "alice", "s3cret"), null),
                "jdbc:postgresql://db:5432/orders", false, props);
        assertEquals(HttpProxySocketFactory.class.getName(), props.getProperty("socketFactory"));
        assertEquals("proxy.example:3128:alice:s3cret", props.getProperty("socketFactoryArg"));
        // and the driver's reflective path — Class.forName + the public (String) constructor — resolves
        Object f = Class.forName(props.getProperty("socketFactory"))
                .getConstructor(String.class).newInstance(props.getProperty("socketFactoryArg"));
        assertTrue(f instanceof javax.net.SocketFactory);
        assertEquals("proxy.example:1080", ProxyArg.of(
                new ConnectionProfile.Proxy("SOCKS5", "proxy.example", 1080, "ignored", "ignored"), false),
                "SOCKS5 carries no credentials in the arg — the factory has no auth handshake");
    }

    @Test
    void unroutableShapesAreRefusedNotSilentlyDialledDirect() {
        ConnectionProfile.Proxy proxy = new ConnectionProfile.Proxy("HTTP", "127.0.0.1", 1, null, null);
        Properties props = new Properties();
        SQLException tunnel = assertThrows(SQLException.class, () -> DbConnections.applyProxy(
                profile(5432, proxy, null), "jdbc:postgresql://127.0.0.1:5432/orders", true, props));
        assertTrue(tunnel.getMessage().contains("SSH tunnel and a proxy"), tunnel.getMessage());
        SQLException driver = assertThrows(SQLException.class, () -> DbConnections.applyProxy(
                profile(5432, proxy, null), "jdbc:duckdb:", false, props));
        assertTrue(driver.getMessage().contains("no per-connection proxy hook"), driver.getMessage());
        SQLException type = assertThrows(SQLException.class, () -> DbConnections.applyProxy(
                profile(5432, new ConnectionProfile.Proxy("FTP", "127.0.0.1", 1, null, null), null),
                "jdbc:postgresql://127.0.0.1:5432/orders", false, props));
        assertTrue(type.getMessage().contains("SOCKS5 or HTTP only"), type.getMessage());
        assertTrue(props.isEmpty(), "a refusal writes nothing into the connection properties");
        // and no proxy at all is a no-op, not an error
        assertDoesNotThrow(() -> DbConnections.applyProxy(profile(5432, null, null),
                "jdbc:duckdb:", false, props));
    }
}
