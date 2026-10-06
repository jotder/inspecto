package com.gamma.job;

import com.gamma.acquire.SecretResolver;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.List;
import java.util.Properties;

/**
 * The pgjdbc {@code sslfactory} {@code publish.postgres} supplies (ASSURE-BI-PUBLICATION-1, TLS fix). It layers TLS on
 * the pinned socket for the AUTHORED host (SNI + {@code HTTPS} endpoint identification), trusting either the JDK's
 * default roots or the CA PEM that {@value #ROOT_CERT_REF} names — which must be a secret reference
 * ({@code ${KEYSTORE:…}}, {@code ${ENV:…}}, …), never a file path. Supplying our own factory also keeps pgjdbc's
 * default {@code LibPQFactory} from reading {@code ~/.postgresql/root.crt} and a client key from the host's home.
 * pgjdbc's own {@code verify-full} hostname check still runs on top.
 */
public final class PublishSslFactory extends SSLSocketFactory {

    /** The connection property carrying the {@code sslrootcert} secret reference (optional). */
    public static final String ROOT_CERT_REF = "inspectoRootCertRef";

    private final SSLSocketFactory delegate;

    public PublishSslFactory(Properties info) throws Exception {
        String ref = info == null ? null : info.getProperty(ROOT_CERT_REF);
        SSLContext ctx = SSLContext.getInstance("TLS");
        if (ref == null || ref.isBlank()) {
            ctx.init(null, null, null);
        } else {
            if (!SecretResolver.isReference(ref))
                throw new IllegalArgumentException("sslrootcert must be a secret reference, never a file path");
            String pem = SecretResolver.resolve(ref);
            if (pem == null || pem.isBlank()) throw new IllegalStateException("the sslrootcert reference does not resolve");
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int i = 0;
            for (Certificate c : CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII))))
                ks.setCertificateEntry("ca" + i++, c);
            if (i == 0) throw new IllegalStateException("the sslrootcert reference holds no certificate");
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            ctx.init(null, tmf.getTrustManagers(), null);
        }
        this.delegate = ctx.getSocketFactory();
    }

    /** TLS over {@code s} (already connected to the pinned address) for the authored {@code host}. */
    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        SSLSocket ssl = (SSLSocket) delegate.createSocket(s, host, port, autoClose);
        SSLParameters p = ssl.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS");
        if (!com.gamma.util.egress.EgressPolicy.isIpLiteral(host)) p.setServerNames(List.of(new SNIHostName(host)));
        ssl.setSSLParameters(p);
        return ssl;
    }

    @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }

    private static IOException direct() { return new IOException("publish.postgres TLS runs only over the pinned socket"); }
    @Override public Socket createSocket(String h, int p) throws IOException { throw direct(); }
    @Override public Socket createSocket(String h, int p, InetAddress la, int lp) throws IOException { throw direct(); }
    @Override public Socket createSocket(InetAddress h, int p) throws IOException { throw direct(); }
    @Override public Socket createSocket(InetAddress a, int p, InetAddress la, int lp) throws IOException { throw direct(); }
}
