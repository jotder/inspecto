/**
 * The score output contract (ANALYTICS-CORE-1 M7, decision D-AN6): what every score kind (Anomaly Score, Risk Score,
 * future Predictions) writes and hands on, defined once. Pure: output names, the column schema, the 0-100 scale and
 * bands, the entity cap, run files and retention, and the explanation part. The I/O that writes it (Parquet, the
 * ownership marker, the atomic {@code _latest} swap) is {@code com.gamma.alert.ScoreOutputWriter} in the engine, beside
 * {@code ScoreOutputDirs}; each kind's model spec, arithmetic and wording stay in its own module.
 *
 * <p>Reference: {@code docs/okf/backend/analytics-kernel.md}, section "Score output contract".
 */
package com.gamma.analytics.score;
