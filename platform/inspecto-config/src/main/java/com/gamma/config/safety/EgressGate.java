package com.gamma.config.safety;




import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * The act-time network gate of the Safety Policy (policy-narrowing-design §4.1, S4): every {@code (host, port)} a
 * run is about to open a socket to - target, bastion, proxy, redirect, broker - passes {@link #require} first.
 * Refuses on the {@code permit.network} switch, an {@code allow.hosts} miss, a {@code deny.hosts} hit, and (D13)
 * a permitted <em>name</em> that resolves into a denied CIDR. {@code mode: audit} (D5) logs the would-refuse and
 * lets the dial proceed.
 *
 * <p>⚠ A gate holds the tier it was built from. {@link #current()} reads the calling thread's pinned snapshot
 * ({@code SafetyPolicy.pinnedForRun}), which a worker pool does not see - a component that dials from such a thread
 * builds its gate on the planning thread and keeps it.
 */
public final class EgressGate {

    private static final System.Logger log = System.getLogger(EgressGate.class.getName());

    private final SafetyPolicyTier tier;

    private EgressGate(SafetyPolicyTier tier) {
        this.tier = tier;
    }

    /** The gate for the calling thread's Space (its run's pinned snapshot when bound). */
    public static EgressGate current() {
        return new EgressGate(SafetyPolicy.effectiveTier());
    }

    /** A gate over an explicit tier - the hand-off to a worker thread. */
    public static EgressGate of(SafetyPolicyTier tier) {
        return new EgressGate(tier);
    }

    /**
     * @param port {@code <= 0} when unknown (only reported)
     * @param purpose what is dialling ({@code "sftp"}, {@code "sftp bastion"}) - named in the refusal
     * @throws EgressRefusedException when the Safety Policy forbids the dial in {@code enforce} mode
     */
    public void require(String host, int port, String purpose) {
        String why = refusal(host);
        if (why == null) return;
        String msg = "egress refused by the Safety Policy: " + purpose + " -> " + host
                + (port > 0 ? ":" + port : "") + " (" + why + ")";
        if (tier.effectiveMode() == SafetyPolicyTier.Mode.AUDIT) {
            log.log(System.Logger.Level.WARNING, "AUDIT would refuse: " + msg);
            return;
        }
        throw new EgressRefusedException(msg);
    }

    /** The gate over the server tier alone (D16: an operator-level act that is not a Space run). */
    public static EgressGate server() {
        String dir = System.getProperty("system.config.dir");
        return new EgressGate(SafetyPolicyFiles.effective(
                dir == null || dir.isBlank() ? null : java.nio.file.Paths.get(dir.trim()), null, null, java.util.List.of()));
    }

    /**
     * Gates every host a JDBC URL names (multi-host URLs included). In-process engines ({@code duckdb},
     * {@code sqlite}, {@code h2}) dial nothing and pass; any other URL whose host cannot be read is refused,
     * because a URL that hides its host must not read as "no host to gate".
     */
    public void requireJdbcUrl(String url, String purpose) {
        if (url == null || url.isBlank()) return;
        String u = url.trim();
        if (u.regionMatches(true, 0, "jdbc:duckdb:", 0, 12) || u.regionMatches(true, 0, "jdbc:sqlite:", 0, 12)
                || u.regionMatches(true, 0, "jdbc:h2:", 0, 8)) return;
        int at = u.indexOf("//");
        String rest;
        if (at >= 0) rest = u.substring(at + 2);
        else {   // jdbc:oracle:thin:@host:port:sid
            int a = u.indexOf('@');
            if (a < 0) throw new EgressRefusedException("egress refused by the Safety Policy: " + purpose
                    + " -> cannot read a host from the JDBC URL");
            rest = u.substring(a + 1);
        }
        int end = rest.length();
        for (char c : new char[]{'/', ';', '?'}) { int i = rest.indexOf(c); if (i >= 0) end = Math.min(end, i); }
        String authority = rest.substring(0, end);
        if (authority.indexOf('@') >= 0) authority = authority.substring(authority.lastIndexOf('@') + 1);
        if (authority.isBlank()) throw new EgressRefusedException("egress refused by the Safety Policy: " + purpose
                + " -> cannot read a host from the JDBC URL");
        requireHostList(authority, purpose);
    }

    /** Gates a comma-separated {@code host:port} list (Kafka {@code bootstrap.servers}, multi-host URLs). */
    public void requireHostList(String list, String purpose) {
        for (String entry : list.split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            String host = e;
            int port = 0;
            if (e.startsWith("[")) {
                int close = e.indexOf(']');
                host = e.substring(0, close < 0 ? e.length() : close + 1);
                if (close >= 0 && e.length() > close + 2 && e.charAt(close + 1) == ':') port = portOf(e.substring(close + 2));
            } else if (e.indexOf(':') >= 0 && e.indexOf(':') == e.lastIndexOf(':')) {
                host = e.substring(0, e.indexOf(':'));
                port = portOf(e.substring(e.indexOf(':') + 1));
            }
            require(host, port, purpose);
        }
    }

    private static int portOf(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private String refusal(String host) {
        if (host == null || host.isBlank()) return "no host";
        if (!tier.permitsNetwork()) return "permit.network is false";
        if (!tier.permitsHost(host)) return "host not permitted by allow.hosts / deny.hosts";
        if (tier.denyHosts() != null && tier.denyHosts().stream().anyMatch(p -> p.kind() == HostPattern.Kind.CIDR)) {
            try {
                for (InetAddress a : InetAddress.getAllByName(HostPattern.normalizeHost(host)))
                    if (tier.deniesAddress(a)) return "resolves to denied address " + a.getHostAddress();
            } catch (UnknownHostException e) {
                // an unresolvable name cannot be dialled; the connector reports it
            }
        }
        return null;
    }
}
