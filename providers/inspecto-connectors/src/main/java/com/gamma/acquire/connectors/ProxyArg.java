package com.gamma.acquire.connectors;

/**
 * The {@code host:port[:user[:password]]} spelling both proxy socket factories accept through their
 * single-string constructors — the only constructor shape a JDBC driver's {@code socketFactoryArg=} can
 * call. One parser so the two factories cannot drift on what the string means.
 */
final class ProxyArg {

    private ProxyArg() {}

    static String[] split(String arg, int max, String who) {
        if (arg == null || arg.isBlank())
            throw new IllegalArgumentException(who + " needs 'host:port' (got nothing)");
        String[] p = arg.trim().split(":", max);
        if (p.length < 2 || p[0].isBlank())
            throw new IllegalArgumentException(who + " needs 'host:port' (got '" + arg + "')");
        return p;
    }

    static int port(String s, String arg) {
        try {
            int port = Integer.parseInt(s.trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("proxy port must be 1..65535 in '" + arg + "'");
        }
    }

    /** {@code host:port} for a profile's proxy, plus {@code :user:password} when the proxy authenticates. */
    static String of(com.gamma.acquire.ConnectionProfile.Proxy proxy, boolean withCredentials) {
        StringBuilder sb = new StringBuilder(proxy.host()).append(':').append(proxy.port());
        if (withCredentials && proxy.username() != null && !proxy.username().isBlank()) {
            sb.append(':').append(proxy.username()).append(':')
              .append(proxy.password() == null ? "" : com.gamma.acquire.SecretResolver.resolve(proxy.password()));
        }
        return sb.toString();
    }
}
