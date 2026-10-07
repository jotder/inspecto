package com.gamma.notify;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Per-reader read state (operator 2026-09-25): one reader's read/unread never changes another's. */
class NotificationReadStateTest {

    @Test
    void readStateIsKeptPerReader() {
        NotificationReadState state = new NotificationReadState();

        assertTrue(state.markRead("alice", "n1", 100L), "first read changes state");
        assertFalse(state.markRead("alice", "n1", 200L), "a repeat read is idempotent");
        assertEquals(100L, state.readAt("alice", "n1"), "the first read time is kept");
        assertNull(state.readAt("bob", "n1"), "another reader is untouched");

        assertEquals(2, state.markAllRead("bob", List.of("n1", "n2"), 300L));
        assertEquals(1, state.markAllRead("bob", List.of("n1", "n2", "n3"), 400L), "only newly-read ids count");
        assertNull(state.readAt("alice", "n2"), "read-all is the caller's only");

        assertTrue(state.markUnread("alice", "n1"));
        assertFalse(state.markUnread("alice", "n1"), "unread of an unread id changes nothing");
        assertNull(state.readAt("alice", "n1"));
        assertEquals(300L, state.readAt("bob", "n1"), "unread is the caller's only");
    }

    @Test
    void eachReaderIsBoundedOldestReadFirst() {
        NotificationReadState state = new NotificationReadState();
        for (int i = 0; i <= NotificationReadState.MAX_PER_READER; i++) state.markRead("alice", "n" + i, i);
        assertNull(state.readAt("alice", "n0"), "the oldest read is evicted past the bound");
        assertNotNull(state.readAt("alice", "n" + NotificationReadState.MAX_PER_READER));
    }
}
