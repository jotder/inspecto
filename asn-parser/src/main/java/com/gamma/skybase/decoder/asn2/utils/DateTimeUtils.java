package com.gamma.skybase.decoder.asn2.utils;

import java.time.*;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DateTimeUtils {

    private static final Logger log = LoggerFactory.getLogger(DateTimeUtils.class);

    private DateTimeUtils() {
        // Private constructor for utility class
    }

    public static long hoursDifference(long epochSeconds, String endDate) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        Instant instant = Instant.ofEpochSecond(epochSeconds);
        LocalDateTime d1 = LocalDateTime.ofInstant(instant, ZoneId.of("UTC"));
        LocalDateTime d2 = LocalDateTime.parse(endDate, formatter);
        Duration duration = Duration.between(d1, d2);
        return duration.toHours();
    }

    public static boolean isDateMatch(long epochSeconds, String from) {
        Instant instant = Instant.ofEpochSecond(epochSeconds);
        LocalDateTime d1 = LocalDateTime.ofInstant(instant, ZoneId.of("UTC"));

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDateTime date2chk = LocalDateTime.parse(from, formatter);
        return date2chk.isEqual(d1);
    }

    public static boolean isDateInRange(String given, String from, String to) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate dateToCheck = LocalDate.parse(given, formatter);
        LocalDate fromDate = LocalDate.parse(from, formatter);
        LocalDate toDate = LocalDate.parse(to, formatter);

        return !dateToCheck.isBefore(fromDate) && !dateToCheck.isAfter(toDate);
    }

    public static String deduceDate(String dateExpression) {
        if (!dateExpression.startsWith("$today")) {
            return dateExpression;
        }

        String[] parts = dateExpression.split("\\s+");
        int daysToAdd = 0;
        if (parts.length > 1) {
            try {
                daysToAdd = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                log.error("unhandled exception", e);
                // Invalid number format, default to 0
            }
        }

        LocalDate date = LocalDate.now(ZoneId.of("UTC")).plusDays(daysToAdd);
        return date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }
}
