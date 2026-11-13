package com.gamma.control;

import com.gamma.config.safety.SafetyPolicy;
import com.gamma.config.safety.SafetyPolicyFiles;
import com.gamma.config.safety.SafetyPolicyUnreadableException;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.service.ColumnContract;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.util.CurrentSpace;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SE-3b — the opt-in Schema save stop ({@code spec-column-contract.md} §9, D-SE4, D-SE11). A Space whose
 * {@code config/safety-policy.toon} sets {@code columns: refuse-breaking} has a Schema save that removes, renames or
 * narrows a column a consumer uses answered 409 with those consumers; {@code ?force=true} saves as before. Every other
 * Space (the key absent or {@code report}) gets the SE-3a WARNING findings and the save goes ahead.
 *
 * <ul>
 *   <li>Only a breaking change with at least one RESOLVED consumer stops a save. A change no consumer reads, and a SQL
 *       reference the parser could not attribute ("not analysed"), never do: they stay warnings.</li>
 *   <li>Editions (D-LC5, as LC-4's {@code links:}): refusing needs Standard or Advanced. On Basic the key parses and
 *       acts as {@code report}, and the integrity report says so ({@code columnsMode}).</li>
 *   <li>An unreadable policy file fails closed, as {@link SpaceLinksMode}: acts as {@code refuse-breaking} where the
 *       edition allows it ({@code ?force=true} still saves). The same file already refuses every run of the Space.</li>
 *   <li>Kill switches: {@code -Dcolumn.contract.refusal=off} ignores the key (warning only);
 *       {@code -Dcolumn.contract=off} turns the whole column contract off (no warning, no stop). Both win over the key.</li>
 * </ul>
 */
final class ColumnRefusals {

    private static final Logger log = LoggerFactory.getLogger(ColumnRefusals.class);

    static final String ERR_COLUMN_BREAKS = "ERR_COLUMN_BREAKS";
    static final String REFUSE_BREAKING = "refuse-breaking";

    private ColumnRefusals() {}

    /** What the Space asks for ({@code report}, {@code refuse-breaking} or {@code unreadable}) and what applies. */
    record State(String configured, String effective, String note) {
        boolean refuses() {
            return REFUSE_BREAKING.equals(effective);
        }

        Map<String, Object> body() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("configured", configured);
            m.put("effective", effective);
            if (note != null) m.put("note", note);
            return m;
        }
    }

    static State of(String spaceId) {
        String configured;
        String note = null;
        try {
            configured = SafetyPolicy.columnsForSpace(spaceId) == SafetyPolicyFiles.Columns.REFUSE_BREAKING
                    ? REFUSE_BREAKING : "report";
        } catch (SafetyPolicyUnreadableException unreadable) {
            configured = "unreadable";
            note = "the Space's safety-policy.toon is unreadable, so a breaking Schema save is stopped as under "
                    + "refuse-breaking: " + unreadable.getMessage();
        }
        if ("report".equals(configured)) return new State(configured, "report", null);
        if (!ColumnContract.enabled())
            return new State(configured, "report", "-D" + ColumnContract.PROPERTY + "=off: the column contract is off");
        if ("off".equalsIgnoreCase(System.getProperty(ColumnContract.REFUSAL_PROPERTY, "").trim()))
            return new State(configured, "report",
                    "-D" + ColumnContract.REFUSAL_PROPERTY + "=off: breaking Schema saves only warn");
        if ("basic".equals(BootstrapRoutes.edition()))
            return new State(configured, "report",
                    "refuse modes need Standard or Advanced (D-LC5); on Basic a breaking Schema save only warns");
        return new State(configured, REFUSE_BREAKING, note);
    }

    /**
     * The Schema save check, after every existing gate and before the link check and the maker-checker hold.
     * {@code stored} is the Schema on disk (null when first written), {@code saved} the document about to be written,
     * {@code target} its file. When the calling request's Space refuses, the save is not forced and a breaking change
     * has consumers: answers 409 and returns the handled marker. Otherwise appends one WARNING per breaking change to
     * {@code findings} and returns null.
     */
    static Object save(HttpExchange ex, Path writeRoot, Path target, Map<?, ?> stored, Map<?, ?> saved,
                       List<Finding> findings) throws IOException {
        List<ColumnContract.Break> breaks = ColumnContract.breaks(writeRoot, target, stored, saved);
        if (breaks.isEmpty()) return null;
        boolean force = "true".equalsIgnoreCase(String.valueOf(ApiContext.query(ex, "force")));
        List<Map<String, Object>> consumers = consumers(breaks);
        if (!consumers.isEmpty() && !force && of(CurrentSpace.id()).refuses()) {
            List<Finding> errors = new ArrayList<>();
            for (ColumnContract.Break b : breaks)
                errors.add(new Finding(b.consumers().isEmpty() ? Severity.WARNING : Severity.ERROR,
                        "mapping.fields[" + b.change().column() + "]", b.message(),
                        b.consumers().isEmpty() ? ColumnContract.WARN_COLUMN_BREAKS : ERR_COLUMN_BREAKS,
                        "update the consumers listed, keep the column, or re-send with ?force=true"));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "schema");
            body.put("written", false);
            body.put("code", ErrorCodes.CONFLICT);
            body.put("error", "the Schema change breaks " + consumers.size() + " consumer(s): " + summary(consumers)
                    + " — update them, keep the column, or re-send with ?force=true; not written");
            body.put("consumers", consumers);
            body.put("findings", errors);
            return ApiContext.respondJson(ex, 409, body);
        }
        if (force && !consumers.isEmpty())
            log.warn("[COLUMNS] forced Schema save {} breaks {} consumer(s): {}", target.getFileName(), consumers.size(),
                    summary(consumers));
        for (ColumnContract.Break b : breaks) findings.add(b.warning());
        return null;
    }

    /** Every consumer of every breaking change, as {kind, name, key, column, change[, to], from}. */
    private static List<Map<String, Object>> consumers(List<ColumnContract.Break> breaks) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ColumnContract.Break b : breaks)
            for (Map<String, Object> u : b.consumers()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("kind", u.get("fromKind"));
                m.put("name", u.get("fromName"));
                m.put("key", u.get("key"));
                m.put("column", b.change().column());
                m.put("change", b.change().change());
                if (b.change().to() != null) m.put("to", b.change().to());
                m.put("from", u.get("from"));
                out.add(m);
            }
        return out;
    }

    private static String summary(List<Map<String, Object>> consumers) {
        List<String> s = new ArrayList<>();
        for (Map<String, Object> d : consumers) s.add(d.get("kind") + " '" + d.get("name") + "' (" + d.get("key") + ")");
        return String.join(", ", s);
    }
}
