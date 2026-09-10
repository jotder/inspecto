package com.gamma.job;

import com.gamma.consignment.ConsignmentProcessor;
import com.gamma.consignment.ProcessorContext;
import com.gamma.consignment.ProcessorResult;

/**
 * Stand-in {@link ConsignmentProcessor}s for {@link ProcessorCatalogTest}.
 *
 * <p>⛔ <b>Deliberately NOT declared in any {@code META-INF/services} file.</b> The product ships zero
 * processors, and {@code ProcessorCatalogTest} asserts exactly that about the stock classpath — a services
 * file here would make these visible to every test in the module and destroy that assertion. The test
 * publishes them through a classloader it builds itself, which is also the only way to reach the
 * duplicate-id and unloadable-provider branches.
 *
 * <p>They are {@code public static} members of a {@code public} class so {@link java.util.ServiceLoader} can
 * instantiate them reflectively by their binary names ({@code com.gamma.job.ProcessorFixtures$Alpha}).
 */
public final class ProcessorFixtures {

    private ProcessorFixtures() {}

    /** Base of the stand-ins: {@code process} is never called, only {@code id()} is. */
    abstract static class Fixture implements ConsignmentProcessor {
        @Override
        public ProcessorResult process(ProcessorContext ctx) {
            throw new UnsupportedOperationException("a catalog read never processes");
        }
    }

    public static final class Alpha extends Fixture {
        public Alpha() {}
        @Override public String id() { return "alpha"; }
    }

    public static final class Beta extends Fixture {
        public Beta() {}
        @Override public String id() { return "beta"; }
    }

    /** A second, different class claiming an id {@link Alpha} already has — the deployment fault the
     *  catalog reports as {@code shadowed}, because {@code fromServiceLoader} takes the FIRST match. */
    public static final class AlphaImpostor extends Fixture {
        public AlphaImpostor() {}
        @Override public String id() { return "alpha"; }
    }

    /** No Job config can ever name this one, so the catalog must count it {@code unusable} rather than
     *  serve a blank entry a picker would render as an empty row. */
    public static final class Blank extends Fixture {
        public Blank() {}
        @Override public String id() { return "  "; }
    }

    /** Equally unselectable: {@code ConsignmentProcessJobType.chainOf} SPLITS the {@code processor}
     *  parameter on commas, so no authored chain can ever name this id. */
    public static final class Comma extends Fixture {
        public Comma() {}
        @Override public String id() { return "a,b"; }
    }
}
