package com.gamma.job;

import org.junit.jupiter.api.Assumptions;

import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * A TLS Postgres for {@code PostgresPublishPgTest} that needs no inputs: a keytool-made CA and a CA-signed server
 * certificate for {@value #HOST}, and a throwaway container of the LOCAL {@code postgres:latest} image
 * ({@code --pull never}) with {@code ssl=on}. JDK tools only — no openssl. Skips (JUnit assumption, naming why)
 * when docker or the local image is not there; always removes its container.
 */
final class SelfProvisionedTlsPostgres implements AutoCloseable {

    static final String HOST = "bi.example.test", PASSWORD = "pubtls", IMAGE = "postgres:latest";

    private final String container;
    private final int port;
    private final Path caFile;

    private SelfProvisionedTlsPostgres(String container, int port, Path caFile) {
        this.container = container;
        this.port = port;
        this.caFile = caFile;
    }

    String setupUrl() { return "jdbc:postgresql://127.0.0.1:" + port + "/postgres?user=postgres&password=" + PASSWORD + "&sslmode=require"; }

    Path caFile() { return caFile; }

    static SelfProvisionedTlsPostgres start(Path dir) throws Exception {
        Assumptions.assumeTrue(run(List.of("docker", "image", "inspect", IMAGE)) == 0,
                "SKIPPED: no docker, or no local " + IMAGE + " image (never pulled) — or set INSPECTO_TEST_PG_TLS_*");
        Files.createDirectories(dir);
        String kt = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Path ca = dir.resolve("ca.p12"), srv = dir.resolve("srv.p12");
        keytool(kt, "-genkeypair", "-alias", "ca", "-dname", "CN=inspecto-test-ca", "-ext", "bc:c", "-keyalg", "RSA", "-validity", "2",
                "-keystore", ca.toString());
        keytool(kt, "-genkeypair", "-alias", "srv", "-dname", "CN=" + HOST, "-keyalg", "RSA", "-validity", "2", "-keystore", srv.toString());
        keytool(kt, "-certreq", "-alias", "srv", "-keystore", srv.toString(), "-file", dir.resolve("srv.csr").toString());
        keytool(kt, "-gencert", "-alias", "ca", "-keystore", ca.toString(), "-infile", dir.resolve("srv.csr").toString(),
                "-outfile", dir.resolve("server.crt").toString(), "-rfc", "-validity", "2", "-ext", "SAN=dns:" + HOST);
        keytool(kt, "-exportcert", "-alias", "ca", "-keystore", ca.toString(), "-rfc", "-file", dir.resolve("ca.crt").toString());
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(srv)) { ks.load(in, "changeit".toCharArray()); }
        PrivateKey key = (PrivateKey) ks.getKey("srv", "changeit".toCharArray());
        Files.writeString(dir.resolve("server.key"), "-----BEGIN PRIVATE KEY-----\n"   // secret-allow: PEM header literal wrapping a key generated at test time, no key is committed
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        Path script = dir.resolve("start.sh");
        Files.writeString(script, "#!/bin/sh\ncp /certs/server.crt /certs/server.key /tmp/\n"
                + "chown postgres /tmp/server.crt /tmp/server.key\nchmod 600 /tmp/server.key\n"
                + "exec docker-entrypoint.sh postgres -c ssl=on -c ssl_cert_file=/tmp/server.crt -c ssl_key_file=/tmp/server.key\n");
        try {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException windows) {
            // Docker Desktop mounts Windows files executable
        }
        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        String name = "inspecto-publish-tls-" + UUID.randomUUID().toString().substring(0, 8);
        int rc = run(List.of("docker", "run", "-d", "--rm", "--pull", "never", "--name", name,
                "-p", "127.0.0.1:" + port + ":5432", "-e", "POSTGRES_PASSWORD=" + PASSWORD,
                "-v", dir.toAbsolutePath().toString().replace('\\', '/') + ":/certs:ro",
                "--entrypoint", "/certs/start.sh", IMAGE));
        if (rc != 0) throw new IllegalStateException("docker run failed (" + rc + ")");
        SelfProvisionedTlsPostgres pg = new SelfProvisionedTlsPostgres(name, port, dir.resolve("ca.crt"));
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (true) {
            try (var c = DriverManager.getConnection(pg.setupUrl())) {
                return pg;
            } catch (java.sql.SQLException notYet) {
                if (System.nanoTime() > deadline) {
                    pg.close();
                    throw new IllegalStateException("TLS Postgres did not come up: " + notYet.getMessage());
                }
                Thread.sleep(500);
            }
        }
    }

    @Override
    public void close() throws Exception {
        run(List.of("docker", "rm", "-f", container));
    }

    private static void keytool(String kt, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(kt));
        cmd.addAll(List.of(args));
        cmd.addAll(List.of("-storetype", "PKCS12", "-storepass", "changeit", "-noprompt"));
        if (run(cmd) != 0) throw new IllegalStateException("keytool failed: " + cmd);
    }

    private static int run(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor();
        } catch (Exception e) {
            return -1;
        }
    }
}
