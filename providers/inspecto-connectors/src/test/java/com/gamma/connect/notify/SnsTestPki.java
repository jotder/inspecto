package com.gamma.connect.notify;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A throwaway PKI for the SNS tests (D8-SES-SNS-1 slice S0), minted AT TEST TIME with the JDK's own
 * {@code keytool} into a temp directory — nothing here is a committed secret. One test CA; leaves are issued by
 * it (or self-signed, for the "does not chain" case) with a chosen subject, SAN, key size and validity.
 *
 * <p>The JDK has no public certificate-builder API, and the repo carries no BouncyCastle; {@code keytool
 * -gencert} is the same route {@code PinnedHttpTlsTest} takes for its TLS identity.
 */
final class SnsTestPki {

    private static final String PW = "changeit";
    private static volatile Path dir;
    private static final Map<String, Leaf> ISSUED = new ConcurrentHashMap<>();

    /** A key and the certificate that names it. {@code pem} is the certificate as SNS would serve it. */
    record Leaf(PrivateKey key, X509Certificate cert, String pem) {}

    private SnsTestPki() {}

    /** The test CA's certificate — the trust anchor a test hands the verifier in place of the JVM store. */
    static synchronized X509Certificate ca() throws Exception {
        Path d = dir();
        Path ca = d.resolve("ca.p12");
        if (!Files.exists(ca)) {
            run("-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Inspecto Test SNS CA",
                    "-ext", "bc:c", "-validity", "3", "-storetype", "PKCS12", "-keystore", ca.toString(),
                    "-storepass", PW, "-keypass", PW);
        }
        return (X509Certificate) load(ca).getCertificate("ca");
    }

    /** The regular SNS signing identity: CN {@code sns.amazonaws.com}, RSA 2048, issued by the test CA. */
    static Leaf signer() throws Exception {
        return leaf("signer", "CN=sns.amazonaws.com", "dns:sns.amazonaws.com", 2048, null, 3, true);
    }

    /** A TLS server identity for the given host, issued by the test CA. */
    static Leaf tlsServer(String host) throws Exception {
        return leaf("tls-" + host, "CN=" + host, "dns:" + host, 2048, null, 3, true);
    }

    /**
     * Issue (once per {@code name}) a leaf. {@code startDate} is a keytool {@code -startdate} such as
     * {@code "-10d"}, or {@code null} for now; {@code chained=false} returns the self-signed certificate.
     */
    static synchronized Leaf leaf(String name, String dname, String san, int bits, String startDate, int validityDays,
                                  boolean chained) throws Exception {
        Leaf cached = ISSUED.get(name);
        if (cached != null) return cached;
        ca();
        Path d = dir();
        Path ks = d.resolve(name + ".p12");
        List<String> gen = new ArrayList<>(List.of("-genkeypair", "-alias", "leaf", "-keyalg", "RSA", "-keysize",
                Integer.toString(bits), "-dname", dname, "-ext", "SAN=" + san, "-validity", Integer.toString(validityDays),
                "-storetype", "PKCS12", "-keystore", ks.toString(), "-storepass", PW, "-keypass", PW));
        if (startDate != null) gen.addAll(List.of("-startdate", startDate));
        run(gen.toArray(String[]::new));
        KeyStore store = load(ks);
        PrivateKey key = (PrivateKey) store.getKey("leaf", PW.toCharArray());
        String pem;
        if (chained) {
            Path csr = d.resolve(name + ".csr");
            Path out = d.resolve(name + ".pem");
            run("-certreq", "-alias", "leaf", "-keystore", ks.toString(), "-storepass", PW, "-file", csr.toString());
            List<String> sign = new ArrayList<>(List.of("-gencert", "-alias", "ca", "-keystore",
                    d.resolve("ca.p12").toString(), "-storepass", PW, "-infile", csr.toString(), "-outfile",
                    out.toString(), "-rfc", "-ext", "SAN=" + san, "-validity", Integer.toString(validityDays)));
            if (startDate != null) sign.addAll(List.of("-startdate", startDate));
            run(sign.toArray(String[]::new));
            pem = Files.readString(out, StandardCharsets.US_ASCII);
        } else {
            Path out = d.resolve(name + ".pem");
            run("-exportcert", "-rfc", "-alias", "leaf", "-keystore", ks.toString(), "-storepass", PW, "-file",
                    out.toString());
            pem = Files.readString(out, StandardCharsets.US_ASCII);
        }
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
        Leaf leaf = new Leaf(key, cert, pem);
        ISSUED.put(name, leaf);
        return leaf;
    }

    /** A PKCS12 key store holding {@code leaf} with its chain to the CA — for an {@code HttpsServer}. */
    static KeyStore serverKeyStore(Leaf leaf) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("srv", leaf.key(), PW.toCharArray(), new java.security.cert.Certificate[] { leaf.cert(), ca() });
        return ks;
    }

    static char[] password() {
        return PW.toCharArray();
    }

    private static Path dir() throws Exception {
        if (dir == null) dir = Files.createTempDirectory("sns-test-pki");
        return dir;
    }

    private static KeyStore load(Path p12) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(p12)) {
            store.load(in, PW.toCharArray());
        }
        return store;
    }

    private static void run(String... args) throws Exception {
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        List<String> cmd = new ArrayList<>();
        cmd.add(keytool);
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IllegalStateException("keytool " + String.join(" ", args) + ": " + out);
    }
}
