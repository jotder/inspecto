package com.gamma.etl.unpack;

import java.io.IOException;

/**
 * An archive opened cleanly but yielded no usable entry.
 *
 * <p>Distinct from an ordinary {@link IOException} out of an expansion (corrupt bytes, an
 * unsupported format, a breached {@link UnpackLimits} cap) because the operator's §6 Q1 sign-off
 * keeps {@link UnpackStatus#EMPTY} and {@link UnpackStatus#UNREADABLE} DISTINCT — an operator should
 * be told "your zip is empty" apart from "your zip is locked". Both cases end in the same throw, so
 * the only thing that separates them is how many entries the walk actually SAW: {@link #entriesFound}
 * carries that count rather than making the caller string-match a message.
 *
 * <p>The caller's failure handling is unchanged: this is still an {@link IOException}, so the
 * expansion still fails open and hands the ORIGINAL to the engine.
 */
public final class NoUsableEntriesException extends IOException {

    private final int entriesFound;

    public NoUsableEntriesException(String message, int entriesFound) {
        super(message);
        this.entriesFound = entriesFound;
    }

    /**
     * Entries seen in the walk, readable or not (directories excluded). {@code 0} means the archive
     * was genuinely EMPTY; {@code > 0} means entries existed but none could be decoded.
     */
    public int entriesFound() {
        return entriesFound;
    }
}
