package com.gamma.job;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * A {@code sql.template} Job's {@code incremental: {by: day, column: <date col>, lookback: N}} block
 * (operator, 2026-10-10): the run recomputes only the last {@code N} days and replaces exactly those day
 * partitions of its sink. {@link JobConfig#fromMap} flattens the block into {@code incremental.*} params and
 * {@link JobConfig#toMap} re-nests it; {@link #parse} is the pure, fail-closed shape check every save path and
 * the boot loader run through.
 */
record IncrementalSpec(String column, int lookback) {

    static final String PREFIX = "incremental.";
    static final int MAX_LOOKBACK = 366;
    private static final Pattern COLUMN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** The spec, {@code null} when the job declares no {@code incremental:} block; refuses a malformed one. */
    static IncrementalSpec parse(String jobName, String type, Map<String, String> params) {
        boolean declared = params.keySet().stream().anyMatch(k -> k.startsWith(PREFIX) || k.equals("incremental"));
        if (!declared) return null;
        String where = "Job '" + jobName + "': incremental";
        if (params.containsKey("incremental"))
            throw new IllegalArgumentException(where + " must be a block {by: day, column: <date column>, lookback: N}");
        if (!SqlTemplateJobType.DESCRIPTOR.id().equals(type))
            throw new IllegalArgumentException(where + " is supported only on sql.template Jobs, not '" + type + "'");
        for (String k : params.keySet())
            if (k.startsWith(PREFIX) && !Map.of("incremental.by", 1, "incremental.column", 1, "incremental.lookback", 1).containsKey(k))
                throw new IllegalArgumentException(where + " has an unknown key '" + k.substring(PREFIX.length()) + "'");
        String by = params.get("incremental.by");
        if (!"day".equals(by == null ? null : by.trim()))
            throw new IllegalArgumentException(where + ".by must be 'day', got '" + by + "'");
        String column = params.get("incremental.column");
        if (column == null || !COLUMN.matcher(column.trim()).matches())
            throw new IllegalArgumentException(where + ".column must name a date column, got '" + column + "'");
        String sql = params.getOrDefault("sql", "");
        if (!Pattern.compile("(?i)(?<![A-Za-z0-9_$])\"?" + Pattern.quote(column.trim()) + "\"?(?![A-Za-z0-9_])").matcher(sql).find())
            throw new IllegalArgumentException(where + ".column '" + column.trim() + "' does not appear in the Job's sql "
                    + "(its output must carry it as a DATE column)");
        String raw = params.get("incremental.lookback");
        int n;
        try { n = Integer.parseInt(raw == null ? "" : raw.trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(where + ".lookback must be an integer 1.." + MAX_LOOKBACK + ", got '" + raw + "'"); }
        if (n < 1 || n > MAX_LOOKBACK)
            throw new IllegalArgumentException(where + ".lookback must be 1.." + MAX_LOOKBACK + ", got " + n);
        return new IncrementalSpec(column.trim(), n);
    }
}
