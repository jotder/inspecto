package com.gamma.notify;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code notifications} Platform Service (S1-3): {@link NotificationService#notify} is both the
 * granted-Run entry and the event dispatcher's in-app leg — one path, one dedupe contract.
 */
class NotificationAccessTest {

    private static Notification note(String dedupeKey) {
        return Notification.create("job", "JOB_RUN", "run:1", "Recon breach", "3 rows off", dedupeKey);
    }

    @Test
    void notifyStoresAndPushesToLiveListeners() {
        NotificationStore store = new InMemoryNotificationStore();
        NotificationService svc = new NotificationService(store, NotificationRules.defaults(),
                new NotificationPreferences());
        List<Notification> pushed = new CopyOnWriteArrayList<>();
        svc.addListener(pushed::add);

        NotificationAccess access = svc;   // the service IS the Platform Service implementation
        Optional<Notification> stored = access.notify(note("recon:orders"));

        assertTrue(stored.isPresent(), "stored and returned");
        assertEquals(1, store.recent(10).size());
        assertEquals(1, pushed.size(), "live listeners see the service-emitted notification");
        assertEquals("Recon breach", pushed.get(0).title());
        svc.close();
    }

    @Test
    void notifyCollapsesAnActiveDuplicate() {
        NotificationStore store = new InMemoryNotificationStore();
        NotificationService svc = new NotificationService(store, NotificationRules.defaults(),
                new NotificationPreferences());

        NotificationAccess access = svc;
        assertTrue(access.notify(note("recon:orders")).isPresent());
        assertTrue(access.notify(note("recon:orders")).isEmpty(), "identical unread notification collapses");
        assertEquals(1, store.recent(10).size());

        // Once read, the key is no longer active — the next emit stores again.
        store.markAllRead();
        assertTrue(access.notify(note("recon:orders")).isPresent());
        assertEquals(2, store.recent(10).size());
        svc.close();
    }
}
