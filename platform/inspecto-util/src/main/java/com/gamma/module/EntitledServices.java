package com.gamma.module;

import java.util.Optional;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * The one way an extension point finds its {@link ServiceLoader} providers ({@code ENTITLEMENT-SPI-NARROWING-1}): a provider
 * whose module the install's signed entitlement leaves out is skipped BEFORE it is instantiated, exactly as that module's
 * routes, features and Job types are closed (D-PA22: a file only narrows). Without a file every provider loads (D-PA23).
 *
 * <p>The provider's module is the module manifest beside its class, in the same jar or classes directory
 * ({@link Entitlements#providerEntitled}). Each call reads the entitlement as it is now; a registry that caches its
 * providers keeps what it loaded until it loads again (a restart, for most).
 *
 * <p>Error behaviour is {@link ServiceLoader}'s own: a broken provider throws {@link java.util.ServiceConfigurationError}
 * as it did at the raw call. A fail-soft loader keeps its own iterator and asks {@link #entitled(Object)} per provider.
 *
 * <p>{@code tools/check-entitled-loaders.mjs} fails when a main-code {@code ServiceLoader.load} bypasses this class, except
 * at the loaders it lists as staying open, each with its reason.
 */
public final class EntitledServices {

    private EntitledServices() {}

    /** The entitled providers of {@code spi} on the thread's context class loader, lazily, in {@link ServiceLoader} order. */
    public static <S> Stream<S> stream(Class<S> spi) {
        return entitled(ServiceLoader.load(spi));
    }

    /** As {@link #stream(Class)}, on {@code loader}. */
    public static <S> Stream<S> stream(Class<S> spi, ClassLoader loader) {
        return entitled(ServiceLoader.load(spi, loader));
    }

    /** {@link #stream(Class)} as an {@code Iterable}, for a {@code for} loop; each iteration loads afresh. */
    public static <S> Iterable<S> load(Class<S> spi) {
        return () -> stream(spi).iterator();
    }

    /** As {@link #load(Class)}, on {@code loader}. */
    public static <S> Iterable<S> load(Class<S> spi, ClassLoader loader) {
        return () -> stream(spi, loader).iterator();
    }

    /** The first entitled provider of {@code spi}, or empty. */
    public static <S> Optional<S> first(Class<S> spi) {
        return stream(spi).findFirst();
    }

    /** Whether an already-instantiated provider may be used (for a fail-soft loader that keeps its own iterator). */
    public static boolean entitled(Object provider) {
        return Entitlements.providerEntitled(provider.getClass());
    }

    private static <S> Stream<S> entitled(ServiceLoader<S> loader) {
        return loader.stream().filter(p -> Entitlements.providerEntitled(p.type())).map(ServiceLoader.Provider::get);
    }
}
