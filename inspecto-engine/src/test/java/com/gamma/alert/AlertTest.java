package com.gamma.alert;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * R2-05: the words an operator reads on a fired Alert and the Incident it raises. The machine ids stay
 * in the record fields / object attributes; these pin the sentence and the title exactly.
 */
class AlertTest {

    private static AlertRule measureRule(String description, String measure, String comparator, double threshold) {
        return new AlertRule("fm_open_exposure", null, comparator, threshold, null, "CRITICAL", null,
                "fraud_cases_open", measure, null, null, null, null, description);
    }

    @Test
    void aMeasureBreachReadsInWordsAndKeepsItsMachineFields() {
        Alert a = Alert.of(measureRule(null, "sum(exposure_sar)", "gt", 298668), "fraud_cases_open", 373335.09, 0L);

        assertEquals("CRITICAL: Sum of exposure_sar on fraud_cases_open is 373,335.09, above the threshold of "
                + "298,668 (over current data)", a.message());
        assertEquals("fm_open_exposure", a.rule());
        assertEquals("sum(exposure_sar)", a.metric(), "the machine measure id is kept for the API");
        assertEquals("gt", a.comparator());
        assertEquals(298668, a.threshold(), 1e-9);
    }

    @Test
    void aRuleWithoutADescriptionGetsATitleBuiltFromWhatItWatches() {
        assertEquals("Sum of failed on control_runs_today is at least 1",
                Alert.title(new AlertRule("ra_failed_controls_today", null, "gte", 1, null, "CRITICAL", null,
                        "control_runs_today", "sum(failed)"), "control_runs_today"));
        assertEquals("Sum of exposure_sar on fraud_cases_open is above 298,668",
                Alert.title(measureRule(null, "sum(exposure_sar)", "gt", 298668), "fraud_cases_open"));
    }

    @Test
    void aRuleWithADescriptionIsTitledByIt() {
        assertEquals("Open fraud exposure too high — fraud_cases_open",
                Alert.title(measureRule("  Open fraud exposure too high ", "sum(exposure_sar)", "gt", 298668),
                        "fraud_cases_open"));
    }

    @Test
    void eachComparatorIsSaidInWords() {
        assertEquals("Row count on ds is below 10", Alert.title(measureRule(null, "count", "lt", 10), "ds"));
        assertEquals("Row count on ds is at most 10", Alert.title(measureRule(null, "count", "lte", 10), "ds"));
        assertEquals("CRITICAL: Row count on ds is 3, at or below the threshold of 10 (over current data)",
                Alert.of(measureRule(null, "count", "lte", 10), "ds", 3, 0L).message());
        assertEquals("CRITICAL: Row count on ds is 30, at or above the threshold of 10 (over current data)",
                Alert.of(measureRule(null, "count", "gte", 10), "ds", 30, 0L).message());
    }

    @Test
    void ledgerAndFreshnessRulesGetReadableTitlesToo() {
        AlertRule ledger = new AlertRule("r-crit", "failed_batches", "gte", 1, "20b", "CRITICAL", null);
        assertEquals("Failed batches on MINI_ETL is at least 1", Alert.title(ledger, "MINI_ETL"));
        assertEquals("CRITICAL: Failed batches on MINI_ETL is 2, at or above the threshold of 1 "
                + "(over the last 20 batches)", Alert.of(ledger, "MINI_ETL", 2, 0L).message());

        AlertRule fresh = new AlertRule("sales-stale", null, null, 0, null, "WARNING", null,
                "sales_ds", null, null, "6h");
        assertEquals("Dataset sales_ds has not published within 6h", Alert.title(fresh, "sales_ds"));
    }

    @Test
    void ledgerMetricIdsAreSaidInWordsAndTheMachineIdIsKept() {
        AlertRule slow = new AlertRule("slow", "duration_ms", "gt", 5000, "1h", "WARNING", null);
        Alert a = Alert.of(slow, "EVENTS", 7250.5, 0L);
        assertEquals("WARNING: Average duration (ms) on EVENTS is 7,250.5, above the threshold of 5,000 "
                + "(over the last 1h)", a.message());
        assertEquals("duration_ms", a.metric(), "the ALERT_FIRED / API metric stays the machine id");
        assertEquals("Error rate on EVENTS is above 0.05",
                Alert.title(new AlertRule("err", "error_rate", "gt", 0.05, "1h", "WARNING", null), "EVENTS"));

        assertEquals("Error rate", Alert.metricLabel("error_rate"));
        assertEquals("Failed batches", Alert.metricLabel("failed_batches"));
        assertEquals("Rejected files", Alert.metricLabel("rejected_files"));
        assertEquals("Average duration (ms)", Alert.metricLabel("duration_ms"));
        assertEquals("Total input rows", Alert.metricLabel("total_input_rows"), "an unmapped id reads as words");
    }

    @Test
    void aDatasetIsNamedByItsLabelInTheWordsButKeepsItsIdInTheFields() {
        AlertRule r = measureRule(null, "sum(exposure_sar)", "gt", 298668);
        Alert a = Alert.of(r, "fraud_cases_open", "Open fraud cases", 373335.09, 0L);
        assertEquals("CRITICAL: Sum of exposure_sar on Open fraud cases is 373,335.09, above the threshold of "
                + "298,668 (over current data)", a.message());
        assertEquals("fraud_cases_open", a.pipeline(), "the scope field stays the Dataset id");
        assertEquals("Sum of exposure_sar on Open fraud cases is above 298,668", Alert.title(r, "Open fraud cases"));

        AlertRule fresh = new AlertRule("sales-stale", null, null, 0, null, "WARNING", null,
                "sales_ds", null, null, "6h");
        Alert stale = Alert.of(fresh, "sales_ds", "Daily sales", 7200, 0L);
        assertEquals("WARNING: dataset Daily sales has not published for 7200s (freshness limit 6h)", stale.message());
        assertEquals("sales_ds", stale.pipeline());
    }

    @Test
    void measuresAreLabelledForReading() {
        assertEquals("Row count", Alert.measureLabel("count"));
        assertEquals("Count of msisdn", Alert.measureLabel("count(msisdn)"));
        assertEquals("Distinct count of msisdn", Alert.measureLabel("countDistinct(msisdn)"));
        assertEquals("Average of amount", Alert.measureLabel("avg(amount)"));
        assertEquals("Minimum of amount", Alert.measureLabel("min(amount)"));
        assertEquals("Maximum of amount", Alert.measureLabel("max(amount)"));
    }

    @Test
    void numbersAreGroupedWithAtMostTwoDecimals() {
        assertEquals("373,335.09", Alert.number(373335.09));
        assertEquals("298,668", Alert.number(298668));
        assertEquals("1", Alert.number(1));
        assertEquals("1,234,567.9", Alert.number(1234567.899));   // rounded, trailing zero dropped
        assertEquals("0.05", Alert.number(0.05));
        assertEquals("0.0042", Alert.number(0.004213), "a small non-zero value never reads as 0");
        assertEquals("-12,000.5", Alert.number(-12000.5));
        assertEquals("0", Alert.number(0));
    }

    @Test
    void numberFormattingDoesNotFollowTheMachineLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);   // would give 373.335,09
            assertEquals("373,335.09", Alert.number(373335.09));
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void theDescriptionRoundTripsThroughTheStoredShape() {
        AlertRule r = AlertRule.fromMap(Map.of("name", "fm_open_exposure", "dataset", "fraud_cases_open",
                "measure", "sum(exposure_sar)", "comparator", "gt", "threshold", 298668, "severity", "CRITICAL",
                "description", "Open fraud exposure too high"));
        assertEquals("Open fraud exposure too high", r.description());
        assertEquals("Open fraud exposure too high", AlertRule.fromMap(r.toMap()).description());
        assertNull(AlertRule.fromMap(Map.of("name", "x", "dataset", "d", "measure", "count", "threshold", 1,
                "description", "  ")).description(), "blank = no description");
    }
}
