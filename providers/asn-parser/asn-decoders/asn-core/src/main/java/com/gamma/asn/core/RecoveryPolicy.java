package com.gamma.asn.core;

/**
 * What the {@link RecordReader} does when a record fails to parse. SKIP_RECORD is only
 * honoured when the framing knows the record boundary (a length prefix); without one there
 * is no reliable resync and the reader stops the file regardless.
 */
public enum RecoveryPolicy {
    SKIP_RECORD,
    STOP_FILE
}
