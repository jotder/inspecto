package com.gamma.la.core;

import com.gamma.spi.auth.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LA-INVESTIGATION-DELETE-1 at the store: the filesystem one removes everything and sweeps; a backend that does not override the default answers 503. */
class InvestigationDeleteTest {

    @Test
    void aBackendWithoutDeleteAnswers503(@TempDir Path root) {
        InvestigationStore noDelete = (InvestigationStore) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{InvestigationStore.class}, (proxy, method, args) -> InvocationHandlerDefault.call(proxy, method, args));
        ApiException refused = assertThrows(ApiException.class, () -> noDelete.deleteInvestigation("inv-a"));
        assertEquals(503, refused.status);
    }

    @Test
    void theFilesystemStoreRemovesTheTreeSweepsATombstoneAndFreesTheId(@TempDir Path root) throws Exception {
        FsInvestigationStore store = new FsInvestigationStore(root);
        assertTrue(store.create("inv-a", "{\"id\":\"inv-a\"}"));
        Path inv = store.investigationDir("inv-a");
        Files.createDirectories(inv.resolve("embeds"));
        Files.writeString(inv.resolve("embeds/abcdefgh12.json"), "{}");
        Path stale = store.investigationsRoot().resolve(".deleting-old-1");   // a tombstone an earlier failed cleanup left
        Files.createDirectories(stale);
        Files.writeString(stale.resolve("x"), "x");

        assertTrue(store.deleteInvestigation("inv-a"));
        assertFalse(Files.exists(inv));
        assertFalse(Files.exists(stale), "the next delete sweeps a leftover tombstone");
        assertFalse(store.deleteInvestigation("inv-a"), "nothing left to delete");
        assertTrue(store.create("inv-a", "{\"id\":\"inv-a\"}"), "the id is free again");
    }

    /** Calls the interface's default method from a Proxy handler. */
    private static final class InvocationHandlerDefault {
        static Object call(Object proxy, java.lang.reflect.Method m, Object[] args) throws Throwable {
            return java.lang.reflect.InvocationHandler.invokeDefault(proxy, m, args);
        }
    }
}
