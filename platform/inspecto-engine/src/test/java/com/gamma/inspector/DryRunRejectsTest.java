package com.gamma.inspector;

import com.gamma.util.CurrentSpace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DryRunRejects} is per SPACE as well as per pipeline: two Spaces may each hold a pipeline of the same name,
 * and one Space's dry run must never hand its rejects to the other's poll (SPACE-UNKEYED-STATICS-1 pattern).
 */
class DryRunRejectsTest {

    @AfterEach
    void unbind() {
        DryRunRejects.forgetSpace("space-a");
        DryRunRejects.forgetSpace("space-b");
        MDC.remove(CurrentSpace.SPACE_MDC_KEY);
    }

    private static <T> T in(String space, Supplier<T> body) {
        MDC.put(CurrentSpace.SPACE_MDC_KEY, space);
        try {
            return body.get();
        } finally {
            MDC.remove(CurrentSpace.SPACE_MDC_KEY);
        }
    }

    private static DryRunRejects.MemberRejects member(String file, long line, String reason) {
        return new DryRunRejects.MemberRejects("c-" + file, file, 1,
                List.of(new PipelineTestRun.RejectedRecord(line, reason)));
    }

    @Test
    void twoSpacesWithTheSamePipelineNameEachTakeOnlyTheirOwn() {
        in("space-a", () -> { DryRunRejects.add("orders", member("a.csv", 2, "MISSING COLUMNS")); return null; });
        in("space-b", () -> { DryRunRejects.add("orders", member("b.csv", 7, "BAD DATE")); return null; });

        List<DryRunRejects.MemberRejects> a = in("space-a", () -> DryRunRejects.take("orders"));
        List<DryRunRejects.MemberRejects> b = in("space-b", () -> DryRunRejects.take("orders"));

        assertEquals(List.of("a.csv"), a.stream().map(DryRunRejects.MemberRejects::file).toList(), a.toString());
        assertEquals(List.of("b.csv"), b.stream().map(DryRunRejects.MemberRejects::file).toList(), b.toString());
        assertEquals(7, b.get(0).rejects().get(0).line());
        assertTrue(in("space-a", () -> DryRunRejects.take("orders")).isEmpty(), "take clears the slot");
    }

    @Test
    void forgetSpaceDropsOnlyThatSpacesSlots() {
        in("space-a", () -> { DryRunRejects.add("orders", member("a.csv", 2, "x")); return null; });
        in("space-b", () -> { DryRunRejects.add("orders", member("b.csv", 3, "y")); return null; });

        DryRunRejects.forgetSpace("space-a");

        assertTrue(in("space-a", () -> DryRunRejects.take("orders")).isEmpty(), "the deleted Space's slot is gone");
        assertEquals(1, in("space-b", () -> DryRunRejects.take("orders")).size(), "the other Space is untouched");
    }
}
