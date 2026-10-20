package com.gamma.control;

import com.gamma.mask.EvidenceMasker;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.spi.http.ApiContext;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-time masking of per-entity ALERT / INCIDENT objects (TELCO-FRAUD-MASKED-DASHBOARD-1 (c)). A {@code by} Alert Rule
 * stores the breached key as {@code key.<column>} attributes (and as {@code key} / {@code alertKey} / the title). For a
 * caller WITHOUT {@link AuditReadMasking#UNMASK_CAPABILITY}, every {@code key.<column>} value whose column the rule's
 * Dataset classifies sensitive ({@link EvidenceMasker#sensitiveColumns}) becomes the Space's {@code masked:<16 hex>} token,
 * and the raw value is replaced by that token wherever it stands in the title, description, correlation id and the other
 * attributes. Stored rows stay RAW (the dedupe key is raw); only the read view is masked. Fail closed: untraceable lineage
 * masks every {@code key.*} value. A request with no Subject (Basic) is unrestricted.
 */
public final class ObjectReadMasking {

    private static final int MIN_INLINE = AuditReadMasking.MIN_INLINE;

    private final EvidenceMasker masker;
    private final ComponentStore registry;
    private final ViewStore views;
    private final Map<String, Set<String>> sensitive = new HashMap<>();

    private ObjectReadMasking(Path root) {
        this.masker = EvidenceMasker.forSpace(root);
        this.registry = new ComponentStore(root.resolve("registry"));
        this.views = new ViewStore(root.resolve("views"));
    }

    /** The masker for this request, or {@code null} when the caller may see raw keys (or there is no Space root). */
    public static ObjectReadMasking forRequest(ApiContext api, HttpExchange ex) {
        return forRequest(api.writeRoot(), ex);
    }

    /** As {@link #forRequest(ApiContext, HttpExchange)} for a caller holding only the Space's write root (engine seams). */
    public static ObjectReadMasking forRequest(Path root, HttpExchange ex) {
        boolean privileged = ApiContext.subject(ex)
                .map(s -> s.capabilities().contains(AuditReadMasking.UNMASK_CAPABILITY)).orElse(true);
        return privileged || root == null ? null : new ObjectReadMasking(root);
    }

    static ObjectReadMasking of(Path root) {
        return new ObjectReadMasking(root);
    }

    /** {@code masking} applied to one {@code /objects} row; the row itself when {@code masking} is {@code null}. */
    public static Map<String, Object> object(ObjectReadMasking masking, Map<String, Object> row) {
        return masking == null ? row : masking.maskObject(row);
    }

    /** A mutation echo / any other object body: masked for this request, one helper for every route. */
    public static Map<String, Object> echo(ApiContext api, HttpExchange ex, Map<String, Object> row) {
        return object(forRequest(api, ex), row);
    }

    /**
     * The classified columns of an object's Dataset. An object WITHOUT a {@code dataset} attribute (hand-made, imported)
     * cannot prove its {@code key.*} columns unclassified, so it fails closed exactly like untraceable lineage.
     */
    private Set<String> sensitiveOfObject(Map<String, String> attrs) {
        String dataset = attrs.get("dataset");
        if (dataset == null || dataset.isBlank()) dataset = attrs.get("entityDataset");   // LA / Screening-minted objects
        return dataset == null || dataset.isBlank() ? Set.of(EvidenceMasker.UNKNOWN_LINEAGE) : sensitiveOf(dataset);
    }

    /** {@code true} when the attribute {@code name} ({@code key.<column>}) is a key this caller may not see raw. */
    public boolean hides(Map<String, String> attrs, String name) {
        Set<String> sens = sensitiveOfObject(attrs);
        return name.equals("entityKey") ? !sens.isEmpty() : name.startsWith("key.") && hit(sens, name.substring(4));
    }

    private Set<String> sensitiveOf(String dataset) {
        return dataset == null ? Set.of()
                : sensitive.computeIfAbsent(dataset, d -> EvidenceMasker.sensitiveColumns(registry, views, d));
    }

    private static boolean hit(Set<String> sens, String column) {
        return sens.contains(EvidenceMasker.UNKNOWN_LINEAGE) || sens.contains(column.toLowerCase(Locale.ROOT));
    }

    /** raw key value to mask token, for one object's own classified keys; empty when it has none. */
    private Map<String, String> replacements(Map<String, String> attrs) {
        Set<String> sens = sensitiveOfObject(attrs);
        Map<String, String> replaced = new LinkedHashMap<>();
        if (sens.isEmpty()) return replaced;
        attrs.forEach((k, v) -> {
            if (k.startsWith("key.") && v != null && hit(sens, k.substring(4))) replaced.put(v, masker.tokenFor(v));
        });
        // An LA / Screening-minted object names its Entity as `entityKey` = entity:[<type>:]<key>; its Dataset has a
        // classified column and the column is not named, so the key is masked (fail closed), full id and bare key alike.
        String entityKey = attrs.get("entityKey");
        String bare = entityKey == null ? null : bareEntityKey(entityKey);
        if (bare != null) replaced.put(bare, masker.tokenFor(bare));
        return replaced;
    }

    /**
     * A note (comment / attachment) on an object, for a caller who may not reveal: its free text ({@code body} and the
     * attribute values, e.g. an attachment name / uri) can carry the object's raw key, so the object's own classified keys
     * are replaced inline. {@code objectRow} is the object's {@code toMap()}; the note when {@code masking} is {@code null}.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> note(ObjectReadMasking masking, Map<String, Object> objectRow, Map<String, Object> note) {
        if (masking == null || !(objectRow.get("attributes") instanceof Map<?, ?> a)) return note;
        Map<String, String> replaced = masking.replacements((Map<String, String>) a);
        if (replaced.isEmpty()) return note;
        Map<String, Object> out = new LinkedHashMap<>(note);
        if (out.get("body") instanceof String s) out.put("body", inline(s, replaced));
        if (out.get("attributes") instanceof Map<?, ?> na) {
            Map<String, String> m = new LinkedHashMap<>();
            na.forEach((k, v) -> m.put(String.valueOf(k), v == null ? null : inline(v.toString(), replaced)));
            out.put("attributes", m);
        }
        return out;
    }

    /**
     * Free text rendered FROM objects (a Regulatory Report's content, its subject title): every classified key of every
     * row in {@code objectRows} ({@code toMap()} shapes) is replaced inline. The text when {@code masking} is {@code null}.
     */
    @SuppressWarnings("unchecked")
    public static String text(ObjectReadMasking masking, List<Map<String, Object>> objectRows, String text) {
        if (masking == null || text == null) return text;
        Map<String, String> replaced = new LinkedHashMap<>();
        for (Map<String, Object> row : objectRows)
            if (row.get("attributes") instanceof Map<?, ?> a) replaced.putAll(masking.replacements((Map<String, String>) a));
        return replaced.isEmpty() ? text : inline(text, replaced);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> maskObject(Map<String, Object> row) {
        if (!(row.get("attributes") instanceof Map<?, ?> a)) return row;
        Map<String, String> attrs = (Map<String, String>) a;
        Map<String, String> replaced = replacements(attrs);
        String entityKey = attrs.get("entityKey");
        String bare = entityKey == null ? null : bareEntityKey(entityKey);
        if (replaced.isEmpty()) return row;
        Map<String, String> maskedAttrs = new LinkedHashMap<>();
        attrs.forEach((k, v) -> maskedAttrs.put(k, v == null ? null
                : k.startsWith("key.") && replaced.containsKey(v) ? replaced.get(v)
                : k.equals("entityKey") && bare != null ? entityKey.replace(bare, replaced.get(bare))
                : inline(v, replaced)));
        Map<String, Object> out = new LinkedHashMap<>(row);
        for (String f : List.of("title", "description", "correlationId"))
            if (out.get(f) instanceof String s) out.put(f, inline(s, replaced));
        out.put("attributes", maskedAttrs);
        out.put("keyMasked", true);
        return out;
    }

    private static String bareEntityKey(String v) {
        String rest = v.startsWith("entity:") ? v.substring("entity:".length()) : v;
        int i = rest.indexOf(':');
        if (i > 0 && rest.substring(0, i).matches("[A-Za-z_][A-Za-z0-9_-]*")) rest = rest.substring(i + 1);
        return rest.isBlank() ? null : rest;
    }

    private static String inline(String text, Map<String, String> replaced) {
        String t = text;
        for (Map.Entry<String, String> r : replaced.entrySet())
            if (r.getKey().length() >= MIN_INLINE) t = t.replace(r.getKey(), r.getValue());
        return t;
    }

    /**
     * An Alert's message names its key as {@code for <column>=<value>[, <column>=<value>]}: mask each sensitive column's
     * value (it ends at {@code ", <next>="}, {@code " is "} or the end). {@code dataset} is the Alert's scope.
     */
    public String alertMessage(String message, String dataset) {
        if (message == null) return null;
        Set<String> sens = sensitiveOf(dataset);
        if (sens.isEmpty()) return message;
        Matcher m = Pattern.compile("(?<=[ ,])([A-Za-z_][A-Za-z0-9_.]*)=(.+?)(?=, [A-Za-z_][A-Za-z0-9_.]*=| is |$)")
                .matcher(message);
        StringBuilder sb = new StringBuilder();
        while (m.find())
            m.appendReplacement(sb, Matcher.quoteReplacement(hit(sens, m.group(1))
                    ? m.group(1) + "=" + masker.tokenFor(m.group(2)) : m.group()));
        return m.appendTail(sb).toString();
    }
}
