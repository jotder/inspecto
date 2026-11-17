package com.gamma.consignment;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Test helper (X5 L3 review): the {@code diag.msg} of each Step Receipt AS STORED in {@code consignment_steps}, read
 * from the raw JSON column — never through {@code StepReceipt.fromJson}, whose {@code Diagnostics} constructor
 * scrubs on the way in and would hide a raw value that reached the table.
 */
public final class StepReceiptRows {

    private StepReceiptRows() {}

    private static final ObjectMapper JSON = new ObjectMapper();
    /** A fingerprint token literal as {@code FailureText.scrub} writes it. */
    private static final Pattern TOKEN_LITERAL = Pattern.compile("(['\"])<(fp:[0-9a-f]{16}|redacted)>\\1");

    /** Every stored, non-null {@code diag.msg} of {@code consignmentId}'s receipts. */
    public static List<String> storedMessages(DbConsignmentOutputStore registry, String consignmentId) throws Exception {
        List<String> out = new ArrayList<>();
        registry.browseSource().run(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT receipt FROM consignment_steps WHERE consignment_id = ?")) {
                ps.setString(1, consignmentId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            var msg = JSON.readTree(rs.getString(1)).path("diag").path("msg");
                            if (!msg.isNull() && !msg.isMissingNode()) out.add(msg.asText());
                        } catch (java.io.IOException e) {
                            throw new java.sql.SQLException(e);
                        }
                    }
                }
            }
        });
        return out;
    }

    /** Whether {@code msg} still holds a quoted literal that is not a fingerprint token. */
    public static boolean quotesAValue(String msg) {
        String rest = TOKEN_LITERAL.matcher(msg).replaceAll("");
        return rest.indexOf('\'') >= 0 || rest.indexOf('"') >= 0;
    }
}
