package com.gamma.demoauth;

import java.net.InetAddress;

/**
 * Demo auth signs anyone in who can reach the port, so it is only acceptable on a loopback-bound control plane.
 * {@code -Dcontrol.bind} unset means every interface ({@code ControlApi}'s default) and is refused like any
 * non-loopback address — the refusal is thrown from the SPI constructors, so the ServiceLoader slot fails boot
 * (fail-closed) instead of serving an open sign-in.
 */
final class LoopbackOnly {

    private LoopbackOnly() {}

    static void require() {
        String bind = System.getProperty("control.bind");
        boolean ok = false;
        if (bind != null && !bind.isBlank()) {
            try {
                ok = InetAddress.getByName(bind.trim()).isLoopbackAddress();
            } catch (java.net.UnknownHostException e) {
                ok = false;
            }
        }
        if (!ok)
            throw new IllegalStateException("inspecto-demo-auth refuses to load: set -Dcontrol.bind=127.0.0.1 "
                    + "(demo sign-in is unauthenticated and must never listen beyond loopback; got "
                    + (bind == null || bind.isBlank() ? "every interface" : "'" + bind + "'") + ")");
    }
}
