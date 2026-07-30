package com.gamma.skybase.decoder.asn2.utils;

import java.util.Arrays;

public final class NumberUtils {

    private NumberUtils() {
        // Private constructor for utility class
    }

    public static Number add(Number n1, Number n2) {
        if (n1 == null) return n2;
        if (n2 == null) return n1;

        if (n1 instanceof Double || n2 instanceof Double) {
            return n1.doubleValue() + n2.doubleValue();
        } else if (n1 instanceof Float || n2 instanceof Float) {
            return n1.floatValue() + n2.floatValue();
        } else if (n1 instanceof Long || n2 instanceof Long) {
            return n1.longValue() + n2.longValue();
        } else {
            return n1.intValue() + n2.intValue();
        }
    }

    public static Number subtract(Number n1, Number n2) {
        if (n1 == null) return n2 != null ? -n2.doubleValue() : 0;
        if (n2 == null) return n1;

        if (n1 instanceof Double || n2 instanceof Double) {
            return n1.doubleValue() - n2.doubleValue();
        } else if (n1 instanceof Float || n2 instanceof Float) {
            return n1.floatValue() - n2.floatValue();
        } else if (n1 instanceof Long || n2 instanceof Long) {
            return n1.longValue() - n2.longValue();
        } else {
            return n1.intValue() - n2.intValue();
        }
    }

    public static Number multiply(Number n1, Number n2) {
        if (n1 == null || n2 == null) return 0;

        if (n1 instanceof Double || n2 instanceof Double) {
            return n1.doubleValue() * n2.doubleValue();
        } else if (n1 instanceof Float || n2 instanceof Float) {
            return n1.floatValue() * n2.floatValue();
        } else if (n1 instanceof Long || n2 instanceof Long) {
            return n1.longValue() * n2.longValue();
        } else {
            return n1.intValue() * n2.intValue();
        }
    }

    public static Number divide(Number n1, Number n2) {
        if (n1 == null || n2 == null) return 0;
        if (n2.doubleValue() == 0) {
            throw new ArithmeticException("Division by zero");
        }
        // Promote to double for division to avoid integer division issues
        return n1.doubleValue() / n2.doubleValue();
    }

    public static long sum(long... numbers) {
        return Arrays.stream(numbers).sum();
    }

    public static double sum(double... numbers) {
        return Arrays.stream(numbers).sum();
    }

    @SuppressWarnings("unchecked")
    public static <T extends Comparable<T>> T max(T o1, T o2) {
        if (o1 == null) return o2;
        if (o2 == null) return o1;
        return o1.compareTo(o2) > 0 ? o1 : o2;
    }

    @SuppressWarnings("unchecked")
    public static <T extends Comparable<T>> T min(T o1, T o2) {
        if (o1 == null) return o2;
        if (o2 == null) return o1;
        return o1.compareTo(o2) < 0 ? o1 : o2;
    }
}
