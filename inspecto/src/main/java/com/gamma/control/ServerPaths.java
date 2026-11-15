package com.gamma.control;

import com.gamma.access.Roles;
import com.gamma.spi.http.ApiContext;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;

/**
 * How a directory on the server is shown in an answer ({@code LA-FULL-CYCLE-TEST-1} finding 32): where it is on the
 * server's disk is for the people who administer the server.
 */
final class ServerPaths {

    private ServerPaths() {}

    /**
     * {@code dir} as this caller may see it: the absolute path for a holder of {@code canAdminister}, and wherever
     * nobody signs in (no Subject: nothing is checked, as before); for anyone else the path relative to the Space's
     * data folder ({@code dataRoot}), with forward slashes, or null when {@code dir} is not inside that folder.
     */
    static String shown(HttpExchange ex, Path dir, Path dataRoot) {
        Path absolute = dir.toAbsolutePath().normalize();
        if (ApiContext.subject(ex).map(s -> s.capabilities().contains(Roles.CAN_ADMINISTER)).orElse(true))
            return absolute.toString();
        if (dataRoot == null) return null;
        Path root = dataRoot.toAbsolutePath().normalize();
        if (!absolute.startsWith(root) || absolute.equals(root)) return null;
        return root.relativize(absolute).toString().replace('\\', '/');
    }
}
