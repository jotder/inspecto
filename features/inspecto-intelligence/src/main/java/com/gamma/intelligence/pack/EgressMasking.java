package com.gamma.intelligence.pack;

import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.tool.Tool;
import com.gamma.entitystore.MaskTokens;
import com.gamma.mask.EvidenceMasker;
import com.gamma.model.ModelSettings;
import com.gamma.model.ModelSettingsStore;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masking of classified / key-like values in tool results that leave the process (AGENT-EGRESS-MASK-1, 2026-10-15).
 * A tool result fed back to a MODEL-BOUND session turn reaches the configured chat provider, which may be a hosted
 * third party, whatever the caller may see on the direct {@code POST /agent/tools/{name}} route.
 *
 * <p><b>When it applies</b> ({@link #active}): a direct dispatch is masked unless the bound {@link ToolCaller} holds
 * {@code canRevealLinkEntities}; a model-bound call ({@link ToolCaller#modelBound}) is masked when the provider is not
 * local (Ollama / llama.cpp, {@link ModelSettings#local}) REGARDLESS of the caller, and with a local provider unless the
 * caller may reveal. An unbound caller fails closed (masked).
 *
 * <p><b>What it masks</b> - the same {@code masked:<16 hex>} token as {@code /db/query}, {@code /bi/query} and the audit
 * read ({@link EvidenceMasker#tokenFor}: the Space identity key): a map value whose key names a column the Space's
 * Datasets classify {@link EvidenceMasker#SENSITIVE}, or a fixed key-like name ({@code msisdn}, {@code imsi},
 * {@code imei}, {@code entityKey}, {@code key.*}); and inside free text, a {@code msisdn=<value>}-style pair. Free prose
 * is otherwise left alone. Where a Dataset's lineage cannot be traced only the key-like names apply (a blanket fail
 * closed would mask every number a metrics tool returns).
 */
public final class EgressMasking {

    static final String REVEAL = "canRevealLinkEntities";
    private static final Set<String> KEY_NAMES = Set.of("msisdn", "imsi", "imei", "iccid", "entitykey", "entity_key");
    private static final Pattern INLINE = Pattern.compile(
            "(?i)\\b(msisdn|imsi|imei|iccid|entitykey|key\\.[a-z0-9_]+)(\\s*[=:]\\s*)([^\\s,;&)\\]}\"']+)");

    private static final EgressMasking SYSTEM = new EgressMasking(EgressMasking::providerIsLocal, () -> {
        String wr = System.getProperty("assist.write.root");
        return wr == null || wr.isBlank() ? null : Path.of(wr);
    });

    private final BooleanSupplier localModel;
    private final Supplier<Path> writeRoot;

    EgressMasking(BooleanSupplier localModel, Supplier<Path> writeRoot) {
        this.localModel = localModel;
        this.writeRoot = writeRoot;
    }

    /** The masking bound to the running Space ({@code -Dassist.write.root}) and its persisted model settings. */
    public static EgressMasking system() {
        return SYSTEM;
    }

    private static boolean providerIsLocal() {
        try {
            return ModelSettingsStore.load().orElseGet(() -> ModelSettings.defaults("ollama")).local();
        } catch (RuntimeException unreadable) {
            return false;   // cannot tell -> treat as third-party
        }
    }

    /** The masker this call must apply, or empty when the caller may see raw values. */
    public Optional<Masker> active() {
        Optional<ToolCaller> caller = ToolCaller.current();
        boolean reveals = caller.isPresent() && caller.get().holds(REVEAL);
        boolean mask = ToolCaller.isModelBound() ? (!localModel.getAsBoolean() || !reveals) : !reveals;
        return mask ? Optional.of(masker()) : Optional.empty();
    }

    private Masker masker() {
        Path root = writeRoot.get();
        Set<String> classified = new TreeSet<>();
        UnaryOperator<String> token;
        if (root == null) {
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            token = v -> MaskTokens.token(k, v);
        } else {
            ComponentStore registry = new ComponentStore(root.resolve("registry"));
            ViewStore views = new ViewStore(root.resolve("views"));
            for (ComponentRegistry.Component c : registry.list("dataset"))
                for (String col : EvidenceMasker.sensitiveColumns(registry, views, c.name()))
                    if (!EvidenceMasker.UNKNOWN_LINEAGE.equals(col)) classified.add(col.toLowerCase(Locale.ROOT));
            EvidenceMasker em = EvidenceMasker.forSpace(root);
            token = em::tokenFor;
        }
        return new Masker(classified, token);
    }

    /** {@code tool} with {@link #active} applied to every result it returns (a refusal carries no value). */
    public Tool decorate(Tool tool) {
        return new Tool() {
            @Override public ToolSpec spec() { return tool.spec(); }
            @Override public ToolResult invoke(ToolCall call) {
                ToolResult r = tool.invoke(call);
                if (!r.ok() || r.value() == null) return r;
                Optional<Masker> m = active();
                if (m.isEmpty()) return r;
                return new ToolResult(true, m.get().apply(r.value()), r.error(), r.meta());
            }
        };
    }

    /** Masks values; built per call by {@link #active}. */
    public static final class Masker {
        private final Set<String> classified;
        private final UnaryOperator<String> token;
        private final Map<String, String> memo = new LinkedHashMap<>();

        Masker(Set<String> classified, UnaryOperator<String> token) {
            this.classified = classified;
            this.token = token;
        }

        /** True when {@code column} (or a dotted {@code key.x} name) is classified or key-like. */
        public boolean masksColumn(String column) {
            if (column == null) return false;
            String n = column.trim().toLowerCase(Locale.ROOT);
            int dot = n.lastIndexOf('.');
            String last = dot < 0 ? n : n.substring(dot + 1);
            return classified.contains(n) || classified.contains(last) || KEY_NAMES.contains(n)
                    || KEY_NAMES.contains(last) || n.startsWith("key.");
        }

        public String token(Object raw) {
            return memo.computeIfAbsent(String.valueOf(raw), token);
        }

        /** {@code value} of {@code column}: its token when the column is masked, else untouched. */
        public Object cell(String column, Object value) {
            return value == null || !masksColumn(column) ? value : token(value);
        }

        /** Free text with every {@code msisdn=<value>}-style pair's value replaced by its token. */
        public String text(String s) {
            if (s == null || s.isEmpty()) return s;
            Matcher m = INLINE.matcher(s);
            StringBuilder out = new StringBuilder();
            while (m.find())
                m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + m.group(2) + token(m.group(3))));
            m.appendTail(out);
            return out.toString();
        }

        /** A structured result with masked-key values tokenised and inline pairs in strings masked. */
        public Object apply(Object v) {
            if (v instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    Object val = e.getValue();
                    boolean scalar = val != null && !(val instanceof Map) && !(val instanceof Iterable);
                    out.put(k, scalar && masksColumn(k) ? token(val) : apply(val));
                }
                return out;
            }
            if (v instanceof Iterable<?> it) {
                List<Object> out = new ArrayList<>();
                for (Object o : it) out.add(apply(o));
                return out;
            }
            return v instanceof String s ? text(s) : v;
        }
    }
}
