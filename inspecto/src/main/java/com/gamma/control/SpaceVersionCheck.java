package com.gamma.control;

import com.gamma.service.SpaceContext;
import com.gamma.util.ToonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

/**
 * A Space downloaded on its own may need a newer product than the one it is attached to (LA-SHOWCASE-DIST-1,
 * 2026-11-07). Its {@code space.toon} may name {@code min_product_version}; at boot each Space that asks for more than
 * {@link ProductVersion#current()} is named in a WARN. It still boots: the warning tells the operator which download to
 * update before a config the older product cannot read surfaces as a stranger error. A {@code dev} run checks nothing.
 */
final class SpaceVersionCheck {
    private SpaceVersionCheck() {}

    private static final Logger log = LoggerFactory.getLogger(SpaceVersionCheck.class);

    /** The {@code space.toon} key naming the oldest product the Space works with. */
    static final String KEY = "min_product_version";

    static void warnNewer(Collection<SpaceContext> spaces, String current) {
        for (SpaceContext ctx : spaces) {
            Path base = ctx.root().base();
            String required = required(base);
            if (tooOld(current, required))
                log.warn("Space '{}' needs product version {} or newer; this server is {} - update the product download",
                        base.getFileName(), required, current);
        }
    }

    /** {@code min_product_version} from the Space's {@code space.toon}, or {@code ""} when absent or unreadable. */
    static String required(Path base) {
        Path manifest = base.resolve("space.toon");
        if (!Files.exists(manifest)) return "";
        try {
            Map<String, Object> m = ToonHelper.load(manifest.toString());
            return ToonHelper.opt(m, KEY, "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** True when {@code current} is older than {@code required}, comparing the numeric parts ({@code 4.0.0-SNAPSHOT} is 4.0.0). */
    static boolean tooOld(String current, String required) {
        if (required.isBlank() || ProductVersion.DEV.equals(current)) return false;
        int[] c = numbers(current), r = numbers(required);
        for (int i = 0; i < Math.max(c.length, r.length); i++) {
            int a = i < c.length ? c[i] : 0, b = i < r.length ? r[i] : 0;
            if (a != b) return a < b;
        }
        return false;
    }

    private static int[] numbers(String version) {
        String core = version.split("[-+]", 2)[0];
        String[] parts = core.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
