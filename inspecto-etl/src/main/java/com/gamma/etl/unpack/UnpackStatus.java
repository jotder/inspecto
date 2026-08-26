package com.gamma.etl.unpack;

/**
 * An Archive's run-level verdict (unpack-stage plan §2.2, operator sign-off 2026-08-26 §6 Q1).
 *
 * <p>This is the ARCHIVE's status, not a file's: entries stay ordinary Files carrying their own
 * per-Consignment status, and this rolls their outcomes up to the container the operator actually
 * dropped. It is <b>reporting, never a gate</b> — {@link #UNPACKED_PARTIAL} commits (§6 Q1b), because
 * today's per-file semantics are that a bad file never blocks its batch-mates and failing whole would
 * discard 499 good ingests for one bad entry in a 500-entry archive.
 *
 * <p>⚠ Deliberately a separate vocabulary from the per-FILE statuses, which since plan item 14
 * live in {@link com.gamma.etl.MemberStatus}. Do not conflate the two: one describes a container,
 * the other a member.
 */
public enum UnpackStatus {

    /** Every entry found was ingested. */
    UNPACKED,

    /**
     * ≥1 entry ingested AND ≥1 entry not ingested — quarantined <b>or</b> skipped-unreadable.
     *
     * <p>⚠ The "or skipped" half is why this definition was WIDENED at sign-off: the plan's original
     * wording said "≥1 quarantined", which an encrypted entry never is (there are no readable bytes
     * to move, so it gets a manifest row and nothing else). An archive with one encrypted entry and
     * four good ones matched neither {@code UNPACKED} nor the old {@code UNPACKED_PARTIAL}.
     */
    UNPACKED_PARTIAL,

    /**
     * Unpack itself failed — corrupt bytes, an unsupported format, a breached {@link UnpackLimits}
     * cap — or entries were found but NONE was readable (e.g. a wholly encrypted zip).
     */
    UNREADABLE,

    /** The archive opened cleanly and contained zero entries. */
    EMPTY;

    /**
     * The verdict for one archive from its tallies.
     *
     * <p>⚠ {@code EMPTY} and {@code UNREADABLE} are ONE code path in the expansion itself (both end
     * in the same {@code no readable entries} throw), and the operator's call was that they stay
     * DISTINCT — an operator should be told "your zip is empty" apart from "your zip is locked".
     * That is why the caller must report {@code found} (entries seen in the walk, readable or not)
     * rather than merely reporting failure: {@code found == 0} is the only thing that separates them.
     *
     * @param found     entries seen in the archive walk, readable or not (directories excluded)
     * @param ingested  entries that reached a successful Consignment commit
     * @param failed    entries that were planned but quarantined
     * @param skipped   entries the walk could not decode at all (encrypted / unsupported method)
     * @param expansionFailed true when the expansion itself threw (corrupt / cap breach), in which
     *                        case nothing downstream ever saw an entry
     */
    public static UnpackStatus verdict(int found, int ingested, int failed, int skipped,
                                       boolean expansionFailed) {
        if (expansionFailed) return UNREADABLE;
        if (found == 0)      return EMPTY;
        if (ingested == 0)   return UNREADABLE;   // entries existed, none of them landed
        return (failed > 0 || skipped > 0 || ingested < found) ? UNPACKED_PARTIAL : UNPACKED;
    }
}
