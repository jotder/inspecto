package com.gamma.consignment;

import com.gamma.api.PublicApi;

import java.time.Instant;

/**
 * One producer's contribution to a stream, as the {@code consignment_outputs} catalog knows it — the input
 * {@link StreamWatermark} folds (consignment addressing §3.6).
 *
 * <p>Both fields are nullable on purpose, and each nullability means something the fold acts on:
 *
 * @param producer      the pipeline that wrote the rows, or {@code null} for the write paths that carry no
 *                      pipeline identity (enrichment, Pipeline sinks, the Consignment processor). Those rows are
 *                      <b>not</b> discarded — they group under a single unattributed producer, so a stream that
 *                      is receiving writes nobody owns cannot quietly report a watermark as if it were not.
 * @param eventTimeMax  the newest event time this producer has delivered, in {@link EventTimeBounds}' ISO-8601
 *                      local text, or {@code null} when none of its rows carry bounds. Null is
 *                      <b>unknown, never "nothing"</b>: a producer delivering data that cannot be placed in
 *                      event time suppresses the whole stream's watermark rather than being skipped over.
 * @param lastSeen      when this producer last wrote, for the observed-producer horizon (decision D4), or
 *                      {@code null} when the catalog's {@code written_at} could not be read as an instant. Null
 *                      counts as <em>in</em> horizon — staleness has to be proven, not assumed, or a producer
 *                      with an unreadable timestamp would drop out and let the watermark advance past it.
 */
@PublicApi(since = "4.0.0")
public record ProducerHighWater(String producer, String eventTimeMax, Instant lastSeen) { }
