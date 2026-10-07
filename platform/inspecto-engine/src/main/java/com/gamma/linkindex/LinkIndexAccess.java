package com.gamma.linkindex;


import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * Platform Service {@code link-index}: build or refresh the Link Analysis Index of one configured Dataset mapping.
 * Granted to a Run through a Job Type's {@code requires: [link-index]} and looked up with
 * {@code ctx.services().find(LinkIndexAccess.class)}; the {@code la.index.build} built-in is its only consumer.
 *
 * <h3>Who it acts as</h3>
 * A scheduled Job has no Subject. The build therefore runs as the delegated service principal
 * {@code index-build:<job>} that holds exactly one capability, {@code canBuildLinkIndex}, and whose authority is the
 * recorded {@link Request#owner() owner id}: re-decided on EVERY run (the standing-detection model, D-LD1 / D-ING2).
 * Anything that cannot be decided is a refusal, never a build.
 *
 * <h3>Dry-run contract</h3>
 * A build writes a new index version, so it has no preview form. Under a dry run the framework substitutes a
 * stand-in that builds nothing and reports {@link Outcome#DRY_RUN}.
 *
 * @since 4.0.0
 */
public interface LinkIndexAccess {

    /** What a build was configured to do. {@code owner} is the user id the principal stands in for. */
    record Request(String job, String dataset, String sourceCol, String targetCol, String kindCol, String timeCol,
                   String timeColZone, String weightCol, List<String> attrCols, String owner, boolean allowFull,
                   long timeoutMs) {
        public Request {
            attrCols = attrCols == null ? List.of() : List.copyOf(attrCols);
        }
    }

    /**
     * The aggregate-only answer: {@code result}, the {@code mode} actually run (or the one refused), a stable
     * refusal {@code code}, a {@code message} that names no row value, and counts. Never an entity id or a row.
     */
    record Outcome(String result, String mode, String code, String message, long edges, long nodes, int deltas) {
        public static final String BUILT = "BUILT", UP_TO_DATE = "UP_TO_DATE", REFUSED = "REFUSED", FAILED = "FAILED",
                RUNNING = "RUNNING", DRY_RUN = "DRY_RUN";

        /** Whether the Run may report success: an index that is current ({@code BUILT} or {@code UP_TO_DATE}). */
        public boolean ok() {
            return BUILT.equals(result) || UP_TO_DATE.equals(result);
        }
    }

    /** Build or refresh now. @throws IllegalStateException when the Link Analysis module is not present */
    Outcome build(Request request);

    /**
     * The production implementation over the {@link LinkIndexBuilder} SPI and the Space's roots, resolved lazily
     * so boot wiring may register the service before anything is discovered. An absent builder fails loudly:
     * a build that never ran must not report a current index.
     */
    static LinkIndexAccess over(Supplier<LinkIndexBuilder> builder, Supplier<Path> writeRoot, Supplier<Path> dataRoot) {
        return request -> {
            LinkIndexBuilder b = builder.get();
            if (b == null)
                throw new IllegalStateException("the Link Analysis module is not present in this build, so no "
                        + "index can be built");
            Path wr = writeRoot.get();
            if (wr == null)
                throw new IllegalStateException("this Space has no write root, so no index can be built");
            return b.build(wr, dataRoot.get(), request);
        };
    }
}
