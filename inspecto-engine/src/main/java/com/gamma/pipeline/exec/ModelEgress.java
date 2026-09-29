package com.gamma.pipeline.exec;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>The model-endpoint allowlist</b> (round-2 verification item 2, 2026-09-29): which hosts the intelligence
 * agent's model gateway may dial. Without it, whoever could write the assist settings' {@code baseUrl} could make
 * the server call any URL (SSRF / exfiltration through prompts).
 *
 * <p>Stricter than the general {@link EgressPolicy}: a model endpoint must be <b>named</b> by the list, as a host
 * entry or by a CIDR containing every address it resolves to. An EMPTY list (the default) allows no model endpoint
 * at all, and the gateway degrades to its offline stub. On top of that, the address-class rules of
 * {@link EgressPolicy} still apply. The one exception is <b>loopback, for a local model server</b>: EgressPolicy
 * never lifts loopback, but a model list may name {@code localhost}, {@code 127.0.0.1} or {@code ::1} explicitly,
 * and only then may the gateway reach a loopback address.
 *
 * <p>Stored as the {@code models} key of the Space's {@code egress.toon}, beside the general {@code allow} list. So
 * it inherits that file's controls: written only through {@code PUT /settings/egress} ({@code canAdminister},
 * validated fail closed, audited before/after) and reserved from every import. The caller connects to the CHECKED
 * address: {@link #pin} rewrites the URL's host to it.
 */
public final class ModelEgress {

    /** The key in {@code egress.toon}. */
    public static final String KEY = "models";

    private ModelEgress() {}

    /** A parsed list: loopback names allowed for a local model server, plus host / CIDR entries. */
    public record Policy(Set<String> loopback, EgressPolicy.Allowlist allow) {
        public static final Policy EMPTY = new Policy(Set.of(), EgressPolicy.Allowlist.EMPTY);
    }

    /** Parse entries; {@link IllegalArgumentException} names the first bad one (the route maps that to 422). */
    public static Policy parse(List<String> entries) {
        Set<String> loopback = new LinkedHashSet<>();
        List<String> rest = new ArrayList<>();
        for (String raw : entries) {
            String e = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (e.equals("localhost") || e.equals("127.0.0.1") || e.equals("::1") || e.equals("[::1]")) {
                loopback.add(e.equals("[::1]") ? "::1" : e);
            } else {
                rest.add(e);
            }
        }
        return new Policy(Set.copyOf(loopback), EgressPolicy.Allowlist.of(rest));
    }

    /**
     * Resolve {@code host} once and check every address: named by the list, and either of an allowed class or a
     * loopback address the list names explicitly. Returns the checked address, which the caller must connect to.
     */
    public static InetAddress resolve(String host, Policy policy, EgressPolicy.Resolver resolver) throws EgressPolicy.Refused {
        try {
            EgressPolicy.checkHost(host);
        } catch (IllegalArgumentException bad) {
            throw new EgressPolicy.Refused(bad.getMessage());
        }
        String bare = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        String lower = bare.toLowerCase(Locale.ROOT);
        InetAddress[] all;
        try {
            all = resolver.resolve(bare);
            if (all == null || all.length == 0) throw new java.net.UnknownHostException(bare);
        } catch (java.net.UnknownHostException e) {
            throw new EgressPolicy.Refused("the model endpoint '" + host + "' does not resolve");
        }
        for (InetAddress a : all) {
            String cls = EgressPolicy.deniedClass(a);
            if ("loopback".equals(cls)) {
                if (policy.loopback().contains(lower) || policy.loopback().contains(a.getHostAddress().toLowerCase(Locale.ROOT))
                        || (a instanceof Inet6Address && policy.loopback().contains("::1")))
                    continue;
                throw new EgressPolicy.Refused("the model endpoint '" + host + "' resolves to loopback " + a.getHostAddress()
                        + ", which the model endpoint allowlist does not name");
            }
            boolean named = policy.allow().namesHost(lower)
                    || policy.allow().cidrs().stream().anyMatch(c -> c.contains(a));
            if (!named)
                throw new EgressPolicy.Refused("the model endpoint '" + host + "' (" + a.getHostAddress()
                        + ") is not on this Space's model endpoint allowlist");
            if (cls != null && !policy.allow().permits(lower, a, cls))
                throw new EgressPolicy.Refused("the model endpoint '" + host + "' resolves to " + a.getHostAddress()
                        + ", a " + cls + " address the egress policy denies");
        }
        return all[0];
    }

    /**
     * {@code baseUrl} with its host replaced by the checked address, so a second resolution cannot swap it. An
     * {@code https} URL naming a DNS host is refused: the gateway's HTTP client takes only a URL, and pinning it to
     * an address would break the TLS name check, so it could not be pinned at all.
     */
    public static String pin(String baseUrl, Policy policy, EgressPolicy.Resolver resolver) throws EgressPolicy.Refused {
        URI u;
        try {
            u = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException bad) {
            throw new EgressPolicy.Refused("the model endpoint '" + baseUrl + "' is not a URL");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https"))
            throw new EgressPolicy.Refused("the model endpoint must be http or https, not '" + baseUrl + "'");
        if (u.getRawUserInfo() != null || u.getHost() == null)
            throw new EgressPolicy.Refused("the model endpoint '" + baseUrl + "' has no plain host");
        InetAddress checked = resolve(u.getHost(), policy, resolver);
        if (scheme.equals("https") && !EgressPolicy.isIpLiteral(u.getHost()))
            throw new EgressPolicy.Refused("an https model endpoint named by host ('" + u.getHost() + "') cannot be "
                    + "pinned to its checked address; name it by IP address instead");
        String literal = checked instanceof Inet6Address ? "[" + checked.getHostAddress() + "]" : checked.getHostAddress();
        try {
            return new URI(u.getScheme(), null, literal.startsWith("[") ? literal.substring(1, literal.length() - 1) : literal,
                    u.getPort(), u.getRawPath(), u.getRawQuery(), null).toString();
        } catch (java.net.URISyntaxException e) {
            throw new EgressPolicy.Refused("the model endpoint '" + baseUrl + "' cannot be rewritten: " + e.getMessage());
        }
    }

    /** The {@code models} entries of the Space whose config root is {@code root}; empty (deny) when absent or bad. */
    public static List<String> entries(java.nio.file.Path root) {
        if (root == null) return List.of();
        java.nio.file.Path f = root.resolve(EgressAllowlist.FILE);
        if (!java.nio.file.Files.exists(f)) return List.of();
        try {
            Object models = com.gamma.util.ToonHelper.load(f.toString()).get(KEY);
            List<String> out = new ArrayList<>();
            if (models instanceof List<?> l) for (Object o : l) out.add(String.valueOf(o));
            parse(out);   // validate
            return out;
        } catch (Exception bad) {
            return List.of();
        }
    }

    /** The current Space's parsed model endpoint allowlist; EMPTY without a config root. */
    public static Policy forCurrentSpace() {
        return parse(entries(com.gamma.pipeline.SpaceConfigRoot.current()));
    }
}
