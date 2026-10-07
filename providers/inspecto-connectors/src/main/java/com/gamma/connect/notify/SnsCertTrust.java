package com.gamma.connect.notify;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.KeyStore;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Whether an SNS signing certificate is one we believe (design §3.2 step 10). Independent of how the certificate
 * was obtained — the fetch, the cache and the pinned file all pass through here — so the signature proves
 * something only if this says yes.
 *
 * <p>The certificate must: chain under PKIX to the trust anchors (the JVM trust store in production; revocation
 * checking OFF, because an OCSP/CRL fetch would be yet another outbound call); be inside its validity period;
 * name {@code sns.amazonaws.com} (or the ARN-derived regional host) as a SAN {@code dNSName}, or as its CN when
 * it carries no DNS SAN; and carry an RSA key of at least 2048 bits. Intermediates may come from the PEM itself
 * or from the TLS session that fetched it — neither is a trust anchor, only path material.
 */
final class SnsCertTrust {

    static final int MIN_RSA_BITS = 2048;

    private final Set<TrustAnchor> anchors;

    SnsCertTrust(Set<TrustAnchor> anchors) {
        if (anchors == null || anchors.isEmpty()) throw new IllegalArgumentException("no trust anchors");
        this.anchors = Set.copyOf(anchors);
    }

    /** Trust exactly these certificates as anchors (a test CA). */
    static SnsCertTrust anchoredAt(Collection<X509Certificate> roots) {
        Set<TrustAnchor> a = new HashSet<>();
        for (X509Certificate c : roots) a.add(new TrustAnchor(c, null));
        return new SnsCertTrust(a);
    }

    /** The JVM's default trust store — what every HTTPS client in the product already trusts. */
    static SnsCertTrust jvmDefault() {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            List<X509Certificate> roots = new ArrayList<>();
            for (var tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager x) roots.addAll(List.of(x.getAcceptedIssuers()));
            }
            return anchoredAt(roots);
        } catch (Exception e) {
            throw new IllegalStateException("cannot load the JVM trust store", e);
        }
    }

    /**
     * Throws naming why {@code cert} is not believed for {@code derivedHost}; returns normally when it is.
     *
     * @param intermediates path material only (may be empty)
     */
    void check(X509Certificate cert, List<X509Certificate> intermediates, String derivedHost, long nowMillis)
            throws Exception {
        cert.checkValidity(new Date(nowMillis));
        if (!(cert.getPublicKey() instanceof RSAPublicKey rsa) || rsa.getModulus().bitLength() < MIN_RSA_BITS)
            throw new SecurityException("the signing certificate's key is not RSA-" + MIN_RSA_BITS + " or stronger");
        if (!namesSns(cert, derivedHost))
            throw new SecurityException("the signing certificate names neither sns.amazonaws.com nor " + derivedHost);
        X509CertSelector target = new X509CertSelector();
        target.setCertificate(cert);
        PKIXBuilderParameters p = new PKIXBuilderParameters(anchors, target);
        p.setRevocationEnabled(false);
        p.setDate(new Date(nowMillis));
        List<X509Certificate> material = new ArrayList<>(intermediates == null ? List.of() : intermediates);
        material.add(cert);
        p.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(material)));
        CertPathBuilder.getInstance("PKIX").build(p);   // throws CertPathBuilderException when it does not chain
    }

    private static boolean namesSns(X509Certificate cert, String derivedHost) throws Exception {
        Set<String> accepted = new HashSet<>();
        accepted.add("sns.amazonaws.com");
        if (derivedHost != null) {
            accepted.add(derivedHost.toLowerCase(Locale.ROOT));
            if (derivedHost.endsWith(".amazonaws.com.cn")) accepted.add("sns.amazonaws.com.cn");
        }
        boolean anyDns = false;
        Collection<List<?>> sans = cert.getSubjectAlternativeNames();
        if (sans != null) {
            for (List<?> san : sans) {
                if (san.size() >= 2 && Integer.valueOf(2).equals(san.get(0))) {
                    anyDns = true;
                    if (accepted.contains(String.valueOf(san.get(1)).toLowerCase(Locale.ROOT))) return true;
                }
            }
        }
        if (anyDns) return false;   // RFC 6125: with a DNS SAN present, the CN is not consulted
        String dn = cert.getSubjectX500Principal().getName();
        for (String rdn : new javax.naming.ldap.LdapName(dn).getRdns().stream().map(Object::toString).toList()) {
            if (rdn.regionMatches(true, 0, "CN=", 0, 3)
                    && accepted.contains(rdn.substring(3).toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
