package com.gamma.service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fail-soft {@link ServiceLoader} discovery for the <b>optional module</b> seam.
 *
 * <h3>Why this exists</h3>
 * Every optional module in this product is discovered by {@code ServiceLoader}, and the contract is the
 * <b>absence contract</b>: a module that is not on the classpath is not an error — its routes answer
 * {@code 503} with an explained panel, never a toast, and the rest of the product is unaffected.
 *
 * <p>🔴 A raw {@code ServiceLoader.load(X.class).findFirst()} does <b>not</b> honour that contract. A jar
 * that is <i>present but unloadable</i> — the case that matters here — throws rather than resolving to
 * empty, and it throws an {@link Error}, which nothing up the call stack catches. The concrete instance:
 * the assistant modules' upstream dependency is compiled to <b>Java 25 bytecode</b> (class-file major 69,
 * measured 2026-09-12 across every {@code eoiagent-*} jar) while the reactor's own compile target — and
 * therefore the floor a skip-runtime deployment must meet — is <b>24</b>. On a Java 24 host with the
 * assistant jar staged, class loading raises {@code UnsupportedClassVersionError}, {@code ServiceLoader}
 * re-throws it, and {@code CollectorService.start()} dies. <b>The whole server fails to boot because an
 * optional component could not load.</b>
 *
 * <p>That is why {@code PKG-5} stood refused, and it is the real blocker — not the "resolve the JDK 25+ vs
 * Java 24+ floor" question the board row names. With this class in place the floor question dissolves:
 * the bundle's stated requirement stays 24, the assistant is staged in every edition as an <b>optional</b>
 * component (operator decision 2026-09-12), and on a host too old to load it the product starts normally
 * and the assistant is simply absent — which is a state the UI already knows how to render.
 *
 * <h3>What it catches, and what it deliberately does not</h3>
 * {@link ServiceConfigurationError} (the provider is declared but broken, missing, or not assignable) and
 * {@link LinkageError} (it exists but cannot link — wrong class-file version, absent transitive dependency,
 * incompatible signature). ⛔ It does <b>not</b> catch {@link RuntimeException} from a provider's own
 * constructor: a module that loads and then throws while initialising is a defect in that module, and
 * swallowing it would turn a bug into a silent absence — the failure mode this codebase has been bitten by
 * repeatedly. Unloadable is an absence; misbehaving is not.
 *
 * @see com.gamma.control.SpiSlot the control plane's cached single-provider facade, which delegates here
 */
public final class OptionalSpi {

    private static final Logger log = LoggerFactory.getLogger(OptionalSpi.class);

    /**
     * Iteration bound. A provider that fails during {@code hasNext()} leaves the iterator's position
     * formally unspecified, so {@link #all} continues rather than stopping (one bad module must not hide
     * the modules behind it) — and this bound is what makes "continue" safe against an iterator that
     * would otherwise fault forever. No classpath in this product declares anywhere near this many
     * providers for one SPI.
     */
    private static final int MAX_PROVIDERS = 256;

    private OptionalSpi() {
    }

    /** The first provider of {@code spi}, or empty when none is present <i>or loadable</i>. */
    public static <T> Optional<T> first(Class<T> spi) {
        List<T> all = all(spi);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /**
     * Every loadable provider of {@code spi}, in {@link ServiceLoader} order. Providers that cannot be
     * loaded are skipped with one warning apiece; the ones that can are returned.
     */
    public static <T> List<T> all(Class<T> spi) {
        List<T> found = new ArrayList<>();
        Iterator<T> it;
        try {
            it = ServiceLoader.load(spi).iterator();
        } catch (ServiceConfigurationError | LinkageError e) {
            warn(spi, e);
            return List.of();
        }
        for (int guard = 0; guard < MAX_PROVIDERS; guard++) {
            try {
                if (!it.hasNext()) return found;
                found.add(it.next());
            } catch (ServiceConfigurationError | LinkageError e) {
                warn(spi, e);
            }
        }
        log.warn("Stopped discovering {} providers after {} attempts — the service loader kept faulting. "
                + "Found {} usable provider(s).", spi.getSimpleName(), MAX_PROVIDERS, found.size());
        return found;
    }

    private static void warn(Class<?> spi, Throwable e) {
        // Named explicitly because it is the expected case on an older host, and an operator reading
        // "UnsupportedClassVersionError" in a stack trace should not have to work out that it is benign.
        boolean tooOld = e instanceof UnsupportedClassVersionError
                || e.getCause() instanceof UnsupportedClassVersionError;
        if (tooOld) {
            log.warn("Optional module providing {} is present but needs a NEWER Java than this host — "
                    + "skipping it. The product runs without it and its routes answer 503. "
                    + "Install a newer Java to enable it. ({})", spi.getSimpleName(), e.toString());
        } else {
            log.warn("Optional module providing {} is present but could not be loaded — skipping it. "
                    + "The product runs without it and its routes answer 503.", spi.getSimpleName(), e);
        }
    }
}
