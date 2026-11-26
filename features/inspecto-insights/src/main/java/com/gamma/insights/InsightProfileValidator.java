package com.gamma.insights;

import com.gamma.alert.ScoreOutputDirs;
import com.gamma.pipeline.ComponentStore;
import com.gamma.spi.http.ComponentKindValidator;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Save-time checks of an Insight Profile (BI-INSIGHTS-1 I-8): its structure ({@link InsightProfile#parse}), that its
 * Dataset exists in the Space, and the reserved store prefix {@code insights_} — only the Profile's own Insights
 * Dataset ({@code id = physicalRef = insights_<profile>} over a saved Profile) may name it.
 */
public final class InsightProfileValidator implements ComponentKindValidator {

    static final List<String> STORE_KEYS = List.of("physicalRef", "store", "output_store", "path", "sourceName");

    @Override public String type() { return InsightProfile.KIND; }

    @Override public void validate(String id, Map<String, Object> content) {
        InsightProfile.parse(id, content);
    }

    @Override public void validateInSpace(Path writeRoot, Supplier<Path> dataRoot, String id, Map<String, Object> content) {
        InsightProfile p = InsightProfile.parse(id, content);
        if (writeRoot != null && !new ComponentStore(writeRoot.resolve("registry")).exists("dataset", p.dataset()))
            throw new IllegalArgumentException("insight-profile '" + id + "': dataset '" + p.dataset() + "' does not exist");
    }

    @Override public void requireNotReserved(Path writeRoot, String type, String id, Map<String, Object> content) {
        String prefix = InsightProfile.PREFIX;
        List<String> named = ScoreOutputDirs.namedStores(id, content, STORE_KEYS);
        if (!ScoreOutputDirs.anyReserved(named, prefix)) return;
        if ("dataset".equals(type) && id != null && id.equals(content.get("physicalRef")) && named.size() == 2
                && id.startsWith(prefix) && writeRoot != null
                && new ComponentStore(writeRoot.resolve("registry")).exists(InsightProfile.KIND, id.substring(prefix.length())))
            return;
        throw new IllegalArgumentException(type + " '" + id + "' names a store under the reserved prefix '" + prefix
                + "' (Insights); only the Dataset with id = physicalRef = " + prefix + "<profile> over a saved Insight "
                + "Profile is allowed");
    }
}
