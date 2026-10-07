package com.gamma.catalog.spi.testkit;

import com.gamma.catalog.Description;
import com.gamma.catalog.spi.DescriptionProvider;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Proves the DescriptionProvider TCK can fail (MODULE-REORG-1 P5b): broken in-test providers must go red, a sound one green. */
class DescriptionProviderTckSelfTest {

    private record P(String name, Function<DescriptionProvider.ColumnContext, Description> column,
                     Function<DescriptionProvider.TableContext, Description> table) implements DescriptionProvider {
        P(String name, Function<DescriptionProvider.ColumnContext, Description> column) {
            this(name, column, t -> Description.EMPTY);
        }
        @Override public Description describeColumn(ColumnContext ctx) { return column.apply(ctx); }
        @Override public Description describeTable(TableContext ctx) { return table.apply(ctx); }
    }

    private static DescriptionProviderContract contract(DescriptionProvider p) {
        return new DescriptionProviderContract() {
            @Override protected DescriptionProvider provider() { return p; }
        };
    }

    private static final Function<DescriptionProvider.ColumnContext, Description> OK = c -> Description.EMPTY;

    @Test
    void aSoundProviderPasses() {
        DescriptionProviderContract c = contract(new P("good", OK));
        assertDoesNotThrow(() -> {
            c.nameIsAStableToken();
            c.nameIsServedByExactlyOneProvider();
            c.awkwardColumnsNeverThrowOrReturnNull();
            c.describeTableNeverReturnsNull();
            c.concurrentUseAgreesWithSerialUse();
        });
    }

    @Test
    void aBlankOrChangingNameIsCaught() {
        assertThrows(AssertionFailedError.class, () -> contract(new P(" ", OK)).nameIsAStableToken());
        AtomicInteger n = new AtomicInteger();
        DescriptionProvider changing = new DescriptionProvider() {
            @Override public String name() { return "n" + n.incrementAndGet(); }
            @Override public Description describeColumn(ColumnContext ctx) { return Description.EMPTY; }
        };
        assertThrows(AssertionFailedError.class, () -> contract(changing).nameIsAStableToken());
    }

    @Test
    void aDuplicateNameIsCaught() {
        DescriptionProvider a = new P("dup", OK);
        DescriptionProviderContract c = new DescriptionProviderContract() {
            @Override protected DescriptionProvider provider() { return a; }
            @Override protected Iterable<DescriptionProvider> registered() { return List.of(a, new P("dup", OK)); }
        };
        assertThrows(AssertionFailedError.class, c::nameIsServedByExactlyOneProvider);
    }

    @Test
    void aNullResultOrAThrowOnAwkwardInputIsCaught() {
        assertThrows(AssertionFailedError.class, () -> contract(new P("nulls", c -> null)).awkwardColumnsNeverThrowOrReturnNull());
        assertThrows(AssertionFailedError.class, () -> contract(new P("throws", c -> {
            if (c.type() == null) throw new NullPointerException("type");
            return Description.EMPTY;
        })).awkwardColumnsNeverThrowOrReturnNull());
    }

    @Test
    void aNullTableResultIsCaught() {
        assertThrows(AssertionFailedError.class, () -> contract(new P("nulltable", OK, t -> null)).describeTableNeverReturnsNull());
    }

    @Test
    void aProviderWhoseAnswerDriftsUnderConcurrencyIsCaught() {
        AtomicInteger calls = new AtomicInteger();
        DescriptionProviderContract c = contract(new P("drift", x -> Description.manual("v" + calls.incrementAndGet())));
        assertThrows(AssertionFailedError.class, c::concurrentUseAgreesWithSerialUse);
    }
}
