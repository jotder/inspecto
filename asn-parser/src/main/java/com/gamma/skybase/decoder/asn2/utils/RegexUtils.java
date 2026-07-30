package com.gamma.skybase.decoder.asn2.utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class RegexUtils {

    private static final Map<String, Pattern> patterns = new ConcurrentHashMap<>();

    private RegexUtils() {
        // Private constructor for utility class
    }

    public static boolean matches(String str, String regex) {
        if (regex == null || regex.trim().isEmpty()) {
            return false;
        }
        Pattern pattern = patterns.computeIfAbsent(regex, Pattern::compile);
        return pattern.matcher(str).matches();
    }
}
