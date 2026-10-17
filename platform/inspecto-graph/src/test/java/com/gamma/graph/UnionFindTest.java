package com.gamma.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnionFindTest {

    @Test
    void anUnseenKeyIsItsOwnSet() {
        UnionFind uf = new UnionFind();
        assertEquals("x", uf.find("x"));
        assertNotEquals(uf.find("x"), uf.find("y"));
    }

    @Test
    void unionJoinsUnderTheSecondArgumentsRootAndIsTransitive() {
        UnionFind uf = new UnionFind();
        assertTrue(uf.union("a", "b"));
        assertEquals("b", uf.find("a"));
        assertTrue(uf.union("c", "a"));
        assertEquals("b", uf.find("c"), "c joins a's set, whose root is b");
        assertTrue(uf.union("d", "e"));
        assertTrue(uf.union("b", "d"));
        for (String k : new String[] {"a", "b", "c", "d", "e"}) assertEquals("e", uf.find(k), k);
        assertFalse(uf.union("a", "e"), "already one set");
        assertFalse(uf.union("x", "x"), "a key with itself");
    }
}
