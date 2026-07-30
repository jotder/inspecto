package com.gamma.skybase.utils;

public class Pair<A, B> {
    private final A key;
    private final B value;

    private Pair(A key, B value) {
        this.key = key;
        this.value = value;
    }

    public static <A, B> Pair<A, B> of(A key, B value) {
        return new Pair<>(key, value);
    }

    public A getKey() {
        return key;
    }

    public B getValue() {
        return value;
    }

    @Override
    public String toString() {
        return "Pair[" + key + ", " + value + "]";
    }
}