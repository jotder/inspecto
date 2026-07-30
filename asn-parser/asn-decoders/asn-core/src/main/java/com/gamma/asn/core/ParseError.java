package com.gamma.asn.core;

/** One failed record, as reported to the {@link RecordReader.ErrorListener}. */
public record ParseError(long fileOffset, long recordIndex, String message, RecoveryPolicy action) {
}
