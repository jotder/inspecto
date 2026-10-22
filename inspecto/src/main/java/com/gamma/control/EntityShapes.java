package com.gamma.control;

import com.gamma.entitystore.EntityTypes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Link Analysis half of an Add data draft ({@code LA-ONBOARD-1} OB-2, UC-03 step 3, UC-04, UC-07, UC-08): which
 * Entity Type each column holds, which column is a link's {@code from} and {@code to} (and its kind, time and amount),
 * the Dataset's primary Entity Type, and for a Reference the column that identifies the entity. Pure: the caller
 * hands it the sampled values; nothing here reads a file.
 *
 * <p><b>Every proposal is a guess with its evidence</b> (plan §7): value shapes (an IMEI's Luhn check digit, an IBAN's
 * mod-97 check, an e-mail, a phone number written {@code +} and 8 to 15 digits) and column names (a token naming an
 * Entity Type's id or one of its classifications: {@code from_account} holds accounts). A shape maps to an Entity Type
 * only when the Space has a type claiming that classification (an IBAN to the type claiming {@code IBAN}, else
 * {@code ACCOUNT}); otherwise the column stays a detail and the evidence says why.
 *
 * <p><b>Confidence (OF-2):</b> {@code high} when every sampled value agrees with the proposal and the sample has at
 * least {@link OnboardDraft#MIN_SAMPLE} non-blank values; {@code low} otherwise.
 */
final class EntityShapes {

    private EntityShapes() {}

    /** One sampled column: its name, its proposed type and its non-blank sampled values. */
    record Column(String name, String type, List<String> values) {}

    private static final Pattern IMEI = Pattern.compile("\\d{15}");
    private static final Pattern PHONE_PLUS = Pattern.compile("\\+\\d{8,15}");
    private static final Pattern DIGITS_8_15 = Pattern.compile("\\d{8,15}");
    private static final Pattern IBAN = Pattern.compile("[A-Z]{2}\\d{2}[A-Z0-9]{11,30}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Pattern ID_LIKE = Pattern.compile("\\S{1,128}");
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9]+");

    /** Name tokens that say "this is a classification", beyond a type's own id and classifications. */
    private static final Map<String, String> NAME_HINTS = Map.ofEntries(
            Map.entry("account", "ACCOUNT"), Map.entry("acct", "ACCOUNT"), Map.entry("acc", "ACCOUNT"), Map.entry("iban", "IBAN"),
            Map.entry("msisdn", "MSISDN"), Map.entry("phone", "MSISDN"), Map.entry("mobile", "MSISDN"),
            Map.entry("caller", "MSISDN"), Map.entry("callee", "MSISDN"), Map.entry("called", "MSISDN"), Map.entry("calling", "MSISDN"),
            Map.entry("anumber", "MSISDN"), Map.entry("bnumber", "MSISDN"),
            Map.entry("imei", "IMEI"), Map.entry("imsi", "IMSI"), Map.entry("wallet", "WALLET"), Map.entry("agent", "AGENT"),
            Map.entry("till", "TILL"), Map.entry("cell", "CELL"), Map.entry("subscriber", "SUBSCRIBER"), Map.entry("handset", "HANDSET"),
            Map.entry("email", "EMAIL"), Map.entry("mail", "EMAIL"));

    private static final Set<String> FROM_WORDS = Set.of("from", "src", "source", "sender", "payer", "debit", "debtor", "caller",
            "calling", "a", "anumber", "orig", "origin", "originator", "out", "remitter");
    private static final Set<String> TO_WORDS = Set.of("to", "dst", "dest", "destination", "target", "receiver", "recipient", "payee",
            "credit", "creditor", "callee", "called", "b", "bnumber", "in", "beneficiary");
    private static final Set<String> KIND_WORDS = Set.of("type", "kind", "channel", "category", "direction", "service", "event",
            "method", "mode");
    private static final Set<String> TIME_WORDS = Set.of("time", "date", "ts", "timestamp", "at", "booked", "created", "when", "day",
            "datetime");
    private static final Set<String> AMOUNT_WORDS = Set.of("amount", "amt", "value", "sum", "total", "price", "duration", "cost",
            "volume", "fee", "qty", "quantity", "seconds", "bytes");

    /** The full {@code entityTypes} block of a draft. */
    static Map<String, Object> propose(List<Column> columns, List<EntityTypes.EntityType> types, String kind) {
        Map<String, Map<String, Object>> perColumn = new LinkedHashMap<>();
        Map<String, String> typeOf = new LinkedHashMap<>();           // column -> Entity Type id (proposed)
        Map<String, Boolean> highOf = new LinkedHashMap<>();
        for (Column c : columns) {
            Map<String, Object> p = columnProposal(c, types);
            perColumn.put(c.name(), p);
            if (p.get("value") != null) {
                typeOf.put(c.name(), String.valueOf(p.get("value")));
                highOf.put(c.name(), "high".equals(p.get("confidence")));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> cols = new ArrayList<>();
        for (var e : perColumn.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("column", e.getKey());
            row.put("entityType", e.getValue());
            cols.add(row);
        }
        out.put("columns", cols);
        out.put("primary", primary(typeOf, highOf));
        if ("reference".equals(kind)) out.put("identifying", identifying(columns, typeOf, highOf));
        else out.put("link", link(columns, typeOf, highOf));
        return out;
    }

    // ── per column ───────────────────────────────────────────────────────────────────────────────────────────

    static Map<String, Object> columnProposal(Column c, List<EntityTypes.EntityType> types) {
        String t = c.type() == null ? "VARCHAR" : c.type().toUpperCase(Locale.ROOT);
        int n = c.values().size();
        if (!t.equals("VARCHAR") && !t.equals("BIGINT"))
            return OnboardDraft.choice(null, "holds " + t.toLowerCase(Locale.ROOT) + " values, not identifiers", n >= OnboardDraft.MIN_SAMPLE);
        if (n == 0) return OnboardDraft.choice(null, "no value in the sample", false);

        String hint = nameHint(c.name(), types);
        Shape shape = shapeOf(c.values());
        String cls = null;
        String why;
        boolean agree;
        if (hint != null) {
            cls = hint;
            Shape expected = shapeFor(hint);
            if (expected != null && shape.cls != null && !shape.cls.equals(expected.cls) && !(hint.equals("ACCOUNT") && "IBAN".equals(shape.cls))) {
                why = "the name says " + hint + " but " + shape.describe(n);
                agree = false;
            } else {
                int fit = fitting(hint, shape, c.values(), types);
                agree = fit == n;
                why = "the name '" + c.name() + "' says " + hint.toLowerCase(Locale.ROOT)
                        + (agree ? "; all " + n + " sampled values look like identifiers of it"
                                : "; " + (n - fit) + " of " + n + " sampled values do not look like one");
            }
        } else if (shape.cls != null && shape.count == n) {
            cls = shape.cls;
            agree = shape.strong;
            why = shape.describe(n) + (shape.strong ? "" : " (digits alone could also be account numbers)");
        } else if (shape.cls != null) {
            return OnboardDraft.choice(null, shape.describe(n) + ", so it is left as a detail", false);
        } else {
            return OnboardDraft.choice(null, "neither the name nor the values point to an Entity Type", n >= OnboardDraft.MIN_SAMPLE);
        }
        EntityTypes.EntityType type = typeClaiming(cls, types);
        if (type == null && "IBAN".equals(cls)) type = typeClaiming("ACCOUNT", types);
        if (type == null)
            return OnboardDraft.choice(null, why + ", but no Entity Type in this Space holds " + cls.toLowerCase(Locale.ROOT)
                    + " - it stays a detail", false);
        Map<String, Object> ch = OnboardDraft.choice(type.id(), why, agree && n >= OnboardDraft.MIN_SAMPLE);
        ch.put("classification", firstClassification(type, cls));
        return ch;
    }

    /** The classification to write for {@code type}: the detected one when the type claims it, else the type's first. */
    private static String firstClassification(EntityTypes.EntityType type, String detected) {
        for (String c : type.classifications()) if (c.equalsIgnoreCase(detected)) return c;
        return type.classifications().isEmpty() ? detected : type.classifications().get(0);
    }

    static EntityTypes.EntityType typeClaiming(String cls, List<EntityTypes.EntityType> types) {
        if (cls == null) return null;
        for (EntityTypes.EntityType t : types)
            for (String c : t.classifications()) if (c.trim().equalsIgnoreCase(cls)) return t;
        return null;
    }

    /** A classification named by the column's name tokens: a hint word, or a type's id or classification. */
    static String nameHint(String column, List<EntityTypes.EntityType> types) {
        List<String> tokens = tokens(column);
        for (String tok : tokens) {
            for (EntityTypes.EntityType t : types) {
                if (t.id().equalsIgnoreCase(tok)) return t.classifications().isEmpty() ? null : t.classifications().get(0).toUpperCase(Locale.ROOT);
                for (String c : t.classifications()) if (c.equalsIgnoreCase(tok)) return c.toUpperCase(Locale.ROOT);
            }
            String h = NAME_HINTS.get(tok);
            if (h != null) return h;
        }
        return null;
    }

    /** Lower-case name tokens: {@code fromAccount}, {@code from_account} and {@code FROM-ACCOUNT} all give from, account. */
    static List<String> tokens(String name) {
        String spaced = name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String t : TOKEN_SPLIT.split(spaced)) if (!t.isEmpty()) out.add(t);
        // a_number / b_number read as one word too
        for (int i = 0; i + 1 < out.size(); i++)
            if ((out.get(i).equals("a") || out.get(i).equals("b")) && out.get(i + 1).equals("number")) out.add(out.get(i) + "number");
        return out;
    }

    /** The values' common shape. */
    record Shape(String cls, int count, boolean strong, String label) {
        String describe(int n) {
            return count == n ? "all " + n + " sampled values are " + label : count + " of " + n + " sampled values are " + label;
        }
    }

    static Shape shapeOf(List<String> values) {
        int imei = 0, phone = 0, digits = 0, iban = 0, email = 0;
        for (String v : values) {
            String s = v.trim();
            if (IMEI.matcher(s).matches() && luhn(s)) imei++;
            if (PHONE_PLUS.matcher(s).matches()) phone++;
            if (DIGITS_8_15.matcher(s).matches()) digits++;
            String compact = s.replace(" ", "").toUpperCase(Locale.ROOT);
            if (IBAN.matcher(compact).matches() && ibanValid(compact)) iban++;
            if (EMAIL.matcher(s).matches()) email++;
        }
        int best = Math.max(Math.max(imei, phone), Math.max(Math.max(iban, email), digits));
        if (best == 0) return new Shape(null, 0, false, "");
        if (imei == best) return new Shape("IMEI", imei, true, "15-digit numbers with a valid IMEI check digit");
        if (iban == best) return new Shape("IBAN", iban, true, "IBANs with a valid check");
        if (email == best) return new Shape("EMAIL", email, true, "e-mail addresses");
        if (phone == best) return new Shape("MSISDN", phone, true, "phone numbers written + and 8 to 15 digits");
        return new Shape("MSISDN", digits, false, "8 to 15 digits long, like phone numbers");
    }

    private static Shape shapeFor(String cls) {
        return switch (cls) {
            case "IMEI" -> new Shape("IMEI", 0, true, "");
            case "IBAN" -> new Shape("IBAN", 0, true, "");
            case "EMAIL" -> new Shape("EMAIL", 0, true, "");
            default -> null;
        };
    }

    /** How many values fit the classification named by the column name. */
    private static int fitting(String cls, Shape shape, List<String> values, List<EntityTypes.EntityType> types) {
        if (shape.cls != null && shape.cls.equals(cls)) return shape.count;
        EntityTypes.EntityType t = typeClaiming(cls, types);
        String normaliser = t == null ? "default" : t.normaliser();
        int fit = 0;
        for (String v : values) {
            String s = v.trim();
            boolean ok = switch (normaliser) {
                case "digits" -> s.chars().allMatch(Character::isDigit) && !s.isEmpty();
                case "e164" -> s.matches("\\+?\\d{6,15}");
                default -> ID_LIKE.matcher(s).matches();
            };
            if (ok) fit++;
        }
        return fit;
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    static boolean ibanValid(String iban) {
        String r = iban.substring(4) + iban.substring(0, 4);
        int mod = 0;
        for (char ch : r.toCharArray()) {
            int v = Character.isDigit(ch) ? ch - '0' : ch - 'A' + 10;
            mod = v > 9 ? (mod * 100 + v) % 97 : (mod * 10 + v) % 97;
        }
        return mod == 1;
    }

    // ── the Dataset level ────────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> primary(Map<String, String> typeOf, Map<String, Boolean> highOf) {
        if (typeOf.isEmpty()) return OnboardDraft.choice(null, "no column holds an Entity Type", false);
        Map<String, List<String>> byType = new LinkedHashMap<>();
        for (var e : typeOf.entrySet()) byType.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        String best = null;
        int bestN = 0;
        boolean tie = false;
        for (var e : byType.entrySet()) {
            if (e.getValue().size() > bestN) {
                best = e.getKey();
                bestN = e.getValue().size();
                tie = false;
            } else if (e.getValue().size() == bestN) tie = true;
        }
        StringBuilder why = new StringBuilder();
        for (var e : byType.entrySet()) {
            if (!why.isEmpty()) why.append("; ");
            why.append(e.getValue().size()).append(e.getValue().size() == 1 ? " column holds " : " columns hold ").append(e.getKey())
                    .append(" (").append(String.join(", ", e.getValue())).append(")");
        }
        boolean allHigh = byType.get(best).stream().allMatch(c -> highOf.getOrDefault(c, false));
        if (tie) why.append(" - a tie, the first is proposed");
        return OnboardDraft.choice(best, why.toString(), !tie && allHigh);
    }

    private static Map<String, Object> identifying(List<Column> columns, Map<String, String> typeOf, Map<String, Boolean> highOf) {
        Column best = null;
        for (Column c : columns) {
            if (!typeOf.containsKey(c.name())) continue;
            if (new LinkedHashSet<>(c.values()).size() != c.values().size()) continue;
            if (best == null || tokens(c.name()).contains("id") || tokens(c.name()).contains("key")) {
                if (best == null || !(tokens(best.name()).contains("id") || tokens(best.name()).contains("key"))) best = c;
            }
        }
        if (best == null) {
            for (Column c : columns)
                if (typeOf.containsKey(c.name()))
                    return OnboardDraft.choice(c.name(), c.name() + " holds " + typeOf.get(c.name())
                            + " but repeats values in the sample, so it may not identify one entity per row", false);
            return OnboardDraft.choice(null, "no column holds an Entity Type with one value per row", false);
        }
        boolean high = highOf.getOrDefault(best.name(), false) && best.values().size() >= OnboardDraft.MIN_SAMPLE;
        Map<String, Object> ch = OnboardDraft.choice(best.name(), best.name() + " holds " + typeOf.get(best.name()) + " and every one of its "
                + best.values().size() + " sampled values is different", high);
        ch.put("entityType", typeOf.get(best.name()));
        return ch;
    }

    private static Map<String, Object> link(List<Column> columns, Map<String, String> typeOf, Map<String, Boolean> highOf) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> entityCols = new ArrayList<>(typeOf.keySet());
        String from = null, to = null, why = null;
        boolean high = false;
        // 1. two columns of one Entity Type whose names say direction
        outer:
        for (String a : entityCols)
            for (String b : entityCols) {
                if (a.equals(b) || !typeOf.get(a).equals(typeOf.get(b))) continue;
                if (hasAny(a, FROM_WORDS) && hasAny(b, TO_WORDS)) {
                    from = a;
                    to = b;
                    why = a + " and " + b + " both hold " + typeOf.get(a) + "; the names say " + a + " → " + b;
                    high = highOf.getOrDefault(a, false) && highOf.getOrDefault(b, false);
                    break outer;
                }
            }
        // 2. two columns of one Entity Type, in column order
        if (from == null)
            outer2:
            for (int i = 0; i < entityCols.size(); i++)
                for (int j = i + 1; j < entityCols.size(); j++)
                    if (typeOf.get(entityCols.get(i)).equals(typeOf.get(entityCols.get(j)))) {
                        from = entityCols.get(i);
                        to = entityCols.get(j);
                        why = from + " and " + to + " both hold " + typeOf.get(from) + "; the names do not say which way, so column order";
                        break outer2;
                    }
        // 3. two columns of different Entity Types (which device a number used)
        if (from == null && entityCols.size() >= 2) {
            from = entityCols.get(0);
            to = entityCols.get(1);
            why = from + " holds " + typeOf.get(from) + " and " + to + " holds " + typeOf.get(to) + "; a link between two kinds of thing";
        }
        if (from == null) {
            out.put("from", OnboardDraft.choice(null, "fewer than two columns hold an Entity Type, so no link is proposed", false));
            out.put("to", OnboardDraft.choice(null, "fewer than two columns hold an Entity Type, so no link is proposed", false));
        } else {
            out.put("from", OnboardDraft.choice(from, why, high));
            out.put("to", OnboardDraft.choice(to, why, high));
        }
        Set<String> used = new LinkedHashSet<>();
        if (from != null) {
            used.add(from);
            used.add(to);
        }
        out.put("kind", pick(columns, used, typeOf, KIND_WORDS, Set.of("VARCHAR"), "kind"));
        out.put("time", pickTime(columns, used));
        out.put("amount", pick(columns, used, typeOf, AMOUNT_WORDS, Set.of("DOUBLE", "BIGINT"), "amount"));
        List<String> details = new ArrayList<>();
        for (Column c : columns) if (!used.contains(c.name())) details.add(c.name());
        out.put("details", OnboardDraft.choice(details, details.isEmpty() ? "every column has a role" : "every other column is shown on the link",
                true));
        return out;
    }

    private static Map<String, Object> pick(List<Column> columns, Set<String> used, Map<String, String> typeOf, Set<String> words,
                                            Set<String> types, String role) {
        for (Column c : columns) {
            if (used.contains(c.name()) || typeOf.containsKey(c.name()) || !types.contains(c.type()) || !hasAny(c.name(), words)) continue;
            int n = c.values().size();
            if ("kind".equals(role)) {
                int distinct = new LinkedHashSet<>(c.values()).size();
                if (distinct > 50) continue;
                used.add(c.name());
                return OnboardDraft.choice(c.name(), "the name '" + c.name() + "' says a kind, and it has " + distinct + " distinct values in "
                        + n + " sampled rows", distinct <= 20 && n >= OnboardDraft.MIN_SAMPLE);
            }
            used.add(c.name());
            return OnboardDraft.choice(c.name(), "the name '" + c.name() + "' says an amount and all " + n + " sampled values are numbers",
                    n >= OnboardDraft.MIN_SAMPLE);
        }
        return OnboardDraft.choice(null, "no column's name and values say " + ("kind".equals(role) ? "a kind of link" : "an amount"), false);
    }

    private static Map<String, Object> pickTime(List<Column> columns, Set<String> used) {
        List<Column> candidates = new ArrayList<>();
        for (Column c : columns)
            if (!used.contains(c.name()) && ("TIMESTAMP".equals(c.type()) || "DATE".equals(c.type()))) candidates.add(c);
        if (candidates.isEmpty()) return OnboardDraft.choice(null, "no column holds dates or times", false);
        Column best = candidates.stream().filter(c -> "TIMESTAMP".equals(c.type())).findFirst().orElse(candidates.get(0));
        for (Column c : candidates) if (c.type().equals(best.type()) && hasAny(c.name(), TIME_WORDS)) {
            best = c;
            break;
        }
        used.add(best.name());
        boolean high = candidates.size() == 1 && best.values().size() >= OnboardDraft.MIN_SAMPLE;
        return OnboardDraft.choice(best.name(), best.name() + " holds " + best.type().toLowerCase(Locale.ROOT) + " values"
                + (candidates.size() == 1 ? ", the only date or time column" : "; " + candidates.size() + " columns hold dates or times"), high);
    }

    private static boolean hasAny(String column, Set<String> words) {
        for (String t : tokens(column)) if (words.contains(t)) return true;
        return false;
    }
}
